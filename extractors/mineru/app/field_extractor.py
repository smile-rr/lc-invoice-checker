"""Extract the 18 contract fields from Docling markdown/text.

Two-tier strategy per CONTRACT.md §Field extraction feasibility:

1. **Regex/heuristic tier (always runs, deterministic)**
   Pulls HIGH-reliability fields directly from the extracted text:
   invoice_number, invoice_date, total_amount, currency, lc_reference,
   trade_terms, port_of_loading, port_of_discharge, country_of_origin,
   quantity / unit / unit_price (line-item regex), signed (keyword scan).

2. **LLM tier (runs only if LLM_API_KEY env-var is present)**
   Fills MED-reliability long-form fields that need contextual judgement:
   seller_name, seller_address, buyer_name, buyer_address, goods_description.
   When LLM is unavailable or fails, the tier-1 fallbacks (first recognised
   address block / joined line-items) populate those fields best-effort.

Contract rule: every field in CONTRACT_FIELD_KEYS is always present in
the output InvoiceFields; unknown values are `null`.
"""

from __future__ import annotations

import json
import logging
import os
import re
from typing import Optional

from dateutil import parser as date_parser

from .contract import InvoiceFields

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Tier 1 — deterministic regex / heuristic extractors
# ---------------------------------------------------------------------------

_RX_INVOICE_NUMBER = re.compile(
    r"invoice\s*(?:no|number|#)\.?\s*[:\-]?\s*([A-Z0-9][\w\-/]{2,})",
    re.IGNORECASE,
)

_RX_INVOICE_DATE_LABELED = re.compile(
    r"(?:invoice\s*date|date\s*of\s*invoice|date)\s*[:\-]\s*([0-9A-Za-z\-/,.\s]{6,30})",
    re.IGNORECASE,
)

_RX_TOTAL = re.compile(
    r"(?:grand\s+total|total\s+amount|total\s+due|total)\s*[:\-]?\s*"
    r"([A-Z]{3}\s*)?"                                # optional ISO currency prefix
    r"([\$€£¥])?"                                    # optional symbol
    r"\s*([0-9]{1,3}(?:[,\s][0-9]{3})*(?:\.[0-9]{2})?)",
    re.IGNORECASE,
)

_RX_CURRENCY_ISO = re.compile(r"\b(USD|EUR|GBP|JPY|CNY|HKD|SGD|AUD|CAD|CHF|INR|KRW)\b")

# Labeled LC reference: "L/C REF: LC2024-000123" / "LC NO: ABC-123" / "Letter of Credit: 12345"
# We require an explicit `:` or `-` separator so we don't accidentally strip a
# "LC" prefix that's actually part of the reference value itself.
_RX_LC_REF = re.compile(
    r"(?:l\.?/?c\.?|letter\s+of\s+credit)"
    r"\s*(?:ref\.?|no\.?|number|#)?\s*[:\-]\s*"
    r"([A-Z0-9][\w\-/]{3,})",
    re.IGNORECASE,
)

_INCOTERMS = (
    "EXW", "FOB", "FAS", "FCA", "CPT", "CIP",
    "DAP", "DPU", "DDP", "CFR", "CIF",
)
_RX_TRADE_TERMS = re.compile(
    # Optional location after the incoterm: uppercase + spaces only (no
    # newlines or punctuation) so "CIF SINGAPORE\nPort of Loading..." doesn't
    # bleed into the capture.
    r"\b(" + "|".join(_INCOTERMS) + r")\b(?:[ \t]+([A-Z][A-Z ]{2,40}))?"
)

# Character class is `[A-Z ,]` (plain space + comma only) not `\s` — otherwise
# the value greedily bleeds across newlines into the next labeled field.
_RX_PORT_LOADING = re.compile(
    r"port\s*of\s*loading\s*[:\-]?\s*([A-Z][A-Z ,]{2,40})", re.IGNORECASE
)
_RX_PORT_DISCHARGE = re.compile(
    r"port\s*of\s*discharge\s*[:\-]?\s*([A-Z][A-Z ,]{2,40})", re.IGNORECASE
)
_RX_COUNTRY_ORIGIN = re.compile(
    r"country\s*of\s*origin\s*[:\-]?\s*([A-Z][A-Z ]{2,40})", re.IGNORECASE
)

_RX_SIGNED_POSITIVE = re.compile(
    r"(authori[sz]ed\s+signator\w*"     # authorized signatory / signatories
    r"|signed\s+by"
    r"|seller'?s?\s+signature"
    r"|signature\s*[:\-])",
    re.IGNORECASE,
)


