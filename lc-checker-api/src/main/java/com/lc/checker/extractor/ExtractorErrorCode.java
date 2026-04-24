package com.lc.checker.extractor;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Arrays;

/**
 * Java mirror of the frozen 8-value error-code vocabulary defined in
 * {@code extractors/docling/app/errors.py}. Use this enum instead of raw strings
 * so the router and callers can switch without typo risk.
 *
 * <p>Each value carries its default HTTP status and its classification for the future
 * {@code ExtractorRouter}:
 * <ul>
 *   <li>{@link Classification#FALLBACK_CANDIDATE} — server-side failure; worth trying
 *       the other extractor. V1 has no router, but the flag is captured so V1.5 can
 *       flip on MiniRU fallback with zero rework.</li>
 *   <li>{@link Classification#CLIENT_BUG} — lc-checker-api produced a bad request.
 *       Should never happen because we control the request shape; logged at ERROR.</li>
 *   <li>{@link Classification#CLIENT_INPUT} — the inbound user PDF / URL itself is bad.
 *       Propagates to the API caller as 4xx; no fallback makes sense.</li>
 * </ul>
 */
public enum ExtractorErrorCode {

    // -- 4xx: user-input errors ------------------------------------------------
    INVALID_FILE_TYPE(400, Classification.CLIENT_BUG),
    INVALID_REQUEST(400, Classification.CLIENT_BUG),
    PAYLOAD_TOO_LARGE(413, Classification.CLIENT_BUG),
    FETCH_FAILED(400, Classification.CLIENT_INPUT),
    PATH_NOT_FOUND(400, Classification.CLIENT_INPUT),
    S3_NOT_SUPPORTED(501, Classification.CLIENT_INPUT),

    // -- 5xx: server errors — fallback worthwhile ------------------------------
    EXTRACTION_FAILED(500, Classification.FALLBACK_CANDIDATE),
    EXTRACTION_TIMEOUT(504, Classification.FALLBACK_CANDIDATE),

    // -- transport/unknown fallbacks the client synthesises locally -----------
    UNREACHABLE(0, Classification.FALLBACK_CANDIDATE),
    EMPTY_RESPONSE(0, Classification.FALLBACK_CANDIDATE),
    UNKNOWN(0, Classification.FALLBACK_CANDIDATE);

    public enum Classification {
        FALLBACK_CANDIDATE,
        CLIENT_BUG,
        CLIENT_INPUT
    }

    private final int defaultStatus;
    private final Classification classification;

    ExtractorErrorCode(int defaultStatus, Classification classification) {
        this.defaultStatus = defaultStatus;
        this.classification = classification;
    }

    public int defaultStatus() {
        return defaultStatus;
    }

    public Classification classification() {
        return classification;
    }

    /** Router hook: is this error worth retrying on the other extractor? */
    public boolean isFallbackCandidate() {
        return classification == Classification.FALLBACK_CANDIDATE;
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
