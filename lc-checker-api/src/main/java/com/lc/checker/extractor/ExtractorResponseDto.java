package com.lc.checker.extractor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Map;

/**
 * Wire-level DTO for the extractor HTTP response, mirroring the frozen
 * {@code extractors/CONTRACT.md v1.0} shape one-to-one. Kept internal to the extractor
 * package so the domain {@link com.lc.checker.model.InvoiceDocument} stays free of
 * transport concerns. Unknown keys are ignored so forward-compatible {@code fields.extras}
 * additions don't break the V1 client.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractorResponseDto(
        String extractor,
        String contractVersion,
        Double confidence,
        Boolean isImageBased,
        Integer pages,
        String rawMarkdown,
        String rawText,
        Map<String, Object> fields,
        Long extractionMs
) {
}
