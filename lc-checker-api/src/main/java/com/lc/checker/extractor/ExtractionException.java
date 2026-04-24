package com.lc.checker.extractor;

/**
 * Raised when the extractor service cannot return a usable {@code InvoiceDocument} — bad
 * request, timeout, 5xx, or a 2xx response that fails the contract shape check. Caught
 * by {@code GlobalExceptionHandler} and mapped to a 502 {@code EXTRACTOR_UNAVAILABLE}
 * (server-side causes) or a 4xx passthrough (client-input causes), based on
 * {@link ExtractorErrorCode#classification()}.
 */
public class ExtractionException extends RuntimeException {

    private final String extractor;
    private final ExtractorErrorCode code;

    public ExtractionException(String extractor, ExtractorErrorCode code, String message) {
        super(message);
        this.extractor = extractor;
        this.code = code == null ? ExtractorErrorCode.UNKNOWN : code;
    }

    public ExtractionException(String extractor, ExtractorErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.extractor = extractor;
        this.code = code == null ? ExtractorErrorCode.UNKNOWN : code;
    }

    public String getExtractor() {
        return extractor;
    }

    public ExtractorErrorCode getCode() {
        return code;
    }

    /** Legacy helper — returns the enum name so existing callers that hold strings keep working. */
    public String getCodeString() {
        return code.name();
    }

    /** Convenience: is this worth sending to a fallback extractor (V2 router)? */
    public boolean isFallbackCandidate() {
        return code.isFallbackCandidate();
    }
}
