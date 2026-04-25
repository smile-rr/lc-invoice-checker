package com.lc.checker.api.error;

import com.lc.checker.api.exception.SessionNotFoundException;
import com.lc.checker.stage.extract.ExtractionException;
import com.lc.checker.stage.extract.ExtractorErrorCode;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.stage.parse.LcParseException;
import com.lc.checker.infra.validation.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import com.lc.checker.domain.result.Summary;

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

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> missingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", "request",
                        "Missing multipart part: " + e.getRequestPartName(),
                        e.getRequestPartName(), mdcSession()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("PAYLOAD_TOO_LARGE", "request", e.getMessage(), null, mdcSession()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> fallback(Exception e) {
        log.error("Unhandled pipeline error", e);
        return ResponseEntity.internalServerError()
                .body(new ApiError("INTERNAL_ERROR", null, e.getMessage(), null, mdcSession()));
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
