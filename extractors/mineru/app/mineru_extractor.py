"""MinerU wrapper.

Thin adapter around the `mineru` (aka `magic-pdf`) Python library. Turns
a PDF (bytes or path) into the contract's extraction-time fields:
`raw_markdown`, `raw_text`, `is_image_based`, `pages`.

Unlike the Docling wrapper, MinerU does NOT expose per-page confidence
scores — its native output is markdown + structured JSON only. Per the
resolved CONTRACT.md Q3, this service's `confidence` is computed in the
field-extraction path as:

    confidence = non_null_field_count / len(CONTRACT_FIELD_KEYS)

That computation happens in `main.py` after field extraction, so this
module does not return a confidence number — it only returns the
post-MinerU primitives.

`is_image_based` detection uses the two-pass heuristic documented in
CONTRACT.md §is_image_based detection: pre-OCR text density is measured
first (PyMuPDF); if chars/page < 50 we flag image-based. The OCR-enabled
pass produces the real `raw_markdown` / `raw_text`.
"""

from __future__ import annotations

import logging
import tempfile
from dataclasses import dataclass
from pathlib import Path

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class MinerUResult:
    """Transport object between the MinerU wrapper and the main handler."""

    raw_markdown: str
    raw_text: str
    is_image_based: bool
    pages: int
    library_version: str


# ---------------------------------------------------------------------------
# Pre-OCR text density — the first pass of the two-pass is_image_based check
# ---------------------------------------------------------------------------

_IMAGE_BASED_CHAR_THRESHOLD = 50


def _pre_ocr_text_density(pdf_path: Path) -> tuple[int, int]:
    """Return (total_chars, total_pages) from the raw PDF text layer, no OCR.

    Uses PyMuPDF (fitz) — a MinerU dependency we can rely on being present
    since MinerU uses it internally. If the import fails (different MinerU
    version), we return (0, 0) which downstream treats as "unknown" and
    defaults is_image_based to False.
    """
    try:
        import fitz  # type: ignore[import-not-found]
    except ImportError:  # pragma: no cover — defensive
        logger.warning("PyMuPDF (fitz) unavailable; pre-OCR density check skipped")
        return (0, 0)

    try:
        with fitz.open(str(pdf_path)) as doc:
            chars = 0
            pages = doc.page_count
            for page in doc:
                text = page.get_text("text") or ""
                chars += len(text.strip())
            return (chars, pages)
    except Exception as exc:  # pragma: no cover — defensive
        logger.warning("pre-OCR density probe failed: %s", exc)
        return (0, 0)


def _detect_image_based(pdf_path: Path) -> tuple[bool, int]:
    """Run the contract's two-pass `is_image_based` heuristic and return
    (is_image_based, page_count)."""
    chars, pages = _pre_ocr_text_density(pdf_path)
    if pages == 0:
        return (False, 0)
    avg = chars / pages
    return (avg < _IMAGE_BASED_CHAR_THRESHOLD, pages)


# ---------------------------------------------------------------------------
# MinerU conversion
# ---------------------------------------------------------------------------


def library_version() -> str:
    """Return installed mineru version, or 'unknown'."""
    try:
        import importlib.metadata as md

        # Try `mineru` first; fall back to legacy `magic-pdf` name.
        for pkg in ("mineru", "magic-pdf"):
            try:
                return md.version(pkg)
            except md.PackageNotFoundError:
                continue
    except Exception:  # pragma: no cover
        pass
    return "unknown"


