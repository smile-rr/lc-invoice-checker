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


class InvoiceFields(BaseModel):
    """Structured invoice fields. `signed`, `stamp_present`, `letterhead_present`
    are bool|null; `line_items` is a list of row dicts; everything else is str|null.

    The 18 original CONTRACT_FIELD_KEYS are always present (value may be null).
    Extended fields (stamp_present, letterhead_present, line_items) are populated
    by the LLM extractor and resolved by Java's FieldPoolRegistry alias table.
    Extras: catch-all for anything not mapped above.
    """

    # --- original 18 contract fields ---
    invoice_number: Optional[str] = None
    invoice_date: Optional[str] = None  # ISO 8601 YYYY-MM-DD preferred; raw allowed
    seller_name: Optional[str] = None
    seller_address: Optional[str] = None
    buyer_name: Optional[str] = None
    buyer_address: Optional[str] = None
    goods_description: Optional[str] = None
    quantity: Optional[str] = None
    unit: Optional[str] = None
    unit_price: Optional[str] = None
    total_amount: Optional[str] = None
    currency: Optional[str] = None
    lc_reference: Optional[str] = None
    trade_terms: Optional[str] = None
    port_of_loading: Optional[str] = None
    port_of_discharge: Optional[str] = None
    country_of_origin: Optional[str] = None
    signed: Optional[bool] = None
    # --- extended fields (LLM extractor; resolved by Java field-pool aliases) ---
    stamp_present: Optional[bool] = None
    letterhead_present: Optional[bool] = None
    line_items: Optional[list[dict[str, Any]]] = None
    # --- catch-all ---
    extras: dict[str, Any] = Field(default_factory=dict)

    def non_null_count(self) -> int:
        """Count of the 18 contract fields that have a non-null value.

        Used by mineru-svc's confidence formula (see CONTRACT.md Q3). Excluded
        by design: the `extras` dict — only declared contract fields count.
        """
        n = 0
        for key in CONTRACT_FIELD_KEYS:
            if getattr(self, key) is not None:
                n += 1
        return n


class ExtractResponse(BaseModel):
    """200 OK response body shared by all 3 input modes."""

    extractor: str  # Literal "docling" or "mineru"
    contract_version: str  # "1.0" for this service
    confidence: float = Field(ge=0.0, le=1.0)
    is_image_based: bool
    raw_markdown: str  # may be "" but never null
    raw_text: str  # may be "" but never null
    fields: InvoiceFields
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
