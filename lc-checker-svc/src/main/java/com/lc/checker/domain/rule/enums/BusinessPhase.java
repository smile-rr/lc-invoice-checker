package com.lc.checker.domain.rule.enums;

/**
 * Business-domain grouping for rules — how a real trade-finance officer mentally
 * walks an LC presentation. Drives the per-phase event stream and the UI's
 * Compliance Check panel; orthogonal to {@link CheckType} (which is the *technical*
 * dispatch — A/B/AB/SPI).
 *
 * <p>Phase order matches the banker's checking sheet:
 * <ol>
 *   <li>{@link #ACTIVATION} — read :46A:/:47A:/:39A:/:39B: to gate the checklist (no rule rows; phase event only).</li>
 *   <li>{@link #PARTIES}    — who issued / who pays / countries match.</li>
 *   <li>{@link #MONEY}      — currency, amount, unit price, charges, math.</li>
 *   <li>{@link #GOODS}      — description, quantity, country of origin, packing.</li>
 *   <li>{@link #LOGISTICS}  — Incoterm and ports.</li>
 *   <li>{@link #PROCEDURAL} — dates, signature, LC# reference, presentation period.</li>
 *   <li>{@link #HOLISTIC}   — Layer-3 agent sweep (Art 14(d) cross-document non-contradiction).</li>
 * </ol>
 *
 * <p>Rules without a {@code business_phase} in the catalog default to {@link #PROCEDURAL} —
 * a safe bucket so the rule still runs but flags itself as needing categorisation.
 */
public enum BusinessPhase {
    ACTIVATION,
    PARTIES,
    MONEY,
    GOODS,
    LOGISTICS,
    PROCEDURAL,
    HOLISTIC
}