def _run_mineru(pdf_path: Path) -> tuple[str, str]:
    """Invoke MinerU and return (raw_markdown, raw_text).

    The MinerU API surface has shifted across versions; this function is
    written defensively. It first tries the high-level Pipeline wrapper,
    then falls back to the magic-pdf CLI-style API. If all surfaces fail,
    it raises RuntimeError which the caller maps to ExtractionFailed.
    """
    md_text, plain_text = "", ""

    # --- Attempt 1: mineru.pipeline.MinerUPipeline (v1.x+) -------------------
    try:
        from mineru.pipeline import MinerUPipeline  # type: ignore[import-not-found]

        pipeline = _get_pipeline()
        result = pipeline(str(pdf_path))
        # to_markdown is documented; to_text / content_list are library-specific.
        if hasattr(result, "to_markdown"):
            md_text = result.to_markdown() or ""
        for attr in ("to_text", "to_plain_text", "content"):
            fn = getattr(result, attr, None)
            if callable(fn):
                try:
                    plain_text = fn() or ""
                    break
                except Exception:  # pragma: no cover
                    continue
        if md_text and not plain_text:
            plain_text = _markdown_to_text(md_text)
        if md_text or plain_text:
            return md_text, plain_text
    except ImportError:
        logger.debug("mineru.pipeline not available; trying magic-pdf fallback")
    except Exception as exc:
        logger.warning("mineru.pipeline call failed: %s; trying fallback", exc)

    # --- Attempt 2: magic-pdf low-level (legacy) -----------------------------
    try:
        from magic_pdf.pipe.UNIPipe import UNIPipe  # type: ignore[import-not-found]
        from magic_pdf.rw.DiskReaderWriter import DiskReaderWriter  # type: ignore[import-not-found]

        image_writer = DiskReaderWriter(tempfile.mkdtemp(prefix="mineru-img-"))
        jso_useful_key = {"_pdf_type": "", "model_list": []}
        pipe = UNIPipe(pdf_path.read_bytes(), jso_useful_key, image_writer)
        pipe.pipe_classify()
        pipe.pipe_analyze()
        pipe.pipe_parse()
        md_text = pipe.pipe_mk_markdown(".") or ""
        plain_text = _markdown_to_text(md_text)
        return md_text, plain_text
    except ImportError:  # pragma: no cover
        pass
    except Exception as exc:  # pragma: no cover
        logger.warning("magic-pdf legacy pipe failed: %s", exc)

    raise RuntimeError(
        "No compatible MinerU API surface available; "
        "check `mineru` package installation in this image"
    )


def _markdown_to_text(md: str) -> str:
    """Best-effort strip of markdown syntax to a plain-text projection."""
    import re

    text = re.sub(r"^\s*#+\s*", "", md, flags=re.MULTILINE)
    text = re.sub(r"^[\-*+]\s+", "", text, flags=re.MULTILINE)
    text = re.sub(r"`+", "", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"\1", text)
    text = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", text)  # image links
    return text


def convert_pdf_bytes(pdf_bytes: bytes, filename: str = "upload.pdf") -> MinerUResult:
    """Convert PDF bytes via MinerU. Materialises to a temp file so is_image_based
    can run its pre-OCR PyMuPDF probe on a real file handle."""
    with tempfile.NamedTemporaryFile(suffix=".pdf", prefix="mineru-", delete=False) as tmp:
        tmp.write(pdf_bytes)
        tmp.flush()
        path = Path(tmp.name)
    try:
        return convert_pdf_path(path)
    finally:
        try:
            path.unlink(missing_ok=True)
        except Exception:  # pragma: no cover
            pass


def convert_pdf_path(path: Path) -> MinerUResult:
    """Convert a PDF located at `path` via MinerU."""
    is_img, pages = _detect_image_based(path)
    md_text, plain_text = _run_mineru(path)
    if pages == 0:
        # Fallback: try to count pages from the markdown page-break marker MinerU emits.
        pages = max(1, md_text.count("\n---\n") + 1) if md_text else 0
    return MinerUResult(
        raw_markdown=md_text or "",
        raw_text=plain_text or "",
        is_image_based=is_img,
        pages=pages,
        library_version=library_version(),
    )


# ---------------------------------------------------------------------------
# Pipeline lifecycle
# ---------------------------------------------------------------------------

_PIPELINE = None


def _get_pipeline():
    global _PIPELINE
    if _PIPELINE is None:
        from mineru.pipeline import MinerUPipeline  # type: ignore[import-not-found]

        _PIPELINE = MinerUPipeline()
        logger.info("mineru MinerUPipeline initialized (version=%s)", library_version())
    return _PIPELINE


def warm_up() -> None:
    """Eagerly initialize MinerU so the first /extract doesn't eat model-load latency."""
    try:
        _get_pipeline()
    except ImportError:  # pragma: no cover
        # Legacy magic-pdf has no pipeline object; its warm-up happens inside the pipe.
        logger.info("mineru.pipeline unavailable; warm-up is a no-op for legacy magic-pdf")
