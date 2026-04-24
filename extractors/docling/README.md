# docling-svc

LC Invoice Checker — Docling-backed PDF extractor service.
Implements the FROZEN `extractors/CONTRACT.md` v1.0.

## What it does

Converts a commercial invoice PDF into the unified JSON shape the
`lc-checker-api` Java client consumes. Accepts the PDF via three input
modes (multipart upload / JSON `{path}` / JSON `{url}`), runs Docling's
layout + OCR pipeline, then structures the output into the 18 contract
fields via a deterministic regex tier plus an optional LLM tier.

## Endpoints

- `GET  /health` — liveness + identity (`contract_version`, `service_version`, `library_version`).
- `POST /extract` — main endpoint; returns the full `ExtractResponse` per contract.

See `../CONTRACT.md` for the authoritative request/response schema and error codes.

## Layout

```
extractors/docling/
├── Dockerfile             Multi-stage build; warms Docling models during build.
├── pyproject.toml         uv-managed dependencies (Python 3.12).
├── app/
│   ├── __init__.py        CONTRACT_VERSION / EXTRACTOR_NAME / SERVICE_VERSION.
│   ├── main.py            FastAPI app + lifespan warm-up + error handlers.
│   ├── contract.py        Pydantic models mirroring CONTRACT.md v1.0.
│   ├── errors.py          Error codes + ExtractorError subclasses per contract.
│   ├── fetching.py        Input-mode dispatch (multipart / path / url).
│   ├── docling_extractor.py  Docling wrapper — raw_markdown, confidence, is_image_based.
│   └── field_extractor.py    Tier-1 regex + optional Tier-2 LLM structuring.
└── tests/
    ├── test_contract.py        Pydantic schema round-trip guarantees.
    ├── test_field_extractor.py Regex/heuristic unit tests.
    ├── test_docling_coercion.py mean_grade + is_image_based helper tests.
    └── test_endpoints.py       End-to-end FastAPI tests with Docling stubbed.
```

## Decisions documented here (per contract Q3)

### `confidence`

```
confidence = float(result.confidence.mean_grade)
```

Pulled directly from Docling's `ConfidenceReport`. If a future Docling
version returns `mean_grade` as a categorical enum instead of a float,
`_coerce_mean_grade` maps deterministically:

| Grade | Value |
|---|---|
| EXCELLENT | 1.00 |
| GOOD | 0.85 |
| FAIR | 0.65 |
| POOR | 0.35 |
| UNKNOWN | 0.00 |

### `is_image_based`

Per-page test using Docling's native score separation:

```
page_is_image = ocr_score >= 0.5 AND parse_score < 0.3
is_image_based = (image_pages * 2) >= total_pages    # majority rule
```

Correctly distinguishes scanned PDFs (OCR carried the content) from
born-digital PDFs (embedded text dominates), before or after OCR runs.

## Running locally

```bash
# Deps via uv (preferred per team-lead direction)
cd extractors/docling
uv venv
uv pip install -e .

# Run the service
uvicorn app.main:app --host 0.0.0.0 --port 8081

# Smoke test
curl http://localhost:8081/health
curl -X POST http://localhost:8081/extract \
     -F "file=@../../test/sample_invoice.pdf;type=application/pdf" | jq
```

## Running the tests (no Docling needed)

The unit-test suite stubs Docling out, so you can run tests against a
minimal dep set in seconds:

```bash
python3 -m venv .testvenv
.testvenv/bin/pip install fastapi 'pydantic>=2.8' httpx python-dateutil \
    openai python-json-logger python-multipart pytest pytest-asyncio
.testvenv/bin/pytest tests/
# → 39 passed
```

The real Docling behaviour is verified at Docker build time (the
Dockerfile runs `warm_up()` during the builder stage) and by qa's
end-to-end curl (project task #25).

## Docker

```bash
docker build -t docling-svc:1.0.0 extractors/docling
docker run --rm -p 8081:8081 docling-svc:1.0.0
curl http://localhost:8081/health
```

Image size is ~1.5–2 GB because the Dockerfile bakes Docling's layout
and OCR model weights into the builder stage and copies them into the
runtime stage. This trades image size for zero cold-start on the first
request — the right call for a demo stack. Devops has been notified.

## Environment variables

| Var | Default | Purpose |
|---|---|---|
| `LOG_LEVEL` | `INFO` | Python logging level for the root logger. |
| `LLM_API_KEY` | — | If set, enables the Tier-2 LLM structuring path for MED-reliability fields (seller/buyer/goods_description). Without it the service still returns those fields using the Tier-1 fallback. |
| `LLM_BASE_URL` | `https://api.deepseek.com` | OpenAI-compatible base URL. |
| `LLM_MODEL` | `deepseek-chat` | Model name for the LLM structuring call. |
