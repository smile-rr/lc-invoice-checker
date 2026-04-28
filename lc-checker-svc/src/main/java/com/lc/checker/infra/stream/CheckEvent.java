package com.lc.checker.infra.stream;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Locale;

/**
 * Unified SSE / trace envelope. Four event types share one shape so the
 * frontend reducer (and the {@code /trace} replay API) consume a single union:
 *
 * <ul>
 *   <li>{@link Type#STATUS} — a stage transition. {@code stage} + {@code state}
 *       (started / completed) + human-readable {@code message}; optional
 *       structured {@code data} on completion (LcDocument, InvoiceDocument,
 *       checks summary).</li>
 *   <li>{@link Type#RULE} — a single completed rule outcome. {@code data} is
 *       a {@code CheckResult}.</li>
 *   <li>{@link Type#ERROR} — pipeline halted. {@code stage} + {@code message};
 *       no further events follow.</li>
 *   <li>{@link Type#COMPLETE} — final report assembled. {@code data} is a
 *       {@code DiscrepancyReport}; UI navigates to the review tab on receipt.</li>
 * </ul>
 *
 * <p>Null fields are omitted from the wire JSON so payloads stay small.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Unified SSE envelope shared by the /stream endpoint and /trace replay API.")
public record CheckEvent(
        @Schema(description = "Monotonically increasing sequence number (0-based)", example = "0") long seq,
        @Schema(description = "Wall-clock timestamp of event emission (ISO-8601 UTC)", example = "2026-04-28T10:15:30.123Z") Instant ts,
        @Schema(description = "Event type: status | rule | error | complete", example = "status") Type type,
        @Schema(description = "Pipeline stage name, present for status and error events", example = "lc_parse") String stage,
        @Schema(description = "State within the stage: 'started' or 'completed', present for status events", example = "completed") String state,
        @Schema(description = "Human-readable message for status and error events", example = "LC parsed successfully") String message,
        @Schema(
                description = """
                        Payload shape is determined by event type:
                        - status+completed: LcDocument, InvoiceDocument, or a checks-summary Map
                        - rule:             CheckResult
                        - error:            null
                        - complete:         DiscrepancyReport""",
                example = "{\"passed\": 11, \"failed\": 2, \"warnings\": 1, \"skipped\": 0}")
        Object data
) {

    @Schema(description = "One of: status (stage transition), rule (rule result), error (pipeline halted), complete (final report)")
    public enum Type {
        STATUS, RULE, ERROR, COMPLETE;

        /** Wire name — lowercase enum name. Used both as the SSE {@code event:}
         *  field and as the JSON {@code type} field (via {@link JsonValue}). */
        @JsonValue
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Tolerant deserialiser — accepts lowercase wire form or uppercase enum name. */
        @JsonCreator
        public static Type fromWire(String s) {
            if (s == null) return null;
            return Type.valueOf(s.toUpperCase(Locale.ROOT));
        }
    }

    // --- factories -------------------------------------------------------

    public static CheckEvent status(long seq, String stage, String state, String message, Object data) {
        return new CheckEvent(seq, Instant.now(), Type.STATUS, stage, state, message, data);
    }

    public static CheckEvent rule(long seq, Object checkResult) {
        return new CheckEvent(seq, Instant.now(), Type.RULE, null, null, null, checkResult);
    }

    public static CheckEvent error(long seq, String stage, String message) {
        return new CheckEvent(seq, Instant.now(), Type.ERROR, stage, null, message, null);
    }

    public static CheckEvent complete(long seq, Object discrepancyReport) {
        return new CheckEvent(seq, Instant.now(), Type.COMPLETE, null, null, null, discrepancyReport);
    }
}