def _clean(s: Optional[str]) -> Optional[str]:
    if s is None:
        return None
    s = s.strip().strip(",.;:").strip()
    return s or None


def _normalize_amount(raw: str) -> Optional[str]:
    """Normalize a printed amount string to `NNNN.NN` (no thousands separators)."""
    if not raw:
        return None
    cleaned = raw.replace(" ", "").replace(",", "")
    if not re.fullmatch(r"[0-9]+(\.[0-9]+)?", cleaned):
        return None
    if "." not in cleaned:
        cleaned += ".00"
    return cleaned


def _normalize_date(raw: Optional[str]) -> Optional[str]:
    """Best-effort ISO 8601 YYYY-MM-DD. Returns the raw string if parsing fails."""
    if raw is None:
        return None
    raw = raw.strip().rstrip(".,;:")
    if not raw:
        return None
    try:
        dt = date_parser.parse(raw, dayfirst=False, fuzzy=True)
        return dt.strftime("%Y-%m-%d")
    except (ValueError, OverflowError, TypeError):
        return raw  # per contract: raw string allowed if not determinable


def _extract_total(text: str) -> tuple[Optional[str], Optional[str]]:
    """Return (total_amount, currency) pulled from the 'Total' row.

    Currency is preferred from the ISO prefix next to the total; falls
    back to the first ISO currency seen anywhere in the document.
    """
    match = _RX_TOTAL.search(text)
    iso_inline: Optional[str] = None
    amount: Optional[str] = None
    if match:
        iso_inline = (match.group(1) or "").strip() or None
        amount = _normalize_amount(match.group(3) or "")

    if iso_inline:
        currency = iso_inline.upper()
    else:
        doc_iso = _RX_CURRENCY_ISO.search(text)
        currency = doc_iso.group(1) if doc_iso else None
    return amount, currency


def _extract_signed(text: str) -> Optional[bool]:
    """Heuristic-only. Returns True when positive signature indicators are found;
    None otherwise (contract treats None as 'unknown')."""
    if _RX_SIGNED_POSITIVE.search(text):
        return True
    return None


def _extract_trade_terms(text: str) -> Optional[str]:
    match = _RX_TRADE_TERMS.search(text)
    if not match:
        return None
    term = match.group(1).upper()
    location = _clean(match.group(2))
    if location:
        return f"{term} {location}".strip()
    return term


# ---------------------------------------------------------------------------
# Line-item extraction (quantity / unit / unit_price)
# ---------------------------------------------------------------------------

_UNIT_TOKENS = (
    "UNITS", "UNIT", "PCS", "PIECES", "MT", "TONS", "KG", "KGS", "LB", "LBS",
    "EA", "EACH", "SET", "SETS", "PACK", "CTN", "CARTON", "CARTONS",
)
_RX_UNIT_TOKEN = re.compile(r"\b(" + "|".join(_UNIT_TOKENS) + r")\b", re.IGNORECASE)

_RX_LINE_ITEM = re.compile(
    # Match a line-item row: quantity + unit + description + unit_price.
    # Requires the price to carry a decimal so we don't accidentally match
    # trailing digits inside a model code like "IW-2024" as the price.
    r"(?P<qty>[0-9]{1,7}(?:[,\s][0-9]{3})*)"
    r"\s*(?P<unit>" + "|".join(_UNIT_TOKENS) + r")\b"
    r"[^\n]*?"
    r"(?P<price>[0-9]{1,6}\.[0-9]{2})",
    re.IGNORECASE,
)


def _extract_line_item(text: str) -> tuple[Optional[str], Optional[str], Optional[str]]:
    """Extract the first qty/unit/unit_price triple from the text.

    Deliberately simple: picks the first row that looks like
    `<digits> <UNIT> ... <price>`. For multi-line-item invoices the LLM
    tier is expected to pick the richer structure; this is a floor.
    """
    match = _RX_LINE_ITEM.search(text)
    if not match:
        return None, None, None
    qty = match.group("qty").replace(",", "").replace(" ", "") or None
    unit = match.group("unit").upper() if match.group("unit") else None
    price = _normalize_amount(match.group("price") or "")
    return qty, unit, price


# ---------------------------------------------------------------------------
# Tier 2 — optional LLM structuring
# ---------------------------------------------------------------------------

_LLM_PROMPT = """You are a trade-finance document parser.
Extract the following fields from this invoice and return ONLY valid JSON.
No explanation, no markdown, no preamble.

Return a JSON object with exactly these keys (use null when unknown):
seller_name, seller_address, buyer_name, buyer_address, goods_description

`seller_*` = the beneficiary / exporter (who is selling).
`buyer_*`  = the applicant / importer (who is buying).
`goods_description` = the full goods text as it should appear on a shipping document.

Addresses joined with "\\n" across lines. If a field is not found, use null. Never guess.

Invoice text:
---
{invoice_text}
---
"""


