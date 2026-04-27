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

Applicability — ISBP 821 Para. C8:
- APPLICABLE only when the LC :45A: states an Incoterm (CIF, FOB, CFR, CIP,
  DAP, DDP, EXW, FCA, CPT, DPU).
- :45A: silent on Incoterm → NOT_REQUIRED with reason citing ISBP C8.

Verdict criteria when applicable:
- Invoice `incoterms` (or text equivalent in goods description) reflects
  the SAME Incoterm AND the SAME destination/port stated in the LC → PASS.
- Different Incoterm or different destination → FAIL. Standing rule 3
  applies — do NOT return NOT_REQUIRED to escape a clear mismatch.
- Invoice silent on trade terms while LC stipulates one → FAIL.
- LC Incoterm present, invoice trade_terms unreadable → DOUBTS.
