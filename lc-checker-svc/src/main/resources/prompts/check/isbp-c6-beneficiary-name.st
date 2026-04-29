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

Applicability — UCP 600 Art. 18(a) / ISBP 821 Para. C6:
- ALWAYS applicable when both LC :59: and an invoice seller name are present.
- Either side missing → DOUBTS, never NOT_REQUIRED.

Verdict criteria:
- Invoice `beneficiary_name` corresponds to LC :59: beneficiary. Allow:
  - ISBP A14 abbreviations (Co. = Company, Ltd. = Limited, Corp. =
    Corporation, Inc. = Incorporated)
  - Word reordering, punctuation differences
  - Minor formatting (case, hyphens, ampersands)
- Names refer to the same entity → PASS.
- Names clearly refer to different entities (different legal name,
  different domicile, parent vs. subsidiary) → FAIL. Standing rule 3 applies.
- One side blank with the other populated → DOUBTS.
