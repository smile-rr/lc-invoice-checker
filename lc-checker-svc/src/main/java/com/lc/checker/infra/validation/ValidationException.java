package com.lc.checker.infra.validation;

import com.lc.checker.api.error.GlobalExceptionHandler;

/**
 * Thrown by {@link InputValidator} when the incoming request fails a pre-pipeline gate
 * (non-PDF, oversize, MT700 shape). Caught by {@code GlobalExceptionHandler} and
 * mapped to a 400 response.
 */
public class ValidationException extends RuntimeException {

    private final String code;
    private final String stage;

    public ValidationException(String code, String stage, String message) {
        super(message);
        this.code = code;
        this.stage = stage;
    }

    public String getCode() {
        return code;
    }

    public String getStage() {
        return stage;
    }
}
