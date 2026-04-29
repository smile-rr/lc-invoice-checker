RULE: {ruleId} — {ruleName}
{ruleDescription}

Authority:
{ucpRef} — {ucpExcerpt}

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

Applicability — UCP 600 Art. 30(b):
UCP 30(b) governs how invoice quantity may deviate from the quantity stated
in the LC. Branch on how the LC :45A: expresses the quantity:

A) BULK quantity (e.g. "kg", "MT", "litres", "barrels"), AND
   "ABOUT"/"APPROXIMATELY" is NOT used, AND total drawings do not exceed
   the LC amount:
   - ±5% tolerance applies to the goods quantity.
   - Invoice quantity within ±5% band → PASS.
   - Invoice quantity outside ±5% band → FAIL.

B) STIPULATED COUNT of packing units or individual items
   (e.g. "1000 cartons", "500 pcs", "TOTAL 30 UNITS"):
   - UCP 30(b) explicitly disallows the ±5% tolerance. The stipulated
     count must therefore be presented EXACTLY.
   - Invoice quantity exactly equals the LC stipulated count → PASS.
   - Invoice quantity differs from the LC stipulated count → FAIL.
     Reason MUST quote the LC :45A: phrase and the invoice quantity, e.g.
     "LC :45A: stipulates 'TOTAL 30 UNITS' (individual-item count, no
     tolerance under UCP 30(b)); invoice presents quantity 500 — exact
     match required, discrepancy."

C) "ABOUT" or "APPROXIMATELY" is used in :45A: for the quantity:
   - That triggers UCP-30a's ±10% rule, not this ±5% rule. Emit
     NOT_REQUIRED and cite UCP 600 Art. 30(a) overlap.

D) LC quantity unit cannot be classified as bulk-vs-piece, or the LC
   :45A: does not state a quantity at all → DOUBTS.

Always cite UCP 600 Art. 30(b) in the reason and name the LC phrase you
relied on.
