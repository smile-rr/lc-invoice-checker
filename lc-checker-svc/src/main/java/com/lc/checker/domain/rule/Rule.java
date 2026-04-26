package com.lc.checker.domain.rule;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.rule.enums.BusinessPhase;
import com.lc.checker.domain.rule.enums.CheckType;
import com.lc.checker.domain.rule.enums.MissingInvoiceAction;
import com.lc.checker.domain.rule.enums.Severity;
import java.util.List;

/**
 * A single entry from {@code rules/catalog.yml}. Field names mirror the YAML
 * keys exactly; loader deserialization uses
 * {@link com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy}.
 *
 * <p>Two-type model:
 * <ul>
 *   <li>{@link CheckType#PROGRAMMATIC} — {@link #expression} is required (SpEL).</li>
 *   <li>{@link CheckType#AGENT} — {@link #promptTemplate} is required (file under {@code prompts/}).</li>
 * </ul>
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Rule(
        String id,
        String name,
        String description,
        CheckType checkType,
        BusinessPhase businessPhase,
        String ucpRef,
        String isbpRef,
        String ucpExcerpt,
        String isbpExcerpt,
        String ruleReferenceLabel,
        String outputField,
        List<String> lcFields,
        List<String> invoiceFields,
        MissingInvoiceAction missingInvoiceAction,
        Severity severityOnFail,
        String expression,
        String promptTemplate,
        Boolean enabled,
        String disabledReason
) {

    public Rule {
        lcFields = lcFields == null ? List.of() : List.copyOf(lcFields);
        invoiceFields = invoiceFields == null ? List.of() : List.copyOf(invoiceFields);
        enabled = enabled == null ? Boolean.TRUE : enabled;
        businessPhase = businessPhase == null ? BusinessPhase.PROCEDURAL : businessPhase;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
