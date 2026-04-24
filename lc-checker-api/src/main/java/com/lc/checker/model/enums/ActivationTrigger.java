package com.lc.checker.model.enums;

/**
 * How a rule becomes "active" in a given checking run (logic-flow.md Stage 2).
 *
 * <ul>
 *   <li>{@link #UNCONDITIONAL} — UCP-bottom-line rules that apply regardless of LC wording.</li>
 *   <li>{@link #LC_STIPULATED} — rules whose applicability is gated by an LC-field predicate
 *       expressed as a SpEL {@code activation_expr} in the catalog entry.</li>
 *   <li>{@link #DYNAMIC_47A} — one check node per parsed :47A: condition targeting INVOICE.
 *       V1 parses :47A: but does not yet emit these nodes (deferred to V2).</li>
 * </ul>
 */
public enum ActivationTrigger {
    UNCONDITIONAL,
    LC_STIPULATED,
    DYNAMIC_47A
}
