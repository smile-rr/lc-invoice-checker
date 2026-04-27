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
- APPLICABLE only when ALL THREE of the following hold:
  1. LC :45A: describes goods in BULK — NOT in a stipulated number of
     packing units or individual items. Bulk = "kg", "MT", "litres",
     "barrels"; stipulated units = "1000 cartons", "500 pcs", "1000 UNITS".
  2. The total amount of the drawings does not exceed the amount of the
     credit.
  3. "ABOUT" or "APPROXIMATELY" is NOT used (those would trigger UCP-30a's
     ±10% rule, not this ±5% rule).
- Any condition fails → NOT_REQUIRED. The reason MUST cite UCP 600
  Art. 30(b) AND name the failing condition with the observed LC value
  (e.g. "LC :45A: stipulates '1000 UNITS' (individual-item count), so
  UCP 30(b) ±5% does not apply.").

Verdict criteria when applicable:
- ±5% tolerance on goods quantity only.
- Invoice quantity within the ±5% band → PASS.
- Invoice quantity outside the ±5% band → FAIL.
- LC quantity unit cannot be classified as bulk-vs-piece → DOUBTS.
