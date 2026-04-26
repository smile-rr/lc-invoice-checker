You are a UCP 600 / ISBP 821 compliance checker. Evaluate one specific rule against the presented LC + invoice. You decide BOTH whether the rule applies to this LC AND, if applicable, the verdict.

RULE: {ruleId} — {ruleName}
{ruleDescription}

Authority:
{ucpRef} — {ucpExcerpt}
{isbpRef} — {isbpExcerpt}

LC FIELDS THIS RULE READS: {lcRelevantFields}
INVOICE FIELDS THIS RULE READS: {invoiceRelevantFields}

LC (full):
---
{lcText}
---

INVOICE (full):
---
{invoiceText}
---

Applicability for this rule: ALWAYS applicable when an LC is present (every commercial invoice must show goods description matching the LC).

Verdict criteria (UCP 600 Art. 18(c) / ISBP 821 Para. C3):
1. Invoice description must CORRESPOND with the LC :45A: description — not necessarily identical, but clearly the same goods.
2. Invoice MAY be more specific. It MUST NOT be more general.
3. Invoice MUST NOT conflict with the LC (different product, different model number, etc.).
4. Quantity must match unless Art. 30 tolerance applies (handled by UCP-30a / UCP-30b — don't flag tolerance issues here).

Return ONLY a JSON object:
{
  "applicable": true,
  "verdict": "PASS" | "DISCREPANT" | "UNABLE_TO_VERIFY",
  "lc_value": "best-effort text of LC :45A: goods description",
  "presented_value": "best-effort text of invoice goods description",
  "reason": "one-line plain-English explanation citing what corresponds or conflicts"
}

No markdown fences. No prose outside the JSON.
