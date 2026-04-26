package com.lc.checker.domain.result;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Aggregate counters appended to the final report. Source of truth for quick
 * dashboards that shouldn't have to re-scan the full result set. The four
 * status counters always sum to {@link #totalChecks}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Summary(
        int totalChecks,
        int passed,
        int failed,
        int doubts,
        int notRequired
) {
}
