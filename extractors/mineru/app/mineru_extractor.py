"""MinerU wrapper.

Thin adapter around the `mineru` >= 3.0 Python library. Turns a PDF (bytes or path)
into the contract's extraction-time fields:
`raw_markdown`, `raw_text`, `is_image_based`, `pages`.

API (mineru >= 3.0):
    mineru.backend.pipeline.pipeline_analyze.doc_analyze_streaming(...)
    mineru.backend.pipeline.pipeline_middle_json_mkcontent.union_make(...)
    mineru.data.data_reader_writer.filebase.FileBasedDataWriter

Unlike the Docling wrapper, MinerU does NOT expose per-page confidence scores —
its native output is markdown + structured JSON only. Per resolved CONTRACT.md Q3,
this service's `confidence` is computed in the field-extraction path as:

    confidence = non_null_field_count / len(CONTRACT_FIELD_KEYS)

That computation happens in main.py after field extraction, so this module only
returns the post-MinerU primitives.

`is_image_based` detection uses the two-pass heuristic in CONTRACT.md:
pre-OCR text density measured via PyMuPDF; if chars/page < 50 we flag image-based.
"""

from __future__ import annotations

import logging
import os
import tempfile
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

# Force offline mode: only use locally-cached model weights, never reach the network.
# Set before any HuggingFace / transformers import so the flag takes effect at import time.
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")


# ---------------------------------------------------------------------------
# Pipeline knobs — env-driven so quality/speed can be tuned without redeploy.
#
# MINERU_TABLE_ENABLED   default true   — render markdown tables (line items)
# MINERU_FORMULA_ENABLED default true   — render formulas (rare in invoices)
# MINERU_LANG            default "en"   — comma-separated OCR languages
# MINERU_MD_MODE         default "mm"   — "mm"=MakeMode.MM_MD (rich, keeps tables),
#                                         "nlp"=MakeMode.NLP_MD (text-stripped)
# ---------------------------------------------------------------------------


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_lang_list(name: str, default: list[str]) -> list[str]:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    return [tok.strip() for tok in raw.split(",") if tok.strip()]


def _resolved_md_mode():
    """Return MakeMode enum value chosen by MINERU_MD_MODE (lazy import)."""
    from mineru.backend.pipeline.pipeline_middle_json_mkcontent import MakeMode

    mode = os.getenv("MINERU_MD_MODE", "mm").strip().lower()
    if mode == "nlp":
        return MakeMode.NLP_MD
    if mode == "mm":
        return MakeMode.MM_MD
    logger.warning("unknown MINERU_MD_MODE=%r; falling back to MM_MD", mode)
    return MakeMode.MM_MD


# ---------------------------------------------------------------------------
# Result dataclass
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class MinerUResult:
    """Transport object between the MinerU wrapper and the rest of the service."""

    raw_markdown: str
    raw_text: str
    is_image_based: bool
    pages: int
    library_version: str


# ---------------------------------------------------------------------------
# Pre-OCR text density — first pass of is_image_based heuristic
# ---------------------------------------------------------------------------

_IMAGE_BASED_CHAR_THRESHOLD = 50


def _pre_ocr_text_density(pdf_path: Path) -> tuple[int, int]:
    """Return (total_chars, total_pages) from the raw PDF text layer, no OCR."""
    try:
        import fitz  # type: ignore[import-not-found]
    except ImportError:
        logger.warning("PyMuPDF (fitz) unavailable; pre-OCR density check skipped")
        return (0, 0)

    try:
        with fitz.open(str(pdf_path)) as doc:
            chars = sum(len((page.get_text("text") or "").strip()) for page in doc)
            return (chars, doc.page_count)
    except Exception as exc:
        logger.warning("pre-OCR density probe failed: %s", exc)
        return (0, 0)


def _detect_image_based(pdf_path: Path) -> tuple[bool, int]:
    """Run the contract's two-pass is_image_based heuristic."""
    chars, pages = _pre_ocr_text_density(pdf_path)
    if pages == 0:
        return (False, 0)
    return (chars / pages < _IMAGE_BASED_CHAR_THRESHOLD, pages)


