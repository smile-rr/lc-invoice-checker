package com.lc.checker.stage.check.strategy;

import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.result.CheckResult;
import com.lc.checker.domain.result.CheckTrace;
import com.lc.checker.domain.rule.Rule;
import com.lc.checker.domain.rule.enums.CheckType;

/**
 * Common contract for the two check strategies — {@link ProgrammaticStrategy}
 * and {@link AgentStrategy}. {@code CheckExecutor} dispatches on
 * {@link Rule#checkType()}.
 *
 * <p>Each strategy returns both a {@link CheckResult} (decision surface
 * consumed by {@code ReportAssembler}) and a {@link CheckTrace} (forensic
 * surface consumed by {@code /trace}).
 */
public interface CheckStrategy {

    CheckType type();

    StrategyOutcome execute(Rule rule, LcDocument lc, InvoiceDocument inv);

    /** Decision + trace tuple. */
    record StrategyOutcome(CheckResult result, CheckTrace trace) {
    }
}
