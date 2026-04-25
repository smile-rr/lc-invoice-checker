package com.lc.checker.stage.extract.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.infra.fields.FieldPoolRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring wiring for vision-LLM invoice extractors. Produces:
 *
 * <ul>
 *   <li>{@code remoteVisionExtractor} — always present, fed from {@code ${vision.*}}.
 *       This is the cloud-hosted vision LLM (Qwen Cloud, OpenAI, Gemini, etc.).</li>
 *   <li>{@code localVisionExtractor} — only created when
 *       {@code extractor.local-vision-enabled=true}, fed from {@code ${local-vision.*}}.
 *       Targets a local Ollama (or compatible) instance on the same host.</li>
 * </ul>
 *
 * <p>Two beans, one class. Each instance carries its own {@link VisionExtractorConfig}
 * (URL, API key, model, timeout, source name) and writes its own row to
 * {@code pipeline_steps} via {@link com.lc.checker.stage.extract.InvoiceExtractor#extractorName()}.
 *
 * <p>The orchestrator picks them up via {@code @Qualifier("remoteVisionExtractor")} and
 * {@code ObjectProvider<VisionLlmExtractor>} — the latter so the orchestrator boots
 * cleanly even when the local bean is absent (disabled).
 */
@Configuration
public class VisionExtractorBeans {

    @Bean(name = "remoteVisionExtractor")
    public VisionLlmExtractor remoteVisionExtractor(
            RestClient.Builder restClientBuilder,
            PdfRenderer renderer,
            VisionInvoiceMapper mapper,
            ObjectMapper json,
            FieldPoolRegistry fieldPool,
            @Value("${vision.base-url}") String baseUrl,
            @Value("${vision.api-key:}") String apiKey,
            @Value("${vision.model}") String model,
            @Value("${vision.render-scale:1.5}") float renderScale,
            @Value("${vision.timeout-seconds:120}") int timeoutSeconds) {
        VisionExtractorConfig cfg = new VisionExtractorConfig(
                "remote_vision", baseUrl, apiKey, model, renderScale, timeoutSeconds);
        return new VisionLlmExtractor(restClientBuilder, cfg, renderer, mapper, json, fieldPool);
    }

    @Bean(name = "localVisionExtractor")
    @ConditionalOnProperty(name = "extractor.local-vision-enabled", havingValue = "true")
    public VisionLlmExtractor localVisionExtractor(
            RestClient.Builder restClientBuilder,
            PdfRenderer renderer,
            VisionInvoiceMapper mapper,
            ObjectMapper json,
            FieldPoolRegistry fieldPool,
            @Value("${local-vision.base-url:http://localhost:11434/v1}") String baseUrl,
            @Value("${local-vision.api-key:}") String apiKey,
            @Value("${local-vision.model:qwen2.5vl}") String model,
            @Value("${local-vision.render-scale:1.5}") float renderScale,
            @Value("${local-vision.timeout-seconds:300}") int timeoutSeconds) {
        VisionExtractorConfig cfg = new VisionExtractorConfig(
                "local_vision", baseUrl, apiKey, model, renderScale, timeoutSeconds);
        return new VisionLlmExtractor(restClientBuilder, cfg, renderer, mapper, json, fieldPool);
    }
}