def _llm_structure(invoice_text: str) -> dict:
    """Ask an OpenAI-compatible LLM to structure the MED-reliability fields.

    Returns an empty dict on any failure (missing env-vars, API error, bad JSON)
    — caller then leaves those fields as-null so the regex/heuristic fallback
    is still honored for the HIGH-reliability fields.
    """
    api_key = os.environ.get("LLM_API_KEY")
    if not api_key:
        logger.debug("LLM_API_KEY not set; skipping LLM structuring tier")
        return {}
    base_url = os.environ.get("LLM_BASE_URL", "https://api.deepseek.com")
    model = os.environ.get("LLM_MODEL", "deepseek-chat")

    try:
        from openai import OpenAI

        client = OpenAI(api_key=api_key, base_url=base_url, timeout=15.0)
        response = client.chat.completions.create(
            model=model,
            temperature=0.0,
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": "You output only strict JSON."},
                {"role": "user", "content": _LLM_PROMPT.format(
                    invoice_text=invoice_text[:8000]  # cap prompt size
                )},
            ],
        )
        content = response.choices[0].message.content or "{}"
        parsed = json.loads(content)
        if not isinstance(parsed, dict):
            return {}
        return parsed
    except Exception as exc:
        logger.warning("LLM structuring tier failed (%s); continuing with regex-only", exc)
        return {}


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------


def extract_fields(raw_markdown: str, raw_text: str) -> InvoiceFields:
    """Build an InvoiceFields from the Docling outputs.

    Regex/heuristic tier runs unconditionally. LLM tier runs only when
    LLM_API_KEY is present; its output overlays the regex results for the
    MED-reliability fields.
    """
    # Prefer raw_text for regex (markdown has extra punctuation); fall back to markdown.
    source = raw_text if raw_text.strip() else raw_markdown

    # Tier 1
    m_inv_num = _RX_INVOICE_NUMBER.search(source)
    m_inv_date = _RX_INVOICE_DATE_LABELED.search(source)
    total_amount, currency = _extract_total(source)
    m_lc = _RX_LC_REF.search(source)
    trade_terms = _extract_trade_terms(source)
    m_pol = _RX_PORT_LOADING.search(source)
    m_pod = _RX_PORT_DISCHARGE.search(source)
    m_coo = _RX_COUNTRY_ORIGIN.search(source)
    qty, unit, unit_price = _extract_line_item(source)

    fields = InvoiceFields(
        invoice_number=_clean(m_inv_num.group(1)) if m_inv_num else None,
        invoice_date=_normalize_date(m_inv_date.group(1)) if m_inv_date else None,
        seller_name=None,
        seller_address=None,
        buyer_name=None,
        buyer_address=None,
        goods_description=None,
        quantity=qty,
        unit=unit,
        unit_price=unit_price,
        total_amount=total_amount,
        currency=currency,
        lc_reference=_clean(m_lc.group(1)) if m_lc else None,
        trade_terms=trade_terms,
        port_of_loading=_clean(m_pol.group(1)) if m_pol else None,
        port_of_discharge=_clean(m_pod.group(1)) if m_pod else None,
        country_of_origin=_clean(m_coo.group(1)) if m_coo else None,
        signed=_extract_signed(source),
    )

    # Tier 2 overlay (best-effort; silent on failure)
    overlay = _llm_structure(source)
    if overlay:
        for key in ("seller_name", "seller_address", "buyer_name",
                    "buyer_address", "goods_description"):
            val = overlay.get(key)
            if isinstance(val, str) and val.strip():
                setattr(fields, key, val.strip())

    # Deterministic fallback for goods_description when LLM was absent or silent.
    if fields.goods_description is None:
        fallback_desc = _first_goods_like_line(source)
        if fallback_desc:
            fields.goods_description = fallback_desc

    return fields


def _first_goods_like_line(text: str) -> Optional[str]:
    """Pick a line that looks like a goods description as a deterministic fallback.

    Scans lines with a unit token and > 20 non-numeric characters — this
    tends to catch the first line-item description row Docling extracted.
    """
    for line in text.splitlines():
        stripped = line.strip()
        if len(stripped) < 20:
            continue
        if not _RX_UNIT_TOKEN.search(stripped):
            continue
        alpha = sum(1 for c in stripped if c.isalpha())
        if alpha >= 15:
            return stripped
    return None
