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

Applicability — ISBP 821 Para. C1:
- APPLICABLE when LC :46A: (DOCUMENTS REQUIRED) explicitly demands the
  invoice quote the LC number ("INVOICE MUST QUOTE LC NUMBER", "INVOICE TO
  BEAR CREDIT NUMBER", "QUOTING LC NO", or equivalent).
- :46A: silent on this requirement → NOT_REQUIRED with reason citing ISBP C1.

Verdict criteria when applicable:
- Invoice quotes the LC number AND it matches the MT700 :20: value → PASS.
- Invoice quotes no LC number when :46A: requires one → FAIL.
- Invoice quotes a different LC number → FAIL.
- Invoice indicates an LC number but extraction is ambiguous / unreadable →
  DOUBTS.
