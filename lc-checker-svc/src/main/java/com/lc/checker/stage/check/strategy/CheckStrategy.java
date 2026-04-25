package com.lc.checker.stage.check.strategy;

import com.lc.checker.domain.result.CheckResult;
import com.lc.checker.domain.result.CheckTrace;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.rule.Rule;
import com.lc.checker.domain.rule.enums.CheckType;
import com.lc.checker.stage.check.CheckExecutor;

/**
 * Common contract for all check strategies (Type A / B / AB). The dispatcher in
 * {@code CheckExecutor} looks up by {@link #type()} and delegates.
 *
 * <p>Every strategy returns both a {@link CheckResult} (decision surface) and a
 * {@link CheckTrace} (forensic surface) — the executor stitches them together into
 * the session record.
 */
public interface CheckStrategy {

    CheckType type();

    StrategyOutcome execute(Rule rule, LcDocument lc, InvoiceDocument inv);

    /** Two-field tuple: decision + trace. Records everywhere, no builders. */
    record StrategyOutcome(CheckResult result, CheckTrace trace) {
    }
}
