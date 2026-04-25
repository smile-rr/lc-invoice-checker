# Extractors — Invoice Extraction Sources

Stage 1b of the pipeline (`docs/refer-doc/logic-flow.md`). Up to **four**
independent sources convert a PDF invoice into an `InvoiceDocument`; the Java
orchestrator runs every enabled source, persists one row per source under
`pipeline_steps (stage='invoice_extract', step_key=<source>)`, and marks one as
the **selected** source for downstream rule checks.

| Source          | Lives in                 | Runtime | LLM? | Typical use |
|-----------------|--------------------------|---------|------|-------------|
| `remote_vision` | `lc-checker-svc` (Java)  | JVM     | **Yes** — vision LLM via OpenAI-compatible endpoint | Image-based PDFs, scanned invoices, layout-heavy documents (cloud Qwen / OpenAI / Gemini) |
| `local_vision`  | `lc-checker-svc` (Java)  | JVM     | **Yes** — local Ollama vision model | Same workload as `remote_vision`, run offline / no API spend |
| `docling`       | `extractors/docling/`    | Python  | No   | Born-digital PDFs with a clean text layer |
| `mineru`        | `extractors/mineru/`     | Python  | No   | Image-heavy PDFs where Docling's OCR struggles |

> **Principle:** Python extractors are pure parsers — regex + heuristic field capture,
> no LLM calls. All semantic structuring that requires a model happens in Java via
> Spring AI (Vision LLM or a future markdown post-processor). This keeps prompts,
> provider config, trace capture, and observability in one place.

---

## HTTP Contract

Both Python services (`docling-svc`, `mineru-svc`) implement the **same** HTTP contract
— frozen at `v1.0` in [`CONTRACT.md`](CONTRACT.md). Don't change the contract without
updating both services and the Java client in lock-step.

Shape (simplified):
```json
POST /extract  (multipart: file=<pdf>)
→ 200
{
  "extractor": "docling",
  "contract_version": "1.0",
  "confidence": 0.92,
  "is_image_based": false,
  "pages": 2,
  "extraction_ms": 342,
  "raw_markdown": "# Invoice ...",
  "raw_text": "Invoice INV-001 ...",
  "fields": { "invoice_number": "INV-001", "...": "...", "signed": true }
}
```

Failure: non-2xx with a typed error body — see `errors.py` and `ExtractorErrorCode.java`.

The Vision source does **not** expose an HTTP endpoint; it's a Java class
(`VisionLlmExtractor`) that renders PDF pages to PNG, base64-encodes them, and calls
an OpenAI-compatible `/v1/chat/completions` endpoint.

---

## Orchestration (Java side) — two-lane parallel

`InvoiceExtractionOrchestrator` in `lc-checker-svc/` drives Stage 1b. Sources
are split into two lanes that execute concurrently on `lcCheckExecutor`:

```
┌── Lane A (parallel) ────────────────────────────────┐
│   remote_vision   (cloud network call, 2–5 s)       │
└─────────────────────────────────────────────────────┘
┌── Lane B (sequential within lane) ──────────────────┐
│   docling → mineru → local_vision                   │
│   (all consume local CPU / RAM, run one-at-a-time)  │
└─────────────────────────────────────────────────────┘
                  ↓ join                              ↓
        persist all attempts → select one row → mark is_selected
```

1. Both lanes start simultaneously. Each source — wherever it runs — invokes
   the orchestrator's `runAndPersistOne` helper, which:
   - emits an `extract.source.started` SSE event,
   - calls `extract(pdfBytes, filename)` on that source,
   - persists one row in `pipeline_steps (stage='invoice_extract', step_key=<source>)`
     with `status`, `confidence`, `inv_output` JSONB, `llm_calls` JSONB,
   - emits an `extract.source.completed` SSE event with the per-source outcome.
2. After **both** lanes finish, attempts are collected in chain priority order
   (`remote_vision → docling → mineru → local_vision`) for selection:
   - First source in chain order whose `status = SUCCESS` AND
     `confidence >= extractor.confidence-threshold`.
   - Fallback: highest-confidence successful source.
   - If all failed: throw.
