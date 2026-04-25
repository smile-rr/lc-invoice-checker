package com.lc.checker.stage.extract.docling;

/**
 * Per-instance configuration for {@link DoclingExtractorClient}.
 * Mirrors {@code VisionExtractorConfig} so both extractor families share the
 * same constructor + bean pattern.
 */
public record DoclingExtractorConfig(String name, String baseUrl, int timeoutSeconds) {}
