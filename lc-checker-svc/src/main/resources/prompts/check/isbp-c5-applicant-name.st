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

Applicability test: ALWAYS applicable when both LC :50: and an invoice buyer name are present.

Verdict criteria (ISBP 821 Para. C5, ISBP 821 Para. A14 — abbreviation tolerance):
- Invoice buyer name corresponds to LC :50: applicant. Allow ISBP A14 abbreviations and minor formatting.
- DISCREPANT when the entity is clearly different.

Return ONLY a JSON object:
{
  "applicable": boolean,
  "verdict": "PASS" | "DISCREPANT" | "UNABLE_TO_VERIFY",
  "lc_value": "LC applicant name (:50:)",
  "presented_value": "invoice buyer name",
  "reason": "one-line plain-English citing the correspondence judgment"
}

No markdown fences. No prose outside the JSON.
