# mineru-svc

LC Invoice Checker — MinerU-backed PDF extractor service.
Implements the FROZEN `extractors/CONTRACT.md` v1.0. Runs as the fallback
/ second-opinion extractor behind docling-svc.

## Endpoints

- `GET  /health` — identical shape to docling-svc's; `extractor` is `"mineru"`.
- `POST /extract` — tri-modal input (multipart / `{path}` / `{url}`); same response contract.

## Why two extractors?

- **docling-svc** is primary. Excellent at born-digital PDFs with clean
  layouts and tables; exposes native per-page confidence scores.
- **mineru-svc** is fallback. MinerU has strong OCR and handles
  complex/scanned PDFs that Docling struggles with. Java's extractor
  router kicks over to mineru when docling returns `confidence < 0.80`.

Both services honour the same unified contract so the Java client can
swap between them with a config change only.

## Decisions documented here (per contract Q3)

### `confidence`

MinerU does not expose a native confidence/grade number, so per the
resolved CONTRACT.md Q3:

```
confidence = non_null_field_count / len(CONTRACT_FIELD_KEYS)   # = 18
```

Deterministic, in `[0, 1]`, and interprets naturally: "how much of the
expected schema did we actually fill?" Java's
`extractor.confidence-threshold = 0.80` rule applies identically to
docling-svc and mineru-svc outputs.

### `is_image_based`

Two-pass heuristic:

1. **Pre-OCR density probe** — open the PDF with PyMuPDF (`fitz`) and
   count characters per page on the raw text layer with no OCR. If
   `chars/page < 50`, the PDF is image-based.
2. **OCR-enabled pass** — MinerU's own pipeline runs with OCR; its
   output populates `raw_markdown` / `raw_text` regardless of the
   image-based classification.

If the pre-OCR probe fails (PyMuPDF import error, corrupt PDF),
`is_image_based` defaults to `False` — conservative, matches the
contract's "unknown → False" semantics.

## Layout

Intentionally mirrors `extractors/docling/` one-for-one — the services
share `contract.py`, `errors.py`, `fetching.py`, and `field_extractor.py`
verbatim by design. Only these two files differ:

- `app/mineru_extractor.py` — MinerU wrapper (replaces Docling's).
- `app/main.py` — uses `mineru_extractor` + computes confidence via
  the non-null formula above.

```
extractors/mineru/
├── Dockerfile
├── pyproject.toml
├── app/
│   ├── contract.py          ← shared with docling-svc (verbatim)
│   ├── errors.py            ← shared
│   ├── fetching.py          ← shared
│   ├── field_extractor.py   ← shared
│   ├── mineru_extractor.py  ← this service's wrapper
│   └── main.py              ← mineru-flavoured
└── tests/
    ├── test_contract.py       ← shared
    ├── test_field_extractor.py← shared
    └── test_endpoints.py      ← mineru-specific (confidence formula assertion)
```

### Refactor note for V2

Keeping two copies of the shared modules is deliberate for V1 — it
makes each service's Docker build context self-contained, and both
services are owned by the same role (extractor-dev) so drift risk is
low. V2 should consider extracting the shared files into a pip-installable
`extractors-shared` package in the repo root, consumed by both services
via `uv`. This will get more compelling if V2 adds a third extractor
(e.g. a vision-LLM-backed one).

## Running locally

```bash
cd extractors/mineru
uv venv
uv pip install -e .

uvicorn app.main:app --host 0.0.0.0 --port 8082
curl http://localhost:8082/health
curl -X POST http://localhost:8082/extract \
     -F "file=@../../test/sample_invoice.pdf;type=application/pdf" | jq
```

## Tests

```bash
python3 -m venv .testvenv
.testvenv/bin/pip install fastapi 'pydantic>=2.8' httpx python-dateutil \
    openai python-json-logger python-multipart pytest pytest-asyncio
.testvenv/bin/pytest tests/
# → 28 passed
```

MinerU is monkey-patched in `test_endpoints.py` so the unit suite runs
without the 2 GB MinerU install. Real MinerU extraction is exercised at
Docker build time and by qa's end-to-end curl (project task #25).

## Docker

```bash
docker build -t mineru-svc:1.0.0 extractors/mineru
docker run --rm -p 8082:8082 mineru-svc:1.0.0
curl http://localhost:8082/health
```

Image size is comparable to docling-svc (~2 GB). The Dockerfile baked-in
model warm-up is guarded (`|| echo skipped`) so a build never fails on a
MinerU API-surface mismatch — the service will surface the real error
at first `/extract` call instead.

## Environment variables

Same set as docling-svc (LOG_LEVEL, optional LLM_API_KEY / LLM_BASE_URL /
LLM_MODEL for the Tier-2 structuring pass). See docling's README.
