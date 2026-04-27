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

Applicability — UCP 600 Art. 18(b):
- UNCONDITIONAL. This rule does not become NOT_REQUIRED. If the LC amount
  or invoice total cannot be determined, prefer DOUBTS.

## Decision tree (apply in order)

1. Is invoice `total_amount` present and parseable as a decimal? If not →
   DOUBTS with reason "invoice total amount missing or unreadable".
2. Is the LC `credit_amount` (from :32B:) present? If not → DOUBTS with
   reason "LC amount missing".
3. Pick the tolerance source. Walk this list and stop at the first match:
   a. **:39A: tolerance present** (e.g. "5/5") → use those plus/minus
      percentages.
   b. **:39B: contains "NOT EXCEEDING"** (or equivalent ceiling phrase) →
      plus = 0, minus = 0.
   c. **Goods are unit-priced** (invoice has unit_price AND quantity) →
      plus = 0, minus = 0 per UCP 30(b) (5% rule applies only to
      bulk-quantity drawings, not amount).
   d. **Bulk goods, no tolerance stated** → plus = 5, minus = 5 per
      UCP 30(b) default.
4. Call `check_within_tolerance(value=invoice.total_amount,
   base=lc.credit_amount, plus_pct=<chosen>, minus_pct=<chosen>)`.
5. Read the tool's `status` field:
   - WITHIN → PASS
   - ABOVE  → FAIL (severity MAJOR — overdrawing)
   - BELOW  → PASS per UCP 30(c) (underdrawing is allowed)
6. Record the equation you used in `equation_used`, e.g.
   "max_allowed = 50000 × (1 + 0.05) = 52500; invoice 55000 > 52500 → ABOVE".

## Tools available

You MUST use this tool — do not perform the percentage math mentally:

- **check_within_tolerance(value, base, plus_pct, minus_pct)** → returns
  `{status: "WITHIN"|"ABOVE"|"BELOW", min, max}`. Computes the band
  base × (1 ± pct/100) and reports where `value` sits.

If a tool input is null in the LC or invoice, do NOT invent it — return
DOUBTS instead. Standing rule 2 (both blank → DOUBTS) takes priority over
this rule's "unconditional" framing only when the inputs are unobservable.
