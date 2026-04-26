package com.lc.checker.stage.check.strategy;

import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.result.CheckResult;
import com.lc.checker.domain.result.CheckTrace;
import com.lc.checker.domain.result.ExpressionTrace;
import com.lc.checker.domain.rule.Rule;
import com.lc.checker.domain.rule.enums.CheckStatus;
import com.lc.checker.domain.rule.enums.CheckType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.infra.fields.FieldPoolRegistry;
import com.lc.checker.infra.observability.LangfuseTags;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.stereotype.Component;

/**
 * Deterministic SpEL evaluator. Rule's {@code expression} runs against
 * {@code lc} + {@code inv}; truthy → PASS, falsy → FAIL, exception →
 * DOUBTS (a broken expression must not crash the whole pipeline).
 *
 * <p>The FAIL description is shaped from the bound-variable snapshot so a
 * reviewer sees concrete values, not a generic "rule X failed" message.
 */
@Component
public class ProgrammaticStrategy implements CheckStrategy {

    private static final Logger log = LoggerFactory.getLogger(ProgrammaticStrategy.class);

    private final SpelBinder binder;
    private final FieldPoolRegistry fieldPool;
    private final Tracer tracer;
    private final ObjectMapper json;

    public ProgrammaticStrategy(SpelBinder binder, FieldPoolRegistry fieldPool,
                                Tracer tracer, ObjectMapper json) {
        this.binder = binder;
        this.fieldPool = fieldPool;
        this.tracer = tracer;
        this.json = json;
    }

    @Override
    public CheckType type() {
        return CheckType.PROGRAMMATIC;
    }

