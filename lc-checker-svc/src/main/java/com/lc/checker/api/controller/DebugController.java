package com.lc.checker.api.controller;

import com.lc.checker.stage.extract.InvoiceExtractionOrchestrator;
import com.lc.checker.stage.parse.Mt700Parser;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/**
 * Temporary debug endpoints for verifying each pipeline stage independently.
 * All responses are plain text — no JSON DTOs.
 *
 * <ul>
 *   <li>{@code POST /api/v1/debug/mt700/parse} — raw LC fields from the deterministic Stage 1a parser</li>
 *   <li>{@code POST /api/v1/debug/invoice/compare} — all enabled extractors side-by-side</li>
 * </ul>
 *
 * <p>These are dev-only endpoints. They are not part of the V1 production API contract.
 */
@RestController
@RequestMapping("/api/v1/debug")
public class DebugController {

    private final Mt700Parser mt700Parser;
    private final InvoiceExtractionOrchestrator extractionOrchestrator;
    private final RestClient.Builder restClientBuilder;
    private final String localVisionBaseUrl;
    private final String localVisionApiKey;
    private final String localVisionModel;

    public DebugController(Mt700Parser mt700Parser,
                           InvoiceExtractionOrchestrator extractionOrchestrator,
                           RestClient.Builder restClientBuilder,
                           @Value("${local-llm-vl.base-url:http://127.0.0.1:11434/v1}") String localVisionBaseUrl,
                           @Value("${local-llm-vl.api-key:}") String localVisionApiKey,
                           @Value("${local-llm-vl.model:qwen3-vl:4b-instruct}") String localVisionModel) {
        this.mt700Parser = mt700Parser;
        this.extractionOrchestrator = extractionOrchestrator;
        this.restClientBuilder = restClientBuilder;
        this.localVisionBaseUrl = localVisionBaseUrl;
        this.localVisionApiKey = localVisionApiKey;
        this.localVisionModel = localVisionModel;
    }

    /**
     * Parse MT700 text with the deterministic regex parser only. Stage 1a output:
     * scalar fields plus raw verbatim :45A:, :46A:, :47A: strings. No LLM involved.
     */
    @PostMapping(value = "/mt700/parse", consumes = MediaType.TEXT_PLAIN_VALUE)
    public String parseMt700(@RequestBody String mt700Text) {
        return mt700Parser.parseAsText(mt700Text);
    }

    /**
     * Run every enabled extractor (vision, docling, mineru) against the same PDF
     * and dump results side-by-side as plain text. Does NOT persist to DB.
     */
    @PostMapping(value = "/invoice/compare", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String compareExtractors(@RequestPart("invoice") MultipartFile pdf) throws IOException {
        return extractionOrchestrator.compareAllAsText(pdf.getBytes(), pdf.getOriginalFilename());
    }

    /**
     * Ping the local vision LLM with a tiny text-only chat request. Surfaces the
     * actual {@link org.springframework.http.client.ClientHttpRequestFactory}
     * class on the auto-injected RestClient.Builder, so we can verify whether
     * {@code Http11RestClientCustomizer} is in effect (look for
     * {@code JdkClientHttpRequestFactory}).
     *
     * <p>Returns plain text — easy to grep. Use:
     * <pre>curl -s http://localhost:8080/api/v1/debug/mlx-ping</pre>
     */
    @GetMapping(value = "/mlx-ping", produces = MediaType.TEXT_PLAIN_VALUE)
    public String mlxPing() {
        StringBuilder out = new StringBuilder();
        out.append("=== local vision LLM ping ===\n");
        out.append("base-url: ").append(localVisionBaseUrl).append('\n');
        out.append("model:    ").append(localVisionModel).append('\n');

        RestClient.Builder b = restClientBuilder
                .baseUrl(localVisionBaseUrl)
                .defaultHeader("Content-Type", "application/json");
        if (localVisionApiKey != null && !localVisionApiKey.isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + localVisionApiKey);
        }
        RestClient client = b.build();
        // Reflectively read the configured factory so we can prove the
        // customizer applied (JdkClientHttpRequestFactory ⇒ HTTP/1.1 pin).
        out.append("factory:  ").append(reflectFactoryClass(client)).append('\n');

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", localVisionModel);
        body.put("stream", false);
        body.put("messages", List.of(Map.of("role", "user", "content", "ping")));

        long t0 = System.currentTimeMillis();
        try {
            String resp = client.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            long ms = System.currentTimeMillis() - t0;
            out.append("status:   200 OK (").append(ms).append(" ms)\n");
            out.append("response: ").append(truncate(resp, 400)).append('\n');
        } catch (HttpStatusCodeException e) {
            long ms = System.currentTimeMillis() - t0;
            out.append("status:   ").append(e.getStatusCode()).append(" (").append(ms).append(" ms)\n");
            out.append("body:     ").append(truncate(e.getResponseBodyAsString(), 600)).append('\n');
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            out.append("status:   EXCEPTION (").append(ms).append(" ms)\n");
            out.append("error:    ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append('\n');
        }
        return out.toString();
    }

    private static String reflectFactoryClass(RestClient client) {
        try {
            java.lang.reflect.Field f = client.getClass().getDeclaredField("clientRequestFactory");
            f.setAccessible(true);
            Object factory = f.get(client);
            return factory == null ? "<null>" : factory.getClass().getName();
        } catch (Exception e) {
            // Fallback: try the field name on DefaultRestClient
            try {
                for (java.lang.reflect.Field f : client.getClass().getDeclaredFields()) {
                    if (org.springframework.http.client.ClientHttpRequestFactory.class
                            .isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object factory = f.get(client);
                        return factory == null ? "<null>" : factory.getClass().getName();
                    }
                }
            } catch (Exception ignored) { }
            return "<introspection failed: " + e.getMessage() + ">";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(+" + (s.length() - max) + " chars)";
    }
}
