package com.lc.checker.stage.extract.docling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.stage.extract.ExtractionException;
import com.lc.checker.stage.extract.ExtractionResult;
import com.lc.checker.stage.extract.ExtractorErrorCode;
import com.lc.checker.infra.observability.LangfuseTags;
import com.lc.checker.stage.extract.InvoiceExtractor;
import com.lc.checker.stage.extract.InvoiceFieldMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
 * HTTP client for the Docling Python sidecar ({@code docling-svc :8081}).
 *
 * <p>Sends the PDF as multipart {@code file} to {@code POST /extract} and
 * maps the response fields through {@link InvoiceFieldMapper} — all field
 * name resolution goes through {@code FieldPoolRegistry} aliases, so no
 * field keys are hardcoded here.
 *
 * <p>This extractor is text-layout based (not image-based) and produces no
 * {@code LlmTrace} — the LLM runs inside the Python sidecar. The
 * {@code raw_markdown} from Docling's structured layout rendering is
 * preserved in {@link InvoiceDocument#rawMarkdown()} for the UI's Markdown tab.
 */
public class DoclingExtractorClient implements InvoiceExtractor {

    private static final Logger log = LoggerFactory.getLogger(DoclingExtractorClient.class);

    private final RestClient restClient;
    private final InvoiceFieldMapper mapper;
    private final ObjectMapper json;
    private final DoclingExtractorConfig config;
    private final com.lc.checker.stage.extract.PromptBuilder promptBuilder;
    private final Tracer tracer;

    public DoclingExtractorClient(RestClient.Builder restClientBuilder,
            InvoiceFieldMapper mapper, ObjectMapper json, DoclingExtractorConfig config,
            com.lc.checker.stage.extract.PromptBuilder promptBuilder, Tracer tracer) {
        this.mapper = mapper;
        this.json = json;
        this.config = config;
        this.promptBuilder = promptBuilder;
        this.tracer = tracer;
        // Use SimpleClientHttpRequestFactory (HttpURLConnection) to match the
        // previously-working ExtractorClientConfig (commit 30c16ee). The default
        // JdkClientHttpRequestFactory mishandles Spring's streaming multipart body
        // — uvicorn h11 sees malformed bytes and the file part never lands.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(Math.max(1, config.timeoutSeconds()) * 1000);
        this.restClient = restClientBuilder
                .baseUrl(config.baseUrl())
                .requestFactory(factory)
                .build();
        log.info("DoclingExtractorClient[{}] configured: base={} timeout={}s",
                config.name(), config.baseUrl(), config.timeoutSeconds());
    }

    @Override
    public ExtractionResult extract(byte[] pdfBytes, String filename) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new ExtractionException(config.name(), ExtractorErrorCode.INVALID_REQUEST,
                    "PDF byte array is empty");
        }

        long start = System.currentTimeMillis();

        // Named ByteArrayResource so the multipart part carries a proper filename header.
        String effectiveFilename = filename != null ? filename : "invoice.pdf";
        ByteArrayResource fileResource = new ByteArrayResource(pdfBytes) {
            @Override public String getFilename() { return effectiveFilename; }
        };
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", fileResource);
        // Ship the rendered text-mode prompt so the sidecar's LLM tier uses
        // the SAME instructions as the vision lanes (single source of truth
        // — `prompts/invoice-extract.st` rendered via PromptBuilder.forText()).
        // Sidecars that don't recognise this part fall through to their
        // hardcoded prompt, preserving backward compatibility.
        parts.add("prompt", promptBuilder.forText());

        // No Java-side wrapper span: the docling Python sidecar emits its
        // own Langfuse Generation for the LLM call inside /extract, and that
        // Generation joins this session's trace via the X-Session-Id header
        // below. The auto-instrumented HTTP client span is enough for any
        // low-level debugging on the Java side.
        String sessionId = org.slf4j.MDC.get(com.lc.checker.infra.observability.MdcKeys.SESSION_ID);
        String rawResponse;
        try {
            rawResponse = restClient.post()
                    .uri("/extract")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    // Carry sessionId so the Python sidecar's Langfuse generation
                    // can set langfuse.trace.id to the SAME value, joining this
                    // session's trace.
                    .header("X-Session-Id", sessionId == null ? "" : sessionId)
                    .body(parts)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            throw new ExtractionException(config.name(), ExtractorErrorCode.INVALID_REQUEST,
                    "Docling client error " + e.getStatusCode() + ": " + e.getMessage(), e);
        } catch (HttpServerErrorException e) {
            throw new ExtractionException(config.name(), ExtractorErrorCode.EXTRACTION_FAILED,
                    "Docling server error " + e.getStatusCode() + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ExtractionException(config.name(), ExtractorErrorCode.UNREACHABLE,
                    "Docling call failed: " + e.getMessage(), e);
        }

        if (rawResponse == null || rawResponse.isBlank()) {
            throw new ExtractionException(config.name(), ExtractorErrorCode.EMPTY_RESPONSE,
                    "Docling returned empty response");
        }

        // Expected envelope: { extractor, confidence, raw_markdown, fields: {...} }
        Map<String, Object> response;
        try {
            response = json.readValue(rawResponse, new TypeReference<>() {});
        } catch (Exception e) {
            throw new ExtractionException(config.name(), ExtractorErrorCode.EXTRACTION_FAILED,
                    "Failed to parse Docling JSON response: " + e.getMessage(), e);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = response.get("fields") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String rawMarkdown = response.get("raw_markdown") instanceof String s ? s : "";
        double confidence = asDouble(response.get("confidence"), 0.8);

        long durMs = System.currentTimeMillis() - start;
        InvoiceDocument doc = mapper.toDocument(fields, config.name(), confidence,
                false, 1, durMs, rawMarkdown, null);
        log.info("DoclingExtractorClient[{}] SUCCESS confidence={} fields={} duration={}ms",
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
