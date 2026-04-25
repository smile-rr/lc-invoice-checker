package com.lc.checker.stage.extract.vision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.stage.extract.ExtractionException;
import com.lc.checker.stage.extract.ExtractionResult;
import com.lc.checker.stage.extract.ExtractorErrorCode;
import com.lc.checker.stage.extract.InvoiceExtractor;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.result.LlmTrace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Vision-LLM invoice extractor using Ollama's native {@code /api/chat} endpoint.
 *
 * <p>PDF pages are rendered to PNG via PDFBox ({@link PdfRenderer}), then sent as
 * base64-encoded images to Ollama. Uses the native Ollama API format rather than
 * the OpenAI-compatible endpoint to ensure multi-modal support works correctly.
 *
 * <p>Provider switch is purely via env vars — no code changes needed.
 * For Qwen Cloud or Gemini, switch to the OpenAI-compatible {@code VisionChatModel}
 * path and fix the {@code baseUrl} wiring.
 */
@Component
public class VisionLlmExtractor implements InvoiceExtractor {

    private static final Logger log = LoggerFactory.getLogger(VisionLlmExtractor.class);
    private static final String PROMPT_TEMPLATE = "prompts/vision-invoice-extract.st";

    private final RestClient restClient;
    private final PdfRenderer renderer;
    private final VisionInvoiceMapper mapper;
    private final ObjectMapper json;
    private final String model;
    private final float renderScale;
    private final int timeoutSeconds;
    private volatile String cachedPrompt;

    public VisionLlmExtractor(
            RestClient.Builder restClientBuilder,
            @Value("${vision.base-url}") String baseUrl,
            @Value("${vision.api-key:}") String apiKey,
            @Value("${vision.model}") String model,
            @Value("${vision.render-scale:1.5}") float renderScale,
            @Value("${vision.timeout-seconds:120}") int timeoutSeconds,
            PdfRenderer renderer,
            VisionInvoiceMapper mapper,
            ObjectMapper json) {
        this.model = model;
        this.renderScale = renderScale;
        this.timeoutSeconds = timeoutSeconds;
        this.renderer = renderer;
        this.mapper = mapper;
        this.json = json;

        // Standalone RestClient for the OpenAI-compatible /v1/chat/completions endpoint.
        // Auth: empty api-key (e.g. local Ollama) → no header; non-empty → Bearer token.
        RestClient.Builder builder = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.restClient = builder.build();
        log.info("VisionLlmExtractor configured: model={} scale={} timeout={} base={} auth={}",
                model, renderScale, timeoutSeconds, baseUrl,
                (apiKey != null && !apiKey.isBlank()) ? "bearer" : "none");
    }

