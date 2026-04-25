# Local LLM Invoice OCR & LC Compliance Checker
## Requirements Plan for Claude Code

---

## Project Overview

Build a local Python service running on MacBook M1 Pro 16GB that:
1. Extracts structured fields from commercial invoice images via Vision LLM (OCR)
2. Checks extracted invoice data against Letter of Credit (LC) terms using LLM rule reasoning
3. Exposes a simple REST API for integration with other local services
4. Is fully configurable to swap models without code changes

All inference runs locally via MLX on Apple Silicon. No data leaves the machine.

---

## Tech Stack

- **Runtime**: Python 3.11+
- **LLM Framework**: `mlx-vlm` (vision), `mlx-lm` (text)
- **API Server**: FastAPI + Uvicorn
- **Config**: Pydantic Settings + `.env` file
- **Models** (HuggingFace mlx-community, downloaded on first run):
  - Default Vision+Text: `mlx-community/Qwen3-VL-8B-Instruct-4bit`
  - Light Vision+Text:   `mlx-community/Qwen3-VL-4B-Instruct-4bit`
  - Text-only fallback:  `mlx-community/Qwen3-4B-Instruct-2507-4bit`
- **No thinking mode**: Always use `-Instruct` models (not `-Thinking`), pass `enable_thinking=False`

---

## Project Structure

```
invoice-lc-checker/
├── .env                        # Model selection and runtime config
├── requirements.txt
├── README.md
├── config.py                   # ModelConfig, ModelRegistry, PresetConfig
├── model_manager.py            # MLX model load/unload, memory management
├── ocr/
│   ├── __init__.py
│   ├── extractor.py            # Vision model invoice field extraction
│   └── prompts.py              # OCR prompt templates
├── lc/
│   ├── __init__.py
│   ├── checker.py              # Text LLM LC compliance checking
│   └── prompts.py              # LC rule prompt templates
├── api/
│   ├── __init__.py
│   ├── main.py                 # FastAPI app entry point
│   ├── routes.py               # /ocr, /lc-check, /pipeline, /health endpoints
│   └── schemas.py              # Pydantic request/response models
├── pipeline.py                 # Orchestrates OCR → LC check flow
└── tests/
    ├── sample_invoice.jpg      # Test invoice image
    ├── sample_lc_terms.json    # Test LC terms
    └── test_pipeline.py
```

---

## Environment Config (.env)

```env
# Model preset: light | balanced | quality
LLM_PRESET=balanced

# Override individual models (optional, overrides preset)
VISION_MODEL_OVERRIDE=
TEXT_MODEL_OVERRIDE=

# API server
API_HOST=127.0.0.1
API_PORT=8080

# Inference settings
MAX_TOKENS_OCR=1024
MAX_TOKENS_LC=2048
TEMPERATURE=0.1
ENABLE_THINKING=false          # Always false for Instruct models

# Memory management
CONCURRENT_MODELS=true         # true for 4B+4B, false for 8B serial mode
CLEAR_CACHE_BETWEEN_CALLS=false
```

---

## config.py Specification

### ModelConfig dataclass
Fields: `name: str`, `repo_id: str`, `model_type: Literal["vlm","lm"]`, `quantization: str`, `max_tokens: int`, `temperature: float = 0.1`

### MODEL_REGISTRY dict (key = model name string)
Must include these entries:
- `"qwen3-vl-4b"` → `mlx-community/Qwen3-VL-4B-Instruct-4bit`, type=vlm
- `"qwen3-vl-8b"` → `mlx-community/Qwen3-VL-8B-Instruct-4bit`, type=vlm
- `"qwen3-4b"`    → `mlx-community/Qwen3-4B-Instruct-2507-4bit`, type=lm
- Registry must be extensible: adding a new model = adding one dict entry only

### PRESET_CONFIGS dict (key = PresetEnum)
- `LIGHT`:    vision=qwen3-vl-4b, text=qwen3-vl-4b, concurrent=True,  single_model=True
- `BALANCED`: vision=qwen3-vl-8b, text=qwen3-vl-8b, concurrent=True,  single_model=True
- `QUALITY`:  vision=qwen3-vl-8b, text=qwen3-4b,    concurrent=False, single_model=False

---

## model_manager.py Specification

### Class: ModelManager

**Constructor**: `__init__(self, preset: PresetEnum, vision_override: str = None, text_override: str = None)`
- Reads preset from config
- Allows per-instance model override for A/B testing

**Key methods**:

