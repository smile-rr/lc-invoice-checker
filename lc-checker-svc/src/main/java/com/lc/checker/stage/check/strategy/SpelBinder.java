package com.lc.checker.stage.check.strategy;

import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.lc.LcDocument;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import com.lc.checker.stage.activate.RuleActivator;

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
 * <p>Two binding flavours are exposed at once:
 * <ul>
 *   <li><b>Legacy (typed-record properties)</b> — {@code lc.amount}, {@code lc.lcNumber},
 *       {@code inv.totalAmount}. Existing rules in {@code catalog.yml} keep working unchanged.</li>
 *   <li><b>Generic (registry-driven map)</b> — {@code #fields['credit_amount']},
 *       {@code #invoiceFields['total_amount']}. Recommended for new rules and any rule
 *       that compares the same canonical concept across both sides.</li>
 * </ul>
 */
@Component
public class SpelBinder {

    private final SpelExpressionParser parser = new SpelExpressionParser();

    /** Root object exposed to SpEL: {@code lc} and {@code inv} resolve as record components. */
    public record Root(LcDocument lc, InvoiceDocument inv) {
    }

    /** Context for activation-time evaluation; invoice is not yet available. */
    public EvaluationContext forActivation(LcDocument lc) {
        StandardEvaluationContext ctx = new StandardEvaluationContext(new Root(lc, null));
        bindGenericVariables(ctx, lc, null);
        return ctx;
    }

    /** Context for rule execution; both documents are bound. */
    public EvaluationContext forExecution(LcDocument lc, InvoiceDocument inv) {
        StandardEvaluationContext ctx = new StandardEvaluationContext(new Root(lc, inv));
        bindGenericVariables(ctx, lc, inv);
        return ctx;
    }

    private void bindGenericVariables(StandardEvaluationContext ctx, LcDocument lc, InvoiceDocument inv) {
        if (lc != null && lc.envelope() != null) {
            ctx.setVariable("fields", lc.envelope().fields());
        }
        if (inv != null && inv.envelope() != null) {
            ctx.setVariable("invoiceFields", inv.envelope().fields());
        } else {
            ctx.setVariable("invoiceFields", java.util.Map.of());
        }
    }

    public SpelExpressionParser parser() {
        return parser;
    }
}
