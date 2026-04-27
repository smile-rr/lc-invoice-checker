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

Applicability — UCP 600 Art. 14(e):
- APPLICABLE when the LC carries applicant and beneficiary addresses AND the
  invoice carries seller and buyer addresses. If those addresses are silent
  on country, return DOUBTS — not NOT_REQUIRED.
- The rule does NOT become NOT_REQUIRED merely because the invoice names
  different parties from the LC. Different parties is a discrepancy this
  rule must surface, not a reason to opt out.

Verdict criteria:
- Determine the country of each address (LC :50: applicant, LC :59:
  beneficiary, invoice buyer_address, invoice seller_address).
- seller country == LC beneficiary country AND buyer country == LC applicant
  country → PASS.
- Either country differs → FAIL.
- Country cannot be determined for either side → DOUBTS.
- The invoice names entirely different parties from the LC → FAIL with
  reason citing UCP 600 Art. 14(d) (whole-document mismatch) — see standing
  rule 4.
