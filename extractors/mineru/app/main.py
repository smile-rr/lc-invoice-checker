"""FastAPI entry point for mineru-svc.

Implements extractors/CONTRACT.md. Differences from docling-svc/main.py
are narrowly scoped to:

1. Extractor identity string ("mineru").
2. The underlying extractor module (mineru_extractor vs docling_extractor).

Both services use the same prompt-driven LLM extraction path and pass
through the LLM's self-rated confidence verbatim — no per-service
confidence formula. The Java client supplies the prompt + field list
(rendered from `field-pool.yaml`); sidecars hold no schema knowledge.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, File, Form, Request, UploadFile
from fastapi.responses import JSONResponse

try:
    from pythonjsonlogger import json as jsonlogger  # type: ignore[no-redef]
except ImportError:  # pragma: no cover
    from pythonjsonlogger import jsonlogger  # type: ignore[no-redef]

from . import CONTRACT_VERSION, EXTRACTOR_NAME, __version__ as SERVICE_VERSION
from .contract import (
    ErrorResponse,
    ExtractResponse,
    HealthResponse,
    InvoiceFields,
)
from .errors import (
    EXTRACTION_FAILED,
    ExtractionFailed,
    ExtractionTimeout,
    ExtractorError,
    InvalidFileType,
    InvalidRequest,
)
from .fetching import fetch_url, read_path, validate_pdf_bytes
from .llm_field_extractor import (
    LlmCallFailed,
    LlmNotConfigured,
    LlmResponseInvalid,
    llm_extract_fields,
)
from .mineru_extractor import (
    MinerUResult,
    convert_pdf_bytes,
    convert_pdf_path,
    library_version,
    warm_up,
)

# -- Logging ------------------------------------------------------------------
_handler = logging.StreamHandler()
_handler.setFormatter(
    jsonlogger.JsonFormatter(
        "%(asctime)s %(levelname)s %(name)s %(message)s",
        rename_fields={"levelname": "level", "asctime": "time", "name": "logger"},
    )
)
_root = logging.getLogger()
if not _root.handlers:
    _root.addHandler(_handler)
_root.setLevel(os.environ.get("LOG_LEVEL", "INFO"))
logger = logging.getLogger("mineru-svc")

# -- Budget ------------------------------------------------------------------
EXTRACTION_BUDGET_SECONDS = 120.0


# -- App ---------------------------------------------------------------------


@asynccontextmanager
async def _lifespan(_: FastAPI):
    """Warm up MinerU before accepting traffic (model init on first use)."""
    try:
        await asyncio.to_thread(warm_up)
        logger.info("mineru warm-up complete", extra={"event": "startup"})
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "mineru warm-up failed: %s", exc, extra={"event": "startup_warmup_failed"}
        )
    yield


app = FastAPI(
    title="mineru-svc",
    description="LC Invoice Checker — MinerU PDF extractor (CONTRACT.md v1.0 FROZEN)",
    version=SERVICE_VERSION,
    lifespan=_lifespan,
)

# Initialise Langfuse + OTel auto-instrumentation. No-op when LANGFUSE_*
# env vars are unset, so this is safe in every environment.
from .observability import init_observability  # noqa: E402

init_observability(app)


# -- Exception handlers -------------------------------------------------------


@app.exception_handler(ExtractorError)
async def _extractor_error_handler(_: Request, exc: ExtractorError) -> JSONResponse:
    body = ErrorResponse(error=exc.code, message=exc.message, extractor=EXTRACTOR_NAME)
    return JSONResponse(status_code=exc.status_code, content=body.model_dump())


@app.exception_handler(Exception)
async def _unhandled_handler(_: Request, exc: Exception) -> JSONResponse:
    logger.exception("unhandled error", extra={"event": "unhandled_error"})
    body = ErrorResponse(
        error=EXTRACTION_FAILED,
        message=f"{type(exc).__name__}: {exc}",
        extractor=EXTRACTOR_NAME,
    )
    return JSONResponse(status_code=500, content=body.model_dump())


# -- /health ------------------------------------------------------------------


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(
        status="ok",
        extractor=EXTRACTOR_NAME,
        contract_version=CONTRACT_VERSION,
        service_version=SERVICE_VERSION,
        library_version=library_version(),
    )


# -- /extract -----------------------------------------------------------------


async def _read_json_body(request: Request) -> Optional[dict]:
    ctype = (request.headers.get("content-type") or "").split(";")[0].strip().lower()
    if ctype != "application/json":
        return None
    raw = await request.body()
    if not raw:
        raise InvalidRequest("empty JSON body")
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise InvalidRequest(f"malformed JSON: {exc}") from exc


def _resolve_input_mode(
    multipart_file: Optional[UploadFile],
    json_body: Optional[dict],
) -> tuple[str, dict]:
    """Pick the single input mode per contract §Exactly one mode."""
    modes_present: list[str] = []
    if multipart_file is not None:
        modes_present.append("multipart")
    if json_body is not None:
        if "path" in json_body and json_body["path"]:
            modes_present.append("path")
        if "url" in json_body and json_body["url"]:
            modes_present.append("url")
    if not modes_present:
        raise InvalidRequest(
            "no input provided — use multipart `file` or JSON {path:...} or {url:...}"
        )
    if len(modes_present) > 1:
        raise InvalidRequest(f"exactly one input mode allowed; got {modes_present}")
    mode = modes_present[0]
    if mode == "multipart":
        return mode, {"file": multipart_file}
    if mode == "path":
        return mode, {"path": json_body["path"]}
    return mode, {"url": json_body["url"]}


async def _load_bytes(mode: str, ctx: dict) -> tuple[bytes, Optional[str]]:
    if mode == "multipart":
        upload: UploadFile = ctx["file"]
        if upload.content_type and upload.content_type != "application/pdf":
            raise InvalidFileType(
                f"multipart content-type must be application/pdf; got {upload.content_type}"
            )
        data = await upload.read()
        validate_pdf_bytes(data)
        return data, None
    if mode == "path":
        path_str = str(ctx["path"])
        data = read_path(path_str)
        return data, path_str
    if mode == "url":
        data = await asyncio.to_thread(fetch_url, str(ctx["url"]))
        return data, None
    raise InvalidRequest(f"unknown mode: {mode}")  # pragma: no cover


def _convert_with_budget(pdf_bytes: bytes, source_path: Optional[str]) -> MinerUResult:
    if source_path is not None:
        from pathlib import Path as _Path

        return convert_pdf_path(_Path(source_path))
    return convert_pdf_bytes(pdf_bytes)


@app.post("/extract", response_model=ExtractResponse)
async def extract(
    request: Request,
    file: Optional[UploadFile] = File(default=None),
    prompt: str = Form(..., description="LLM system prompt — REQUIRED."),
) -> ExtractResponse:
    """Extract invoice fields from a PDF.

    The sidecar holds NO prompt or field list — both are supplied by the
    Java client (rendered from `field-pool.yaml` + `invoice-extract.st`).
    All four extractor lanes share the same prompt — see
    lc-checker-svc / PromptBuilder.java.
    """
    start_ns = time.perf_counter_ns()

    json_body = await _read_json_body(request) if file is None else None
    mode, ctx = _resolve_input_mode(file, json_body)
    pdf_bytes, source_path = await _load_bytes(mode, ctx)

    # Run MinerU library with 25 s service budget.
    try:
        result: MinerUResult = await asyncio.wait_for(
            asyncio.to_thread(_convert_with_budget, pdf_bytes, source_path),
            timeout=EXTRACTION_BUDGET_SECONDS,
        )
    except asyncio.TimeoutError as exc:
        raise ExtractionTimeout() from exc
    except Exception as exc:
        raise ExtractionFailed(f"{type(exc).__name__}: {exc}") from exc

    # Field extraction: LLM is mandatory, no fallback.
    # Confidence comes from the LLM's own self-rating in the response
    # envelope — replaces the previous `non_null_count / len(CONTRACT_FIELD_KEYS)`
    # formula. The number on the card now represents extraction quality
    # comparable across all 4 extractor lanes.
    try:
        fields, llm_confidence = await asyncio.to_thread(
            llm_extract_fields, result.raw_markdown, prompt
        )
    except LlmNotConfigured as exc:
        raise ExtractionFailed(f"LLM not configured: {exc}") from exc
    except (LlmCallFailed, LlmResponseInvalid) as exc:
        raise ExtractionFailed(f"LLM extraction failed: {exc}") from exc

    elapsed_ms = (time.perf_counter_ns() - start_ns) // 1_000_000

    response = ExtractResponse(
        extractor=EXTRACTOR_NAME,
        contract_version=CONTRACT_VERSION,
        confidence=llm_confidence,
        is_image_based=result.is_image_based,
        raw_markdown=result.raw_markdown,
        raw_text=result.raw_text,
        fields=fields,
        pages=result.pages,
        extraction_ms=int(elapsed_ms),
    )

    logger.info(
        "extract",
        extra={
            "event": "extract",
            "input_mode": mode,
            "duration_ms": int(elapsed_ms),
            "confidence": llm_confidence,
            "is_image_based": result.is_image_based,
            "pages": result.pages,
            "extractor": EXTRACTOR_NAME,
        },
    )
    return response
