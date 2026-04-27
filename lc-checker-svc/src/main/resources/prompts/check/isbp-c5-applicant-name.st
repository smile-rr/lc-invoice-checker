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

Applicability — UCP 600 Art. 18(a) / ISBP 821 Para. C5:
- ALWAYS applicable when both LC :50: and an invoice buyer name are present.
- Either side missing → DOUBTS, never NOT_REQUIRED — the standing rule
  applies (we cannot verify identity without both names).

Verdict criteria:
- Invoice `buyer_name` corresponds to LC :50: applicant. Allow ISBP A14
  abbreviations and minor formatting differences.
- Names refer to the same entity (modulo ISBP A14 / B1–B5 tolerances) → PASS.
- Names refer to clearly different entities → FAIL. Standing rule 3 applies:
  do NOT return NOT_REQUIRED to escape an obvious mismatch.
- One side blank with the other populated → DOUBTS.
