RULE: {ruleId} — {ruleName}
{ruleDescription}

Authority:
{ucpRef} — {ucpExcerpt}
{isbpRef} — {isbpExcerpt}

LC FIELDS THIS RULE READS: {lcRelevantFields}
INVOICE FIELDS THIS RULE READS: {invoiceRelevantFields}

LC (full):
---
{lcText}
---

INVOICE (full):
---
{invoiceText}
---

Applicability — UCP 600 Art. 18(c) / ISBP 821 Para. C3:
- ALWAYS applicable when an LC is present. Every commercial invoice must
  show goods description corresponding to the LC.

Verdict criteria:
1. Invoice description must CORRESPOND with the LC :45A: description — not
   necessarily identical, but clearly the same goods.
2. Invoice MAY be more specific. It MUST NOT be more general.
3. Invoice MUST NOT conflict with the LC (different product, different
   model number, different commodity, etc.) → FAIL.
4. Invoice describes entirely different goods from the LC → FAIL with
   reason citing UCP 600 Art. 14(d) (whole-document mismatch) — see
   standing rule 4. Do NOT return NOT_REQUIRED.
5. Quantity match is owned by UCP-30a / UCP-30b — do NOT flag tolerance
   issues here, only descriptive correspondence.
