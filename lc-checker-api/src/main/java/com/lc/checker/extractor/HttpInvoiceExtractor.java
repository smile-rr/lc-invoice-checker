package com.lc.checker.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.model.InvoiceDocument;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP client for the extractor sidecars (docling-svc, mineru-svc).
 *
 * <p>Both services implement the identical contract described in
 * {@code extractors/CONTRACT.md v1.0}. This single class handles both — the
 * service name is passed in at construction. No per-extractor subclasses are needed.
 *
 * <p>Non-2xx responses are parsed as {@link ExtractorErrorBody} and the
 * {@code error} string is resolved against {@link ExtractorErrorCode}. The typed
 * code drives retry policy and fallback decisions in {@link ExtractorRouter}.
 */
@Component
public class HttpInvoiceExtractor implements InvoiceExtractor {

    private static final Logger log = LoggerFactory.getLogger(HttpInvoiceExtractor.class);

    private final RestClient restClient;
    private final String extractorName;
    private final ExtractorResponseMapper mapper;
    private final ObjectMapper json;
    private final int retries;
    private final Duration retryBackoff;

    public HttpInvoiceExtractor(
            RestClient restClient,
            @Value("${extractor.name:docling}") String extractorName,
            ExtractorResponseMapper mapper,
            ObjectMapper json,
            @Value("${extractor.retries:1}") int retries,
            @Value("${extractor.retry-backoff-ms:500}") long retryBackoffMs) {
        this.restClient = restClient;
        this.extractorName = extractorName;
        this.mapper = mapper;
        this.json = json;
        this.retries = retries;
        this.retryBackoff = Duration.ofMillis(retryBackoffMs);
        log.info("HttpInvoiceExtractor configured: name={} retries={} backoff={}ms",
                extractorName, retries, retryBackoffMs);
    }

    @Override
    public InvoiceDocument extract(byte[] pdfBytes, String filename) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new ExtractionException(extractorName, ExtractorErrorCode.INVALID_REQUEST,
                    "PDF byte array is empty");
        }
        String name = (filename == null || filename.isBlank()) ? "invoice.pdf" : filename;

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new NamedPdfResource(pdfBytes, name));

        RestClientResponseException lastError = null;
        ExtractorErrorCode lastCode = null;
        String lastBodyMessage = null;

        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                log.debug("{} /extract attempt {} ({} bytes)", extractorName, attempt + 1, pdfBytes.length);
                ExtractorResponseDto dto = restClient.post()
                        .uri("/extract")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .body(parts)
                        .retrieve()
                        .body(ExtractorResponseDto.class);
                if (dto == null) {
                    throw new ExtractionException(extractorName, ExtractorErrorCode.EMPTY_RESPONSE,
                            extractorName + " returned no body");
                }
                return mapper.toDocument(dto);
            } catch (RestClientResponseException e) {
                TypedError typed = classifyHttpError(e);
                lastError = e;
                lastCode = typed.code();
                lastBodyMessage = typed.message();
                logRetryDecision(typed, attempt);

                if (typed.code().classification() != ExtractorErrorCode.Classification.FALLBACK_CANDIDATE) {
                    throw new ExtractionException(extractorName, typed.code(), typed.message(), e);
                }
            } catch (ResourceAccessException e) {
                log.warn("{} unreachable on attempt {}: {}", extractorName, attempt + 1, e.getMessage());
                if (attempt == retries) {
                    throw new ExtractionException(extractorName, ExtractorErrorCode.UNREACHABLE,
                            extractorName + " service unreachable: " + e.getMessage(), e);
                }
                lastCode = ExtractorErrorCode.UNREACHABLE;
                lastBodyMessage = e.getMessage();
            } catch (ExtractionException e) {
                throw e;
            } catch (Exception e) {
                throw new ExtractionException(extractorName, ExtractorErrorCode.UNKNOWN,
                        "Unexpected error calling " + extractorName + ": " + e.getMessage(), e);
            }

            if (attempt < retries) {
                sleepBackoff(attempt);
            }
        }

        throw new ExtractionException(
                extractorName,
                lastCode == null ? ExtractorErrorCode.UNKNOWN : lastCode,
                extractorName + " failed after " + (retries + 1) + " attempts: "
                        + (lastBodyMessage != null ? lastBodyMessage
                            : (lastError == null ? "unknown" : lastError.getMessage())),
                lastError);
    }

    @Override
    public String extractorName() {
        return extractorName;
    }

    // -------------------------------------------------------------------------
    // internals
    // -------------------------------------------------------------------------

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(retryBackoff.toMillis() * (1L << attempt));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ExtractionException(extractorName, ExtractorErrorCode.UNKNOWN, "Retry interrupted", ie);
        }
    }

    TypedError classifyHttpError(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body != null && !body.isBlank()) {
            try {
                ExtractorErrorBody parsed = json.readValue(body, ExtractorErrorBody.class);
                if (parsed != null && parsed.error() != null) {
                    return new TypedError(
                            ExtractorErrorCode.fromString(parsed.error()),
                            parsed.message() == null ? body : parsed.message());
                }
            } catch (Exception ignore) {
                // fall through to status-based mapping
            }
        }
        return new TypedError(
                ExtractorErrorCode.fromHttpStatus(e.getStatusCode().value()),
                extractorName + " " + e.getStatusCode() + (body == null ? "" : ": " + body));
    }

    private void logRetryDecision(TypedError typed, int attempt) {
        ExtractorErrorCode.Classification c = typed.code().classification();
        switch (c) {
            case CLIENT_BUG -> log.error(
                    "{} returned {} on attempt {} — lc-checker-api produced a bad request: {}",
                    extractorName, typed.code(), attempt + 1, typed.message());
            case CLIENT_INPUT -> log.warn(
                    "{} returned {} on attempt {} — user-input error, no retry: {}",
                    extractorName, typed.code(), attempt + 1, typed.message());
            case FALLBACK_CANDIDATE -> log.warn(
                    "{} returned {} on attempt {} — server-side, retry eligible: {}",
                    extractorName, typed.code(), attempt + 1, typed.message());
        }
    }

    record TypedError(ExtractorErrorCode code, String message) {}

    private static final class NamedPdfResource extends ByteArrayResource {
        private final String filename;
        private NamedPdfResource(byte[] data, String filename) {
            super(data);
            this.filename = filename;
        }
        @Override
        public String getFilename() {
            return filename;
        }
    }
}
