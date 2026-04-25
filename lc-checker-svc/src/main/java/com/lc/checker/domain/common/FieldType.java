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
    CURRENCY_CODE
}
