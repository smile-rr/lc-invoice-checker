package com.lc.checker.domain.result;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.rule.enums.ActivationTrigger;
import java.util.List;

/**
 * Stage-2 output: for every rule in the catalog, whether it activated and why. This is
 * the single source of truth for "why did INV-019 show up / not show up on this run"
 * and is consumed verbatim by the V2 trace UI.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RuleActivationTrace(
        List<RuleActivation> activations,
        long durationMs
) {

    public RuleActivationTrace {
        activations = activations == null ? List.of() : List.copyOf(activations);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RuleActivation(
            String ruleId,
            ActivationTrigger trigger,
            boolean activated,
            String reason,               // human-readable: "46A references LC number" / "44E absent"
            String evaluatedExpression   // null for UNCONDITIONAL; the SpEL text for LC_STIPULATED
    ) {
    }
}
