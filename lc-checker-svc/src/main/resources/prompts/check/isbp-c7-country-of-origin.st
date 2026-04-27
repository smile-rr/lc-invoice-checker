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

Applicability — ISBP 821 Para. C7:
- APPLICABLE only when LC :46A: demands the country of origin be stated
  ("COUNTRY OF ORIGIN", "ORIGIN OF GOODS", or a "CERTIFICATE OF ORIGIN"
  document requirement implies it must appear on the invoice as well).
- :46A: silent → NOT_REQUIRED with reason citing ISBP C7.

Verdict criteria when applicable:
- Invoice's `country_of_origin` is non-empty → PASS.
- Country of origin missing → FAIL.
- Country of origin present but conflicts with the LC :45A: stated origin →
  FAIL. Standing rule 3 applies (clear mismatch).
- Country of origin field unreadable → DOUBTS.
