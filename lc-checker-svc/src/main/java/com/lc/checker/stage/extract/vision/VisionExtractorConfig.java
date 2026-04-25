package com.lc.checker.stage.extract.vision;

/**
 * Per-instance configuration for {@link VisionLlmExtractor}. Lets us spin up
 * multiple vision-LLM extractors (vendor Qwen Cloud, local MLX/Ollama, future
 * providers) from the same class without subclassing — only the URL/model/
 * caps differ.
 *
 * <p>The {@link #name} is the source identifier surfaced to callers, the
 * extractor-attempt rows in the DB, and the SSE event payload (e.g.
 * {@code "cloud_llm_vl"} vs {@code "local_llm_vl"}).
 *
 * <p>Render knobs:
 * <ul>
 *   <li>{@link #renderDpi} — pages are rasterised at this DPI before being
 *       sent to the vision LLM. 200 DPI is industry-standard OCR resolution
 *       and matches what Qwen-VL was trained on.</li>
 *   <li>{@link #maxPages} — hard cap on pages per extraction; defends against
 *       huge PDFs blowing up local LLM memory.</li>
 *   <li>{@link #maxLongEdgePx} — long-edge clamp applied after rasterising;
 *       vision LLMs internally resize anyway, so sending bigger wastes
 *       bandwidth and tokens.</li>
 * </ul>
 */
public record VisionExtractorConfig(
        String name,
        String baseUrl,
        String apiKey,
        String model,
        int renderDpi,
        int maxPages,
        int maxLongEdgePx,
        int timeoutSeconds
) {
}
