package com.lc.checker.stage.extract;

import com.lc.checker.domain.result.LlmTrace;
import com.lc.checker.api.error.GlobalExceptionHandler;
import com.lc.checker.domain.invoice.InvoiceDocument;

/**
 * Raised when the extractor service cannot return a usable {@code InvoiceDocument} — bad
 * request, timeout, 5xx, or a 2xx response that fails the contract shape check. Caught
 * by {@code GlobalExceptionHandler} and mapped to a 502 {@code EXTRACTOR_UNAVAILABLE}
 * (server-side causes) or a 4xx passthrough (client-input causes), based on
 * {@link ExtractorErrorCode#classification()}.
 *
 * <p>May carry an {@link LlmTrace} recording the prompt/response/latency of a failed
 * LLM call so the orchestrator can persist forensic detail even for failures.
 */
public class ExtractionException extends RuntimeException {

    private final String extractor;
    private final ExtractorErrorCode code;
    private LlmTrace llmTrace;

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

    public ExtractionException withLlmTrace(LlmTrace trace) {
        this.llmTrace = trace;
        return this;
    }

    public LlmTrace getLlmTrace() {
        return llmTrace;
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