    @Override
    public StrategyOutcome execute(Rule rule, LcDocument lc, InvoiceDocument inv) {
        // Per-rule child span — gives Langfuse a Span (not a Generation, since
        // there's no LLM here) with input = bound variable snapshot and output
        // = verdict + description + expression. Same trace as agent rules, so
        // a session view shows all 20 rule outcomes side-by-side regardless of
        // execution strategy.
        Span span = LangfuseTags.applySession(tracer.nextSpan())
                .name("rule." + rule.id())
                // Deterministic SpEL — Langfuse type "span" (no LLM, no tokens).
                .tag("langfuse.observation.type", "span")
                .tag("rule.id", rule.id())
                .tag("rule.type", "PROGRAMMATIC")
                .tag("rule.ucp_ref", rule.ucpRef() == null ? "" : rule.ucpRef())
                .tag("rule.isbp_ref", rule.isbpRef() == null ? "" : rule.isbpRef())
                .tag("rule.severity_on_fail", String.valueOf(rule.severityOnFail()))
                .start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            StrategyOutcome out = doExecute(rule, lc, inv);
            CheckStatus verdict = out.result().status();
            // Langfuse-recognised attributes — make input + output show up in
            // the UI as first-class fields.
            span.tag("rule.verdict", String.valueOf(verdict));
            Map<String, Object> bound = out.trace() != null && out.trace().expressionTrace() != null
                    ? out.trace().expressionTrace().boundVariables()
                    : Map.of();
            span.tag("langfuse.observation.input", toJson(Map.of(
                    "expression", rule.expression() == null ? "" : rule.expression(),
                    "bound_vars", bound)));
            span.tag("langfuse.observation.output", toJson(Map.of(
                    "verdict", String.valueOf(verdict),
                    "description", out.result().description() == null ? "" : out.result().description())));
            return out;
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private StrategyOutcome doExecute(Rule rule, LcDocument lc, InvoiceDocument inv) {
        long start = System.currentTimeMillis();

        if (rule.expression() == null || rule.expression().isBlank()) {
            return outcome(rule, CheckStatus.DOUBTS,
                    new ExpressionTrace(null, bind(lc, inv), null, "Rule has no expression"),
                    "PROGRAMMATIC rule " + rule.id() + " has no expression in catalog",
                    start);
        }

        Map<String, Object> bound = bind(lc, inv);
        Object result;
        try {
            Expression expr = binder.parser().parseExpression(rule.expression());
            EvaluationContext ctx = binder.forExecution(lc, inv);
            result = expr.getValue(ctx);
        } catch (Exception e) {
            log.warn("PROGRAMMATIC rule {} evaluation failed: {}", rule.id(), e.getMessage());
            return outcome(rule, CheckStatus.DOUBTS,
                    new ExpressionTrace(rule.expression(), bound, null, e.getMessage()),
                    "Expression evaluation failed: " + e.getMessage(),
                    start);
        }

        boolean passed = isTruthy(result);
        CheckStatus status = passed ? CheckStatus.PASS : CheckStatus.FAIL;
        String description = passed
                ? rule.name() + " satisfied"
                : buildDiscrepancyDescription(rule, bound);

        return outcome(rule, status,
                new ExpressionTrace(rule.expression(), bound, result, null),
                description, start);
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    private boolean rulesAgainstLc(Rule rule) {
        return rule.fieldKeys().stream()
                .map(fieldPool::byKey)
                .anyMatch(opt -> opt.isPresent() && opt.get().appliesToLc());
    }

    private static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        return true;
    }

    /** Snapshot of the LC + invoice fields the rule actually reads — sized for the trace. */
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
            m.put("inv.sellerName", inv.sellerName());
            m.put("inv.buyerName", inv.buyerName());
        }
        return m;
    }

    /**
     * Pattern-aware DISCREPANT message inferred from the rule's expression shape.
     * The aim is a concrete sentence with values, not "rule X failed: {map}".
     */
    private String buildDiscrepancyDescription(Rule rule, Map<String, Object> bound) {
        String expr = rule.expression() == null ? "" : rule.expression();
        String outputField = rule.outputField() == null ? rule.id() : rule.outputField();

        // Mandatory presence — rule reads only inv.* and tests non-null/non-blank
        if (!rulesAgainstLc(rule) && expr.contains("isEmpty")) {
            return outputField + " is mandatory on every commercial invoice per "
                    + safeRef(rule) + " but was not found";
        }

        // Currency / string equality
        if (expr.contains("equalsIgnoreCase")) {
            Object inv = firstNonNull(bound, "inv.currency", "inv.lcReference");
            Object lcv = firstNonNull(bound, "lc.currency", "lc.lcNumber");
            return "Invoice " + outputField + " '" + inv + "' does not match LC value '" + lcv + "'";
        }

        // Date compare
        if (expr.contains("isAfter") || expr.contains("isBefore")) {
            return "Invoice date " + bound.get("inv.invoiceDate")
                    + " is after LC expiry " + bound.get("lc.expiryDate");
        }

        // Numeric range — amount within tolerance
        if (expr.contains("compareTo") && expr.contains("multiply")) {
            return "Invoice " + outputField + " " + bound.get("inv.totalAmount")
                    + " is outside the LC tolerance range around " + bound.get("lc.amount");
        }

        // Internal arithmetic (qty * price = total)
        if (expr.contains("inv.quantity") && expr.contains("inv.unitPrice")
                && expr.contains("inv.totalAmount")) {
            return "Invoice arithmetic inconsistent: quantity × unit_price ≠ total_amount";
        }

        return rule.name() + " failed (" + bound + ")";
    }

    private static Object firstNonNull(Map<String, Object> bound, String... keys) {
        for (String k : keys) {
            Object v = bound.get(k);
            if (v != null) return v;
        }
        return null;
    }

    private static String safeRef(Rule rule) {
        if (rule.ucpRef() != null) return rule.ucpRef();
        if (rule.isbpRef() != null) return rule.isbpRef();
        return rule.id();
    }

    private static StrategyOutcome outcome(
            Rule rule, CheckStatus status, ExpressionTrace trace, String description, long start) {
        long duration = System.currentTimeMillis() - start;
        CheckResult result = new CheckResult(
                rule.id(),
                rule.name(),
                CheckType.PROGRAMMATIC,
                rule.businessPhase(),
                status,
                status == CheckStatus.FAIL ? rule.severityOnFail() : null,
                firstFieldOrNull(rule.fieldKeys()),
                null,
                null,
                rule.ucpRef(),
                rule.isbpRef(),
                description
        );
        CheckTrace checkTrace = new CheckTrace(
                rule.id(), CheckType.PROGRAMMATIC, status,
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
