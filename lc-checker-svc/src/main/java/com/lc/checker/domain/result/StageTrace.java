package com.lc.checker.domain.result;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.session.enums.StageStatus;
import java.time.Instant;
import java.util.List;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.session.CheckSession;

/**
 * Per-stage record (parse / extract / activate / execute / assemble) stored on
 * {@link CheckSession}. The {@link #output} is the stage's structured product
 * (e.g. an {@link LcDocument} for the parse stage) so the trace endpoint can surface
 * every intermediate state without re-running anything.
 *
 * <p>{@link #llmCalls} is populated for stages that issue LLM calls (mt700 Part B,
 * invoice extraction fallback). Empty list when the stage is purely deterministic.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StageTrace(
        String stageName,
        StageStatus status,
        Instant startedAt,
        long durationMs,
        Object output,
        List<LlmTrace> llmCalls,
        String error
) {

    public StageTrace {
        llmCalls = llmCalls == null ? List.of() : List.copyOf(llmCalls);
    }
}
