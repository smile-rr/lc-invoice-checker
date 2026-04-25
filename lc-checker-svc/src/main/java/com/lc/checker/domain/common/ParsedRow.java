package com.lc.checker.domain.common;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Map;

/**
 * Display-ready row of a parsed document, computed by the backend so the UI can
 * render every pane without per-tag grouping, primary-field selection, or value
 * formatting logic of its own.
 *
 * <p>One {@code ParsedRow} per unique source-tag (or envelope block). When a
 * single tag drives multiple canonical fields (e.g. {@code :45A:} →
 * {@code goods_description} + {@code incoterms}), the "primary" field becomes
 * the row's {@link #displayValue} and the rest become {@link #sublines}.
 *
 * <p>{@link #displayValue} is already formatted: MULTILINE_TEXT preserves its
 * newlines (no separator collapse), AMOUNT is locale-formatted, DATE is ISO,
 * DOCUMENT_LIST falls back to the raw source text. The frontend just renders.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ParsedRow(
        String tag,                  // "20", "45A", "block1", "block3:108" — null for synthetic rows
        String group,                // "header" | "amount" | "parties" | "shipment" | "documents" | ...
        String label,                // primary field's name_en (e.g. "Goods Description")
        String displayValue,         // formatted, ready to render verbatim
        List<Subline> sublines,      // derived/secondary fields under same tag
        Map<String, Object> meta,    // optional extras (parsed_count, enum_values, raw_value, ...)
        String sortKey               // for "sort by tag" UI mode — block* sorts before body tags
) {

    public ParsedRow {
        sublines = sublines == null ? List.of() : List.copyOf(sublines);
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Subline(String label, String value) {
    }
}
