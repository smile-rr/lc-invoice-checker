package com.lc.checker.domain.result;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * One row in the final {@code DiscrepancyReport.discrepancies[]}.
 *
 * <p>Field names here match the sample output in the test case exactly (snake_case),
 * so JSON round-trips to the spec without per-field {@code @JsonProperty} overrides.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Discrepancy(
        String field,
        String lcValue,
        String presentedValue,
        String ruleReference,
        String description
) {
}
