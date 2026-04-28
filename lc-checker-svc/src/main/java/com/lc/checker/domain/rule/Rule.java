package com.lc.checker.domain.rule;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.rule.enums.BusinessPhase;
import com.lc.checker.domain.rule.enums.CheckType;
import com.lc.checker.domain.rule.enums.DisabledCategory;
import com.lc.checker.domain.rule.enums.MissingInvoiceAction;
import com.lc.checker.domain.rule.enums.Severity;
import java.util.List;

/**
 * A single entry from {@code rules/catalog.yml}. Field names mirror the YAML
 * keys exactly; loader deserialization uses
 * {@link com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy}.
 *
 * <p>Two-type model with three effective tiers:
 * <ul>
 *   <li>{@link CheckType#PROGRAMMATIC} — {@link #expression} is required (SpEL). Tier 1.</li>
 *   <li>{@link CheckType#AGENT} with empty {@link #tools} — pure semantic LLM call. Tier 2.</li>
 *   <li>{@link CheckType#AGENT} with non-empty {@link #tools} — LLM agent with Spring AI
 *       tool callbacks. Tier 3. Tool names resolve via {@code ToolRegistry}.</li>
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
        String invoiceField,
        List<String> fieldKeys,
        MissingInvoiceAction missingInvoiceAction,
        Severity severityOnFail,
        String expression,
        String promptTemplate,
        List<String> tools,
        Integer maxToolCalls,
        Integer agentTimeoutSeconds,
        Boolean enabled,
        DisabledCategory disabledCategory,
        String disabledReason
) {

    public Rule {
        fieldKeys = fieldKeys == null ? List.of() : List.copyOf(fieldKeys);
        tools = tools == null ? List.of() : List.copyOf(tools);
        enabled = enabled == null ? Boolean.TRUE : enabled;
        businessPhase = businessPhase == null ? BusinessPhase.PROCEDURAL : businessPhase;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** Tier 3 = AGENT with at least one declared tool. */
    public boolean usesTools() {
        return checkType == CheckType.AGENT && !tools.isEmpty();
    }
}
