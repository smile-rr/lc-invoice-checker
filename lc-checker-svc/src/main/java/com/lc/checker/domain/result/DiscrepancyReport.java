package com.lc.checker.domain.result;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * API response body for {@code POST /api/v1/lc-check}. Structure matches the sample
 * output in {@code refer-doc/AI-Engineer-test-case-v2.txt} and is the deliverable the
 * V1 demo is evaluated against.
 *
 * <p>{@link #compliant} is {@code false} iff any DISCREPANT check was produced.
 * {@code UNABLE_TO_VERIFY} checks do NOT flip compliance — they appear under
 * {@code unableToVerify} for the reviewer.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DiscrepancyReport(
        String sessionId,
        boolean compliant,
        /** Discrepancy summaries (no rule_id) — preserved for backward compat with
         *  the sync /lc-check consumers and the original test-case JSON contract. */
        List<Discrepancy> discrepancies,
        /** Typed CheckResults for DISCREPANT rules. UI source of truth: the same
         *  shape as {@link #passed} / {@link #unableToVerify} so the frontend can
         *  treat all four buckets uniformly. */
        List<CheckResult> discrepant,
        List<CheckResult> unableToVerify,
        List<CheckResult> passed,
        List<CheckResult> notApplicable,
        Summary summary
) {

    public DiscrepancyReport {
        discrepancies = discrepancies == null ? List.of() : List.copyOf(discrepancies);
        discrepant = discrepant == null ? List.of() : List.copyOf(discrepant);
        unableToVerify = unableToVerify == null ? List.of() : List.copyOf(unableToVerify);
        passed = passed == null ? List.of() : List.copyOf(passed);
        notApplicable = notApplicable == null ? List.of() : List.copyOf(notApplicable);
    }
}
