package com.lc.checker.stage.extract.mineru;

/**
 * Per-instance configuration for {@link MineruExtractorClient}.
 */
public record MineruExtractorConfig(String name, String baseUrl, int timeoutSeconds) {}
