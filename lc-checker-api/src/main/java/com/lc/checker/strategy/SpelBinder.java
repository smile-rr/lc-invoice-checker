package com.lc.checker.strategy;

import com.lc.checker.model.InvoiceDocument;
import com.lc.checker.model.LcDocument;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * Single place that creates a populated SpEL {@link EvaluationContext} for an
 * {@code LcDocument} + {@code InvoiceDocument} pair. Shared by:
 *
 * <ul>
 *   <li>{@code RuleActivator} — evaluates {@code activation_expr} against {@code lc}.</li>
 *   <li>{@code TypeAStrategy} — evaluates {@code expression} against {@code lc} + {@code inv}.</li>
 *   <li>{@code TypeABStrategy} — evaluates {@code pre_gate_expression} before the LLM call.</li>
 * </ul>
 *
 * <p>Expressions reference {@code lc} and {@code inv} as root-object properties (not SpEL
 * {@code #variables}) so catalog.yml expressions read naturally —
 * {@code inv.totalAmount <= lc.amount ...} rather than {@code #inv.totalAmount <= #lc.amount ...}.
 * The {@link Root} record is the evaluation root.
 */
@Component
public class SpelBinder {

    private final SpelExpressionParser parser = new SpelExpressionParser();

    /** Root object exposed to SpEL: {@code lc} and {@code inv} resolve as record components. */
    public record Root(LcDocument lc, InvoiceDocument inv) {
    }

    /** Context for activation-time evaluation; invoice is not yet available. */
    public EvaluationContext forActivation(LcDocument lc) {
        return new StandardEvaluationContext(new Root(lc, null));
    }

    /** Context for rule execution; both documents are bound. */
    public EvaluationContext forExecution(LcDocument lc, InvoiceDocument inv) {
        return new StandardEvaluationContext(new Root(lc, inv));
    }

    public SpelExpressionParser parser() {
        return parser;
    }
}
