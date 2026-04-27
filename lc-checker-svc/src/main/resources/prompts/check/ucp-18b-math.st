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
- UNCONDITIONAL. This rule does not become NOT_REQUIRED. If the inputs
  cannot be determined or the invoice structure makes header arithmetic
  meaningless (multi-line, freight breakdown), prefer DOUBTS.

## Decision tree (apply in order)

1. Identify the invoice's quantity, unit_price, and total_amount as
   **header values**. If the invoice is unambiguous (single-line,
   single-price, single-total), proceed to step 4.
2. **Multiple line items?** If the invoice shows several rows each with
   its own quantity × unit_price, the per-line equation is per-line
   subtotal. The header `total_amount` should equal the sum of subtotals.
   This rule is about reconciling the *header* arithmetic — V1 does NOT
   iterate line items. Multi-line invoice → DOUBTS with reason
   "multi-line invoice; header arithmetic cannot be reconciled from a
   single qty × price".
3. **Separately stated freight / insurance / other charges?** Under
   CIF/CIP, freight and insurance are part of the invoice value. If the
   invoice breaks them out as separate lines, `total_amount` =
   (qty × unit_price) + freight + insurance. The simple `qty × unit_price
   ≈ total` equation is then expected to fail. DOUBTS with reason
   "invoice has separately-stated freight/insurance; header arithmetic
   does not reconcile to qty × unit_price".
4. Otherwise: call `verify_arithmetic(quantity, unit_price, total_amount,
   epsilon=0.01)`.
5. Read the tool's `match` field:
   - true  → PASS
   - false → FAIL (severity MAJOR — invoice arithmetic does not reconcile)
6. Record the equation you used in `equation_used`, e.g.
   "1000 × 50.00 = 50000.00; invoice total 55000.00; diff 5000.00 > 0.01
   → mismatch".

## Tools available

You MUST use this tool — do not multiply mentally:

- **verify_arithmetic(quantity, unit_price, total_amount, epsilon)** →
  returns `{match, computed, diff}`. Computes quantity × unit_price and
  compares to total_amount within epsilon (default 0.01).

If quantity, unit_price, or total_amount is null on the invoice, do NOT
invent values — return DOUBTS.
