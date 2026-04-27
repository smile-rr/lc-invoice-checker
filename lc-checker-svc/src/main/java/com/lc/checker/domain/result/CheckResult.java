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
        String description,
        /**
         * Deterministic computation the agent committed to, in plain English
         * (e.g. {@code "max_allowed = 50000 × 1.05 = 52500; invoice 55000 > 52500 → ABOVE"}).
         * Mandatory for Tier-3 (AGENT + tool) rules whose toolset includes any
         * Numeric or Date tool. Null for Tier-1 / Tier-2.
         */
        String equationUsed
) {
}
