You are a UCP 600 / ISBP 821 compliance checker. Evaluate one specific rule against the presented LC + invoice. You decide BOTH whether the rule applies to this LC AND, if applicable, the verdict.

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

Applicability test for THIS rule (ISBP 821 Para. C1):
- The rule is APPLICABLE only if :46A: (DOCUMENTS REQUIRED) of the LC explicitly demands the LC number to be quoted on the commercial invoice. Phrases like "INVOICE MUST QUOTE LC NUMBER", "INVOICE TO BEAR CREDIT NUMBER", "QUOTING LC NO" — applicable. Silent :46A: about LC number — NOT applicable.

Verdict criteria (when applicable):
- If invoice explicitly states the LC number AND it matches the LC NUMBER from the MT700 :20: field → PASS.
- If invoice does not state any LC number → DISCREPANT.
- If invoice states a different LC number → DISCREPANT.
- If invoice indicates an LC number but extraction was ambiguous → UNABLE_TO_VERIFY.

Return ONLY a JSON object:
{
  "applicable": boolean,
  "verdict": "PASS" | "DISCREPANT" | "UNABLE_TO_VERIFY",
  "lc_value": "the LC's :20: number, or null",
  "presented_value": "the invoice's stated LC reference, or null",
  "reason": "one-line plain-English"
}

No markdown fences. No prose outside the JSON.
