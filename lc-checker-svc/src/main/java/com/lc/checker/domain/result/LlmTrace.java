package com.lc.checker.domain.result;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.stage.check.strategy.TypeABStrategy;
import com.lc.checker.stage.check.strategy.TypeBStrategy;
import com.lc.checker.stage.extract.vision.VisionLlmExtractor;

/**
 * Verbatim forensic record of one LLM call — populated by {@code VisionLlmExtractor},
 * {@code TypeBStrategy}, and {@code TypeABStrategy}. Exposed on {@code /trace} so a
 * reviewer can see the full prompt, raw response, and latency/token accounting
 * without re-running anything.
 *
 * <p>Token counts are nullable because not every OpenAI-compatible endpoint surfaces
 * them the same way; we still capture the response envelope and can compute from it.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LlmTrace(
        String purpose,               // e.g. "vision.extract", "rule.INV-015.check"
        String model,
        String promptRendered,
        String rawResponse,
        Integer inputTokens,
        Integer outputTokens,
        long latencyMs,
        String error                  // null on success
) {
}