```
load_vision(model_name)   → loads mlx_vlm model+processor, skips if already loaded
load_text(model_name)     → loads mlx_lm model+tokenizer, skips if already loaded
unload_vision()           → del model, call mx.metal.clear_cache(), gc.collect()
unload_text()             → same
get_memory_usage() → dict → returns approximate current memory in GB (use mx.metal.get_peak_memory())

ocr_invoice(
  image_path: str,
  model_override: str = None,
  prompt_override: str = None
) -> str                  → raw JSON string from model

check_lc(
  invoice_data: dict,
  lc_terms: dict,
  model_override: str = None,
  extra_rules: str = None
) -> dict                 → parsed compliance result dict
```

**Memory management rules**:
- If `single_model=True`: load vision model once, reuse for text calls (both use same vlm instance)
- If `single_model=False`: unload vision before loading text (serial mode)
- Always pass `enable_thinking=False` to `apply_chat_template` for all models
- Never load two 8B models simultaneously

---

## ocr/extractor.py Specification

**Function**: `extract_invoice_fields(image_path: str, manager: ModelManager, model_override: str = None) -> InvoiceData`

**Behavior**:
- Open image with PIL
- Call `manager.ocr_invoice(image_path, model_override)`
- Parse JSON output → InvoiceData pydantic model
- On JSON parse failure: retry once with stricter prompt asking for valid JSON only
- On second failure: raise `OCRExtractionError` with raw model output attached

**Output schema (InvoiceData)**:
```python
class GoodsLine(BaseModel):
    description: str
    quantity: str
    unit_price: str
    amount: str
    hs_code: str = ""

class InvoiceData(BaseModel):
    invoice_no: str
    invoice_date: str
    shipper: dict           # name, address, country
    consignee: dict         # name, address, country
    notify_party: dict = {}
    goods: List[GoodsLine]
    total_amount: str
    currency: str
    incoterms: str = ""
    port_of_loading: str = ""
    port_of_discharge: str = ""
    lc_no: str = ""
    raw_text: str = ""      # full model output for debugging
```

---

## ocr/prompts.py Specification

Define `OCR_SYSTEM_PROMPT` and `OCR_USER_PROMPT_TEMPLATE` as module-level constants.

OCR prompt must instruct the model to:
- Extract ALL visible fields from the invoice image
- Return ONLY valid JSON matching the InvoiceData schema above
- Not add any preamble, markdown, or explanation
- Use empty string `""` for fields not found, never null
- Preserve original currency and amount strings exactly as shown

---

## lc/checker.py Specification

**Function**: `check_lc_compliance(invoice: InvoiceData, lc_terms: LCTerms, manager: ModelManager, extra_rules: str = None) -> LCCheckResult`

**Output schema (LCCheckResult)**:
```python
class Discrepancy(BaseModel):
    field: str
    invoice_value: str
    lc_requirement: str
    severity: Literal["critical", "major", "minor"]
    ucp600_article: str = ""    # e.g. "Article 14", "Article 20"

class LCCheckResult(BaseModel):
    compliant: bool
    discrepancies: List[Discrepancy]
    warnings: List[str]
    summary: str
    checked_at: str             # ISO timestamp
```

**LC Terms input schema (LCTerms)**:
```python
class LCTerms(BaseModel):
    lc_no: str
    issuing_bank: str = ""
    applicant: str = ""
    beneficiary: str = ""
    amount: str
    currency: str
    expiry_date: str
    latest_shipment_date: str = ""
    port_of_loading: str = ""
    port_of_discharge: str = ""
    partial_shipment: Literal["allowed", "not allowed"] = "not allowed"
    transhipment: Literal["allowed", "not allowed"] = "not allowed"
    goods_description: str = ""
    special_conditions: str = ""
```

---

## lc/prompts.py Specification

Define `LC_SYSTEM_PROMPT` as module-level constant covering:
- UCP 600 rules as the compliance framework
- Strict JSON-only output instruction
- Severity classification guide (critical = amount/currency/date, major = port/goods, minor = formatting)
- Instruction to cite relevant UCP 600 article for each discrepancy where applicable

---

## api/schemas.py Specification

```python
# Request schemas
class OCRRequest(BaseModel):
    image_base64: str           # base64 encoded image
    model_override: str = None

class LCCheckRequest(BaseModel):
    invoice: InvoiceData
    lc_terms: LCTerms
    model_override: str = None
    extra_rules: str = None

class PipelineRequest(BaseModel):
    image_base64: str
    lc_terms: LCTerms
    vision_model_override: str = None
    text_model_override: str = None
    extra_rules: str = None

# Response schemas
class OCRResponse(BaseModel):
    success: bool
    invoice: InvoiceData = None
    error: str = None
    model_used: str
    processing_time_ms: float

class LCCheckResponse(BaseModel):
    success: bool
    result: LCCheckResult = None
    error: str = None
    model_used: str
    processing_time_ms: float

class PipelineResponse(BaseModel):
    success: bool
    invoice: InvoiceData = None
    lc_result: LCCheckResult = None
    error: str = None
    vision_model_used: str
    text_model_used: str
    total_processing_time_ms: float

class HealthResponse(BaseModel):
    status: str
    preset: str
    vision_model_loaded: str = None
    text_model_loaded: str = None
    memory_usage_gb: float = None
    available_models: List[str]
```

