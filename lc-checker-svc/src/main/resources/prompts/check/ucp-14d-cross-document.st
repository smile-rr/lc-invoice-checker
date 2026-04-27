RULE: {ruleId} — {ruleName}
{ruleDescription}

Authority:
{ucpRef} — {ucpExcerpt}
{isbpRef} — {isbpExcerpt}

LC (full):
---
{lcText}
---

INVOICE (full):
---
{invoiceText}
---

Applicability — UCP 600 Art. 14(d):
- Multi-document non-contradiction. In V1 single-invoice mode (only LC +
  commercial invoice presented) there is no second stipulated document to
  cross-check, so this rule never fires here.
- ALWAYS return NOT_REQUIRED in V1 single-invoice mode.

Return verbatim (override the standard schema for this disabled rule):
{
  "verdict":         "NOT_REQUIRED",
  "lc_value":        null,
  "presented_value": null,
  "reason":          "Per UCP 600 Art. 14(d), cross-document non-contradiction requires the full presentation set (B/L, packing list, etc.); only the commercial invoice is available in single-invoice mode."
}
