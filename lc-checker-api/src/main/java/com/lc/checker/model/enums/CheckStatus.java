package com.lc.checker.model.enums;

/**
 * Outcome taxonomy for a single rule check (logic-flow.md Stage 3/5).
 *
 * <p>Mapping into the final {@code DiscrepancyReport}:
 * <ul>
 *   <li>{@link #DISCREPANT} → {@code discrepancies[]}</li>
 *   <li>{@link #UNABLE_TO_VERIFY} → human-review queue</li>
 *   <li>{@link #PASS} → {@code passed[]}</li>
 *   <li>{@link #NOT_APPLICABLE} → {@code not_applicable[]}</li>
 *   <li>{@link #HUMAN_REVIEW} → human-review queue (Layer-3 output, not emitted in V1)</li>
 * </ul>
 */
public enum CheckStatus {
    PASS,
    DISCREPANT,
    UNABLE_TO_VERIFY,
    NOT_APPLICABLE,
    HUMAN_REVIEW
}