---

## api/routes.py Specification

### Endpoints

**POST /ocr**
- Accept: `OCRRequest` (base64 image)
- Decode base64 → save to temp file → call `extract_invoice_fields` → delete temp file
- Return: `OCRResponse`
- Error handling: return `success=False` with error message, HTTP 200 always (let client decide)

**POST /lc-check**
- Accept: `LCCheckRequest`
- Call `check_lc_compliance`
- Return: `LCCheckResponse`

**POST /pipeline**
- Accept: `PipelineRequest`
- Runs OCR → LC check in sequence
- Return: `PipelineResponse` with both invoice and lc_result
- If OCR fails, skip LC check and return error

**GET /health**
- Return: `HealthResponse` with loaded model names and memory usage
- Never fails, always returns HTTP 200

**GET /models**
- Return: list of all available model names from MODEL_REGISTRY with their metadata

---

## api/main.py Specification

- Create FastAPI app with title "Invoice LC Checker"
- Instantiate `ModelManager` as app-level singleton on startup
- Use `@app.on_event("startup")` to pre-warm vision model (load but don't run inference)
- Use `@app.on_event("shutdown")` to unload all models and clear cache
- Include router from `routes.py`
- Add CORS middleware allowing localhost origins

---

## pipeline.py Specification

**Function**: `run_pipeline(image_path: str, lc_terms: LCTerms, manager: ModelManager, ...) -> PipelineResponse`

- Records start time
- Calls OCR, catches errors
- If OCR succeeds, calls LC check
- Records per-stage timings
- Returns PipelineResponse with full details

---

## requirements.txt

```
mlx-vlm>=0.3.4
mlx-lm>=0.21.0
fastapi>=0.115.0
uvicorn>=0.30.0
pydantic>=2.0.0
pydantic-settings>=2.0.0
pillow>=10.0.0
python-multipart>=0.0.9
python-dotenv>=1.0.0
```

---

## Implementation Notes for Claude Code

1. **Model download**: mlx-vlm and mlx-lm auto-download from HuggingFace on first `load()` call.
   No manual download step needed. Models cache to `~/.cache/huggingface/hub/`.

2. **enable_thinking=False**: Apply to ALL `apply_chat_template` calls. This is critical — 
   never omit it, even on Instruct models, to guarantee no thinking overhead.

3. **Single model reuse**: When `single_model=True`, the loaded VLM instance handles both
   vision and text calls. Text-only calls pass no image, just messages.

4. **JSON retry logic**: Model sometimes outputs markdown-wrapped JSON (```json ... ```). 
   Strip these before parsing. On failure, retry with prompt: "Output ONLY valid JSON, no markdown."

5. **Temp file handling**: Base64 images must be decoded to a real file path because
   mlx_vlm.generate() requires a file path, not bytes. Use `tempfile.NamedTemporaryFile`.

6. **Memory on M1 Pro 16GB**: Do NOT load both an 8B VLM and an 8B LM simultaneously.
   The QUALITY preset must enforce serial (unload vision before loading text).

7. **Process isolation**: This service shares memory with other local services.
   The `/health` endpoint memory reading helps the user monitor pressure.

8. **No GPU OOM handling**: MLX on Apple Silicon uses unified memory — OOM appears as
   system slowdown, not a Python exception. Guard with memory checks before loading.

9. **Fine-tuning readiness**: Keep model loading paths clean and separate from inference logic.
   When LoRA adapters are added later, they slot into `model_manager.py` load functions only.

10. **Port**: Default 8080 to avoid conflict with common 8000/8001 ports used by other services.

---

## Testing Requirements

Provide `tests/test_pipeline.py` with:
- Unit test for `OCRRequest` base64 decode and temp file creation
- Unit test for `LCCheckResult` JSON parsing with sample compliance output
- Integration test for `/health` endpoint (no model load needed)
- Mock-based test for full pipeline (mock model output, test orchestration logic)
- One real inference test (marked `@pytest.mark.slow`, skipped in CI) using sample_invoice.jpg

---

## Out of Scope (Do NOT implement)

- Authentication / API keys
- Database persistence of results
- PDF input (image only for now)
- Batch processing endpoint
- Frontend UI
- Docker / containerization
- Fine-tuning code (placeholder comments OK)

