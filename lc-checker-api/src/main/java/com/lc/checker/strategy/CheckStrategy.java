package com.lc.checker.strategy;

import com.lc.checker.model.CheckResult;
import com.lc.checker.model.CheckTrace;
import com.lc.checker.model.InvoiceDocument;
import com.lc.checker.model.LcDocument;
import com.lc.checker.model.Rule;
import com.lc.checker.model.enums.CheckType;

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