# ---------------------------------------------------------------------------
# MinerU conversion
# ---------------------------------------------------------------------------


def library_version() -> str:
    """Return installed mineru version, or 'unknown'."""
    try:
        import importlib.metadata as md
        return md.version("mineru")
    except Exception:
        return "unknown"


# ---------------------------------------------------------------------------
# Pipeline lifecycle — singleton so model weights are loaded once per process.
# ---------------------------------------------------------------------------

_PIPELINE = None
_pipeline_lock = threading.Lock()


def _get_pipeline():
    global _PIPELINE
    if _PIPELINE is not None:
        return _PIPELINE
    with _pipeline_lock:
        if _PIPELINE is not None:  # double-check after acquiring lock
            return _PIPELINE
        from mineru.backend.pipeline import pipeline_analyze

        logger.info("mineru: initialising pipeline (offline=%s)", os.environ.get("HF_HUB_OFFLINE"))
        _PIPELINE = pipeline_analyze
    return _PIPELINE


def _run_mineru(pdf_bytes: bytes) -> tuple[str, str]:
    """Run MinerU >= 3.0 pipeline on PDF bytes, return (markdown, plain_text).

    Uses:
      - mineru.backend.pipeline.pipeline_analyze.doc_analyze_streaming(...)
      - mineru.backend.pipeline.pipeline_middle_json_mkcontent.union_make(...)

    Raises RuntimeError if the pipeline fails.
    """
    from mineru.backend.pipeline import pipeline_middle_json_mkcontent as mk
    from mineru.data.data_reader_writer.filebase import FileBasedDataWriter

    pipeline_analyze = _get_pipeline()

    # Thread-safe result collection from the on_doc_ready callback
    result_holder: dict[str, Any] = {}
    lock = threading.Lock()

    out_dir = tempfile.mkdtemp(prefix="mineru-out-")
    img_writer = FileBasedDataWriter(out_dir)

    def on_doc_ready(
        doc_index: int,
        model_list: list,
        middle_json: dict,
        ocr_enable: bool,
    ) -> None:
        with lock:
            result_holder["middle_json"] = middle_json
            result_holder["out_dir"] = out_dir

    table_enabled = _env_bool("MINERU_TABLE_ENABLED", True)
    formula_enabled = _env_bool("MINERU_FORMULA_ENABLED", True)
    lang_list = _env_lang_list("MINERU_LANG", ["en"])
    md_mode = _resolved_md_mode()
    logger.info(
        "mineru pipeline: table=%s formula=%s lang=%s md_mode=%s",
        table_enabled, formula_enabled, lang_list, md_mode,
    )

    pipeline_analyze.doc_analyze_streaming(
        pdf_bytes_list=[pdf_bytes],
        image_writer_list=[img_writer],
        lang_list=lang_list,
        on_doc_ready=on_doc_ready,
        parse_method="auto",
        formula_enable=formula_enabled,
        table_enable=table_enabled,
    )

    middle_json = result_holder.get("middle_json")
    if not middle_json:
        raise RuntimeError("MinerU pipeline produced no output")

    # middle_json is {'pdf_info': [...], ...} — extract the pdf_info list
    pdf_info: list = middle_json.get("pdf_info", [])
    md_text = mk.union_make(pdf_info, make_mode=md_mode, img_buket_path=out_dir)
    plain_text = _markdown_to_text(md_text)
    return md_text, plain_text


def _markdown_to_text(md: str) -> str:
    """Best-effort strip of markdown syntax to a plain-text projection."""
    import re

    text = re.sub(r"^\s*#+\s*", "", md, flags=re.MULTILINE)
    text = re.sub(r"^[\-*+]\s+", "", text, flags=re.MULTILINE)
    text = re.sub(r"`+", "", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"\1", text)
    text = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", text)  # image links
    return text


# ---------------------------------------------------------------------------
# Public entry points
# ---------------------------------------------------------------------------


