package com.lc.checker.stage.extract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Wire DTO for the extractor's error response body (both 4xx and 5xx):
 *
 * <pre>{@code
 * {"error": "EXTRACTION_FAILED", "message": "docling raised …", "extractor": "docling"}
 * }</pre>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractorErrorBody(String error, String message, String extractor) {
}
