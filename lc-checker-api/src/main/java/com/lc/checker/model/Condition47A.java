package com.lc.checker.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.model.enums.ConditionTarget;
import com.lc.checker.model.enums.ConditionType;

/**
 * One parsed :47A: condition — the Part-B LLM splits the :47A: free text into independent
 * condition items so Stage 2 DYNAMIC_47A activation can emit one check per INVOICE-targeted
 * condition (see logic-flow.md Stage 1 Part B step 2).
 *
 * <p>V1 populates this list and exposes it in the trace but does not emit dynamic check
 * nodes from it yet — that flip is a Stage-2 activator change, not a model change.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Condition47A(
        String id,
        ConditionType type,
        ConditionTarget target,
        String text,
        String checkableField,
        String expectedValue
) {
}
