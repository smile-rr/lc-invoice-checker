package com.lc.checker.domain.rule.enums;

/**
 * Outcome taxonomy for a single rule check. Four buckets, one per
 * possible verdict:
 *
 * <ul>
 *   <li>{@link #PASS} — rule applied and the invoice complies.</li>
 *   <li>{@link #FAIL} — rule applied and a discrepancy was found.</li>
 *   <li>{@link #DOUBTS} — rule applied but the agent isn't confident
 *       enough to call it (ambiguous LC text, partial extraction, etc.).
 *       The agent must NOT guess — it returns DOUBTS rather than
 *       fabricate.</li>
 *   <li>{@link #NOT_REQUIRED} — rule does not apply to this presentation
 *       (the LC doesn't stipulate the rule's condition). Reason MUST cite
 *       UCP/ISBP.</li>
 * </ul>
 */
public enum CheckStatus {
    PASS,
    FAIL,
    DOUBTS,
    NOT_REQUIRED
}
