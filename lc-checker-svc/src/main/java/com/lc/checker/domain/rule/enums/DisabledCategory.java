package com.lc.checker.domain.rule.enums;

/**
 * Why a rule is registered in the catalog with {@code enabled: false}.
 * Structured form of the prose {@code disabled_reason} so the UI / coverage
 * report can sort and filter parked rules.
 *
 * <ul>
 *   <li>{@link #CONDITIONAL_ON_DOC} — needs another document type that V1
 *       does not accept (B/L, insurance policy, certificate of origin).
 *       Will activate when the API ingests that document.</li>
 *   <li>{@link #MULTI_INVOICE_DEFERRED} — V1 is single-invoice; will
 *       activate when multi-invoice mode lands.</li>
 *   <li>{@link #NOT_APPLICABLE_V1} — V1 input model does not surface the
 *       concept (e.g. original/copy distinction).</li>
 *   <li>{@link #BANK_OPTIONAL} — bank-specific policy, opt-in per
 *       deployment.</li>
 * </ul>
 */
public enum DisabledCategory {
    CONDITIONAL_ON_DOC,
    MULTI_INVOICE_DEFERRED,
    NOT_APPLICABLE_V1,
    BANK_OPTIONAL
}
