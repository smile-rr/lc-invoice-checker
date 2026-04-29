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
import com.lc.checker.infra.fields.FieldDefinition;
import com.lc.checker.infra.fields.FieldPoolRegistry;
import com.lc.checker.infra.fields.TagMappingRegistry;
import com.lc.checker.infra.observability.LangfuseTags;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final TagMappingRegistry tagMappings;
    private final Tracer tracer;
    private final ObjectMapper json;

    public ProgrammaticStrategy(SpelBinder binder, FieldPoolRegistry fieldPool,
                                TagMappingRegistry tagMappings,
                                Tracer tracer, ObjectMapper json) {
        this.binder = binder;
        this.fieldPool = fieldPool;
        this.tagMappings = tagMappings;
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

        // Read once: the actual values (from canonical envelopes) the rule
        // would have inspected. These are what we display alongside the
        // verdict, regardless of which strategy branch we land in.
        ValuePair vp = extractValuePair(rule, lc, inv);

        if (rule.expression() == null || rule.expression().isBlank()) {
            return outcome(rule, CheckStatus.DOUBTS, vp,
                    new ExpressionTrace(null, bind(lc, inv), null, "Rule has no expression"),
                    "PROGRAMMATIC rule " + rule.id() + " has no expression in catalog",
                    start);
        }

        // Domain-aware short-circuit:
        //   - LC blank+optional AND invoice blank → DOUBTS. A configured rule
        //     with no observable inputs cannot be ruled out; NOT_REQUIRED
        //     would require a positive observation in the LC, which we
        //     don't have here. Defer to a human reviewer.
        //   - LC blank+optional AND invoice has data → NOT_REQUIRED. The LC
        //     is silent on the precondition, so the rule does not apply
        //     even though the invoice happens to mention the field.
        //   - LC mandatory tag missing → fall through to regular evaluation
        //     so the failure surfaces loudly (InputValidator should have
        //     caught it, but we don't NOT_REQUIRED away a mandatory miss).
        if (rule.fieldKeys() != null && !rule.fieldKeys().isEmpty()
                && allLcFieldsBlankAndOptional(rule, lc)) {
            boolean invoiceBlank = allInvoiceFieldsBlank(rule, inv);
            CheckStatus shortCircuit = invoiceBlank
                    ? CheckStatus.DOUBTS
                    : CheckStatus.NOT_REQUIRED;
            String desc = invoiceBlank
                    ? "Neither LC nor invoice carries " + describeFields(rule)
                            + " — cannot verify, deferring to reviewer"
                    : "LC does not specify " + describeFields(rule)
                            + " — rule does not apply";
            return outcome(rule, shortCircuit, vp,
                    new ExpressionTrace(rule.expression(), bind(lc, inv), null, null),
                    desc, start);
        }

        Map<String, Object> bound = bind(lc, inv);
        Object result;
        try {
            Expression expr = binder.parser().parseExpression(rule.expression());
            EvaluationContext ctx = binder.forExecution(lc, inv);
            result = expr.getValue(ctx);
        } catch (Exception e) {
            log.warn("PROGRAMMATIC rule {} evaluation failed: {}", rule.id(), e.getMessage());
            // Bare-arithmetic rules (e.g. UCP-18b-math) throw NPE when an
            // input is null. Map the failure by what's actually observable:
            //   - both LC-side and invoice-side blank → DOUBTS (cannot
            //     verify; NOT_REQUIRED needs a positive LC observation)
            //   - only invoice blank, LC has data → NOT_REQUIRED (LC says
            //     something but invoice doesn't reflect it; rule
            //     intentionally non-applicable on this drawing)
            //   - some inputs present but expression still threw → fall
            //     back to per-rule missing_invoice_action.
            boolean invoiceBlank = allInvoiceFieldsBlank(rule, inv);
            boolean lcBlank = allLcFieldsBlankAndOptional(rule, lc);
            CheckStatus fallbackStatus;
            String fallbackDesc;
            if (invoiceBlank && lcBlank) {
                fallbackStatus = CheckStatus.DOUBTS;
                fallbackDesc = "Neither LC nor invoice carries " + describeFields(rule)
                        + " — cannot verify, deferring to reviewer";
            } else if (invoiceBlank) {
                fallbackStatus = CheckStatus.NOT_REQUIRED;
                fallbackDesc = "Invoice does not provide " + describeFields(rule)
                        + " — rule does not apply";
            } else {
                fallbackStatus = mapMissingAction(rule);
                // Don't leak raw exception text into the verdict description —
                // it's confusing to a reviewer and exposes Java internals.
                String outField = rule.outputField() == null ? rule.id() : rule.outputField();
                fallbackDesc = "Cannot verify " + outField + " — required "
                        + describeFields(rule) + " is missing or invalid";
                log.debug("Rule {} expression error suppressed from UI: {}", rule.id(), e.toString());
            }
            return outcome(rule, fallbackStatus, vp,
                    new ExpressionTrace(rule.expression(), bound, null, e.getMessage()),
                    fallbackDesc, start);
        }

        boolean passed = isTruthy(result);
        CheckStatus status = passed ? CheckStatus.PASS : CheckStatus.FAIL;
        String description = passed
                ? rule.name() + " satisfied"
                : buildDiscrepancyDescription(rule, bound);

        return outcome(rule, status, vp,
                new ExpressionTrace(rule.expression(), bound, result, null),
                description, start);
    }

    /**
     * The {@code (lc_value, presented_value)} pair surfaced to the UI for
     * this rule. Pulled from the canonical envelopes keyed by the rule's
     * declared {@code field_keys}, so the displayed pair is always exactly
     * what the rule actually inspected.
     */
    private record ValuePair(String lcValue, String invoiceValue) {}

    private ValuePair extractValuePair(Rule rule, LcDocument lc, InvoiceDocument inv) {
        if (rule.fieldKeys() == null || rule.fieldKeys().isEmpty()) {
            return new ValuePair(null, null);
        }
        List<String> lcVals = new ArrayList<>();
        List<String> invVals = new ArrayList<>();
        Map<String, Object> lcFields = lc != null && lc.envelope() != null
                ? lc.envelope().fields() : Map.of();
        Map<String, Object> invFields = inv != null && inv.envelope() != null
                ? inv.envelope().fields() : Map.of();
        for (String key : rule.fieldKeys()) {
            FieldDefinition def = fieldPool.byKey(key).orElse(null);
            if (def == null) continue;
            if (def.appliesToLc()) {
                Object v = lcFields.get(key);
                if (v != null && !String.valueOf(v).isBlank()) {
                    lcVals.add(formatLabelled(key, v));
                }
            }
            if (def.appliesToInvoice()) {
                Object v = invFields.get(key);
                if (v != null && !String.valueOf(v).isBlank()) {
                    invVals.add(formatLabelled(key, v));
                }
            }
        }
        return new ValuePair(
                lcVals.isEmpty() ? null : String.join("\n", lcVals),
                invVals.isEmpty() ? null : String.join("\n", invVals));
    }

    private static String formatLabelled(String key, Object value) {
        return key + ": " + value;
    }

    /**
     * True when every LC field referenced by this rule is null/blank AND
     * none of those fields' MT700 source tags are mandatory in
     * {@link TagMappingRegistry}. In that case the LC is silent on what the
     * rule checks, which is a NOT_REQUIRED outcome rather than DOUBTS.
     */
    private boolean allLcFieldsBlankAndOptional(Rule rule, LcDocument lc) {
        if (lc == null || lc.envelope() == null) return false;
        Map<String, Object> lcFields = lc.envelope().fields();
        boolean sawLcField = false;
        for (String key : rule.fieldKeys()) {
            FieldDefinition def = fieldPool.byKey(key).orElse(null);
            if (def == null || !def.appliesToLc()) continue;
            sawLcField = true;
            Object v = lcFields.get(key);
            if (v != null && !String.valueOf(v).isBlank()) return false;   // some field present
            // Field absent — is it mandatory at the LC tag level? If yes,
            // treat as a real check failure (don't NOT_REQUIRED away a
            // mandatory miss).
            if (anySourceTagMandatory(def)) return false;
        }
        return sawLcField;
    }

    private boolean allInvoiceFieldsBlank(Rule rule, InvoiceDocument inv) {
        if (inv == null || inv.envelope() == null) return false;
        Map<String, Object> invFields = inv.envelope().fields();
        boolean sawInvField = false;
        for (String key : rule.fieldKeys()) {
            FieldDefinition def = fieldPool.byKey(key).orElse(null);
            if (def == null || !def.appliesToInvoice()) continue;
            sawInvField = true;
            Object v = invFields.get(key);
            if (v != null && !String.valueOf(v).isBlank()) return false;
        }
        return sawInvField;
    }

    private boolean anySourceTagMandatory(FieldDefinition def) {
        if (def.sourceTags() == null) return false;
        for (String tag : def.sourceTags()) {
            if (tagMappings.mandatoryTags().contains(tag)) return true;
        }
        return false;
    }

    private String describeFields(Rule rule) {
        return rule.fieldKeys() == null || rule.fieldKeys().isEmpty()
                ? "the required field"
                : String.join(", ", rule.fieldKeys());
    }

    /** Map the catalog's {@link MissingInvoiceAction} to the CheckStatus the
     *  UI consumes. Default to DOUBTS so unspecified rules don't accidentally
     *  fail. */
    private static CheckStatus mapMissingAction(Rule rule) {
        var ma = rule.missingInvoiceAction();
        if (ma == null) return CheckStatus.DOUBTS;
        return switch (ma) {
            case FAIL   -> CheckStatus.FAIL;
            case DOUBTS -> CheckStatus.DOUBTS;
        };
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
            Object invDate = bound.get("inv.invoiceDate");
            Object lcExpiry = bound.get("lc.expiryDate");
            if (invDate == null || lcExpiry == null) {
                String missing = invDate == null && lcExpiry == null
                        ? "invoice date and LC expiry date"
                        : invDate == null ? "invoice date" : "LC expiry date";
                return "Cannot verify " + outputField + " — " + missing + " is missing";
            }
            return "Invoice date " + invDate + " is after LC expiry " + lcExpiry;
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
            Rule rule, CheckStatus status, ValuePair vp,
            ExpressionTrace trace, String description, long start) {
        long duration = System.currentTimeMillis() - start;
        CheckResult result = new CheckResult(
                rule.id(),
                rule.name(),
                CheckType.PROGRAMMATIC,
                rule.businessPhase(),
                status,
                status == CheckStatus.FAIL ? rule.severityOnFail() : null,
                firstFieldOrNull(rule.fieldKeys()),
                vp == null ? null : vp.lcValue(),
                vp == null ? null : vp.invoiceValue(),
                rule.ucpRef(),
                rule.isbpRef(),
                description,
                null
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
