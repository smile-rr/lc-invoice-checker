RULE: {ruleId} — {ruleName}
{ruleDescription}

Authority:
{isbpRef} — {isbpExcerpt}

LC (full):
---
{lcText}
---

INVOICE (full):
---
{invoiceText}
---

LC FIELDS THIS RULE READS: {lcRelevantFields}
INVOICE FIELDS THIS RULE READS: {invoiceRelevantFields}

Applicability — ISBP 821 Para. C10:
- APPLICABLE only when the invoice itemises charges or deductions BEYOND
  the goods price (freight, insurance premium, inland transport, packaging,
  bank charges) AND the LC :45A: has stated a trade term (Incoterm).
- Invoice has only the goods price (no separately-stated charges) AND the
  LC trade term is consistent with that → NOT_REQUIRED with reason citing
  ISBP C10.
- Note: a trade-term mismatch (e.g. LC says CIF, invoice says DAP) is not
  this rule's concern — that belongs to ISBP-C8. Do not let a trade-term
  mismatch alone trigger FAIL here; pass the verdict to ISBP-C8.

Verdict criteria when applicable:
1. Charges shown on the invoice must be CONSISTENT with the LC's trade term:
   - CIF/CIP: invoice may include freight + insurance up to destination.
     Including them is correct; excluding is also accepted but values must
     be realistic.
   - FOB/FCA: invoice should NOT include onward freight/insurance to
     destination — those are buyer's cost. Charging the buyer for them on
     top of the goods price → FAIL.
   - EXW: invoice should NOT include any onward charges.
2. The invoice TOTAL (goods + any charges) must NOT exceed the LC amount
   after applying :39A: tolerance. Charges cannot push the total above
   the LC limit → FAIL.
3. Trade-term-vs-charges relationship is ambiguous (e.g. "freight prepaid"
   annotation but no separate freight line) → DOUBTS.
