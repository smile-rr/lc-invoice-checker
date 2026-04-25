package com.lc.checker.domain.common;

/**
 * Vocabulary of sub-field parsers referenced by {@code lc-tag-mapping.yaml}.
 * The Mt700 field-mapper dispatches tag values to a specific parser by this
 * value. For tags Prowide already typifies (32B, 31D, 39A, 44C, 48), the
 * parser dispatches into Prowide directly. For the structural cases Prowide
 * doesn't typify, a class under {@code stage/parse/subfield/} handles it.
 */
public enum ParserType {
    SIMPLE_STRING,
    AMOUNT_WITH_CURRENCY,
    DATE_YYMMDD,
    DATE_PLUS_TEXT,
    SLASH_SEPARATED_INT,
    INT_BEFORE_SLASH,
    MULTILINE_FIRST_LINE,
    MULTILINE_FULL,
    ENUM_NORMALIZED,
    BIC,
    DOCUMENT_LIST,
    INCOTERMS_EXTRACT
}
