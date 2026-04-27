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

Applicability — UCP 600 Art. 18(d) / ISBP 821 Para. C2:
- An invoice need NOT be signed unless the LC explicitly stipulates it.
- APPLICABLE only when LC :46A: contains explicit signature requirement
  language ("SIGNED COMMERCIAL INVOICE", "MANUALLY SIGNED INVOICE",
  "INVOICE MUST BE SIGNED", or equivalent).
- :46A: silent on signature → NOT_REQUIRED with reason citing ISBP C2.

Verdict criteria when applicable:
- Invoice `signed` is true → PASS.
- Invoice `signed` is false → FAIL.
- Invoice `signed` is absent / unreadable → DOUBTS.
