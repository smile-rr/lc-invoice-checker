package com.lc.checker.strategy;

import com.lc.checker.model.CheckResult;
import com.lc.checker.model.CheckTrace;
import com.lc.checker.model.ExpressionTrace;
import com.lc.checker.model.InvoiceDocument;
import com.lc.checker.model.LcDocument;
import com.lc.checker.model.Rule;
import com.lc.checker.model.enums.CheckStatus;
import com.lc.checker.model.enums.CheckType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.stereotype.Component;

/**
 * Strategy for Type-A rules: deterministic SpEL expression with no LLM involvement.
 *
 * <p>Evaluates {@code rule.expression} against a context binding {@code lc} + {@code inv}.
 * A truthy result is {@link CheckStatus#PASS}; falsy is {@link CheckStatus#DISCREPANT}
 * with the rule's declared severity. Evaluation errors become {@link CheckStatus#UNABLE_TO_VERIFY}
 * so a bad expression never crashes the whole pipeline.
 *
 * <p>Every evaluation captures an {@link ExpressionTrace} — the expression text, the
 * bound variables (lc + inv summary), and the final value — so the {@code /trace}
 * endpoint can show a reviewer exactly why the rule passed or failed.
 */
@Component
public class TypeAStrategy implements CheckStrategy {

    private static final Logger log = LoggerFactory.getLogger(TypeAStrategy.class);

    private final SpelBinder binder;

    public TypeAStrategy(SpelBinder binder) {
        this.binder = binder;
    }

    @Override
    public CheckType type() {
        return CheckType.A;
    }

    @Override
    public StrategyOutcome execute(Rule rule, LcDocument lc, InvoiceDocument inv) {
        long start = System.currentTimeMillis();

        if (rule.expression() == null || rule.expression().isBlank()) {
            return outcome(rule, CheckStatus.UNABLE_TO_VERIFY,
                    new ExpressionTrace(null, bind(lc, inv), null, "Rule has no expression"),
                    "Type-A rule " + rule.id() + " has no expression in catalog",
                    start);
        }

        Map<String, Object> bound = bind(lc, inv);
        Object result;
        try {
            Expression expr = binder.parser().parseExpression(rule.expression());
            EvaluationContext ctx = binder.forExecution(lc, inv);
            result = expr.getValue(ctx);
        } catch (Exception e) {
            log.warn("Type-A rule {} evaluation failed: {}", rule.id(), e.getMessage());
            return outcome(rule, CheckStatus.UNABLE_TO_VERIFY,
                    new ExpressionTrace(rule.expression(), bound, null, e.getMessage()),
                    "Expression evaluation failed: " + e.getMessage(),
                    start);
        }

        boolean passed = isTruthy(result);
        CheckStatus status = passed ? CheckStatus.PASS : CheckStatus.DISCREPANT;
        String description = passed
                ? rule.name() + " satisfied"
                : buildDiscrepancyDescription(rule, bound);

        return outcome(rule, status,
                new ExpressionTrace(rule.expression(), bound, result, null),
                description, start);
    }

    // -----------------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------------

    private static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        // Defensive: non-boolean expressions — treat non-null as truthy.
        return true;
    }

    /**
     * Build a minimal bound-variables snapshot for the trace. We record <em>only</em> the
     * field values the rule actually reads (per {@code rule.lcFields} / {@code invoice_fields}).
     * Full documents would balloon the trace; a reviewer needs the inputs to the decision,
     * not the whole record.
     */
    private Map<String, Object> bind(LcDocument lc, InvoiceDocument inv) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (lc != null) {
            m.put("lc.amount", lc.amount());
            m.put("lc.currency", lc.currency());
            m.put("lc.tolerancePlus", lc.tolerancePlus());
            m.put("lc.toleranceMinus", lc.toleranceMinus());
            m.put("lc.lcNumber", lc.lcNumber());
            m.put("lc.expiryDate", lc.expiryDate());
            m.put("lc.presentationDays", lc.presentationDays());
        }
        if (inv != null) {
            m.put("inv.totalAmount", inv.totalAmount());
            m.put("inv.currency", inv.currency());
            m.put("inv.lcReference", inv.lcReference());
            m.put("inv.invoiceDate", inv.invoiceDate());
            m.put("inv.quantity", inv.quantity());
            m.put("inv.unitPrice", inv.unitPrice());
        }
        return m;
    }

    /**
     * Human-readable description for a discrepancy. Uses the first two bound variables
     * the rule reads to give the reviewer an anchor; {@code ReportAssembler} further
     * shapes this into the final JSON for well-known rule ids.
     */
    private static String buildDiscrepancyDescription(Rule rule, Map<String, Object> bound) {
        return "Rule " + rule.id() + " (" + rule.name() + ") failed: " + bound;
    }

    private static StrategyOutcome outcome(
            Rule rule, CheckStatus status, ExpressionTrace trace, String description, long start) {
        long duration = System.currentTimeMillis() - start;
        CheckResult result = new CheckResult(
                rule.id(),
                rule.name(),
                CheckType.A,
                status,
                status == CheckStatus.DISCREPANT ? rule.severityOnFail() : null,
                firstFieldOrNull(rule.invoiceFields()),
                null,   // lcValue populated by ReportAssembler with pretty-printed snapshot
                null,
                rule.ucpRef(),
                rule.isbpRef(),
                description
        );
        CheckTrace checkTrace = new CheckTrace(
                rule.id(), CheckType.A, status,
                trace.boundVariables(),
                trace,
                null,
                duration,
                trace.error());
        return new StrategyOutcome(result, checkTrace);
    }

    private static String firstFieldOrNull(java.util.List<String> fields) {
        return (fields == null || fields.isEmpty()) ? null : fields.get(0);
    }
}
