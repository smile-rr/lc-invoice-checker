package com.lc.checker.stage.extract.vision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds OpenAI-compatible multi-modal request bodies for the Vision LLM extractor.
 *
 * <p>All three providers (Ollama, Qwen Cloud, Gemini via OpenAI-compatible adapter)
 * accept the standard content-array format with text + image_url blocks. Provider-
 * specific fields are handled via conditional injection.
 *
 * <h3>Request body shape (all providers)</h3>
 * <pre>
 * {
 *   "model": "...",
 *   "messages": [{ "role": "user", "content": [...] }],
 *   "stream": false,
 *   ...provider-specific fields...
 * }
 * </pre>
 *
 * <h3>Content array</h3>
 * <pre>
 * [
 *   { "type": "text",      "text": "..." },
 *   { "type": "image_url", "image_url": { "url": "data:image/png;base64,..." } },
 *   ...
 * ]
 * </pre>
 */
@Component
public class VisionContentBuilder {

    private final String provider;

    public VisionContentBuilder(
            @Value("${vision.provider:ollama}") String provider) {
        this.provider = provider;
    }

    /**
     * Build the multi-modal content array for a vision request.
     *
     * @param promptText  instruction / system-prompt text (placed first)
     * @param pngPages    PNG byte arrays, one per PDF page
     * @return OpenAI-compatible content list
     */
    public List<Map<String, Object>> buildContent(String promptText, List<byte[]> pngPages) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", promptText));

        for (byte[] page : pngPages) {
            String b64 = java.util.Base64.getEncoder().encodeToString(page);
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", "data:image/png;base64," + b64)
            ));
        }
        return content;
    }

    /**
     * Build the full request body, injecting provider-specific fields.
     *
     * <ul>
     *   <li>Ollama: injects {@code "options": {temperature, num_predict}}</li>
     *   <li>Qwen / Gemini: uses top-level {@code temperature}</li>
     * </ul>
     *
     * @param model       model name from config
     * @param content     result of {@link #buildContent}
     * @param temperature temperature override (use config default if null)
     * @return complete request body as a Map (Jackson-compatible)
     */
    public Map<String, Object> buildRequestBody(
            String model,
            List<Map<String, Object>> content,
            Double temperature) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        body.put("stream", false);

        double temp = temperature != null ? temperature : 0.0;

        if ("ollama".equalsIgnoreCase(provider)) {
            // Ollama: temperature goes inside "options"
            body.put("options", Map.of(
                    "temperature", temp,
                    "num_predict", 2048
            ));
        } else {
            // Qwen, Gemini, vLLM, and other OpenAI-compatible providers
            body.put("temperature", temp);
        }

        return body;
    }
}
