package com.lc.checker.stage.check.spi;

import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.rule.Rule;
import com.lc.checker.stage.check.strategy.CheckStrategy.StrategyOutcome;
import com.lc.checker.stage.check.CheckExecutor;

/**
 * Escape hatch for rules that cannot be expressed as a SpEL expression or a prompt
 * template (e.g. the 21-day presentation rule with calendar math and holiday skip).
 *
 * <p>Beans annotated with {@link RuleImpl} implement this interface and are
 * auto-discovered by {@code CheckExecutor}. If present for a rule id, the SPI path
 * wins over the strategy dispatch.
 *
 * <p>V1 registers ZERO SPI beans — the whole point of the catalog-driven design is
 * that the common case doesn't need Java changes. The class exists so V1.5 can add an
 * exotic rule without touching the executor.
 */
public interface SpiRuleChecker {

    StrategyOutcome check(Rule rule, LcDocument lc, InvoiceDocument inv);
}
