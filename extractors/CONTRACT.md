# Extractor Service HTTP Contract (V1)

This contract is the source-of-truth interface between `lc-checker-api` (Java client) and the Python extractor services (`docling-svc`, `mineru-svc`). **Both services implement this contract identically** so the Java client can swap between them with no code change.

| Owner | Concerns |
|---|---|
| `extractor-dev` | Implements both services to this spec. May propose changes via SendMessage → team-lead. |
| `team-lead` | Consumes the contract from Java via `RestClient`. May propose changes via SendMessage → extractor-dev. |
| `devops` | Wires service names, ports, networking, env-vars in `docker-compose.yml`. |

**Status**: `FROZEN — V1 Phase 0`. Any change requires re-approval from both `team-lead` and `extractor-dev`.

---

## Status: FROZEN

Contract version: `1.0`
Approved by: `extractor-dev`, `team-lead` — 2026-04-24

---

## Service identity

| Service | Host (docker network) | Host (local dev) | Port |
|---|---|---|---|
| docling-svc | `docling-svc` | `localhost` | `8081` |
| mineru-svc  | `mineru-svc`  | `localhost` | `8082` |

Both services share the same public API below. Only the `extractor` field in responses distinguishes them.

---

## Endpoints

### `GET /health`

Liveness + identity probe. Used by docker healthcheck and by Java client pre-flight.

**Response 200**:
```json
{
  "status": "ok",
  "extractor": "docling",
  "contract_version": "1.0",
  "service_version": "0.1.0",
  "library_version": "2.12.0",
  "model_versions": {
    "docling": "2.12.0"
  }
}
```

- `contract_version` — version of THIS contract spec the service implements. Clients that pin a contract version compare against this key.
- `service_version` — the service's own build/release version (semver of the FastAPI app).
- `library_version` — version of the primary extraction library (Docling for docling-svc, MiniRU for mineru-svc).

Return `503` if the service can load but the underlying model/library is unavailable.

### `POST /extract`

Main extraction endpoint. Accepts **one of three** input modes per request.

#### Mode A — Multipart PDF upload

```
Content-Type: multipart/form-data
file: <binary PDF bytes>
```

- Field name: `file` (exact).
- Content-Type of the part: `application/pdf`.
- Server should reject non-PDF content-types with `400 INVALID_FILE_TYPE`.
- Max body size: 20 MB (reject with `413 PAYLOAD_TOO_LARGE` otherwise).

#### Mode B — JSON body with local file path

```
Content-Type: application/json

{"path": "/mnt/shared/invoice-123.pdf"}
```

- Path must be accessible from inside the container. The client (Java) is responsible for mounting a shared volume if using this mode.
- V1 Java client does NOT use this mode; it is present for V2 batch flows.

#### Mode C — JSON body with URL

```
Content-Type: application/json

{"url": "s3://my-bucket/invoices/invoice-123.pdf"}
{"url": "https://example.com/invoice.pdf"}
```

- Supported schemes: `s3://`, `https://`, `http://` (http only on non-prod).
- If `s3://` is used, the service must have `AWS_*` or `MINIO_*` env-vars configured.
- V1 Java client does NOT use this mode; it is present for V2 MinIO flows.

**Exactly one** of the three modes per request. Mixing modes → `400 INVALID_REQUEST`.

#### Response 200 — unified schema (all three input modes)

```json
{
  "extractor": "docling",
  "contract_version": "1.0",
  "confidence": 0.92,
  "is_image_based": false,
  "pages": 1,
  "raw_markdown": "# Invoice INV-2024-001 ...",
  "raw_text": "Invoice INV-2024-001 ...",
  "fields": {
    "invoice_number": "INV-2024-001",
    "invoice_date": "2024-03-15",
    "seller_name": "WIDGET EXPORTS PTE LTD",
    "seller_address": "88 INDUSTRIAL AVENUE, SINGAPORE 638888",
    "buyer_name": "SINO IMPORTS CO LTD",
    "buyer_address": "123 NATHAN ROAD, KOWLOON, HONG KONG",
    "goods_description": "1000 UNITS INDUSTRIAL WIDGETS MODEL IW-2024",
    "quantity": "1000",
    "unit": "UNITS",
    "unit_price": "50.00",
    "total_amount": "50000.00",
    "currency": "USD",
    "lc_reference": "LC2024-000123",
    "trade_terms": "CIF SINGAPORE",
    "port_of_loading": "PORT KLANG",
    "port_of_discharge": "SINGAPORE",
    "country_of_origin": "SINGAPORE",
    "signed": true
  },
  "extraction_ms": 1234
}
```

