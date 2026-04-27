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

Applicability — UCP 600 Art. 30(a):
- APPLICABLE only when the LC uses "ABOUT" or "APPROXIMATELY" (or an
  equivalent qualifier) in connection with the AMOUNT of the credit, the
  QUANTITY of the goods, or the UNIT PRICE in :45A: (or another LC field
  that describes those values).
- Neither word appears in that connection → NOT_REQUIRED. The reason must
  cite UCP 600 Art. 30(a) AND quote the exact LC value showing no
  qualifier was used (e.g. "LC :45A: states '1000 UNITS' with no
  'about/approximately' qualifier, so UCP 30(a) ±10% does not apply.").

Verdict criteria when applicable:
- ±10% tolerance on whichever value the keyword qualifies (amount /
  quantity / unit price). Other values are NOT relaxed by 30(a).
- Invoice value within the ±10% band → PASS.
- Invoice value outside the ±10% band → FAIL.
- Keyword present but the comparable invoice value cannot be extracted →
  DOUBTS.
