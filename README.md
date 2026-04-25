# LC Invoice Checker

A trade-finance backend that accepts a SWIFT **MT700 Letter of Credit** (plain text)
and a **commercial invoice** (PDF), checks the invoice against **UCP 600 / ISBP 821**
rules, and returns a structured JSON discrepancy report.

> Stage numbering and semantics follow [`docs/refer-doc/logic-flow.md`](docs/refer-doc/logic-flow.md).
> Project spec for AI assistants lives in [`CLAUDE.md`](CLAUDE.md).

---

## Architecture

```
┌────────────────┐   multipart (lc + invoice)    ┌────────────────────────────────┐
│ curl / client  │ ─────────────────────────────▶│  lc-checker-svc  [local JVM]   │
└────────────────┘                                │  Java 21 + Spring Boot 3      │
                                                  │                                │
                                                  │  Stage 1a  LC regex parse      │
                                                  │  Stage 1b  Invoice extract     │
                                                  │     └─ orchestrator runs       │
                                                  │        every enabled source    │
                                                  │  Stage 2   Rule activation     │
                                                  │  Stage 3   Rule check          │
                                                  │     └─ Tier 1 Type A (code)    │
                                                  │     └─ Tier 2 Type B (LLM)     │
                                                  │     └─ Tier 3 Type AB (hybrid) │
                                                  │  Stage 4   Holistic sweep*     │
                                                  │  Stage 5   Report assembly     │
                                                  └───┬────────────────┬────┬──────┘
                                                      │                │    │
                                           vision LLM │       docling-svc    │
                                       (Spring AI)    │  mineru-svc          │
                                    ┌─────────────────┴─┐  ┌──────────────┐  │
                                    │ Ollama / Qwen /   │  │ Python       │  │
                                    │ DeepSeek (OpenAI- │  │ pure OCR     │  │
                                    │  compatible API)  │  │ [local]      │  │
                                    └───────────────────┘  └──────────────┘  │
                                                                              │
                                                             PostgreSQL 16 [Docker]
                                                             v2 schema, persistent volume
                                                             6 tables + 3 views

* Stage 4 holistic sweep: table shipped, executor deferred.
```

---

## Modules

| Module              | Purpose                                               | Docs                                                      |
|---------------------|-------------------------------------------------------|-----------------------------------------------------------|
| `lc-checker-svc/`   | Java 21 Spring Boot service — pipeline orchestration, rule checks, API, persistence | [`lc-checker-svc/README.md`](lc-checker-svc/README.md)    |
| `extractors/`       | Invoice extraction sources (Vision LLM in Java, Docling + MinerU as pure Python parsers) | [`extractors/README.md`](extractors/README.md)            |
| `infra/`            | Postgres schema v2, docker-compose, init scripts      | [`infra/README.md`](infra/README.md)                      |
| `test/`             | Test fixtures and end-to-end test driver              | [`test/README.md`](test/README.md)                        |

Design principle: **LLM lives in Java / Spring AI only** — Python extractors are pure
parsers (regex + heuristic field capture). All prompts, models, and traces are managed
in one place.

---

## Runtime layout

**Only PostgreSQL runs in Docker.** Every other component runs locally on the
host — this keeps iteration fast and avoids rebuilding images for every code change.

| Component       | Runs where             | How to start                                   |
|-----------------|------------------------|------------------------------------------------|
| PostgreSQL 16   | Docker                 | `make up`  (or `docker compose up -d`)         |
| `lc-checker-svc` | Local JVM             | `make bootrun` (or `cd lc-checker-svc && ./gradlew bootRun`) |
| `docling-svc`   | Local Python (optional) | `cd extractors/docling && uvicorn app.main:app --port 8081` |
| `mineru-svc`    | Local Python (optional) | `cd extractors/mineru  && uvicorn app.main:app --port 8082` |

**Data persistence:** the named volume `lc-checker-postgres-data` survives
`docker compose down/up`, `restart`, and host reboots. Data is only wiped by
`make wipe` (= `docker compose down -v`). The v2 schema auto-applies on first
cluster init (empty volume).

---

## Quickstart

Prerequisites: Docker, JDK 21 (Temurin via SDKMAN recommended), and an OpenAI-compatible
LLM endpoint (local Ollama on `:11434` works out of the box).

```bash
# 1. One-time: copy env, point LLM_* and VISION_* at your endpoint
cp .env.example .env

# 2. Start Postgres (data persists across restarts)
make up

# 3. Run the Java service locally
make bootrun                     # foreground
# — or —
cd lc-checker-svc && ./gradlew bootRun

# 4. End-to-end check
curl -sS -X POST http://localhost:8080/api/v1/lc-check \
  -F "lc=@docs/refer-doc/sample_lc_mt700.txt;type=text/plain" \
  -F "invoice=@docs/refer-doc/invoice-1-apple.pdf;type=application/pdf" | jq

# 5. Inspect the session trace
curl -sS http://localhost:8080/api/v1/lc-check/{sessionId}/trace | jq
```

For stage-by-stage verification and DB inspection queries, see
[`run-book.md`](run-book.md).

---

## API

**Interactive reference:** <http://localhost:8080/docs> (or just <http://localhost:8080/>).
A [Scalar](https://scalar.com) UI consumes the OpenAPI 3 spec at `/v3/api-docs`
— every endpoint below is documented, try-it-out ready, with request/response schemas.

```
POST /api/v1/lc-check
  multipart: lc (text/plain), invoice (application/pdf)
  → 200 DiscrepancyReport { sessionId, compliant, discrepancies[], summary }
  → 400 VALIDATION_FAILED | LC_PARSE_ERROR | PDF_UNREADABLE
  → 502 EXTRACTOR_UNAVAILABLE

GET  /api/v1/lc-check/{sessionId}/trace
  → 200 CheckSession (full intermediate state — per-stage + per-rule + LLM traces)
  → 404 SESSION_NOT_FOUND
```

Dev-only debug endpoints:
```
POST /api/v1/debug/mt700/parse           — Stage 1a output, plain text
POST /api/v1/debug/invoice/compare       — every enabled source side-by-side, plain text
```

---

## What's in this repo

```
.
├── README.md                         ← you are here
├── run-book.md                        ← start / verify / stop commands
├── CLAUDE.md                          ← agent project spec (AI assistants)
├── docker-compose.yml                 ← Postgres only (svc + parsers run locally)
├── Makefile                           ← up / down / bootrun / psql / curl-demo
├── .env.example
│
├── lc-checker-svc/                    ← Java Spring Boot service
│   └── README.md
├── extractors/
│   ├── README.md
│   ├── CONTRACT.md                    ← frozen HTTP contract (Java ↔ Python)
│   ├── docling/                       ← Python pure parser
│   │   └── README.md
│   └── mineru/                        ← Python pure parser
│       └── README.md
├── infra/
│   ├── README.md
│   └── postgres/
│       └── init/01-schema.sql          ← v2 schema, auto-loaded on first boot
├── test/
│   └── README.md
└── docs/
    └── refer-doc/                     ← test-case reference materials (read-only)
```
