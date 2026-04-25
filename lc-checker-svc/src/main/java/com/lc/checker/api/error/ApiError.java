package com.lc.checker.api.error;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Structured error body returned by {@code GlobalExceptionHandler}. Kept flat and
 * documentation-friendly — same shape for every failure category so clients can
 * handle them uniformly.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ApiError(
        String error,
        String stage,
        String message,
        String field,
        String sessionId
) {
}
