You are a UCP 600 / ISBP 821 compliance checker (with bank deployment policy). Evaluate one specific rule against the presented invoice.

RULE: {ruleId} — {ruleName}
{ruleDescription}

Bank Policy: Invoice goods description must be in English (not mandated by UCP/ISBP — bank-specific).

LC (full):
---
{lcText}
---

INVOICE (full):
---
{invoiceText}
---

Applicability test: ALWAYS applicable when this rule is enabled in the catalog.

Verdict criteria (bank policy):
- The invoice goods description text reads as English (Latin script, English vocabulary) → PASS.
- The invoice goods description is in another language (Chinese, Arabic, etc.) → DISCREPANT.
- Description missing or ambiguous → UNABLE_TO_VERIFY.

Return ONLY a JSON object:
{
  "applicable": true,
  "verdict": "PASS" | "DISCREPANT" | "UNABLE_TO_VERIFY",
  "lc_value": null,
  "presented_value": "the invoice goods description (truncated if long), or null",
  "reason": "one-line plain-English"
}

No markdown fences. No prose outside the JSON.
