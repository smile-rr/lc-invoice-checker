package com.lc.checker.domain.result;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
/**
 * Deterministic-strategy forensic record: the SpEL expression evaluated, the bound
 * variables at evaluation time, and the final result. Captured by
 * {@code ProgrammaticStrategy} so a reviewer can see exactly <em>why</em>
 * a deterministic rule passed or failed.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExpressionTrace(
        String expression,
        Map<String, Object> boundVariables,
        Object evaluationResult,
        String error
) {

    public ExpressionTrace {
        // Preserve null values (absent fields are diagnostic); Map.copyOf forbids them.
        boundVariables = boundVariables == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(boundVariables));
    }
}
