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

Applicability — ISBP 821 Para. C4:
- APPLICABLE only when LC :45A: stipulates a unit price (e.g.
  "USD 50.00 PER UNIT", "PRICE PER MT 1200 USD", "@ 25 PER PIECE").
- :45A: silent on unit price → NOT_REQUIRED with reason citing ISBP C4.
- LC stipulates a unit price but invoice does not state any unit price →
  FAIL (the invoice must reflect the stipulated price). Do NOT return
  NOT_REQUIRED in this case.

Verdict criteria when applicable:
- Extract the LC's stipulated unit price. Compare against invoice
  `unit_price`.
- Numeric match (allowing minor rounding to 2 decimals) → PASS.
- Numeric mismatch → FAIL.
- Unit price cannot be extracted from one side and the other side has it →
  DOUBTS.
