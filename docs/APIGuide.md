# LC Invoice Checker — API Guide

## Overview

The API has two public entry points — task submission and progress inquiry — both under `/api/v1/`.

```
POST /api/v1/lc-check/start          submit LC + invoice, get sessionId immediately
GET  /api/v1/lc-check/{id}/stream    live SSE progress stream
GET  /api/v1/lc-check/{id}/trace    synchronous replay of all session events
```

Full OpenAPI spec auto-generated at `/docs.html` (Scalar).

---

## 1. Task Submission

```
POST /api/v1/lc-check/start
Content-Type: multipart/form-data

lc       — MT700 plain text (text/plain)
invoice  — commercial invoice PDF (application/pdf)

→ 200 { session_id, status: "QUEUED", queue_position, invoice_filename, invoice_bytes, lc_length }
→ 400 VALIDATION_FAILED | LC_PARSE_ERROR | PDF_UNREADABLE
→ 503 STORAGE_UNAVAILABLE  (MinIO unreachable)
```

The session is queued immediately; the actual pipeline runs asynchronously. Poll `GET /stream` for live progress.

### curl example

```bash
curl -sS -X POST http://localhost:8080/api/v1/lc-check/start \
  -F "lc=@./test/sample_mt700.txt;type=text/plain" \
  -F "invoice=@./test/sample_invoice.pdf;type=application/pdf"
```

Response:

```json
{
  "session_id": "0ff7b0e3-0b7e-4348-8ae1-85af4218e8f8",
  "status": "QUEUED",
  "queue_position": 1,
  "invoice_filename": "sample_invoice.pdf",
  "invoice_bytes": 3264,
  "lc_length": 1591
}
```

---

## 2. Progress Inquiry — SSE Stream

```
GET /api/v1/lc-check/{sessionId}/stream
Accept: text/event-stream

→ 200 text/event-stream   (SSE, one event per line)
→ 404 SESSION_NOT_FOUND
```

Opens a Server-Sent Events (SSE) connection. Four event types are emitted in sequence as the pipeline runs:

| SSE `event:` field | Meaning | `data` payload |
|---|---|---|
| `status` | Stage transition (started / completed) | `LcDocument`, `InvoiceDocument`, or checks-summary `Map` |
| `rule` | One event per completed rule check | `CheckResult` |
| `error` | Pipeline halted — no further events follow | `null` |
| `complete` | Final report assembled | `DiscrepancyReport` |

All events share one envelope — `CheckEvent`:

```json
{
  "seq":       0,
  "ts":        "2026-04-28T10:00:00.000Z",
  "type":      "status",
  "stage":     "lc_parse",
  "state":     "completed",
  "message":   "LC parsed successfully",
  "data":      { ...LcDocument or Map... }
}
```

### SSE Format

Each event is sent as two SSE lines:

```
event: status
data: {"seq":0,"ts":"2026-04-28T10:00:00Z","type":"status","stage":"lc_parse","state":"completed","message":"MT700 parsed successfully","data":{...}}
```

The SSE `event:` field carries the type name; the JSON `data:` line carries the full payload.

### curl example (live stream)

```bash
curl -sS -N "http://localhost:8080/api/v1/lc-check/<SESSION_ID>/stream"
```

With colored JSON per event:

```bash
SID=$(curl -sS -X POST http://localhost:8080/api/v1/lc-check/start \
  -F "lc=@./test/sample_mt700.txt;type=text/plain" \
  -F "invoice=@./test/sample_invoice.pdf;type=application/pdf" \
  | jq -r '.session_id')

curl -sS -N "http://localhost:8080/api/v1/lc-check/$SID/stream" \
  | sed -n 's/^data: //p' | jq -C '.'
```

---

## 3. Trace Replay

```
GET /api/v1/lc-check/{sessionId}/trace

→ 200 { session_id, events[], queue_context }
→ 404 SESSION_NOT_FOUND
```

Synchronous replay of all events for a session — same data as the SSE stream, returned as a single JSON array. `queue_context` is populated only while the session is still `QUEUED`.

```bash
curl -sS "http://localhost:8080/api/v1/lc-check/<SESSION_ID>/trace" | jq -C '.'
```

---

## SSE Internal Architecture

The SSE mechanism is built on Spring's `SseEmitter` + a custom `CheckEventBus`.

### CheckEventBus

```
CheckEventBus
  channels: Map<sessionId, SessionChannel>
  SessionChannel
    emitters: CopyOnWriteArrayList<SseEmitter>   ← live SSE connections
    buffer:  ArrayDeque<CheckEvent> (cap=1024)   ← ring buffer for replay
```

### Registration Flow

```
GET /{id}/stream
  └─ new SseEmitter(30min timeout)
         bus.register(sessionId, emitter)
           ├─ replay ring buffer (补发历史事件，断线重连时有用)
           ├─ emitters.add(emitter)
           └─ emitter.onCompletion/Timeout/Error → emitters.remove(emitter)
```

### Event Publishing Flow

```
Pipeline stages run asynchronously (JobDispatcher, every 2s)
  └─ ctx.publisher.status/rule/complete(event)
         bus.publish(sessionId, event)
           ├─ store.appendEvent(sessionId, event)  ← persist to DB
           └─ deliver(sessionId, event)
                  ├─ buffer.append(event)         ← write to ring buffer
                  └─ for each emitter in channel:
                         emitter.send(SseEmitter.event()
                           .name("status|rule|error|complete")
                           .data(event))           ← real-time push
```

Key design points:

- **Double-write**: every event is persisted to DB (for `/trace`) **and** pushed to SSE simultaneously
- **Ring buffer (cap=1024)**: new SSE subscribers receive a replay of recent events — supports reconnect without data loss
- **Auto cleanup**: client disconnect / timeout / normal completion all remove the emitter from the channel
- **Multi-subscriber**: one `sessionId` can have multiple SSE clients connected simultaneously
- **Non-blocking send**: if a client disconnects mid-stream, `IOException` is caught and the emitter is removed; the pipeline continues unaffected

### Event Type Reference

| Type | Emitted when | `data` field |
|---|---|---|
| `status` (started) | A pipeline stage begins | `null` |
| `status` (completed) | A stage finishes | `LcDocument` / `InvoiceDocument` / `Map` (checks summary) |
| `progress` | A rule check starts | `{ ruleId, ruleName, tier }` |
| `rule` | A rule check finishes | `CheckResult` |
| `error` | Pipeline halts | `null` |
| `complete` | All stages done | `DiscrepancyReport` |

### Emitter Lifetime

| Trigger | Action |
|---|---|
| Client disconnects mid-stream | `IOException` → `emitter.completeWithError()` → remove |
| SSE timeout (30 min) | `emitter.onTimeout()` → remove |
| Pipeline completes normally | `bus.complete(sessionId)` → `emitter.complete()` → remove channel |
| Server error | `emitter.onError(e)` → remove |

---

## Error Codes

| HTTP | Code | Meaning |
|---|---|---|
| `400` | `VALIDATION_FAILED` | MT700 missing mandatory fields, PDF too large, not a PDF |
| `400` | `LC_PARSE_ERROR` | MT700 field `:20:` / `:32B:` / etc. absent |
| `400` | `PDF_UNREADABLE` | PDF corrupted or password-protected |
| `404` | `SESSION_NOT_FOUND` | Unknown sessionId |
| `503` | `STORAGE_UNAVAILABLE` | MinIO unreachable |
| `502` | `EXTRACTOR_UNAVAILABLE` | All invoice extractors failed |
