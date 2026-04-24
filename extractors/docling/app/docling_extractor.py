"""Docling wrapper.

Thin adapter around `docling.document_converter.DocumentConverter` that turns
a PDF (bytes or path) into the contract's extraction-time fields:
`raw_markdown`, `raw_text`, `confidence`, `is_image_based`, `pages`.

The field-structuring step lives in `field_extractor.py` — this module only
handles the Docling pass and is intentionally isolated so we can swap
library versions or back-ends without touching field logic.

Resolved question #3 per CONTRACT.md:
    confidence = float(result.confidence.mean_grade)
Defensive: if `mean_grade` turns out to be a categorical grade instead of a
float in the installed Docling version, we map deterministically (see
`_coerce_mean_grade`).

`is_image_based` per the heuristic codified in CONTRACT.md §is_image_based
detection (parse_score vs ocr_score separation).
"""

from __future__ import annotations

import logging
import tempfile
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path

logger = logging.getLogger(__name__)

# Imported lazily inside functions so `import app.docling_extractor` at
# collection time (tests, linters) doesn't require the full library.
# Docling's first import is expensive — models load on first convert() call.


@dataclass(frozen=True)
class DoclingResult:
    """Transport object between docling_extractor and the rest of the service."""

    raw_markdown: str
    raw_text: str
    confidence: float  # [0, 1]
    is_image_based: bool
    pages: int
    library_version: str


# ---------------------------------------------------------------------------
# Docling → primitive-values adapters
# ---------------------------------------------------------------------------


def _coerce_mean_grade(value) -> float:  # noqa: ANN001 — upstream type may be Enum/float
    """Normalize Docling's `mean_grade` to float in [0, 1].

    If the installed Docling version returns a float, pass through (clamped).
    If it returns a categorical grade enum (Excellent/Good/Fair/Poor/Unknown),
    map deterministically — documented behaviour per CONTRACT.md Q3.
    """
    if value is None:
        return 0.0
    # Float / int path — most Docling versions.
    try:
        f = float(value)
        if f != f or f < 0:  # NaN or negative
            return 0.0
        return 1.0 if f > 1 else f
    except (TypeError, ValueError):
        pass
    # Enum path — map by the name rather than the enum object to avoid
    # coupling to any specific enum class in Docling.
    name = getattr(value, "name", str(value)).upper()
    mapping = {
        "EXCELLENT": 1.0,
        "GOOD": 0.85,
        "FAIR": 0.65,
        "POOR": 0.35,
        "UNKNOWN": 0.0,
    }
    return mapping.get(name, 0.0)


def _is_image_based(confidence_report) -> bool:  # noqa: ANN001
    """Implementation of the heuristic in CONTRACT.md §is_image_based detection.

    A page is image-based when Docling's OCR score is meaningful but its
    parse (embedded text) score is low — i.e. OCR carried the content.
    Overall PDF is image-based if ≥ 50 % of pages meet that condition.
    """
    pages_obj = getattr(confidence_report, "pages", None) or {}
    try:
        pages = list(pages_obj.values()) if hasattr(pages_obj, "values") else list(pages_obj)
    except Exception:  # pragma: no cover — defensive against Docling API drift
        return False
    if not pages:
        return False
    image_pages = 0
    for p in pages:
        ocr = getattr(p, "ocr_score", None) or 0
        parse = getattr(p, "parse_score", None) or 0
        try:
            if float(ocr) >= 0.5 and float(parse) < 0.3:
                image_pages += 1
        except (TypeError, ValueError):
            continue
    return image_pages * 2 >= len(pages)


