"""LLM-based invoice field extraction from Docling markdown.

Reads LLM_BASE_URL / LLM_API_KEY / LLM_MODEL from the environment (same
settings the Java service uses for Type-B rule checks). Returns None if
the LLM is not configured or the call fails — the caller falls back to
the regex field_extractor.
"""

from __future__ import annotations

import json
import logging
import os
from typing import Any, Optional

import httpx

from .contract import InvoiceFields

logger = logging.getLogger(__name__)

_SYSTEM_PROMPT = """\
You are extracting fields from a commercial invoice for Letter-of-Credit
compliance review (UCP 600 / ISBP 821). Read the invoice text carefully and
return ONE JSON object — no preamble, no code fences, no commentary.

Return null for any field not found on the invoice.

Schema (return exactly this shape):
{
  "invoice_number": "string|null",
  "invoice_date": "YYYY-MM-DD|null",
  "seller_name": "beneficiary/seller name|null",
  "seller_address": "address, \\n for newlines|null",
  "buyer_name": "applicant/buyer name|null",
  "buyer_address": "address|null",
  "goods_description": "verbatim goods wording|null",
  "quantity": "number string|null",
  "unit": "PCS/MT/KG/…|null",
  "unit_price": "number string|null",
  "total_amount": "number string|null",
  "currency": "ISO-4217|null",
  "lc_reference": "LC number quoted on invoice|null",
  "trade_terms": "e.g. CIF SINGAPORE|null",
  "port_of_loading": "string|null",
  "port_of_discharge": "string|null",
  "country_of_origin": "string|null",
  "signed": true|false|null,
  "stamp_present": true|false|null,
  "letterhead_present": true|false|null,
  "line_items": [{"description":"…","hs_code":"…","quantity":"…","unit":"…","unit_price":"…","amount":"…"}]
}

Conventions:
- currency: ISO 4217 code only (USD/EUR/CNY). Never a symbol.
- invoice_date: ISO 8601 YYYY-MM-DD.
- Amounts / quantities: bare numbers, no thousands separators, no currency prefix.
- goods_description: verbatim — do NOT rephrase or shorten.
- signed: true if a signature is visible; false if explicitly absent; null if unclear.
- stamp_present: true if a company seal or rubber stamp is visible.
- letterhead_present: true if the seller's printed header/logo appears at the top.
- quantity / unit_price scalars: populate when the invoice has exactly ONE product
  line AND also include that row in line_items. For multi-line invoices leave these
  null — the rule engine reads line_items directly.
- line_items: one object per product row. Use null for any cell not printed.
  Return [] if no line-item table exists.
Output ONLY the JSON object.\
"""


def llm_extract_fields(markdown: str) -> Optional[InvoiceFields]:
    """Call the text LLM to extract invoice fields from Docling's markdown.

    Returns None if LLM is not configured or the call fails, so the caller
    can fall through to the regex extractor.
    """
    base_url = (os.getenv("LLM_BASE_URL") or "").rstrip("/")
    api_key = os.getenv("LLM_API_KEY") or ""
    model = os.getenv("LLM_MODEL") or ""

    if not base_url or not model:
        logger.debug("LLM not configured (LLM_BASE_URL/LLM_MODEL unset); skipping LLM extraction")
        return None

    payload = {
        "model": model,
        "temperature": 0.0,
        "messages": [
            {"role": "system", "content": _SYSTEM_PROMPT},
            {"role": "user", "content": f"Invoice text:\n\n{markdown}"},
        ],
    }
    headers: dict[str, str] = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    try:
        with httpx.Client(timeout=60.0) as client:
            resp = client.post(f"{base_url}/chat/completions", json=payload, headers=headers)
            resp.raise_for_status()
        raw_content: str = resp.json()["choices"][0]["message"]["content"]
    except Exception as exc:
        logger.warning("LLM call failed: %s; falling back to regex extractor", exc)
        return None

    try:
        data: dict[str, Any] = _parse_json(raw_content)
    except Exception as exc:
        logger.warning("LLM response is not valid JSON: %s; raw=%r", exc, raw_content[:200])
        return None

    try:
        fields = _map_to_invoice_fields(data)
        logger.info(
            "LLM extraction succeeded",
            extra={"event": "llm_extract", "non_null": fields.non_null_count()},
        )
        return fields
    except Exception as exc:
        logger.warning("Failed to map LLM output to InvoiceFields: %s", exc)
        return None


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _parse_json(text: str) -> dict[str, Any]:
    """Strip optional code fences the LLM may have added, then parse."""
    text = text.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        # drop first line (```json or ```) and last line (```)
        text = "\n".join(lines[1:-1] if lines[-1].strip() == "```" else lines[1:])
    return json.loads(text)


def _str(v: Any) -> Optional[str]:
    if v is None:
        return None
    s = str(v).strip()
    if s.lower() in ("null", "none", "n/a", ""):
        return None
    return s


def _bool(v: Any) -> Optional[bool]:
    if isinstance(v, bool):
        return v
    if v is None:
        return None
    s = str(v).strip().lower()
    if s in ("true", "yes", "1"):
        return True
    if s in ("false", "no", "0"):
        return False
    return None


def _list_of_dicts(v: Any) -> list[dict[str, Any]]:
    if not isinstance(v, list):
        return []
    return [row for row in v if isinstance(row, dict)]


def _map_to_invoice_fields(data: dict[str, Any]) -> InvoiceFields:
    return InvoiceFields(
        invoice_number=_str(data.get("invoice_number")),
        invoice_date=_str(data.get("invoice_date")),
        seller_name=_str(data.get("seller_name")),
        seller_address=_str(data.get("seller_address")),
        buyer_name=_str(data.get("buyer_name")),
        buyer_address=_str(data.get("buyer_address")),
        goods_description=_str(data.get("goods_description")),
        quantity=_str(data.get("quantity")),
        unit=_str(data.get("unit")),
        unit_price=_str(data.get("unit_price")),
        total_amount=_str(data.get("total_amount")),
        currency=_str(data.get("currency")),
        lc_reference=_str(data.get("lc_reference")),
        trade_terms=_str(data.get("trade_terms")),
        port_of_loading=_str(data.get("port_of_loading")),
        port_of_discharge=_str(data.get("port_of_discharge")),
        country_of_origin=_str(data.get("country_of_origin")),
        signed=_bool(data.get("signed")),
        stamp_present=_bool(data.get("stamp_present")),
        letterhead_present=_bool(data.get("letterhead_present")),
        line_items=_list_of_dicts(data.get("line_items")) or None,
    )
