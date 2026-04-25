"""Pydantic models mirroring extractors/CONTRACT.md v1.0.

These are the authoritative shapes for the HTTP interface. Every response
must round-trip through these models so the Java client's deserializer
never hits an unexpected key or missing field.

Key invariants enforced here:
- `fields` always has all 18 CONTRACT_FIELD_KEYS present (value may be null).
- Top-level response always carries `extractor`, `contract_version`,
  `confidence`, `is_image_based`, `raw_markdown`, `raw_text`, `fields`,
  `pages`, `extraction_ms` (all required post-Phase-0).
"""

from __future__ import annotations

from typing import Any, Optional

from pydantic import BaseModel, Field, field_validator

# The 18 invoice field keys the contract guarantees are always present in
# the response `fields` object. Services MUST NOT drop keys; value may be null.
CONTRACT_FIELD_KEYS: tuple[str, ...] = (
    "invoice_number",
    "invoice_date",
    "seller_name",
    "seller_address",
    "buyer_name",
    "buyer_address",
    "goods_description",
    "quantity",
    "unit",
    "unit_price",
    "total_amount",
    "currency",
    "lc_reference",
    "trade_terms",
    "port_of_loading",
    "port_of_discharge",
    "country_of_origin",
    "signed",
)


# Pass-through field map: whatever keys the LLM produces (driven by the
# client-supplied prompt + field-pool.yaml on Java side) flow through to
# Java unchanged. The sidecar holds NO field list of its own.
InvoiceFields = dict[str, Any]


class ExtractResponse(BaseModel):
    """200 OK response body shared by all 3 input modes."""

    extractor: str  # Literal "docling" or "mineru"
    contract_version: str  # "1.0" for this service
    confidence: float = Field(ge=0.0, le=1.0)
    is_image_based: bool
    raw_markdown: str  # may be "" but never null
    raw_text: str  # may be "" but never null
    fields: dict[str, Any]
    pages: int = Field(ge=0)
    extraction_ms: int = Field(ge=0)


class HealthResponse(BaseModel):
    """GET /health body. Per contract, distinguishes contract vs service vs library version."""

    status: str  # "ok"
    extractor: str
    contract_version: str
    service_version: str  # semver of this service package
    library_version: str  # semver of underlying extraction library (e.g. docling 2.91.0)


class ErrorResponse(BaseModel):
    """Standard error body used for 4xx and 5xx.

    Both `error` (machine code) and `message` (human) are always present;
    `extractor` identifies which service produced the error so the Java
    client can correlate in logs.
    """

    error: str
    message: str
    extractor: str


class PathRequest(BaseModel):
    """Mode B — local file path body."""

    path: str

    @field_validator("path")
    @classmethod
    def non_empty(cls, v: str) -> str:
        if not v or not v.strip():
            raise ValueError("path must be non-empty")
        return v


class UrlRequest(BaseModel):
    """Mode C — URL body."""

    url: str

    @field_validator("url")
    @classmethod
    def non_empty(cls, v: str) -> str:
        if not v or not v.strip():
            raise ValueError("url must be non-empty")
        return v
