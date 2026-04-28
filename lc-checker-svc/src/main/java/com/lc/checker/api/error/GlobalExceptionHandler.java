package com.lc.checker.api.error;

import com.lc.checker.api.error.ApiError;
import com.lc.checker.api.error.StorageUnavailableException;
import com.lc.checker.api.exception.SessionNotFoundException;
import com.lc.checker.stage.extract.ExtractionException;
import com.lc.checker.stage.extract.ExtractorErrorCode;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.stage.parse.LcParseException;
import com.lc.checker.infra.validation.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.lc.checker.domain.result.Summary;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Translates pipeline exceptions into the flat {@link ApiError} body + matching HTTP
 * status. The mapping intentionally mirrors the table in {@code CLAUDE.md § Error
 * Handling Summary} so API consumers can write against documented, stable codes.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> validation(ValidationException e) {
        log.warn("Validation failed: {}", e.getMessage());
        return ResponseEntity.status(codeForValidation(e))
                .body(new ApiError(e.getCode(), e.getStage(), e.getMessage(), null, mdcSession()));
    }

    @ExceptionHandler(LcParseException.class)
    public ResponseEntity<ApiError> lcParse(LcParseException e) {
        log.warn("LC parse failed: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(new ApiError("LC_PARSE_ERROR", "lc_parsing", e.getMessage(),
                        e.getField(), mdcSession()));
    }

    @ExceptionHandler(ExtractionException.class)
    public ResponseEntity<ApiError> extractor(ExtractionException e) {
        log.warn("Extractor failure [{}]: {}", e.getExtractor(), e.getMessage());
        // 4xx client-origin codes should be surfaced as 400 so users can retry with a fixed PDF.
        HttpStatus status = isClientCause(e.getCode().name()) ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(
                new ApiError(
                        status == HttpStatus.BAD_GATEWAY ? "EXTRACTOR_UNAVAILABLE" : e.getCode().name(),
                        "invoice_extraction", e.getMessage(), null, mdcSession()));
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ApiError> notFound(SessionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("SESSION_NOT_FOUND", null, e.getMessage(), null, e.getSessionId()));
    }

    /**
     * Raised by /invoice and /lc-raw when a session row exists in the DB (SHA
     * is recorded) but the bytes are not retrievable from MinIO — either because
     * MinIO was restarted and lost data, or it is temporarily unreachable. The
     * session IS known; only the file bytes are missing.
     */
    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<ApiError> storageUnavailable(StorageUnavailableException e) {
        log.warn("Storage unavailable [{}] for session {}: {}", e.fileType(), e.sessionId(), e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("X-Error-Code", "STORAGE_UNAVAILABLE")
                .body(new ApiError("STORAGE_UNAVAILABLE", "file_storage",
                        e.fileType() + " file unavailable — " + e.getMessage(), null, e.sessionId()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> missingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", "request",
                        "Missing multipart part: " + e.getRequestPartName(),
                        e.getRequestPartName(), mdcSession()));
    }

    /**
     * Spring throws this when no @RequestMapping matches and the request falls
     * through to the static-resource handler with no static file either. Map
     * to a clean 404 instead of letting it land in the generic 500 handler —
     * "this URL does not exist" is a 4xx, not a server fault.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", null,
                        "No handler for " + e.getResourcePath(), null, mdcSession()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("PAYLOAD_TOO_LARGE", "request", e.getMessage(), null, mdcSession()));
    }

    /**
     * Client's Accept header didn't include any media type we can produce —
     * common when an SSE-only client (Accept: text/event-stream) hits a URL
     * that 404'd or otherwise needs to return a JSON ApiError. Spring
     * normally surfaces this as a noisy "Failure in @ExceptionHandler" WARN
     * via {@code ExceptionHandlerExceptionResolver}; handling it explicitly
     * silences that and returns a clean 406 with no body, which is what the
     * RFC says to do here.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> notAcceptable(HttpMediaTypeNotAcceptableException e) {
        log.debug("406 Not Acceptable — supported types: {}", e.getSupportedMediaTypes());
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> fallback(Exception e,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {
        // SSE responses are locked to text/event-stream — writing a JSON body here
        // causes a secondary HttpMessageNotWritableException. For committed or
        // streaming responses just log and let the emitter close naturally.
        if (response.isCommitted()
                || MediaType.TEXT_EVENT_STREAM_VALUE.equals(response.getContentType())) {
            log.debug("Exception on SSE/committed response (likely client disconnect): {}",
                    e.getMessage());
            return null;
        }
        // ALWAYS log the original exception at ERROR — this is the root cause
        // we care about, regardless of what we can write back to the client.
        log.error("Unhandled pipeline error on {} {} (Accept='{}')",
                request.getMethod(), request.getRequestURI(),
                request.getHeader(HttpHeaders.ACCEPT), e);
        // If the client only accepts text/event-stream (or anything else that
        // can't carry JSON), trying to return ResponseEntity<ApiError> would
        // trip Spring's ExceptionHandlerExceptionResolver into logging a
        // secondary WARN about HttpMediaTypeNotAcceptableException. Detect
        // that up-front and write 406 with no body instead.
        if (!clientAcceptsJson(request)) {
            try { response.setStatus(HttpStatus.NOT_ACCEPTABLE.value()); } catch (Exception ignored) { /* committed */ }
            return null;
        }
        return ResponseEntity.internalServerError()
                .body(new ApiError("INTERNAL_ERROR", null, e.getMessage(), null, mdcSession()));
    }

    private static boolean clientAcceptsJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        // Missing / empty / "*/*" all mean "no preference" — JSON is fine.
        if (accept == null || accept.isBlank() || accept.contains("*/*")) return true;
        return accept.contains(MediaType.APPLICATION_JSON_VALUE)
                || accept.contains("application/*");
    }

    private static HttpStatus codeForValidation(ValidationException e) {
        return "PAYLOAD_TOO_LARGE".equals(e.getCode()) ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.BAD_REQUEST;
    }

    private static boolean isClientCause(String code) {
        return switch (code == null ? "" : code) {
            case "INVALID_FILE_TYPE", "PAYLOAD_TOO_LARGE", "EMPTY_INPUT" -> true;
            default -> false;
        };
    }

    private static String mdcSession() {
        return MDC.get(MdcKeys.SESSION_ID);
    }
}
