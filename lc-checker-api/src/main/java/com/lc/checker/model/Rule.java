package com.lc.checker.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.model.enums.ActivationTrigger;
import com.lc.checker.model.enums.CheckType;
import com.lc.checker.model.enums.MissingInvoiceAction;
import com.lc.checker.model.enums.Severity;
import java.util.List;

/**
 * A single entry from {@code rules/catalog.yml} — the authoritative definition of a
 * check rule in V1.
 *
 * <p>This record is the whole reason there is no class-per-rule in V1: adding a rule
 * is a YAML-only (Type A) or YAML + prompt template (Type B / AB) change. The field
 * names here mirror the YAML keys exactly; loader deserialization uses
 * {@link com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy}.
 *
 * <p>Optional fields:
 * <ul>
 *   <li>{@link #activationExpr} — required when {@code trigger=LC_STIPULATED}, null otherwise.</li>
 *   <li>{@link #expression} — SpEL evaluated by TypeAStrategy; required for A and AB.</li>
 *   <li>{@link #preGateExpression} — SpEL pre-gate for AB; null for A and B.</li>
 *   <li>{@link #promptTemplate} — classpath file under {@code prompts/}; required for B and AB.</li>
 * </ul>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Rule(
        String id,
        String name,
        String description,
        CheckType checkType,
        ActivationTrigger trigger,
        String activationExpr,
        String ucpRef,
        String isbpRef,
        List<String> lcFields,
        List<String> invoiceFields,
        MissingInvoiceAction missingInvoiceAction,
        Severity severityOnFail,
        String expression,
        String preGateExpression,
        String promptTemplate,
        String comment,
        Boolean enabled
) {

    public Rule {
        lcFields = lcFields == null ? List.of() : List.copyOf(lcFields);
        invoiceFields = invoiceFields == null ? List.of() : List.copyOf(invoiceFields);
        enabled = enabled == null ? Boolean.TRUE : enabled;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