    @Override
    public ExtractionResult extract(byte[] pdfBytes, String filename) {
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
        if (pages.isEmpty()) {
            throw new ExtractionException("vision", ExtractorErrorCode.INVALID_REQUEST,
                    "PDF has no renderable pages");
        }
        log.debug("Calling vision LLM: model={} pages={}", model, pages.size());

        // 2. Build native Ollama API request body
        Map<String, Object> body = buildOllamaRequest(pages);

        // 3. Call Ollama /api/chat (capture timing + prompt/response for LlmTrace)
        String promptRendered = summarisePromptForTrace(body, pages.size());
        long llmStart = System.currentTimeMillis();
        String rawResponse;
        try {
            rawResponse = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            LlmTrace errTrace = new LlmTrace("vision.extract", model, promptRendered, null,
                    null, null, System.currentTimeMillis() - llmStart, e.getMessage());
            throw new ExtractionException("vision", ExtractorErrorCode.UNREACHABLE,
                    "Vision LLM client error " + e.getStatusCode() + ": " + e.getMessage(), e)
                    .withLlmTrace(errTrace);
        } catch (HttpServerErrorException e) {
            LlmTrace errTrace = new LlmTrace("vision.extract", model, promptRendered, null,
                    null, null, System.currentTimeMillis() - llmStart, e.getMessage());
            throw new ExtractionException("vision", ExtractorErrorCode.UNREACHABLE,
                    "Vision LLM server error " + e.getStatusCode() + ": " + e.getMessage(), e)
                    .withLlmTrace(errTrace);
        } catch (Exception e) {
            LlmTrace errTrace = new LlmTrace("vision.extract", model, promptRendered, null,
                    null, null, System.currentTimeMillis() - llmStart, e.getMessage());
            throw new ExtractionException("vision", ExtractorErrorCode.UNREACHABLE,
                    "Vision LLM call failed: " + e.getMessage(), e)
                    .withLlmTrace(errTrace);
        }
        long llmLatency = System.currentTimeMillis() - llmStart;
        log.debug("Vision LLM response: {}", truncate(rawResponse, 300));

        // 4. Parse OpenAI-compatible response: { choices: [{ message: { content: "..." } }] }
        String contentText = extractContent(rawResponse);

        LlmTrace trace = new LlmTrace("vision.extract", model, promptRendered,
                contentText, null, null, llmLatency, null);

        // 5. Parse JSON from response (fallback to key:value text parser for non-JSON models)
        Map<String, Object> fields;
        try {
            fields = json.readValue(contentText, new TypeReference<>() {});
        } catch (Exception jsonFail) {
            log.debug("Vision LLM response is not JSON (model may have written prose); "
                    + "using key:value text parser. JSON error: {}",
                    jsonFail.getMessage());
            fields = parseTextFormat(contentText);
        }

        // 6. Build InvoiceDocument
        // Tolerate common extractor-meta variants ("confidence_score", "rawtext")
        // — these are extractor-internal (not invoice fields), so the field-pool
        // doesn't cover them. Field-pool aliases handle every actual invoice field.
        Object confidenceVal = fields.getOrDefault("confidence",
                fields.getOrDefault("confidence_score", fields.get("score")));
        double confidence = asDouble(confidenceVal, 0.8);
        Object rawTextVal = fields.getOrDefault("raw_text",
                fields.getOrDefault("rawtext", fields.get("raw_text_summary")));
        String rawText = rawTextVal == null ? "" : String.valueOf(rawTextVal);

        long durMs = System.currentTimeMillis() - start;
        InvoiceDocument doc = mapper.toDocument(fields, "vision", confidence, true, pages.size(), durMs,
                contentText, rawText);
        return new ExtractionResult(doc, List.of(trace));
    }

    /**
     * Build a compact prompt summary suitable for LlmTrace. The raw request body contains
     * base64-encoded page images that would bloat the trace JSON to multi-MB; we record
     * the prompt text + page count instead.
     */
    private String summarisePromptForTrace(Map<String, Object> body, int pageCount) {
        String prompt = loadPrompt();
        return "[vision-extract model=" + model + " pages=" + pageCount + "]\n" + prompt;
    }

    /**
     * Build the OpenAI-compatible /v1/chat/completions request body.
     * Uses the array content format that both Ollama and OpenAI support.
     */
    private Map<String, Object> buildOllamaRequest(List<byte[]> pages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);

