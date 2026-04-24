package com.lc.checker.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Aggregate counters appended to the final report. Source of truth for quick dashboards
 * that shouldn't have to re-scan the full result set.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Summary(
        int totalChecks,
        int passed,
        int discrepant,
        int unableToVerify,
        int notApplicable
) {
}
