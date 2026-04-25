# Extractors — Invoice Extraction Sources

Stage 1b of the pipeline (`refer-doc/logic-flow.md`). Three independent sources
convert a PDF invoice into an `InvoiceDocument`; the Java orchestrator runs every
enabled source, persists one `stage_invoice_extract` row per source, and marks one
as the **selected** source for downstream rule checks.

| Source    | Lives in                 | Runtime | LLM involved? | Typical use |
|-----------|--------------------------|---------|---------------|-------------|
| `vision`  | `lc-checker-svc` (Java)  | JVM     | **Yes** — Vision LLM via Spring AI | Image-based PDFs, scanned invoices, layout-heavy documents |
| `docling` | `extractors/docling/`    | Python  | No            | Born-digital PDFs with a clean text layer |
| `mineru`  | `extractors/mineru/`     | Python  | No            | Image-heavy PDFs where Docling's OCR struggles |

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

## Orchestration (Java side)

`InvoiceExtractionOrchestrator` in `lc-checker-svc/` drives Stage 1b:

1. For every enabled source (priority order: `vision → docling → mineru`):
   - Call `extract(pdfBytes, filename)`.
   - Persist one row in `stage_invoice_extract` with `source`, `status`, `confidence`,
     `raw_markdown`, `raw_text`, `inv_output` JSONB, `llm_calls` JSONB.
2. Select one row as `is_selected = true`:
   - First source (priority order) whose `status = SUCCESS` AND `confidence >= extractor.confidence-threshold`.
   - Fallback: highest-confidence successful source.
   - If all failed: throw.
3. Return the selected `InvoiceDocument` — only this one drives Stage 2–3.

The selection rule + threshold are configurable via `extractor.confidence-threshold`.
Sources are enabled/disabled with `EXTRACTOR_VISION_ENABLED`, `EXTRACTOR_DOCLING_ENABLED`,
`EXTRACTOR_MINERU_ENABLED` (see [`../lc-checker-svc/README.md`](../lc-checker-svc/README.md)).

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
