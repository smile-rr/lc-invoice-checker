package com.lc.checker.domain.common;

/**
 * Canonical value-type vocabulary for entries in {@code field-pool.yaml}. The
 * registry uses this to decide how to coerce raw extractor / parser strings into
 * Java values when populating {@link FieldEnvelope#fields()}.
 */
public enum FieldType {
    STRING,
    AMOUNT,
    DATE,
    INTEGER,
    ENUM,
    MULTILINE_TEXT,
    DOCUMENT_LIST,
    CURRENCY_CODE,
    /**
     * Array of row-objects, where each column is itself a typed cell.
     * Used for invoice tables (line_items, charges-breakdown). The row schema
     * is declared by {@code FieldDefinition.columns()} — each
     * {@code ColumnDefinition} has its own scoped {@code invoice_aliases} so
     * raw extractor keys per row are resolved row-locally without polluting
     * the top-level alias map.
     *
     * <p>Storage shape in {@link FieldEnvelope#fields}:
     * {@code List<Map<String, Object>>} — each row is a canonical-key map of
     * coerced cell values, same coercion rules as flat fields.
     */
    TABLE
}
