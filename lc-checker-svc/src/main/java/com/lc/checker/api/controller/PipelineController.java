package com.lc.checker.api.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.pipeline.LcCheckPipeline;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pipeline configuration introspection — lets the UI render a faithful map of
 * which stages are wired to run and which are off (commented in
 * {@code pipeline/LcCheckPipeline.java} for the current build).
 */
@RestController
@RequestMapping("/api/v1/pipeline")
public class PipelineController {

    private final LcCheckPipeline pipeline;

    public PipelineController(LcCheckPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @GetMapping
    public ResponseEntity<PipelineConfig> get() {
        return ResponseEntity.ok(new PipelineConfig(pipeline.configuredStageNames()));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PipelineConfig(List<String> configuredStages) {
    }
}
