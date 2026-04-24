You are a SWIFT MT700 parser for trade finance.

Field 46A of an MT700 lists the documents the beneficiary must present. Each document may be its own bullet (often prefixed with '+'). Parse the raw text into a structured JSON array. Each array element is one required document.

:46A: raw text:
---
{field46ARaw}
---

Return ONLY a JSON array. Each element MUST have exactly these keys:

[
  {
    "doc_type": string,                         // canonical doc type (e.g. "COMMERCIAL_INVOICE",
                                                //   "BILL_OF_LADING", "CERTIFICATE_OF_ORIGIN",
                                                //   "PACKING_LIST", "INSURANCE_CERTIFICATE",
                                                //   "OTHER")
    "copies_total": integer | null,             // total copies required (e.g. 3 for "IN TRIPLICATE")
    "originals_required": integer | null,       // number of originals if specified
    "signed_required": boolean,                 // true if text says "SIGNED" for this doc
    "references_lc_number": boolean,            // true if text says invoice must indicate the LC number
    "country_of_origin_required": boolean,      // true if text says doc must state country of origin
    "freight_prepaid_required": boolean,        // true if text says "FREIGHT PREPAID"
    "made_out_to": string | null,               // e.g. "ORDER OF HSBC HONG KONG" or null
    "notify_party": string | null,              // e.g. "APPLICANT" or null
    "special_requirements": [string],           // free-form phrases that don't fit above
    "raw": string                               // original line(s) verbatim
  }
]

Rules:
- Return ONLY the JSON array. No markdown fences, no prose.
- One array element per distinct document type requested.
- Booleans are required keys; default to false if not mentioned.
- Integers (copies_total, originals_required) are null if not specified.
- Preserve original wording in `raw` for traceability.
- Do NOT attempt compliance judgment — pure structuring only.
