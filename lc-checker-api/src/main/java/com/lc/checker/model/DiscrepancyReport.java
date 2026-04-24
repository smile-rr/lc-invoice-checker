package com.lc.checker.model;

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
        List<Discrepancy> discrepancies,
        List<CheckResult> unableToVerify,
        List<CheckResult> passed,
        List<CheckResult> notApplicable,
        Summary summary
) {

    public DiscrepancyReport {
        discrepancies = discrepancies == null ? List.of() : List.copyOf(discrepancies);
        unableToVerify = unableToVerify == null ? List.of() : List.copyOf(unableToVerify);
        passed = passed == null ? List.of() : List.copyOf(passed);
        notApplicable = notApplicable == null ? List.of() : List.copyOf(notApplicable);
    }
}
