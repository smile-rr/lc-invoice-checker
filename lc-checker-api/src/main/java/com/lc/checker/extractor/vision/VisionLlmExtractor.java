package com.lc.checker.extractor.vision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.extractor.ExtractionException;
import com.lc.checker.extractor.ExtractorErrorCode;
import com.lc.checker.extractor.InvoiceExtractor;
import com.lc.checker.model.InvoiceDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Vision-LLM invoice extractor — the V1.5 primary invoice extractor.
 *
 * <p>PDF pages are rendered to PNG via JPedal ({@link PdfRenderer}), then sent to a
 * multi-modal LLM (Ollama / Qwen Cloud / Gemini) via the OpenAI-compatible
 * {@code /v1/chat/completions} endpoint with a text+image_url content array.
 *
 * <p>Provider is switched purely via env vars — no code changes required:
 * <ul>
 *   <li>Ollama (local):     VISION_PROVIDER=ollama</li>
 *   <li>Qwen Cloud:          VISION_PROVIDER=qwen</li>
 *   <li>Gemini (via adapter):VISION_PROVIDER=gemini</li>
 * </ul>
 *
 * <p>Failures are surfaced as {@link ExtractionException} with
 * {@link com.lc.checker.extractor.ExtractorErrorCode#isFallbackCandidate()}=true,
 * allowing {@link com.lc.checker.extractor.ExtractorRouter} to fall back to
 * Docling or Mineru.
 */
@Component
public class VisionLlmExtractor implements InvoiceExtractor {

    private static final Logger log = LoggerFactory.getLogger(VisionLlmExtractor.class);
    private static final String PROMPT_TEMPLATE = "prompts/vision-invoice-extract.st";

    private final RestClient visionRestClient;
    private final PdfRenderer renderer;
    private final VisionInvoiceMapper mapper;
    private final VisionContentBuilder contentBuilder;
    private final ObjectMapper json;
    private final String model;
    private final String provider;
    private final float renderScale;
    private final Double temperature;
    private volatile String cachedPrompt;

    public VisionLlmExtractor(
            @Qualifier(VisionClientConfig.VISION_REST_CLIENT) RestClient visionRestClient,
            PdfRenderer renderer,
            VisionInvoiceMapper mapper,
            VisionContentBuilder contentBuilder,
            ObjectMapper json,
            @Value("${vision.model}") String model,
            @Value("${vision.provider:ollama}") String provider,
            @Value("${vision.render-scale:1.5}") float renderScale,
            @Value("${vision.temperature:#{null}}") Double temperature) {
        this.visionRestClient = visionRestClient;
        this.renderer = renderer;
        this.mapper = mapper;
        this.contentBuilder = contentBuilder;
        this.json = json;
        this.model = model;
        this.provider = provider;
        this.renderScale = renderScale;
        this.temperature = temperature;
        log.info("VisionLlmExtractor initialised: provider={} model={} scale={} temperature={}",
                provider, model, renderScale, temperature);
    }

    @Override
    public InvoiceDocument extract(byte[] pdfBytes, String filename) {
        long start = System.currentTimeMillis();

        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new ExtractionException("vision", ExtractorErrorCode.INVALID_REQUEST,
                    "PDF byte array is empty");
        }

        // 1. Render PDF pages to PNG
        List<byte[]> pages;
        try {
            pages = renderer.renderAllPages(pdfBytes, renderScale);
        } catch (IllegalArgumentException e) {
            throw new ExtractionException("vision", ExtractorErrorCode.INVALID_REQUEST,
                    "PDF render failed: " + e.getMessage(), e);
        }
        log.debug("PDF rendered: {} pages", pages.size());

        // 2. Load prompt template
        String prompt = loadPrompt();

        // 3. Build multi-modal content array
        List<Map<String, Object>> content = contentBuilder.buildContent(prompt, pages);

        // 4. Build provider-aware request body
        Map<String, Object> body = contentBuilder.buildRequestBody(model, content, temperature);

        // 5. Call vision LLM
        String rawResponse;
        try {
            rawResponse = visionRestClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            String hint = parseErrorHint(e.getResponseBodyAsString());
            throw new ExtractionException("vision", ExtractorErrorCode.UNREACHABLE,
                    "Vision LLM call failed (" + e.getStatusCode() + "): " + hint, e);
        } catch (Exception e) {
            throw new ExtractionException("vision", ExtractorErrorCode.UNREACHABLE,
                    "Vision LLM unreachable: " + e.getMessage(), e);
        }

        // 6. Extract text from response
        String contentText;
        try {
            Map<String, Object> responseMap = json.readValue(rawResponse, new TypeReference<>() {});
            contentText = extractContentText(responseMap);
        } catch (Exception e) {
            throw new ExtractionException("vision", ExtractorErrorCode.UNKNOWN,
                    "Failed to parse vision LLM response: " + e.getMessage(), e);
        }

        // 7. Parse fields from LLM JSON
        Map<String, Object> fields;
        try {
            fields = json.readValue(contentText, new TypeReference<>() {});
        } catch (Exception e) {
            throw new ExtractionException("vision", ExtractorErrorCode.UNKNOWN,
                    "Vision LLM returned unparseable JSON: " + e.getMessage() + ". Raw: " +
                            truncate(contentText, 300), e);
        }

        // 8. Extract confidence and build InvoiceDocument
        double confidence = asDouble(fields.get("confidence"), 0.8);
        String rawText = String.valueOf(fields.getOrDefault("raw_text", ""));

        long durMs = System.currentTimeMillis() - start;
        return mapper.toDocument(fields, "vision", confidence, true, pages.size(), durMs,
                contentText, rawText);
    }

    private String loadPrompt() {
        String cached = cachedPrompt;
        if (cached != null) return cached;
        try {
            ClassPathResource res = new ClassPathResource(PROMPT_TEMPLATE);
            cached = StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
            cachedPrompt = cached;
            return cached;
        } catch (IOException e) {
            log.warn("Could not load {}; using inline fallback prompt", PROMPT_TEMPLATE);
            return FALLBACK_PROMPT;
        }
    }

    @SuppressWarnings("unchecked")
    private String extractContentText(Map<String, Object> response) {
        try {
            List<?> choices = (List<?>) response.get("choices");
            if (choices == null || choices.isEmpty()) return "{}";
            Map<?, ?> choice = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) choice.get("message");
            Object content = message != null ? message.get("content") : null;
            return content != null ? String.valueOf(content) : "{}";
        } catch (Exception e) {
            log.warn("Could not extract content from vision LLM response: {}", e.getMessage());
            return "{}";
        }
    }

    private String parseErrorHint(String body) {
        if (body == null || body.isBlank()) return "no details";
        try {
            Map<String, Object> m = json.readValue(body, new TypeReference<>() {});
            return String.valueOf(m.getOrDefault("error", body));
        } catch (Exception e) {
            return body;
        }
    }

    private double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (Exception e) { return def; }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    @Override
    public String extractorName() {
        return "vision";
    }

    // -------------------------------------------------------------------------
    // Fallback inline prompt — used only when the .st template file is missing
    // -------------------------------------------------------------------------
    private static final String FALLBACK_PROMPT = """
            You are a trade finance document extraction assistant.

            Examine the invoice pages provided and extract the following fields.
            Return ONLY valid JSON with these exact keys (use null when a field is not present):

            invoice_number, invoice_date (YYYY-MM-DD), seller_name, seller_address,
            buyer_name, buyer_address, goods_description, quantity (number),
            unit (e.g. "pcs", "kg"), unit_price, total_amount, currency,
            lc_reference, trade_terms (e.g. CIF SINGAPORE),
            port_of_loading, port_of_discharge, country_of_origin, signed (true/false),
            confidence (0.0–1.0), raw_text (concise text summary of all invoice content)

            Rules:
            - Never guess or fabricate values; use null when uncertain
            - Dates must be ISO format (YYYY-MM-DD)
            - Numbers should be plain (no commas or currency symbols)
            - confidence is your own estimate of extraction reliability (0.0–1.0)
            - raw_text should be a brief summary, not the full invoice text
            """;
}
