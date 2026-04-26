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

Applicability test (ISBP 821 Para. C7):
- The rule is APPLICABLE only if :46A: (DOCUMENTS REQUIRED) demands the country of origin to be stated on the invoice. Phrases like "COUNTRY OF ORIGIN", "ORIGIN OF GOODS", "CERTIFICATE OF ORIGIN" within :46A: imply the requirement.
- Silent :46A: → NOT applicable.

Verdict criteria (when applicable):
- Invoice's country_of_origin is non-empty → PASS.
- Country of origin missing → DISCREPANT.

Return ONLY a JSON object:
{
  "applicable": boolean,
  "verdict": "PASS" | "DISCREPANT" | "UNABLE_TO_VERIFY",
  "lc_value": "the :46A: phrase that triggered the requirement, or null",
  "presented_value": "the invoice country of origin, or null",
  "reason": "one-line plain-English"
}

No markdown fences. No prose outside the JSON.
