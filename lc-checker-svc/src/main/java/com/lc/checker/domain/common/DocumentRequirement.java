package com.lc.checker.domain.common;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Structured representation of a single bullet under MT700 :46A: (Documents
 * Required). Produced by {@code DocumentListParser}. Most ISBP 821 C-section
 * discrepancies hinge on these flags — pre-Slice-1 rules had to grep raw text;
 * after Slice 1 they query a typed list via {@code lc.documentsRequired()}.
 *
 * <p>{@link #rawText} is always preserved verbatim; if the parser cannot
 * categorise the line, {@link #type} = {@link DocType#OTHER} and rules can
 * still fall back to text matching.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentRequirement(
        DocType type,
        Integer originals,        // null if not stated; "TRIPLICATE" → 3, "ONE ORIGINAL" → 1
        Integer copies,           // null if not stated; "DUPLICATE" → 2
        boolean signed,           // "SIGNED" keyword
        boolean fullSet,          // "FULL SET"
        boolean onBoard,          // "ON BOARD"
        String  consignee,        // "MADE OUT TO ORDER OF ..."
        String  freightCondition, // "PREPAID" | "COLLECT" | null
        String  notifyParty,
        String  issuingBody,      // e.g. "SINGAPORE CHAMBER OF COMMERCE"
        String  rawText           // always preserved
) {
}
