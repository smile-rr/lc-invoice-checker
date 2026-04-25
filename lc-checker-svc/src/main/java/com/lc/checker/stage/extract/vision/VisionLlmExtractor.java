package com.lc.checker.stage.extract.vision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.domain.common.FieldType;
import com.lc.checker.infra.fields.ColumnDefinition;
import com.lc.checker.infra.fields.FieldDefinition;
import com.lc.checker.infra.fields.FieldPoolRegistry;
import com.lc.checker.stage.extract.ExtractionException;
import com.lc.checker.stage.extract.ExtractionResult;
import com.lc.checker.stage.extract.ExtractorErrorCode;
import com.lc.checker.stage.extract.InvoiceExtractor;
import com.lc.checker.stage.extract.InvoiceFieldMapper;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.result.LlmTrace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Vision-LLM invoice extractor over an OpenAI-compatible {@code /chat/completions}
 * endpoint. Provider-agnostic: works for remote (Qwen Cloud, OpenAI, etc.) and
 * local (Ollama) installations alike — only the URL/model/auth differ.
 *
 * <p>PDF pages are rendered to PNG via PDFBox ({@link PdfRenderer}), then sent as
 * base64-encoded images in the array-style content format that both OpenAI and
 * Ollama accept.
 *
 * <p>Not a Spring component — instantiated via {@link VisionExtractorBeans} which
 * spins up two beans (remote + local) from two separate {@link VisionExtractorConfig}
 * blocks. The {@link VisionExtractorConfig#name()} surfaces as
 * {@link #extractorName()} so each instance writes its own row to
 * {@code pipeline_steps} and can be distinguished in SSE events / UI.
 */
public class VisionLlmExtractor implements InvoiceExtractor {

    private static final Logger log = LoggerFactory.getLogger(VisionLlmExtractor.class);
    private static final String PROMPT_TEMPLATE = "prompts/vision-invoice-extract.st";

    private final RestClient restClient;
    private final PdfRenderer renderer;
    private final InvoiceFieldMapper mapper;
    private final ObjectMapper json;
    private final FieldPoolRegistry fieldPool;
    private final String name;
    private final String model;
    private final int renderDpi;
    private final int maxPages;
    private final int maxLongEdgePx;
    private final int timeoutSeconds;
    private volatile String cachedPrompt;

    public VisionLlmExtractor(
            RestClient.Builder restClientBuilder,
            VisionExtractorConfig config,
            PdfRenderer renderer,
            InvoiceFieldMapper mapper,
            ObjectMapper json,
            FieldPoolRegistry fieldPool) {
        this.name = config.name();
        this.model = config.model();
        this.renderDpi = config.renderDpi();
        this.maxPages = config.maxPages();
        this.maxLongEdgePx = config.maxLongEdgePx();
        this.timeoutSeconds = config.timeoutSeconds();
        this.renderer = renderer;
        this.mapper = mapper;
        this.json = json;
        this.fieldPool = fieldPool;

        // Standalone RestClient for the OpenAI-compatible /v1/chat/completions endpoint.
        // Auth: empty api-key (e.g. local Ollama) → no header; non-empty → Bearer token.
        String apiKey = config.apiKey();
        RestClient.Builder builder = restClientBuilder
                .baseUrl(config.baseUrl())
                .defaultHeader("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.restClient = builder.build();
        log.info("VisionLlmExtractor[{}] configured: model={} dpi={} maxPages={} maxLongEdge={}px timeout={}s base={} auth={}",
                name, model, renderDpi, maxPages, maxLongEdgePx, timeoutSeconds, config.baseUrl(),
                (apiKey != null && !apiKey.isBlank()) ? "bearer" : "none");
    }

    public ExtractionResult extract(byte[] pdfBytes, String filename) {
        long start = System.currentTimeMillis();

        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new ExtractionException(name, ExtractorErrorCode.INVALID_REQUEST,
                    "PDF byte array is empty");
        }

        // 1. Render PDF pages to JPEG (200 DPI, grayscale, ≤2048 px long edge by default)
        List<byte[]> pages;
        try {
            pages = renderer.renderAllPages(pdfBytes, renderDpi, maxPages, maxLongEdgePx, true);
        } catch (IllegalArgumentException e) {
            throw new ExtractionException(name, ExtractorErrorCode.INVALID_REQUEST,
                    "PDF render failed: " + e.getMessage(), e);
        }
        if (pages.isEmpty()) {
            throw new ExtractionException(name, ExtractorErrorCode.INVALID_REQUEST,
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
            LlmTrace errTrace = new LlmTrace(name + ".extract", model, promptRendered, null,
                    null, null, System.currentTimeMillis() - llmStart, e.getMessage());
            throw new ExtractionException(name, ExtractorErrorCode.UNREACHABLE,
                    "Vision LLM client error " + e.getStatusCode() + ": " + e.getMessage(), e)
                    .withLlmTrace(errTrace);
        } catch (HttpServerErrorException e) {
            LlmTrace errTrace = new LlmTrace(name + ".extract", model, promptRendered, null,
                    null, null, System.currentTimeMillis() - llmStart, e.getMessage());
            throw new ExtractionException(name, ExtractorErrorCode.UNREACHABLE,
                    "Vision LLM server error " + e.getStatusCode() + ": " + e.getMessage(), e)
                    .withLlmTrace(errTrace);
        } catch (Exception e) {
            LlmTrace errTrace = new LlmTrace(name + ".extract", model, promptRendered, null,
                    null, null, System.currentTimeMillis() - llmStart, e.getMessage());
            throw new ExtractionException(name, ExtractorErrorCode.UNREACHABLE,
                    "Vision LLM call failed: " + e.getMessage(), e)
                    .withLlmTrace(errTrace);
        }
        long llmLatency = System.currentTimeMillis() - llmStart;
        log.debug("Vision LLM response: {}", truncate(rawResponse, 300));

        // 4. Parse OpenAI-compatible response: { choices: [{ message: { content: "..." } }] }
        String contentText = extractContent(rawResponse);

        LlmTrace trace = new LlmTrace(name + ".extract", model, promptRendered,
                contentText, null, null, llmLatency, null);

        // 5. Parse the JSON envelope: {markdown, fields, confidence, raw_text}.
        //    Backward-compat: if the model returns a flat map (old prompt shape
        //    or a model that ignored the new schema), treat it as `fields` and
        //    leave markdown empty.
        Map<String, Object> root;
        try {
            root = json.readValue(contentText, new TypeReference<>() {});
        } catch (Exception jsonFail) {
            log.debug("Vision LLM response is not JSON; using key:value text parser. JSON error: {}",
                    jsonFail.getMessage());
            root = parseTextFormat(contentText);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = root.get("fields") instanceof Map
                ? (Map<String, Object>) root.get("fields")
                : root;

        String markdown = stringOrEmpty(root.get("markdown"));
        String rawText = stringOrEmpty(root.getOrDefault("raw_text",
                root.getOrDefault("rawtext", root.get("raw_text_summary"))));
        Object confidenceVal = root.getOrDefault("confidence",
                root.getOrDefault("confidence_score", root.get("score")));
        double confidence = asDouble(confidenceVal, 0.8);

        // 6. Build InvoiceDocument. rawMarkdown carries the structured layout
        //    rendering for the UI's Markdown tab; rawText carries free text.
        long durMs = System.currentTimeMillis() - start;
        String rawMarkdown = markdown.isBlank() ? contentText : markdown;
        InvoiceDocument doc = mapper.toDocument(fields, name, confidence, true, pages.size(), durMs,
                rawMarkdown, rawText);
        return new ExtractionResult(doc, List.of(trace));
    }

    private static String stringOrEmpty(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * Build a compact prompt summary suitable for LlmTrace. The raw request body contains
     * base64-encoded page images that would bloat the trace JSON to multi-MB; we record
     * the prompt text + page count instead.
     */
    private String summarisePromptForTrace(Map<String, Object> body, int pageCount) {
        String prompt = loadPrompt();
        return "[" + name + " model=" + model + " pages=" + pageCount + "]\n" + prompt;
    }

    /**
     * Build the OpenAI-compatible /v1/chat/completions request body.
     * Uses the array content format that both Ollama and OpenAI support.
     */
    private Map<String, Object> buildOllamaRequest(List<byte[]> pages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        // Extraction is a deterministic, structured task — no creativity.
        body.put("temperature", 0.0);
        // Long invoices (3+ pages, dense tables) need plenty of headroom; running
        // out mid-JSON is the #1 cause of parse failures. 4096 fits even an
        // image-heavy multi-page invoice with comfortable margin.
        body.put("max_tokens", 4096);
        // OpenAI-compatible JSON-mode hint. Honoured by DashScope (qwen-vl-plus,
        // qwen-plus). The MLX server's pydantic-settings ignores unknown keys, so
        // this is a safe no-op there — guarantees still come from the prompt.
        body.put("response_format", Map.of("type", "json_object"));
        // Disable Qwen3+ "thinking" mode. Without this, qwen3.6-plus / qwen3-vl-*
        // emit hundreds of reasoning tokens (billed as completion) AND wrap the
        // JSON in ```json …``` markdown fences, breaking strict parsing. Setting
        // this to false: cuts cost ~30×, removes the markdown wrapping, leaves
        // `content` as raw JSON. Ignored by Ollama (qwen3-vl:4b-instruct) and by
        // older Qwen2-based models (qwen-vl-plus / qwen-vl-max) — safe no-op.
        body.put("enable_thinking", false);

        String promptText = loadPrompt();

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", promptText));
        for (byte[] page : pages) {
            String b64 = java.util.Base64.getEncoder().encodeToString(page);
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", "data:image/jpeg;base64," + b64)
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
        String template;
        try {
            template = StreamUtils.copyToString(
                    new ClassPathResource(PROMPT_TEMPLATE).getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not load {}; using inline fallback prompt", PROMPT_TEMPLATE);
            template = FALLBACK_PROMPT;
        }
        // Render {{fields}} from the canonical registry — single source of truth
        // for what the LLM is asked to produce. Adding a new invoice field is
        // a one-line YAML edit; the prompt updates automatically on next boot.
        String fieldList = renderFieldList();
        cached = template.replace("{{fields}}", fieldList);
        cachedPrompt = cached;
        return cached;
    }

    /** Render the canonical-field reference block injected into the prompt. */
    private String renderFieldList() {
        if (fieldPool == null) return "";
        return fieldPool.appliesToInvoice().stream()
                .map(this::renderFieldLine)
                .collect(Collectors.joining("\n"));
    }

    private String renderFieldLine(FieldDefinition f) {
        if (f.type() == FieldType.TABLE) {
            return renderTableField(f);
        }
        String typeHint = switch (f.type()) {
            case DATE -> "DATE — ISO yyyy-MM-dd";
            case CURRENCY_CODE -> "CURRENCY_CODE — ISO 4217";
            case ENUM -> "ENUM — one of " + String.join(", ", f.enumValues());
            case AMOUNT -> "AMOUNT (number)";
            case INTEGER -> "INTEGER";
            case MULTILINE_TEXT -> "MULTILINE_TEXT";
            default -> f.type().name();
        };
        String label = f.nameEn() == null ? f.key() : f.nameEn();
        return "- " + f.key() + " (" + label + ", " + typeHint + ")";
    }

    /**
     * Render a TABLE field as a header line plus indented column hints. The
     * model is asked to return an ARRAY of row objects under {@code f.key()};
     * each row uses the canonical column keys with values typed per column.
     */
    private String renderTableField(FieldDefinition f) {
        String label = f.nameEn() == null ? f.key() : f.nameEn();
        StringBuilder sb = new StringBuilder("- ")
                .append(f.key())
                .append(" (")
                .append(label)
                .append(", TABLE — array of rows; each row has the columns below. ")
                .append("Return [] if the invoice has no such table.)");
        for (ColumnDefinition c : f.columns()) {
            String hint = switch (c.type()) {
                case DATE -> "DATE";
                case CURRENCY_CODE -> "CURRENCY_CODE";
                case ENUM -> "ENUM (" + String.join("|", c.enumValues()) + ")";
                case AMOUNT -> "AMOUNT";
                case INTEGER -> "INTEGER";
                case MULTILINE_TEXT -> "TEXT";
                default -> c.type().name();
            };
            String colLabel = c.nameEn() == null ? c.key() : c.nameEn();
            sb.append("\n    • ")
                    .append(c.key())
                    .append(" (")
                    .append(colLabel)
                    .append(", ")
                    .append(hint)
                    .append(")");
        }
        return sb.toString();
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

    public String extractorName() {
        return name;
    }

    private static final String FALLBACK_PROMPT = """
            Extract invoice fields as plain key:value pairs — ONE pair per line.
            Return ONLY key:value lines. Use null if absent.
            Keys: invoice_number, invoice_date, seller_name, seller_address, buyer_name,
            buyer_address, goods_description, quantity, unit, unit_price, total_amount,
            currency, lc_reference, trade_terms, port_of_loading, port_of_discharge,
            country_of_origin, signed (true/false/null), confidence (0.0-1.0)
            """;
}
