"""FastAPI entry point for docling-svc.

Implements:
- `GET  /health`  — liveness + identity (contract_version, service_version, library_version)
- `POST /extract` — tri-modal input (multipart / JSON {path} / JSON {url})

All responses round-trip through `contract.py` Pydantic models so the Java
client never sees an unexpected shape. All errors round-trip through
`errors.ExtractorError` so the status code and error-code vocabulary match
the FROZEN contract exactly.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from typing import Optional

from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Request, UploadFile
from fastapi.responses import JSONResponse

# pythonjsonlogger renamed the module in 3.x; try the new path, fall back to the old.
try:
    from pythonjsonlogger import json as jsonlogger  # type: ignore[no-redef]
except ImportError:  # pragma: no cover — older pythonjsonlogger
    from pythonjsonlogger import jsonlogger  # type: ignore[no-redef]

from . import CONTRACT_VERSION, EXTRACTOR_NAME, __version__ as SERVICE_VERSION
from .contract import ErrorResponse, ExtractResponse, HealthResponse, InvoiceFields
from .docling_extractor import DoclingResult, convert_pdf_bytes, convert_pdf_path, library_version, warm_up
from .errors import (
    EXTRACTION_FAILED,
    ExtractionFailed,
    ExtractionTimeout,
    ExtractorError,
    InvalidFileType,
    InvalidRequest,
)
from .fetching import fetch_url, read_path, validate_pdf_bytes
from .field_extractor import extract_fields

# -- Logging ------------------------------------------------------------------
# One JSON line per event, per contract §Logging (recommended).

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
logger = logging.getLogger("docling-svc")


# -- Budget constants ---------------------------------------------------------
# Contract §Timeout budget: services SHOULD return 504 if their extraction
# exceeds 25 s so the Java 30 s read timeout still has room to fall back.
EXTRACTION_BUDGET_SECONDS = 25.0


# -- App ----------------------------------------------------------------------

@asynccontextmanager
async def _lifespan(_: FastAPI):
    """FastAPI lifespan: warm up Docling before accepting traffic.

    Running warm_up in a thread prevents blocking the event loop during
    startup (Docling model init can take several seconds the first time).
    Warm-up failures are logged but do not block startup — /health will
    surface the problem and the first /extract call will raise the real
    error, matching contract §/health 503 guidance.
    """
    try:
        await asyncio.to_thread(warm_up)
        logger.info("docling warm-up complete", extra={"event": "startup"})
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "docling warm-up failed: %s", exc,
            extra={"event": "startup_warmup_failed"},
        )
    yield


app = FastAPI(
    title="docling-svc",
    description="LC Invoice Checker — Docling PDF extractor (CONTRACT.md v1.0 FROZEN)",
    version=SERVICE_VERSION,
    lifespan=_lifespan,
)


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
    """Returns the parsed JSON body if the request is JSON-typed, else None."""
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
    """Pick the single input mode for this request.

    Returns (mode, context) where mode ∈ {"multipart","path","url"} and
    `context` is the input-specific payload. Raises InvalidRequest if zero
    or more than one mode is provided (contract §Exactly one mode).
    """
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
        raise InvalidRequest(
            f"exactly one input mode allowed; got {modes_present}"
        )

    mode = modes_present[0]
    if mode == "multipart":
        return mode, {"file": multipart_file}
    if mode == "path":
        return mode, {"path": json_body["path"]}
    return mode, {"url": json_body["url"]}


async def _load_bytes(mode: str, ctx: dict) -> tuple[bytes, Optional[str]]:
    """Materialise input into bytes. Returns (bytes, source_path_if_any)."""
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
        # httpx is synchronous here — wrap in thread so we don't block the loop.
        data = await asyncio.to_thread(fetch_url, str(ctx["url"]))
        return data, None
    raise InvalidRequest(f"unknown mode: {mode}")  # pragma: no cover


def _convert_with_budget(pdf_bytes: bytes, source_path: Optional[str]) -> DoclingResult:
    """Run Docling conversion. Enforces the contract's 25 s service budget by
    wrapping the call in a timeout — Docling itself doesn't accept a timeout arg,
    so we bound it cooperatively via asyncio.wait_for at the call site.
    """
    if source_path is not None:
        from pathlib import Path as _Path

        return convert_pdf_path(_Path(source_path))
    return convert_pdf_bytes(pdf_bytes)


@app.post("/extract", response_model=ExtractResponse)
async def extract(
    request: Request,
    file: Optional[UploadFile] = File(default=None),
) -> ExtractResponse:
    start_ns = time.perf_counter_ns()

    json_body = await _read_json_body(request) if file is None else None
    mode, ctx = _resolve_input_mode(file, json_body)
    pdf_bytes, source_path = await _load_bytes(mode, ctx)

    # Run extraction with the 25 s service budget.
    try:
        result: DoclingResult = await asyncio.wait_for(
            asyncio.to_thread(_convert_with_budget, pdf_bytes, source_path),
            timeout=EXTRACTION_BUDGET_SECONDS,
        )
    except asyncio.TimeoutError as exc:
        raise ExtractionTimeout() from exc
    except Exception as exc:  # docling raised something
        raise ExtractionFailed(f"{type(exc).__name__}: {exc}") from exc

    # Tier-1 + optional-Tier-2 field extraction.
    try:
        fields: InvoiceFields = await asyncio.to_thread(
            extract_fields, result.raw_markdown, result.raw_text
        )
    except Exception as exc:
        # Don't fail the whole request on field-extraction errors — emit
        # empty fields and let Java's fallback re-parse raw_markdown.
        logger.warning(
            "field extraction failed; returning empty fields",
            extra={"event": "field_extract_error", "error": str(exc)},
        )
        fields = InvoiceFields()

    elapsed_ms = (time.perf_counter_ns() - start_ns) // 1_000_000

    response = ExtractResponse(
        extractor=EXTRACTOR_NAME,
        contract_version=CONTRACT_VERSION,
        confidence=result.confidence,
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
            "confidence": result.confidence,
            "is_image_based": result.is_image_based,
            "pages": result.pages,
            "extractor": EXTRACTOR_NAME,
        },
    )
    return response
