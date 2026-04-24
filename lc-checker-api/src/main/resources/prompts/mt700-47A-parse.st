You are a SWIFT MT700 parser for trade finance.

Field 47A lists additional conditions the presentation must satisfy. Each condition may span multiple lines. Split them into independent, self-contained items and classify each.

:47A: raw text:
---
{field47ARaw}
---

Return ONLY a JSON array. Each element MUST have exactly these keys:

[
  {
    "id": string,                   // "47A-1", "47A-2", ... in the order they appear
    "type": string,                 // one of: "REQUIREMENT", "RESTRICTION", "RELAXATION",
                                    //   "INFORMATION"
    "target_doc": string,           // one of: "INVOICE", "BILL_OF_LADING", "CERTIFICATE_OF_ORIGIN",
                                    //   "PACKING_LIST", "INSURANCE", "ALL", "OTHER"
    "text": string,                 // the condition text, cleaned and joined to a single line
    "checkable_field": string | null, // if the condition can be mapped to a single invoice field
                                    //   (e.g. "currency", "language", "presentation_days"), name it
    "expected_value": string | null, // the value that field must equal, if deterministic
                                    //   (e.g. "21 DAYS", "ENGLISH")
    "raw": string                   // original condition text verbatim
  }
]

Rules:
- Return ONLY the JSON array. No markdown fences, no prose.
- Type guidance:
    REQUIREMENT  — imposes an obligation ("MUST BE IN ENGLISH")
    RESTRICTION  — prohibits or limits ("THIRD PARTY DOCUMENTS NOT ACCEPTABLE")
    RELAXATION   — loosens a default rule ("STALE BILLS ACCEPTABLE")
    INFORMATION  — neutral statement, no check implied
- `target_doc` defaults to "ALL" when the condition applies to the full presentation (e.g. "ALL DOCUMENTS IN ENGLISH").
- Preserve original wording in `raw` for traceability.
- Do NOT attempt compliance judgment.
