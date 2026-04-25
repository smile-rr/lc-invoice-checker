package com.lc.checker.domain.common;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic map-based view of a parsed document. The contract between
 * parser/extractor → engine → API → UI: any document type (LC, invoice, future
 * doc types) projects into this same shape.
 *
 * <ul>
 *   <li>{@link #fields} — values keyed by canonical names from {@code field-pool.yaml}.
 *       The map is the single source of truth for field-level data; types are coerced
 *       per {@link FieldType} at the registry boundary.</li>
 *   <li>{@link #extras} — anything the source produced that doesn't match a registered
 *       canonical key or alias. Preserved verbatim so nothing is lost.</li>
 *   <li>{@link #rawSource} — original tag/key → raw string text, for full traceability.</li>
 *   <li>{@link #warnings} — non-fatal notes about parsing / extraction.</li>
 * </ul>
 *
 * <p>Adding a new field is a YAML edit. No Java type binds to specific field names —
 * including this record.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FieldEnvelope(
        String docType,                        // "LC" | "INVOICE"
        Map<String, Object> fields,
        Map<String, Object> extras,
        Map<String, String> rawSource,
        List<ParseWarning> warnings
) {

    public FieldEnvelope {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        extras = extras == null ? Map.of() : Map.copyOf(extras);
        rawSource = rawSource == null ? Map.of() : Map.copyOf(rawSource);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** Convenience for stage-internal builders. */
    public static FieldEnvelope empty(String docType) {
        return new FieldEnvelope(docType, new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>(), List.of());
    }
}
