package com.lc.checker.domain.result;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.rule.enums.BusinessPhase;
import com.lc.checker.domain.rule.enums.CheckStatus;
import com.lc.checker.domain.rule.enums.CheckType;
import com.lc.checker.domain.rule.enums.Severity;
import com.lc.checker.stage.assemble.ReportAssembler;

/**
 * Outcome of a single rule check. {@code CheckResult} is the <em>decision</em>
 * surface consumed by {@code ReportAssembler}; {@code CheckTrace} is the
 * forensic surface consumed by {@code /trace}.
 *
 * <p>{@link #severity} is non-null only when {@link #status} is
 * {@link CheckStatus#FAIL}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CheckResult(
        String ruleId,
        String ruleName,
        CheckType checkType,
        BusinessPhase businessPhase,
        CheckStatus status,
        Severity severity,
        String field,
        String lcValue,
        String presentedValue,
        String ucpRef,
        String isbpRef,
        String description
) {
}
