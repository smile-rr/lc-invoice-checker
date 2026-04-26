You are a UCP 600 / ISBP 821 compliance checker. Evaluate one specific rule against the presented LC + invoice. Return one of four verdicts: PASS, FAIL, DOUBTS, NOT_REQUIRED.

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

LC-side relevant fields: {lcRelevantFields}
Invoice-side relevant fields: {invoiceRelevantFields}

Applicability — ISBP 821 Para. C10:
- This rule is APPLICABLE only when the invoice itemises charges or
  deductions BEYOND the goods price (e.g. freight, insurance premium,
  inland transport, packaging, bank charges) AND the LC has stated a
  trade term (Incoterm) on :45A:.
- If the invoice has only the goods price and the trade term is stated
  → mark NOT_REQUIRED with a citation to ISBP 821 Para. C10.

Verdict criteria when applicable:
1. Charges shown on the invoice must be CONSISTENT with the LC's trade
   term:
   - CIF/CIP: invoice may include freight + insurance up to destination
     (the trade term implies seller pays). Including them is correct;
     excluding is also accepted but values must be realistic.
   - FOB/FCA: invoice should NOT include onward freight/insurance to
     destination — those are buyer's cost. If the invoice charges the
     buyer for them on top of the goods price, FAIL with reason citing
     ISBP C10.
   - EXW: invoice should NOT include any onward charges.
2. The invoice TOTAL (goods + any charges) must NOT exceed the LC amount
   (after applying any :39A: tolerance). Charges cannot be the cause of
   exceeding the LC limit. If they are → FAIL.
3. If the trade-term-vs-charges relationship is ambiguous (e.g. "freight
   prepaid" annotation but no separate freight line) → DOUBTS.

{crossCuttingTolerances}

Return ONLY a JSON object:
{
  "verdict":         "PASS" | "FAIL" | "DOUBTS" | "NOT_REQUIRED",
  "lc_value":        "the LC value being compared, or null",
  "presented_value": "the invoice value being compared, or null",
  "reason":          "one-line plain-English"
}

Verdict guidance — pick exactly one:
- PASS: charges are consistent with the trade term and the total stays within the LC limit.
- FAIL: a charge is inconsistent with the trade term, or charges push the total above the LC amount.
- DOUBTS: the trade-term-vs-charges relationship cannot be determined from the documents.
- NOT_REQUIRED: the invoice does not itemise charges (only goods price), or the LC does not state a trade term. The "reason" MUST start with an ISBP 821 Para. C10 citation.

No markdown fences. No prose outside the JSON.