##### Field semantics

| Field | Type | Required | Notes |
|---|---|---|---|
| `extractor` | string | yes | Literal `"docling"` or `"mineru"`. |
| `contract_version` | string | yes | Version of THIS contract the service implements. Currently `"1.0"`. |
| `confidence` | float [0,1] | yes | Overall confidence. Java uses `< extractor.confidence-threshold` (default 0.80) to trigger fallback. |
| `is_image_based` | bool | yes | `true` when the PDF was predominantly scanned images — OCR carried the content. See "`is_image_based` detection" section below for the authoritative per-service heuristic. Drives V2 routing to vision LLM. |
| `pages` | int | yes | Page count of the source PDF. Cheap to compute; lets `/trace` show document size without parsing `raw_markdown`. |
| `raw_markdown` | string | yes | Full markdown conversion. May be empty string if image-based. |
| `raw_text` | string | yes | Plain-text extraction. Source for Java fallback re-parse. |
| `fields` | object | yes | Best-effort structured extraction. All fields listed below are present as keys; value is `null` when unknown. Services MUST NOT drop keys. |
| `extraction_ms` | int | yes | Service-side extraction wall time (ms) from request received → response written. One `time.perf_counter()` subtraction; no reason to omit. Populates the `/trace` endpoint's per-stage latency. |

##### `fields` contract — keys and value expectations

Every key below is always present in the response (value may be `null`). Java's `InvoiceDocument` record depends on this.

| Key | Type | Format expectations |
|---|---|---|
| `invoice_number` | string\|null | As printed on invoice. |
| `invoice_date` | string\|null | ISO 8601 `YYYY-MM-DD` if determinable; else the raw string as printed. |
| `seller_name` | string\|null | Beneficiary / exporter. |
| `seller_address` | string\|null | Multiline joined with `\n`. |
| `buyer_name` | string\|null | Applicant / importer. |
| `buyer_address` | string\|null | Multiline joined with `\n`. |
| `goods_description` | string\|null | Full goods text. |
| `quantity` | string\|null | Numeric-looking string if possible (`"1000"`); raw if ambiguous. |
| `unit` | string\|null | `UNITS` / `PCS` / `MT` / `KG` / null. |
| `unit_price` | string\|null | Numeric string if possible (`"50.00"`); no currency symbol. |
| `total_amount` | string\|null | Numeric string, decimal point (`"50000.00"`). |
| `currency` | string\|null | ISO 4217 3-letter where possible (`USD`). |
| `lc_reference` | string\|null | LC number as printed, or `null` if absent. **Critical for INV-007 check.** |
| `trade_terms` | string\|null | `CIF SINGAPORE`, `FOB SHANGHAI`, etc. |
| `port_of_loading` | string\|null | — |
| `port_of_discharge` | string\|null | — |
| `country_of_origin` | string\|null | — |
| `signed` | bool\|null | Best-effort indicator of signature presence. |

Services MAY include additional keys under `fields.extras` (namespaced to avoid collision) if they extract extra data, but no extras are required. Java ignores unknown keys.

#### Response 400 — input errors

```json
{
  "error": "INVALID_FILE_TYPE",
  "message": "Only application/pdf is accepted; got text/plain",
  "extractor": "docling"
}
```

Error codes:

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `INVALID_FILE_TYPE` | Upload was not a PDF. |
| 400 | `INVALID_REQUEST` | Missing file / multiple input modes / malformed JSON. |
| 404 | `PATH_NOT_FOUND` | Path mode: path not readable inside container. |
| 413 | `PAYLOAD_TOO_LARGE` | >20 MB. |
| 502 | `FETCH_FAILED` | URL mode: remote fetch failed. |
| 501 | `S3_NOT_SUPPORTED` | V1 does not support `s3://` URLs. |
| 500 | `EXTRACTION_FAILED` | Library raised an error. Java falls back to the other extractor. |
| 504 | `EXTRACTION_TIMEOUT` | Extraction exceeded the 25 s service budget. Java MAY retry with reduced OCR (V2) rather than fall back to a different extractor. |

#### Response 5xx — extraction error

