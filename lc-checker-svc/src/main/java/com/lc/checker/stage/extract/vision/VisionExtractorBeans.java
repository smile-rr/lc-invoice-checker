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
 * Spring wiring for the two vision-LLM invoice extractors. Naming convention:
 * {@code <location>_llm_vl} — location prefix (cloud / local) + role (llm)
 * + modality (vl = vision-language). Mirrors the {@code make llm} /
 * {@code make llm-vl} pattern in the Makefile.
 *
 * <ul>
 *   <li>{@code cloudLlmVlExtractor} — always present, fed from
 *       {@code ${cloud-llm-vl.*}}. The hosted vision LLM (Qwen Cloud,
 *       OpenAI, Gemini, etc.).</li>
 *   <li>{@code localLlmVlExtractor} — only created when
 *       {@code extractor.local-llm-vl-enabled=true}, fed from
 *       {@code ${local-llm-vl.*}}. Targets a local Ollama or MLX server on
 *       the same host.</li>
 * </ul>
 *
 * <p>Two beans, one class. Each instance carries its own
 * {@link VisionExtractorConfig} (URL, API key, model, timeout, source name)
 * and writes its own row to {@code pipeline_steps} via
 * {@link VisionLlmExtractor#extractorName()}.
 *
 * <p>The orchestrator picks them up via {@code @Qualifier("cloudLlmVlExtractor")}
 * and {@code ObjectProvider<VisionLlmExtractor>} (the latter so the
 * orchestrator boots cleanly even when the local bean is absent).
 *
 * <p>The two LLMs run in parallel, not as fall-back — each fires its own
 * {@code CompletableFuture} in {@code InvoiceExtractionOrchestrator}. Both
 * results are persisted; selection picks the first SUCCESS in chain priority
 * order ({@code local} first), so {@code local_llm_vl} wins by default and
 * {@code cloud_llm_vl} is shown alongside for comparison.
 */
@Configuration
public class VisionExtractorBeans {

    @Bean(name = "cloudLlmVlExtractor")
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
            @Value("${local-llm-vl.model:qwen2.5vl}") String model,
            @Value("${local-llm-vl.render-dpi:200}") int renderDpi,
            @Value("${local-llm-vl.max-pages:10}") int maxPages,
            @Value("${local-llm-vl.max-long-edge-px:2048}") int maxLongEdgePx,
            @Value("${local-llm-vl.timeout-seconds:300}") int timeoutSeconds) {
        VisionExtractorConfig cfg = new VisionExtractorConfig(
                model + "_local", baseUrl, apiKey, model,
                renderDpi, maxPages, maxLongEdgePx, timeoutSeconds);
        return new VisionLlmExtractor(restClientBuilder, cfg, renderer, mapper, json, promptBuilder, tracer);
    }
}
