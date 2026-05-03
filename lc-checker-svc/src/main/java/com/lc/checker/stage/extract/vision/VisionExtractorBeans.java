package com.lc.checker.stage.extract.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.stage.extract.InvoiceFieldMapper;
import com.lc.checker.stage.extract.PromptBuilder;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring wiring for up to four vision-LLM invoice extractors (slots 1–4).
 *
 * <p>All slots share identical config structure — the only difference is the
 * provider URL and whether an api-key is needed (local Ollama = no key;
 * cloud providers = set {@code VISION_N_API_KEY}).
 *
 * <p>Priority order (first SUCCESS wins): slot 1 → 2 → 3 → 4.
 * Non-selected results are persisted and shown as reference in the UI.
 *
 * <p>Source names follow the pattern {@code <model>_<slot>}
 * (e.g. {@code qwen3-vl:4b-instruct_1}, {@code qwen3.6-plus_2}).
 * Enable/disable per slot via {@code vision-llm.slot-N.enabled} /
 * {@code VISION_N_ENABLED} env var.
 */
@Configuration
public class VisionExtractorBeans {

    @Bean(name = "visionSlot1")
    @ConditionalOnProperty(name = "vision-llm.slot-1.enabled", havingValue = "true", matchIfMissing = true)
    public VisionLlmExtractor visionSlot1(
            RestClient.Builder restClientBuilder,
            PdfRenderer renderer,
            InvoiceFieldMapper mapper,
            ObjectMapper json,
            PromptBuilder promptBuilder,
            Tracer tracer,
            @Value("${vision-llm.slot-1.base-url:http://localhost:11434/v1}") String baseUrl,
            @Value("${vision-llm.slot-1.api-key:}") String apiKey,
            @Value("${vision-llm.slot-1.model:qwen3-vl:4b-instruct}") String model,
            @Value("${vision-llm.slot-1.render-dpi:200}") int renderDpi,
            @Value("${vision-llm.slot-1.max-pages:10}") int maxPages,
            @Value("${vision-llm.slot-1.max-long-edge-px:2048}") int maxLongEdgePx,
            @Value("${vision-llm.slot-1.timeout-seconds:300}") int timeoutSeconds) {
        VisionExtractorConfig cfg = new VisionExtractorConfig(
                model + "_1", baseUrl, apiKey, model,
                renderDpi, maxPages, maxLongEdgePx, timeoutSeconds);
        return new VisionLlmExtractor(restClientBuilder, cfg, renderer, mapper, json, promptBuilder, tracer);
    }

    @Bean(name = "visionSlot2")
    @ConditionalOnProperty(name = "vision-llm.slot-2.enabled", havingValue = "true", matchIfMissing = true)
    public VisionLlmExtractor visionSlot2(
            RestClient.Builder restClientBuilder,
            PdfRenderer renderer,
            InvoiceFieldMapper mapper,
            ObjectMapper json,
            PromptBuilder promptBuilder,
            Tracer tracer,
            @Value("${vision-llm.slot-2.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${vision-llm.slot-2.api-key:}") String apiKey,
            @Value("${vision-llm.slot-2.model:qwen3.6-plus-2026-04-02}") String model,
            @Value("${vision-llm.slot-2.render-dpi:200}") int renderDpi,
            @Value("${vision-llm.slot-2.max-pages:10}") int maxPages,
            @Value("${vision-llm.slot-2.max-long-edge-px:2048}") int maxLongEdgePx,
            @Value("${vision-llm.slot-2.timeout-seconds:120}") int timeoutSeconds) {
        VisionExtractorConfig cfg = new VisionExtractorConfig(
                model + "_2", baseUrl, apiKey, model,
                renderDpi, maxPages, maxLongEdgePx, timeoutSeconds);
        return new VisionLlmExtractor(restClientBuilder, cfg, renderer, mapper, json, promptBuilder, tracer);
    }

    @Bean(name = "visionSlot3")
    @ConditionalOnProperty(name = "vision-llm.slot-3.enabled", havingValue = "true")
    public VisionLlmExtractor visionSlot3(
            RestClient.Builder restClientBuilder,
            PdfRenderer renderer,
            InvoiceFieldMapper mapper,
            ObjectMapper json,
            PromptBuilder promptBuilder,
            Tracer tracer,
            @Value("${vision-llm.slot-3.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${vision-llm.slot-3.api-key:}") String apiKey,
            @Value("${vision-llm.slot-3.model:qwen3.6-flash}") String model,
            @Value("${vision-llm.slot-3.render-dpi:200}") int renderDpi,
            @Value("${vision-llm.slot-3.max-pages:10}") int maxPages,
            @Value("${vision-llm.slot-3.max-long-edge-px:2048}") int maxLongEdgePx,
            @Value("${vision-llm.slot-3.timeout-seconds:120}") int timeoutSeconds) {
        VisionExtractorConfig cfg = new VisionExtractorConfig(
                model + "_3", baseUrl, apiKey, model,
                renderDpi, maxPages, maxLongEdgePx, timeoutSeconds);
        return new VisionLlmExtractor(restClientBuilder, cfg, renderer, mapper, json, promptBuilder, tracer);
    }

    @Bean(name = "visionSlot4")
    @ConditionalOnProperty(name = "vision-llm.slot-4.enabled", havingValue = "true")
    public VisionLlmExtractor visionSlot4(
            RestClient.Builder restClientBuilder,
            PdfRenderer renderer,
            InvoiceFieldMapper mapper,
            ObjectMapper json,
            PromptBuilder promptBuilder,
            Tracer tracer,
            @Value("${vision-llm.slot-4.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${vision-llm.slot-4.api-key:}") String apiKey,
            @Value("${vision-llm.slot-4.model:qwen3.6-27b}") String model,
            @Value("${vision-llm.slot-4.render-dpi:200}") int renderDpi,
            @Value("${vision-llm.slot-4.max-pages:10}") int maxPages,
            @Value("${vision-llm.slot-4.max-long-edge-px:2048}") int maxLongEdgePx,
            @Value("${vision-llm.slot-4.timeout-seconds:120}") int timeoutSeconds) {
        VisionExtractorConfig cfg = new VisionExtractorConfig(
                model + "_4", baseUrl, apiKey, model,
                renderDpi, maxPages, maxLongEdgePx, timeoutSeconds);
        return new VisionLlmExtractor(restClientBuilder, cfg, renderer, mapper, json, promptBuilder, tracer);
    }
}
