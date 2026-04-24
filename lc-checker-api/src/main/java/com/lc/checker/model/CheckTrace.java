package com.lc.checker.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.model.enums.CheckStatus;
import com.lc.checker.model.enums.CheckType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-rule execution trace — the forensic twin of {@link CheckResult}. One of
 * {@link #expressionTrace} (Type A / AB) or {@link #llmTrace} (Type B / AB) will be
 * populated; Type AB populates both.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CheckTrace(
        String ruleId,
        CheckType checkType,
        CheckStatus status,
        Map<String, Object> inputSnapshot,
        ExpressionTrace expressionTrace,
        LlmTrace llmTrace,
        long durationMs,
        String error
) {

    public CheckTrace {
        inputSnapshot = inputSnapshot == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(inputSnapshot));
    }
}
