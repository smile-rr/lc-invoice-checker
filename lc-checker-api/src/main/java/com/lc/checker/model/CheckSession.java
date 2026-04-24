package com.lc.checker.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/**
 * Full forensic record of a single {@code /lc-check} call. Stored by the
 * {@code CheckSessionStore} (in-memory + Caffeine TTL) and returned verbatim by
 * {@code GET /api/v1/lc-check/{sessionId}/trace}.
 *
 * <p>Every pipeline stage writes into this structure. The final report is also
 * embedded so consumers of {@code /trace} don't have to correlate two endpoints.
 *
 * <p>{@link #traceId} and {@link #spanId} are pre-populated from Spring's
 * {@code ObservationRegistry} so the V2 Tempo/Jaeger wiring can cross-link this
 * record to a distributed trace with zero rework.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CheckSession(
        String sessionId,
        String traceId,
        String spanId,
        Instant startedAt,
        Instant completedAt,
        StageTrace lcParsing,
        StageTrace invoiceExtraction,
        RuleActivationTrace activation,
        List<CheckTrace> checks,
        DiscrepancyReport finalReport,
        String error
) {

    public CheckSession {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }
}
