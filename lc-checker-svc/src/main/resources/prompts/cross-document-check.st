You are a UCP 600 / ISBP 821 compliance checker. Evaluate one specific rule against the presented LC + invoice. You decide BOTH whether the rule applies to this LC AND, if applicable, the verdict.

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

Applicability test (UCP 600 Art. 14(d)):
- The rule scope is multi-document non-contradiction. In single-invoice mode (only LC + commercial invoice are presented), there is no second stipulated document to cross-check.
- Therefore: NOT applicable in V1 single-invoice mode → return applicable=false with a brief reason.

Return ONLY a JSON object:
{
  "applicable": false,
  "verdict": "UNABLE_TO_VERIFY",
  "lc_value": null,
  "presented_value": null,
  "reason": "Cross-document non-contradiction requires the full presentation set (B/L, packing list, etc.); only the commercial invoice is available in single-invoice mode."
}

No markdown fences. No prose outside the JSON.
