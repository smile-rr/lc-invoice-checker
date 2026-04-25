package com.lc.checker.stage.extract.mineru;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.stage.extract.ExtractionException;
import com.lc.checker.stage.extract.ExtractionResult;
import com.lc.checker.stage.extract.ExtractorErrorCode;
import com.lc.checker.stage.extract.InvoiceExtractor;
import com.lc.checker.stage.extract.InvoiceFieldMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the MiniRU Python sidecar ({@code mineru-svc :8082}).
 *
 * <p>Wire format is identical to the Docling sidecar: multipart {@code file}
 * upload to {@code POST /extract}, JSON response envelope
 * {@code { extractor, confidence, raw_markdown, fields: {...} }}.
 * All field resolution goes through {@link InvoiceFieldMapper} and
 * {@code FieldPoolRegistry} — no field keys are hardcoded here.
 */
public class MineruExtractorClient implements InvoiceExtractor {

    private static final Logger log = LoggerFactory.getLogger(MineruExtractorClient.class);

    private final RestClient restClient;
    private final InvoiceFieldMapper mapper;
    private final ObjectMapper json;
    private final MineruExtractorConfig config;
    private final com.lc.checker.stage.extract.PromptBuilder promptBuilder;

    public MineruExtractorClient(RestClient.Builder restClientBuilder,
            InvoiceFieldMapper mapper, ObjectMapper json, MineruExtractorConfig config,
            com.lc.checker.stage.extract.PromptBuilder promptBuilder) {
        this.mapper = mapper;
        this.json = json;
        this.config = config;
        this.promptBuilder = promptBuilder;
        // Use SimpleClientHttpRequestFactory (HttpURLConnection) to match the
        // previously-working ExtractorClientConfig (commit 30c16ee). The default
        // JdkClientHttpRequestFactory mishandles Spring's streaming multipart body
        // — uvicorn h11 rejects with "Invalid HTTP request received."
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(Math.max(1, config.timeoutSeconds()) * 1000);
        this.restClient = restClientBuilder
                .baseUrl(config.baseUrl())
                .requestFactory(factory)
                .build();
        log.info("MineruExtractorClient[{}] configured: base={} timeout={}s",
                config.name(), config.baseUrl(), config.timeoutSeconds());
    }

    @Override
    public ExtractionResult extract(byte[] pdfBytes, String filename) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new ExtractionException(config.name(), ExtractorErrorCode.INVALID_REQUEST,
                    "PDF byte array is empty");
        }

        long start = System.currentTimeMillis();

        String effectiveFilename = filename != null ? filename : "invoice.pdf";
        ByteArrayResource fileResource = new ByteArrayResource(pdfBytes) {
            @Override public String getFilename() { return effectiveFilename; }
        };
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", fileResource);
        // Same canonical prompt the vision lanes use, rendered for text mode.
        parts.add("prompt", promptBuilder.forText());

        String rawResponse;
        try {
            rawResponse = restClient.post()
                    .uri("/extract")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .body(parts)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            throw new ExtractionException(config.name(), ExtractorErrorCode.INVALID_REQUEST,
                    "MiniRU client error " + e.getStatusCode() + ": " + e.getMessage(), e);
        } catch (HttpServerErrorException e) {
            throw new ExtractionException(config.name(), ExtractorErrorCode.EXTRACTION_FAILED,
                    "MiniRU server error " + e.getStatusCode() + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ExtractionException(config.name(), ExtractorErrorCode.UNREACHABLE,
                    "MiniRU call failed: " + e.getMessage(), e);
        }

        if (rawResponse == null || rawResponse.isBlank()) {
            throw new ExtractionException(config.name(), ExtractorErrorCode.EMPTY_RESPONSE,
                    "MiniRU returned empty response");
        }

        Map<String, Object> response;
        try {
            response = json.readValue(rawResponse, new TypeReference<>() {});
        } catch (Exception e) {
            throw new ExtractionException(config.name(), ExtractorErrorCode.EXTRACTION_FAILED,
                    "Failed to parse MiniRU JSON response: " + e.getMessage(), e);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = response.get("fields") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String rawMarkdown = response.get("raw_markdown") instanceof String s ? s : "";
        double confidence = asDouble(response.get("confidence"), 0.8);

        long durMs = System.currentTimeMillis() - start;
        InvoiceDocument doc = mapper.toDocument(fields, config.name(), confidence,
                false, 1, durMs, rawMarkdown, null);
        log.info("MineruExtractorClient[{}] SUCCESS confidence={} fields={} duration={}ms",
                config.name(), confidence, fields.size(), durMs);
        return ExtractionResult.of(doc);
    }

    @Override
    public String extractorName() {
        return config.name();
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (Exception e) { return def; }
    }
}
