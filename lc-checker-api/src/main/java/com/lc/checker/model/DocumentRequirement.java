package com.lc.checker.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * One entry of the parsed :46A: "documents required" list produced by the Part-B LLM parser.
 * The {@link #referencesLcNumber} flag is the activation signal for rule INV-007 — if any
 * entry declares that the invoice must carry the LC number, INV-007 is activated and
 * compared against the extractor's {@code invoice.lcReference} value.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentRequirement(
        String docType,
        Integer copies,
        Boolean signed,
        boolean referencesLcNumber,
        List<String> specialRequirements,
        String raw
) {
    public DocumentRequirement {
        specialRequirements = specialRequirements == null ? List.of() : List.copyOf(specialRequirements);
    }
}
