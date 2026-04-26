package com.lc.checker.domain.result;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * API response body for {@code POST /api/v1/lc-check}.
 *
 * <p>{@link #compliant} is {@code false} iff at least one rule landed in
 * {@code failed[]}. {@link #doubts} (agent uncertain) and {@link #notRequired}
 * (rule does not apply to this LC) NEVER flip compliance — Art 16 protocol.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DiscrepancyReport(
        String sessionId,
        boolean compliant,
        /** Spec-shape Discrepancy summaries (no rule_id) — wire-format compat
         *  with the original test-case JSON contract. Sourced from {@link #failed}. */
        List<Discrepancy> discrepancies,
        /** Typed CheckResults grouped by the four-bucket taxonomy. */
        List<CheckResult> passed,
        List<CheckResult> failed,
        List<CheckResult> doubts,
        List<CheckResult> notRequired,
        Summary summary
) {

    public DiscrepancyReport {
        discrepancies = discrepancies == null ? List.of() : List.copyOf(discrepancies);
        passed = passed == null ? List.of() : List.copyOf(passed);
        failed = failed == null ? List.of() : List.copyOf(failed);
        doubts = doubts == null ? List.of() : List.copyOf(doubts);
        notRequired = notRequired == null ? List.of() : List.copyOf(notRequired);
    }
}
