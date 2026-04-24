package com.lc.checker.parser;

/**
 * Raised when MT700 input is structurally unparseable or is missing a mandatory tag.
 * Surface is caught by {@code GlobalExceptionHandler} and mapped to a 400
 * {@code VALIDATION_FAILED} error response with {@code stage=lc_parsing}.
 */
public class LcParseException extends RuntimeException {

    private final String field;

    public LcParseException(String message) {
        super(message);
        this.field = null;
    }

    public LcParseException(String field, String message) {
        super(message);
        this.field = field;
    }

    public LcParseException(String message, Throwable cause) {
        super(message, cause);
        this.field = null;
    }

    public String getField() {
        return field;
    }
}