```json
{
  "error": "EXTRACTION_FAILED",
  "message": "docling raised RuntimeError: ...",
  "extractor": "docling"
}
```

The Java client treats:
- `EXTRACTION_FAILED` (500) → try the fallback extractor immediately.
- `EXTRACTION_TIMEOUT` (504) → treat as a transient, retryable condition distinct from a hard failure (V1: still falls back to other extractor; V2: may retry with `ocr_mode=off`).
- All other non-2xx → propagate to the API caller via `ExtractionException`.

---

## Operational requirements

### Healthcheck

Docker healthcheck in `docker-compose.yml`:
```yaml
healthcheck:
  test: ["CMD", "curl", "-fsS", "http://localhost:8081/health"]
  interval: 10s
  timeout: 3s
  start_period: 30s
  retries: 3
```

### Timeout budget

| Client-side | Value |
|---|---|
| Java `RestClient` read timeout | 30 s |
| Java retry-on-timeout | 1 retry (same URL) |

Services SHOULD return 500 if their own extraction exceeds 25 s, giving the client time to fall back within its budget.

### Versioning

- Increment `contract_version` MINOR for additive changes (new fields added to `fields`, new optional request params).
- Increment `contract_version` MAJOR for breaking changes (field renames, type changes, error-code changes). Requires re-approval from team-lead + extractor-dev.
- `service_version` and `library_version` evolve independently of `contract_version`; they're informational only.

### Logging (recommended)

Both services should log a single structured line per request:
```
{"level":"info","event":"extract","input_mode":"multipart","duration_ms":1234,"confidence":0.92,"extractor":"docling"}
```

Java can scrape this for cross-service correlation once OTel is wired in V2.

### `is_image_based` detection

**Behavioural intent**: `true` when the PDF was predominantly scanned images (no embedded text layer, content recovered via OCR). Drives V2 routing to vision LLM and informs Java fallback decisions in V1.

**Why the draft's char-per-page heuristic is wrong**: once Docling runs OCR on a scan, `raw_text` is populated, so a post-extraction char-density check will always return `false` for successful scans. We need a signal from *before* OCR fills the gap.

**docling-svc (authoritative)**: use Docling's own per-page confidence, which separates embedded-text quality (`parse_score`) from OCR quality (`ocr_score`):

```python
def is_image_based(confidence_report) -> bool:
    """
    A page is image-based when OCR carried the content (high ocr_score)
    but there was little-to-no embedded text (low parse_score).
    PDF is image-based overall if >= 50% of pages meet that condition.
    """
    pages = list(confidence_report.pages.values())
    if not pages:
        return False
    image_pages = sum(
        1 for p in pages
        if (p.ocr_score or 0) >= 0.5 and (p.parse_score or 0) < 0.3
    )
    return image_pages * 2 >= len(pages)
```

**mineru-svc**: MiniRU does not expose the same score separation. Falls back to a two-pass approach: extract once with OCR disabled → if char count per page < 50, flag image-based; then re-run with OCR on for the actual content. Implementation detail for MiniRU; Java treats the boolean identically regardless of service.

**Contract guarantee**: the boolean is populated according to behavioural intent above. Services are free to refine the heuristic without breaking the contract.

---

## Resolved questions (Phase 0 review)

1. **Base image — RESOLVED.** Both services use `python:3.12-slim` as base, but each has its own Dockerfile with service-specific apt packages (docling: `tesseract-ocr`, `libgl1`; mineru: `poppler-utils`, `libmagic1`). No shared intermediate image in V1 — the duplicated layer-caching cost is small, and service-specific deps make a single shared image leaky. Can be revisited in V2 if both images cross 2 GB.

2. **`s3://` URLs — RESOLVED.** V1 returns `501 NOT_IMPLEMENTED` with error code `S3_NOT_SUPPORTED` for `s3://` URLs. `http://` and `https://` URL fetching IS supported in V1 (via `httpx` with 20 s timeout). `S3_NOT_SUPPORTED` is added to the error-code table below.

