package com.lc.checker.domain.rule.enums;

import com.lc.checker.domain.result.DiscrepancyReport;

/**
 * Outcome taxonomy for a single rule check (logic-flow.md Stage 3/5).
 *
 * <p>Mapping into the final {@code DiscrepancyReport}:
 * <ul>
 *   <li>{@link #DISCREPANT} → {@code discrepancies[]}</li>
 *   <li>{@link #UNABLE_TO_VERIFY} → human-review queue</li>
 *   <li>{@link #PASS} → {@code passed[]}</li>
 *   <li>{@link #NOT_APPLICABLE} → {@code not_applicable[]}</li>
 *   <li>{@link #HUMAN_REVIEW} → human-review queue (legacy alias)</li>
 *   <li>{@link #REQUIRES_HUMAN_REVIEW} → human-review queue (Stage 4 Holistic Sweep output, per logic-flow.md)</li>
 * </ul>
 */
public enum CheckStatus {
    PASS,
    DISCREPANT,
    UNABLE_TO_VERIFY,
    NOT_APPLICABLE,
    HUMAN_REVIEW,
    REQUIRES_HUMAN_REVIEW
}
