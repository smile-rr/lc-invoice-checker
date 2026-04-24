package com.lc.checker.model.enums;

/**
 * Pre-gate Q2 from logic-flow.md Stage 3: what to emit when the invoice side of a rule
 * is missing the field being compared.
 *
 * <ul>
 *   <li>{@link #DISCREPANT} — LC required the field; its absence is a discrepancy.</li>
 *   <li>{@link #NOT_APPLICABLE} — rule only applies when the field is present; skip quietly.</li>
 *   <li>{@link #UNABLE_TO_VERIFY} — field is expected but extractor confidence is unclear;
 *       surface to human review rather than flag as a discrepancy.</li>
 * </ul>
 */
public enum MissingInvoiceAction {
    DISCREPANT,
    NOT_APPLICABLE,
    UNABLE_TO_VERIFY
}
