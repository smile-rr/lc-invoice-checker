package com.lc.checker.infra.stream;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

/**
 * One SSE envelope emitted to the UI during a streamed lc-check run.
 *
 * <p>{@link Type} values double as the SSE {@code event:} field so the client can
 * dispatch by type without parsing the payload. The {@code payload} is a generic
 * {@link Object} because each type carries a different shape — see the event
 * schema in the plan doc for what to expect per type.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CheckEvent(Type type, String stage, Object payload, Instant timestamp) {

    public static CheckEvent of(Type type, Object payload) {
        return new CheckEvent(type, null, payload, Instant.now());
    }

    public static CheckEvent ofStage(Type type, String stage, Object payload) {
        return new CheckEvent(type, stage, payload, Instant.now());
    }

    public enum Type {
        SESSION_STARTED,
        STAGE_STARTED,
        STAGE_COMPLETED,
        CHECK_STARTED,
        CHECK_COMPLETED,
        /** One invoice extractor source has begun running. Payload: {source}. */
        EXTRACT_SOURCE_STARTED,
        /**
         * One invoice extractor source has finished. Payload:
         * {source, success, confidence, duration_ms, error}.
         */
        EXTRACT_SOURCE_COMPLETED,
        REPORT_COMPLETE,
        ERROR;

        /** Wire name used as the SSE {@code event:} field — dotted lowercase. */
        public String wireName() {
            return name().toLowerCase().replace('_', '.');
        }
    }
}