3. **`confidence` definition — RESOLVED.**
   - **docling-svc**: `confidence = float(result.confidence.mean_grade)` — Docling exposes a per-document `mean_grade` in `[0, 1]` that aggregates per-page `layout_score / ocr_score / parse_score` (table_score is not yet implemented by the library). If at implementation time `mean_grade` turns out to be a categorical grade instead of a float, it's mapped deterministically and documented in `extractors/docling/README.md`.
   - **mineru-svc**: `confidence = non_null_field_count / len(CONTRACT_FIELDS)` — best-effort proxy until MiniRU adds native scoring.
   - Both yield `[0, 1]`; Java's `extractor.confidence-threshold = 0.80` fallback rule works identically regardless of which extractor served the request.

4. **`signed` detection — RESOLVED.** `signed` is `null` when Docling cannot confidently detect a signature via keyword scan or picture-element proximity. Java treats `null` as "unknown, not a discrepancy" for UCP 18(d).

---

## Field extraction feasibility (docling-svc, V1)

Response to team-lead's ask: which `fields` entries are realistic for Docling heuristic extraction, and which must be best-effort-null-OK.

| Field | Expected reliability | Notes |
|---|---|---|
| `invoice_number` | HIGH | Regex `INVOICE\s*(NO\|#\|NUMBER)[:.]?\s*(\S+)`. |
| `invoice_date` | HIGH | Regex + `dateparser`. |
| `total_amount` | HIGH | "Total" / "Grand Total" row pulled from Docling's table extraction. |
| `currency` | HIGH | ISO 4217 3-letter near the total row. |
| `quantity` / `unit` / `unit_price` | MED-HIGH | Line-item table; Docling's table extractor is strong. |
| `goods_description` | MED | Long-form. Service uses an in-container LLM call (OpenAI-compat endpoint) if `LLM_API_KEY` env-var is present; else joins line-item descriptions as a deterministic fallback. |
| `lc_reference` | MED | Regex `LC\s*(REF\|NO\|NUMBER)?[:.]?\s*([\w\-/]+)`. **Critical for INV-007 check.** |
| `trade_terms` | MED | Regex `\b(EXW\|FOB\|FAS\|FCA\|CPT\|CIP\|DAP\|DPU\|DDP\|CFR\|CIF)\b[\s\w,]*`. |
| `seller_name` / `seller_address` | MED | Usually near top of invoice; LLM-structured when available, else first recognised address block. |
| `buyer_name` / `buyer_address` | MED | "Bill To" / "Sold To" / "Consignee" block heuristic; LLM fallback. |
| `port_of_loading` / `port_of_discharge` | **LOW — best-effort null-OK** | Rarely present on plain invoices (more common on B/L). Regex `(LOADING\|DISCHARGE)\s*PORT[:.]?\s*([A-Z ]+)`; `null` if no match. |
| `country_of_origin` | **LOW — best-effort null-OK** | Regex `COUNTRY\s*OF\s*ORIGIN[:.]?\s*([A-Z ]+)`; `null` if not printed. |
| `signed` | LOW-MED | Keyword scan ("signed", "authorized signatory") plus Docling picture-detection near end of document; `null` if uncertain. |

**Implication for Java**: `TradeTermsChecker`, `PortChecker`, `CountryOfOriginChecker`, `AddressCountryChecker` MUST handle `null` from extractors gracefully (map to `UNABLE_TO_VERIFY`, not `DISCREPANT`). The V1 3-rule slice (INV-011, INV-015, INV-007) is unaffected — those rules only need `total_amount` / `currency` / `goods_description` / `lc_reference`, all of which are HIGH or MED reliability.

---

## Change log

- 2026-04-24 — Initial draft by team-lead. Pending review.
- 2026-04-24 — extractor-dev Phase 0 review:
  - Rewrote `is_image_based` heuristic to use Docling's `parse_score` vs `ocr_score` separation (draft version would always return `false` post-OCR).
  - Resolved open questions Q1 (base image), Q2 (s3://), Q3 (`confidence` formula), Q4 (`signed` null semantics).
  - Added field-extraction feasibility table flagging `port_of_loading` / `port_of_discharge` / `country_of_origin` as best-effort-null-OK for V1.
- 2026-04-24 — team-lead accepted all 3 polish items; FROZEN:
  - Renamed top-level `version` → `contract_version` in /health and /extract; added `service_version` + `library_version` to /health.
  - Made `extraction_ms` required; promoted `pages: int` to a required top-level field.
  - Added `EXTRACTION_TIMEOUT` (504) and `S3_NOT_SUPPORTED` (501) to the error codes table with distinct Java-client behaviour.
  - Status flipped to FROZEN. Further changes require re-approval from both roles.
