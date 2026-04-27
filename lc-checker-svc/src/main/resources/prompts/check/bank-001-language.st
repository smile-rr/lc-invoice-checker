RULE: {ruleId} — {ruleName}
{ruleDescription}

Bank Policy: Invoice goods description must be in English (bank-specific
deployment policy; not mandated by UCP/ISBP).

LC (full):
---
{lcText}
---

INVOICE (full):
---
{invoiceText}
---

Applicability — Bank Policy BANK-001:
- ALWAYS applicable when this rule is enabled in the catalog.

Verdict criteria:
- Invoice goods description reads as English (Latin script, English
  vocabulary) → PASS.
- Invoice goods description is in another language (Chinese, Arabic, etc.)
  → FAIL.
- Description missing or ambiguous → DOUBTS.
