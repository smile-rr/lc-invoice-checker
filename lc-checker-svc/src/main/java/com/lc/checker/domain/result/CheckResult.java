package com.lc.checker.domain.result;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.rule.enums.CheckStatus;
import com.lc.checker.domain.rule.enums.CheckType;
import com.lc.checker.domain.rule.enums.Severity;
import com.lc.checker.stage.assemble.ReportAssembler;

/**
 * Outcome of a single rule check. Merged with its {@link CheckTrace} counterpart in the
 * session store — {@code CheckResult} is the <em>decision</em> surface consumed by
 * {@code ReportAssembler}, {@code CheckTrace} is the forensic surface consumed by
 * {@code /trace}.
 *
 * <p>{@link #severity} is null for non-DISCREPANT outcomes.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CheckResult(
        String ruleId,
        String ruleName,
        CheckType checkType,
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
