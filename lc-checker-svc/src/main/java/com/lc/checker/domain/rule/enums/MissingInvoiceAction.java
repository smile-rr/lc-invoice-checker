package com.lc.checker.domain.rule.enums;

/**
 * Pre-gate policy for PROGRAMMATIC rules when none of the rule's
 * {@code invoice_fields} are present on the extracted invoice.
 *
 * <ul>
 *   <li>{@link #FAIL} — the field is mandatory; its absence is a
 *       discrepancy.</li>
 *   <li>{@link #DOUBTS} — the field is expected but the extractor
 *       didn't surface it; flag for human review rather than
 *       fabricating a verdict.</li>
 * </ul>
 *
 * <p>NOT_REQUIRED is intentionally not a value here: programmatic rules
 * always apply (every commercial invoice is in scope). NOT_REQUIRED is
 * exclusively an AGENT verdict ("the LC doesn't stipulate this rule").
 */
public enum MissingInvoiceAction {
    FAIL,
    DOUBTS
}
