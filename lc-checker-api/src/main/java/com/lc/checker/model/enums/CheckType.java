package com.lc.checker.model.enums;

/**
 * Dispatch discriminator for the three check strategies defined in the plan (§ Check Execution).
 *
 * <ul>
 *   <li>{@link #A} — pure deterministic code / SpEL expression, no LLM.</li>
 *   <li>{@link #B} — semantic check executed via prompt template + ChatClient.</li>
 *   <li>{@link #AB} — deterministic pre-gate followed by semantic confirmation.</li>
 *   <li>{@link #SPI} — escape hatch: a {@code @RuleImpl(id)} bean handles this rule in Java.
 *       V1 uses zero SPI rules; the value exists so the catalog loader and dispatcher
 *       do not need to change when the first exotic rule arrives.</li>
 * </ul>
 */
public enum CheckType {
    A,
    B,
    AB,
    SPI
}