        String promptText = loadPrompt();

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", promptText));
        for (byte[] page : pages) {
            String b64 = java.util.Base64.getEncoder().encodeToString(page);
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", "data:image/png;base64," + b64)
            ));
        }

        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        return body;
    }

    private String extractContent(String rawResponse) {
        try {
            Map<String, Object> resp = json.readValue(rawResponse, new TypeReference<>() {});
            List<?> choices = (List<?>) resp.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<?, ?> choice = (Map<?, ?>) choices.get(0);
                Map<?, ?> message = (Map<?, ?>) choice.get("message");
                if (message != null) {
                    Object content = message.get("content");
                    if (content instanceof String s) return s;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse OpenAI response: {}", e.getMessage());
        }
        return "{}";
    }

    private String loadPrompt() {
        String cached = cachedPrompt;
        if (cached != null) return cached;
        try {
            cached = StreamUtils.copyToString(
                    new ClassPathResource(PROMPT_TEMPLATE).getInputStream(),
                    StandardCharsets.UTF_8);
            cachedPrompt = cached;
            return cached;
        } catch (IOException e) {
            log.warn("Could not load {}; using inline fallback prompt", PROMPT_TEMPLATE);
            return FALLBACK_PROMPT;
        }
    }

    private double asDouble(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (Exception e) { return def; }
    }

    /**
     * Parse a plain-text key:value response (e.g. from qwen3-vl:2b) into a Map.
     * Handles:
     * - Simple "key: value\n" lines
     * - camelCase JSON objects embedded in the content string (model ignores prompt and
     *   returns JSON as a string value, which then fails JSON parse at the top level)
     */
    Map<String, Object> parseTextFormat(String text) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return fields;

        // Fast path: trim and try to extract a JSON object from within the string.
        // Handles cases where the model returns JSON as a string value inside the
        // OpenAI content field, e.g. content = '{\n  "invoiceNumber": "123"\n}'
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            // Find the matching closing brace
            int close = findMatchingBrace(trimmed, 0);
            if (close > 0) {
                String jsonCandidate = trimmed.substring(0, close + 1);
                try {
                    Map<?, ?> parsed = json.readValue(jsonCandidate, new TypeReference<>() {});
                    normaliseAndPutAll(fields, parsed);
                    log.info("Text parser: detected and parsed embedded JSON ({} keys)", fields.size());
                    return fields;
                } catch (Exception ignored) {
                    // Not valid JSON — fall through to line-by-line parsing
                }
            }
        }

        // Line-by-line: "key: value" or "key  value"
        String[] lines = text.split("\n");
        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                if (currentKey != null) {
                    fields.put(currentKey, currentValue.toString().trim());
                    currentKey = null;
                    currentValue.setLength(0);
                }
                continue;
            }

            int colon = line.indexOf(':');
            String keyPart;
            String valuePart;

            if (colon > 0) {
                keyPart = line.substring(0, colon).trim().toLowerCase();
                valuePart = line.substring(colon + 1).trim();
            } else {
                String[] parts = line.split("\\s+", 2);
                keyPart = parts[0].trim().toLowerCase();
                valuePart = parts.length > 1 ? parts[1].trim() : "";
            }

            if (keyPart.isEmpty() || keyPart.startsWith("{")) continue;

            // Flush previous field
            if (currentKey != null) {
                fields.put(currentKey, currentValue.toString().trim());
            }
            currentKey = keyPart.replace("\"", "");
            currentValue.setLength(0);
            currentValue.append(valuePart.replace("\"", ""));
        }
        if (currentKey != null) {
            fields.put(currentKey, currentValue.toString().trim());
        }
        return fields;
    }

    private int findMatchingBrace(String s, int openPos) {
        int depth = 0;
        boolean inString = false;
        for (int i = openPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    /**
     * Recursively flatten a parsed camelCase JSON map into snake_case keys. Key
     * canonicalisation (alias → field-pool key) and type coercion both happen
     * downstream in {@link com.lc.checker.stage.extract.InvoiceFieldMapper}, so
     * here we only normalise the spelling.
     */
    @SuppressWarnings("unchecked")
    private void normaliseAndPutAll(Map<String, Object> dest, Map<?, ?> src) {
        for (Map.Entry<?, ?> e : src.entrySet()) {
            String key = toSnakeCase(String.valueOf(e.getKey()));
            Object val = e.getValue();
            if (val instanceof Map) {
                normaliseAndPutAll(dest, (Map<?, ?>) val);
            } else {
                dest.put(key, val);
            }
        }
    }

    private String toSnakeCase(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    @Override
    public String extractorName() {
        return "vision";
    }

    private static final String FALLBACK_PROMPT = """
            Extract invoice fields as plain key:value pairs — ONE pair per line.
            Return ONLY key:value lines. Use null if absent.
            Keys: invoice_number, invoice_date, seller_name, seller_address, buyer_name,
            buyer_address, goods_description, quantity, unit, unit_price, total_amount,
            currency, lc_reference, trade_terms, port_of_loading, port_of_discharge,
            country_of_origin, signed (true/false/null), confidence (0.0-1.0), raw_text
            """;
}
