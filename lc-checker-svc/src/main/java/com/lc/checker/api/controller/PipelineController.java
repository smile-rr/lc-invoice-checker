package com.lc.checker.api.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.pipeline.LcCheckPipeline;
import com.lc.checker.stage.extract.InvoiceExtractionOrchestrator;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pipeline configuration introspection — lets the UI render a faithful map of
 * which stages are wired to run and which are off (commented in
 * {@code pipeline/LcCheckPipeline.java} for the current build).
 *
 * <p>Also surfaces the configured invoice-extractor source list so the UI can
 * pre-populate per-source cards (PENDING / RUNNING / SUCCESS / FAILED) the
 * moment the page opens, rather than waiting for the first SSE event.
 */
@RestController
@RequestMapping("/api/v1/pipeline")
public class PipelineController {

    private final LcCheckPipeline pipeline;
    private final InvoiceExtractionOrchestrator extractors;

    public PipelineController(LcCheckPipeline pipeline,
                              InvoiceExtractionOrchestrator extractors) {
        this.pipeline = pipeline;
        this.extractors = extractors;
    }

    @GetMapping
    public ResponseEntity<PipelineConfig> get() {
        return ResponseEntity.ok(new PipelineConfig(
                pipeline.configuredStageNames(),
                extractors.configuredSources()));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PipelineConfig(List<String> configuredStages,
                                  List<String> extractorSources) {
    }
}