def convert_pdf_bytes(pdf_bytes: bytes, filename: str = "upload.pdf") -> MinerUResult:
    """Convert PDF bytes via MinerU >= 3.0 pipeline."""
    is_img, pages = False, 0
    md_text, plain_text = "", ""

    # is_image_based detection needs a temp file path for PyMuPDF
    with tempfile.NamedTemporaryFile(suffix=".pdf", prefix="mineru-", delete=False) as tmp:
        tmp.write(pdf_bytes)
        tmp.flush()
        path = Path(tmp.name)

    try:
        is_img, pages = _detect_image_based(path)
        md_text, plain_text = _run_mineru(pdf_bytes)
        if pages == 0 and md_text:
            pages = max(1, md_text.count("\n---\n") + 1)
    finally:
        try:
            path.unlink(missing_ok=True)
        except Exception:
            pass

    return MinerUResult(
        raw_markdown=md_text or "",
        raw_text=plain_text or "",
        is_image_based=is_img,
        pages=pages or 1,
        library_version=library_version(),
    )


def convert_pdf_path(path: Path) -> MinerUResult:
    """Convert a PDF located at `path` via MinerU >= 3.0 pipeline."""
    is_img, pages = _detect_image_based(path)
    pdf_bytes = path.read_bytes()
    md_text, plain_text = _run_mineru(pdf_bytes)
    if pages == 0 and md_text:
        pages = max(1, md_text.count("\n---\n") + 1)
    return MinerUResult(
        raw_markdown=md_text or "",
        raw_text=plain_text or "",
        is_image_based=is_img,
        pages=pages or 1,
        library_version=library_version(),
    )


# ---------------------------------------------------------------------------
# Warm-up — trigger model loading during container startup
# ---------------------------------------------------------------------------

def warm_up() -> None:
    """Eagerly load MinerU models so the first /extract call is fast.

    MinerU >= 3.0 downloads models lazily on first use. warm_up() forces that
    to happen at container start (before the 120 s extraction budget starts).
    With HF_HUB_OFFLINE=1 this reads only from the local disk cache.
    """
    try:
        pipeline_analyze = _get_pipeline()
        from mineru.data.data_reader_writer.filebase import FileBasedDataWriter
        import tempfile

        out_dir = tempfile.mkdtemp(prefix="mineru-warmup-")
        img_writer = FileBasedDataWriter(out_dir)
        result_holder: dict[str, Any] = {}
        lock = threading.Lock()

        def on_doc_ready(
            doc_index: int, model_list: list, middle_json: dict, ocr_enable: bool
        ) -> None:
            with lock:
                result_holder["done"] = True

        # Minimal 1-page PDF to trigger model initialisation without heavy OCR
        fake_pdf = (
            b"%PDF-1.0\n"
            b"1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
            b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
            b"3 0 obj<</Type/Page/MediaBox[0 0 200 200]>>endobj\n"
            b"xref\n0 4\n"
            b"0000000000 65535 f\n"
            b"0000000009 00000 n\n"
            b"0000000058 00000 n\n"
            b"0000000115 00000 n\n"
            b"trailer<</Size 4/Root 1 0 R>>\n"
            b"startxref\n199\n"
            b"%%EOF"
        )

        # Warm-up uses the same env-driven knobs as a real extraction so the
        # right model bundle (table / formula) is loaded ahead of first request.
        pipeline_analyze.doc_analyze_streaming(
            pdf_bytes_list=[fake_pdf],
            image_writer_list=[img_writer],
            lang_list=_env_lang_list("MINERU_LANG", ["en"]),
            on_doc_ready=on_doc_ready,
            parse_method="auto",
            formula_enable=_env_bool("MINERU_FORMULA_ENABLED", True),
            table_enable=_env_bool("MINERU_TABLE_ENABLED", True),
        )
        logger.info("mineru warm-up complete (version=%s)", library_version())
    except Exception as exc:
        logger.warning("mineru warm-up failed: %s; models will load on first use", exc)
