package com.lc.checker.extractor.vision;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires a provider-configurable {@link RestClient} for the Vision LLM extractor.
 *
 * <p>All three supported providers (Ollama, Qwen Cloud, Gemini via OpenAI-compatible
 * adapter) speak the same {@code /v1/chat/completions} HTTP protocol — only the
 * base URL, model name, and optional API key differ. Switching providers requires
 * only env-var changes; no Java code changes.
 *
 * <p>Provider matrix:
 * <ul>
 *   <li>Ollama (local): base-url=http://host.docker.internal:11434/v1, no API key</li>
 *   <li>Qwen Cloud: base-url=https://dashscope.aliyuncs.com/compatible-mode/v1, API key required</li>
 *   <li>Gemini (via adapter): base-url=https://generativelanguage.googleapis.com/v1beta/openai/, API key required</li>
 * </ul>
 */
@Configuration
public class VisionClientConfig {

    public static final String VISION_REST_CLIENT = "visionRestClient";

    @Bean(VISION_REST_CLIENT)
    public RestClient visionRestClient(
            RestClient.Builder builder,
            @Value("${vision.base-url}") String baseUrl,
            @Value("${vision.api-key:}") String apiKey) {

        RestClient.Builder configured = builder.clone().baseUrl(baseUrl);

        if (apiKey != null && !apiKey.isBlank()) {
            configured.defaultHeader("Authorization", "Bearer " + apiKey);
        }

        return configured.build();
    }
}
