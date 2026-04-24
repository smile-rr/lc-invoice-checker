package com.lc.checker.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Verbatim forensic record of one LLM call — populated by Mt700LlmParser (Part B) and
 * by {@code TypeBStrategy} / {@code TypeABStrategy}. Exposed on {@code /trace} so a
 * reviewer can see the full prompt, raw response, and latency/token accounting
 * without re-running anything.
 *
 * <p>Token counts are nullable because not every OpenAI-compatible endpoint surfaces
 * them the same way; we still capture the response envelope and can compute from it.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LlmTrace(
        String purpose,               // e.g. "mt700.parse.45A", "rule.INV-015.check"
        String model,
        String promptRendered,
        String rawResponse,
        Integer inputTokens,
        Integer outputTokens,
        long latencyMs,
        String error                  // null on success
) {
}
