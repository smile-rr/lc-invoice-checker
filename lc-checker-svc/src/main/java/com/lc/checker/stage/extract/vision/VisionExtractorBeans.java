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
 * Spring wiring for up to four vision-LLM invoice extractors.
 *
 * <p>Priority order (first SUCCESS wins): local → cloud → cloud2 → cloud3.
 * Non-selected results are persisted and shown as reference in the UI.
 *
 * <ul>
 *   <li>{@code localLlmVlExtractor} — Ollama on this host; default winner.</li>
 *   <li>{@code cloudLlmVlExtractor} — cloud slot 1; always-on fallback.</li>
 *   <li>{@code cloudLlmVl2Extractor} — cloud slot 2; optional reference.</li>
 *   <li>{@code cloudLlmVl3Extractor} — cloud slot 3; disabled by default (cost guard).</li>
 * </ul>
 *
 * <p>Source names follow the pattern {@code <model>_local}, {@code <model>_cloud},
 * {@code <model>_cloud2}, {@code <model>_cloud3} — the UI uses the suffix to
 * determine display label and markdown-tab visibility.
 */
@Configuration
public class VisionExtractorBeans {

    @Bean(name = "localLlmVlExtractor")
    @ConditionalOnProperty(name = "extractor.local-llm-vl-enabled", havingValue = "true")
    public VisionLlmExtractor localLlmVlExtractor(
            RestClient.Builder restClientBuilder,
            PdfRenderer renderer,
            InvoiceFieldMapper mapper,
            ObjectMapper json,
            PromptBuilder promptBuilder,
            Tracer tracer,
            @Value("${local-llm-vl.base-url:http://localhost:11434/v1}") String baseUrl,
            @Value("${local-llm-vl.api-key:}") String apiKey,
            @Value("${local-llm-vl.model:qwen3-vl:4b-instruct}") String model,
            @Value("${local-llm-vl.render-dpi:200}") int renderDpi,
            @Value("${local-llm-vl.max-pages:10}") int maxPages,
            @Value("${local-llm-vl.max-long-edge-px:2048}") int maxLongEdgePx,
            @Value("${local-llm-vl.timeout-seconds:300}") int timeoutSeconds) {
        VisionExtractorConfig cfg = new VisionExtractorConfig(
                model + "_local", baseUrl, apiKey, model,
                renderDpi, maxPages, maxLongEdgePx, timeoutSeconds);
        return new VisionLlmExtractor(restClientBuilder, cfg, renderer, mapper, json, promptBuilder, tracer);
    }

    @Bean(name = "cloudLlmVlExtractor")
    @ConditionalOnProperty(name = "extractor.cloud-llm-vl-enabled", havingValue = "true", matchIfMissing = true)
    public VisionLlmExtractor cloudLlmVlExtractor(
            RestClient.Builder restClientBuilder,
            PdfRenderer renderer,
            InvoiceFieldMapper mapper,
            ObjectMapper json,
            PromptBuilder promptBuilder,
            Tracer tracer,
            @Value("${cloud-llm-vl.base-url}") String baseUrl,
            @Value("${cloud-llm-vl.api-key:}") String apiKey,
            @Value("${cloud-llm-vl.model}") String model,
            @Value("${cloud-llm-vl.render-dpi:200}") int renderDpi,
            @Value("${cloud-llm-vl.max-pages:10}") int maxPages,
            @Value("${cloud-llm-vl.max-long-edge-px:2048}") int maxLongEdgePx,
            @Value("${cloud-llm-vl.timeout-seconds:120}") int timeoutSeconds) {
        VisionExtractorConfig cfg = new VisionExtractorConfig(
                model + "_cloud", baseUrl, apiKey, model,
                renderDpi, maxPages, maxLongEdgePx, timeoutSeconds);
        return new VisionLlmExtractor(restClientBuilder, cfg, renderer, mapper, json, promptBuilder, tracer);
    }

    @Bean(name = "cloudLlmVl2Extractor")
    @ConditionalOnProperty(name = "extractor.cloud-llm-vl-2-enabled", havingValue = "true")
    public VisionLlmExtractor cloudLlmVl2Extractor(
            RestClient.Builder restClientBuilder,
            PdfRenderer renderer,
            InvoiceFieldMapper mapper,
            ObjectMapper json,
            PromptBuilder promptBuilder,
            Tracer tracer,
            @Value("${cloud-llm-vl-2.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${cloud-llm-vl-2.api-key:}") String apiKey,
            @Value("${cloud-llm-vl-2.model:qwen-vl-plus}") String model,
            @Value("${cloud-llm-vl-2.render-dpi:200}") int renderDpi,
            @Value("${cloud-llm-vl-2.max-pages:10}") int maxPages,
            @Value("${cloud-llm-vl-2.max-long-edge-px:2048}") int maxLongEdgePx,
            @Value("${cloud-llm-vl-2.timeout-seconds:120}") int timeoutSeconds) {
        VisionExtractorConfig cfg = new VisionExtractorConfig(
                model + "_cloud2", baseUrl, apiKey, model,
                renderDpi, maxPages, maxLongEdgePx, timeoutSeconds);
        return new VisionLlmExtractor(restClientBuilder, cfg, renderer, mapper, json, promptBuilder, tracer);
    }

    @Bean(name = "cloudLlmVl3Extractor")
    @ConditionalOnProperty(name = "extractor.cloud-llm-vl-3-enabled", havingValue = "true")
    public VisionLlmExtractor cloudLlmVl3Extractor(
            RestClient.Builder restClientBuilder,
            PdfRenderer renderer,
            InvoiceFieldMapper mapper,
            ObjectMapper json,
            PromptBuilder promptBuilder,
            Tracer tracer,
            @Value("${cloud-llm-vl-3.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${cloud-llm-vl-3.api-key:}") String apiKey,
            @Value("${cloud-llm-vl-3.model:qwen3-vl-flash}") String model,
            @Value("${cloud-llm-vl-3.render-dpi:200}") int renderDpi,
            @Value("${cloud-llm-vl-3.max-pages:10}") int maxPages,
            @Value("${cloud-llm-vl-3.max-long-edge-px:2048}") int maxLongEdgePx,
            @Value("${cloud-llm-vl-3.timeout-seconds:120}") int timeoutSeconds) {
        VisionExtractorConfig cfg = new VisionExtractorConfig(
                model + "_cloud3", baseUrl, apiKey, model,
                renderDpi, maxPages, maxLongEdgePx, timeoutSeconds);
        return new VisionLlmExtractor(restClientBuilder, cfg, renderer, mapper, json, promptBuilder, tracer);
    }
}
