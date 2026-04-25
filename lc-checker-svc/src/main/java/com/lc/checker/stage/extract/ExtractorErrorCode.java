package com.lc.checker.stage.extract;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Arrays;

/**
 * Java mirror of the frozen 8-value error-code vocabulary defined in
 * {@code extractors/docling/app/errors.py}. Use this enum instead of raw strings
 * so callers can switch without typo risk.
 */
public enum ExtractorErrorCode {

    // -- 4xx: user-input errors ------------------------------------------------
    INVALID_FILE_TYPE(400),
    INVALID_REQUEST(400),
    PAYLOAD_TOO_LARGE(413),
    FETCH_FAILED(400),
    PATH_NOT_FOUND(400),
    S3_NOT_SUPPORTED(501),

    // -- 5xx / transport errors ------------------------------------------------
    EXTRACTION_FAILED(500),
    EXTRACTION_TIMEOUT(504),
    UNREACHABLE(0),
    EMPTY_RESPONSE(0),
    UNKNOWN(0);

    private final int defaultStatus;

    ExtractorErrorCode(int defaultStatus) {
        this.defaultStatus = defaultStatus;
    }

    public int defaultStatus() {
        return defaultStatus;
    }

    /**
     * Tolerant parser for the {@code error} string in the extractor's JSON error body.
     * Unknown values map to {@link #UNKNOWN} so an older client against a newer service
     * never throws on a never-seen error code.
     */
    @JsonCreator
    public static ExtractorErrorCode fromString(String s) {
        if (s == null) return UNKNOWN;
        return Arrays.stream(values())
                .filter(v -> v.name().equalsIgnoreCase(s))
                .findFirst()
                .orElse(UNKNOWN);
    }

    /** Map an HTTP status back to the most specific code (used when body is missing). */
    public static ExtractorErrorCode fromHttpStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_REQUEST;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 500 -> EXTRACTION_FAILED;
            case 501 -> S3_NOT_SUPPORTED;
            case 504 -> EXTRACTION_TIMEOUT;
            default -> UNKNOWN;
        };
    }
}