def _export_text(doc) -> str:  # noqa: ANN001
    """Get plain text from a DoclingDocument, falling back across library versions.

    Later Docling exposes `export_to_text()`. Earlier versions only export
    markdown; in that case we strip markdown markers as a best-effort plain-text
    surrogate. Returning `""` is acceptable per the contract — `raw_text` may
    be empty, but must be present and non-null.
    """
    fn = getattr(doc, "export_to_text", None)
    if callable(fn):
        try:
            return fn() or ""
        except Exception as exc:  # pragma: no cover — defensive
            logger.warning("export_to_text failed, falling back: %s", exc)
    md = ""
    fn_md = getattr(doc, "export_to_markdown", None)
    if callable(fn_md):
        try:
            md = fn_md() or ""
        except Exception:  # pragma: no cover
            md = ""
    # Strip common markdown markers for a rough plain-text projection.
    import re

    text = re.sub(r"^\s*#+\s*", "", md, flags=re.MULTILINE)
    text = re.sub(r"^[\-*+]\s+", "", text, flags=re.MULTILINE)
    text = re.sub(r"`+", "", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"\1", text)
    return text


# ---------------------------------------------------------------------------
# Public entry points
# ---------------------------------------------------------------------------


def library_version() -> str:
    """Return installed docling version, or 'unknown' if unavailable."""
    try:
        import importlib.metadata as md

        return md.version("docling")
    except Exception:  # pragma: no cover
        return "unknown"


def convert_pdf_bytes(pdf_bytes: bytes, filename: str = "upload.pdf") -> DoclingResult:
    """Convert PDF bytes via Docling. Uses a temp file to avoid depending on
    a specific ``DocumentStream`` API shape across Docling minor versions.

    Raises:
        RuntimeError: propagated from Docling. Caller maps to ExtractionFailed.
    """
    with tempfile.NamedTemporaryFile(suffix=".pdf", prefix="docling-", delete=True) as tmp:
        tmp.write(pdf_bytes)
        tmp.flush()
        return convert_pdf_path(Path(tmp.name))


def convert_pdf_path(path: Path) -> DoclingResult:
    """Convert a PDF located at `path` via Docling.

    Separate from `convert_pdf_bytes` so Mode B (JSON {path}) avoids the
    bytes round-trip. Both paths converge on the same DoclingResult shape.
    """
    from docling.document_converter import DocumentConverter  # lazy import

    converter = _get_converter()
    result = converter.convert(str(path))
    document = getattr(result, "document", None)
    if document is None:
        # Status-based failure: Docling accepted input but produced no doc.
        status = getattr(result, "status", "unknown")
        raise RuntimeError(f"Docling produced no document (status={status})")

    raw_markdown = ""
    export_md = getattr(document, "export_to_markdown", None)
    if callable(export_md):
        raw_markdown = export_md() or ""
    raw_text = _export_text(document)

    conf = 0.0
    is_img = False
    confidence_report = getattr(result, "confidence", None)
    if confidence_report is not None:
        conf = _coerce_mean_grade(getattr(confidence_report, "mean_grade", None))
        is_img = _is_image_based(confidence_report)

    pages_attr = getattr(result, "pages", None)
    pages_count = 0
    if pages_attr is not None:
        try:
            pages_count = len(pages_attr)
        except TypeError:
            pages_count = 0
    if pages_count == 0 and confidence_report is not None:
        # Fallback: count confidence-report pages.
        per_page = getattr(confidence_report, "pages", None) or {}
        try:
            pages_count = len(per_page)
        except TypeError:
            pages_count = 0

    return DoclingResult(
        raw_markdown=raw_markdown,
        raw_text=raw_text,
        confidence=conf,
        is_image_based=is_img,
        pages=pages_count,
        library_version=library_version(),
    )


# ---------------------------------------------------------------------------
# Converter lifecycle
# ---------------------------------------------------------------------------

# Module-level singleton so we don't pay Docling's model-init cost per request.
# The Dockerfile warms this up during build so the first prod request is fast.
_CONVERTER = None


def _get_converter():
    global _CONVERTER
    if _CONVERTER is None:
        from docling.document_converter import DocumentConverter

        _CONVERTER = DocumentConverter()
        logger.info("docling DocumentConverter initialized (version=%s)", library_version())
    return _CONVERTER


def warm_up() -> None:
    """Force Docling initialization. Called during container image build and at
    service startup so the first real `/extract` request doesn't eat the
    one-off model-load latency.
    """
    _get_converter()