3. Return the selected `InvoiceDocument` — only this one drives Stage 2–3.

**Per-source persistence is preserved across lane failures** — `runAndPersistOne`
catches every exception and persists a `FAILED` row, so a single source's
crash never loses the other lane's results.

### Configuration

Sources are enabled/disabled via env vars (see [`../.env.example`](../.env.example)):

| Env var                         | Default | Notes |
|---------------------------------|---------|-------|
| `EXTRACTOR_VISION_ENABLED`      | `true`  | remote vision LLM (lane A) |
| `EXTRACTOR_LOCAL_VISION_ENABLED`| `false` | local Ollama vision LLM (lane B) |
| `EXTRACTOR_DOCLING_ENABLED`     | `false` | docling sidecar (lane B) |
| `EXTRACTOR_MINERU_ENABLED`      | `false` | mineru sidecar (lane B) |
| `EXTRACTOR_CONFIDENCE_THRESHOLD`| `0.80`  | selection cutoff |

**Disabled sources are HIDDEN from the UI**, not greyed — the chain only contains
enabled sources, so `/extracts` and the SSE events never include a disabled name.

### Local vision (Ollama) setup

To enable `local_vision`:

```bash
# 1. Install Ollama (https://ollama.com) and pull a vision-capable model.
ollama pull qwen2.5vl       # ~6 GB; or use moondream:2b for ~1.5 GB
ollama serve                # exposes /v1/chat/completions on :11434

# 2. Confirm reachable.
curl http://localhost:11434/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen2.5vl","messages":[{"role":"user","content":"hi"}]}'

# 3. Flip the env var and restart the service.
echo "EXTRACTOR_LOCAL_VISION_ENABLED=true" >> .env
echo "LOCAL_VISION_BASE_URL=http://localhost:11434/v1" >> .env
echo "LOCAL_VISION_MODEL=qwen2.5vl" >> .env
```

`local_vision` shares the same Java class as `remote_vision`
(`VisionLlmExtractor` — see `stage/extract/vision/VisionExtractorBeans.java`)
but is wired as a separate Spring bean with its own config block.

---

## Debug endpoint

Compare every enabled source against the same PDF without persisting:

```bash
curl -sS -X POST http://localhost:8080/api/v1/debug/invoice/compare \
     -F "invoice=@docs/refer-doc/invoice-1-apple.pdf"
```

Returns plain text with per-source field dumps, confidence, and duration. Handy for
picking thresholds and debugging field-capture regressions.

---

## Running Python parsers locally

Both parsers run directly on the host (they are NOT part of `docker-compose.yml`;
only Postgres is). Each has its own `pyproject.toml` and uvicorn entry point:

```bash
# Docling — port 8081
cd extractors/docling
uv venv .venv && source .venv/bin/activate
uv pip install -e .
uvicorn app.main:app --host 0.0.0.0 --port 8081

# MinerU — port 8082 (separate terminal)
cd extractors/mineru
uv venv .venv && source .venv/bin/activate
uv pip install -e .
uvicorn app.main:app --host 0.0.0.0 --port 8082
```

Then enable them in `.env` and restart the Java service:
```bash
EXTRACTOR_DOCLING_ENABLED=true
EXTRACTOR_MINERU_ENABLED=true
```

Individual service docs:
- [`docling/README.md`](docling/README.md)
- [`mineru/README.md`](mineru/README.md)

---

## Adding a new source

1. Implement the `InvoiceExtractor` interface in `lc-checker-svc/` (returns
   `ExtractionResult(document, llmCalls)`).
2. Register it as a Spring bean and add the `EXTRACTOR_<NAME>_ENABLED` flag.
3. Add the source to the `InvoiceExtractionOrchestrator` constructor chain in
   priority order.
4. If it's a Python HTTP parser, implement `CONTRACT.md v1.0` and register its
   RestClient bean — reuse `HttpInvoiceExtractor` for the wire protocol.

No schema changes required — `stage_invoice_extract.source VARCHAR(20)` already
accepts arbitrary source names.
