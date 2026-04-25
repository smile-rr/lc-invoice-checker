package com.lc.checker.stage.extract.vision;

/**
 * Per-instance configuration for {@link VisionLlmExtractor}. Lets us spin up
 * multiple vision-LLM extractors (remote Qwen Cloud, local Ollama, future
 * providers) from the same class without subclassing — only the URL/model/
 * timeout differ.
 *
 * <p>The {@link #name} is the source identifier surfaced to callers, the
 * extractor-attempt rows in the DB, and the SSE event payload (e.g.
 * {@code "remote_vision"} vs {@code "local_vision"}).
 */
public record VisionExtractorConfig(
        String name,
        String baseUrl,
        String apiKey,
        String model,
        float renderScale,
        int timeoutSeconds
) {
}
