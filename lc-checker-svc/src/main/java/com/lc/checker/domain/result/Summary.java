package com.lc.checker.domain.result;

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
        int notApplicable,
        /**
         * Items that the Layer-3 Holistic Sweep agent flagged for human review.
         * These never count as DISCREPANT — a bank cannot reject a presentation
         * on an unaudited LLM finding (Art 16 protocol). Surfaced separately so
         * the officer sees what the AI flagged without conflating it with hard
         * deterministic failures.
         */
        int requiresHumanReview
) {
}
