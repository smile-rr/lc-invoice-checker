package com.lc.checker.strategy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.model.CheckResult;
import com.lc.checker.model.CheckTrace;
import com.lc.checker.model.InvoiceDocument;
import com.lc.checker.model.LcDocument;
import com.lc.checker.model.LlmTrace;
import com.lc.checker.model.Rule;
import com.lc.checker.model.enums.CheckStatus;
import com.lc.checker.model.enums.CheckType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Strategy for Type-B rules: semantic comparison via an LLM prompt template.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Load the prompt template named by {@code rule.promptTemplate}. Templates live
 *       under {@code src/main/resources/prompts/} and use StringTemplate single-brace
 *       {@code {variable}} placeholders.</li>
 *   <li>Build the variable bindings from the documents. The current V1 rule (INV-015)
 *       needs LC goods raw + structured + invoice goods description + quantity + unit;
 *       the binder provides a superset so future prompts can pick what they need without
 *       a code change.</li>
 *   <li>Call {@link ChatClient}, capture verbatim request + response on an
 *       {@link LlmTrace}, parse the {@code {compliant, reason, ...}} JSON.</li>
 *   <li>Map {@code compliant=false} to {@link CheckStatus#DISCREPANT}, {@code true} to
 *       {@link CheckStatus#PASS}. Parse errors or LLM failures → {@link CheckStatus#UNABLE_TO_VERIFY}
 *       (a broken LLM must not silently become PASS).</li>
 * </ol>
 */
@Component
public class TypeBStrategy implements CheckStrategy {

    private static final Logger log = LoggerFactory.getLogger(TypeBStrategy.class);

    private final ChatClient chatClient;
    private final ObjectMapper json;

    /** Prompt-template cache — templates are immutable at runtime, cheap to memoize. */
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public TypeBStrategy(ChatClient chatClient, ObjectMapper json) {
        this.chatClient = chatClient;
        this.json = json;
    }

    @Override
    public CheckType type() {
        return CheckType.B;
    }

    @Override
    public StrategyOutcome execute(Rule rule, LcDocument lc, InvoiceDocument inv) {
        return runTypeB(rule, lc, inv);
    }

    /** Exposed for reuse by {@code TypeABStrategy}. */
    StrategyOutcome runTypeB(Rule rule, LcDocument lc, InvoiceDocument inv) {
        long start = System.currentTimeMillis();

        if (rule.promptTemplate() == null || rule.promptTemplate().isBlank()) {
            return fail(rule, CheckStatus.UNABLE_TO_VERIFY,
                    "Type-B rule " + rule.id() + " has no prompt_template",
                    null, start);
        }

        String template;
        try {
            template = loadTemplate(rule.promptTemplate());
        } catch (Exception e) {
            log.warn("Cannot load prompt {}: {}", rule.promptTemplate(), e.getMessage());
            return fail(rule, CheckStatus.UNABLE_TO_VERIFY,
                    "Prompt template missing: " + rule.promptTemplate(), null, start);
        }

        Map<String, Object> vars = buildVariables(lc, inv);
        String rendered = render(template, vars);

        String rawResponse;
        long llmStart = System.currentTimeMillis();
        try {
            rawResponse = chatClient.prompt().user(rendered).call().content();
        } catch (Exception e) {
            long llmElapsed = System.currentTimeMillis() - llmStart;
            log.warn("Rule {} LLM call failed: {}", rule.id(), e.getMessage());
            LlmTrace trace = new LlmTrace("rule." + rule.id() + ".check", null,
                    rendered, null, null, null, llmElapsed, e.getMessage());
            return fail(rule, CheckStatus.UNABLE_TO_VERIFY,
                    "LLM call failed: " + e.getMessage(), trace, start);
        }
        long llmElapsed = System.currentTimeMillis() - llmStart;
        LlmTrace llmTrace = new LlmTrace("rule." + rule.id() + ".check", null,
                rendered, rawResponse, null, null, llmElapsed, null);

        Map<String, Object> parsed;
        try {
            parsed = json.readValue(com.lc.checker.parser.Mt700LlmParser.stripFences(rawResponse),
                    new TypeReference<>() {
                    });
        } catch (Exception e) {
            log.warn("Rule {} response JSON parse failed: {}", rule.id(), e.getMessage());
            LlmTrace withErr = new LlmTrace(llmTrace.purpose(), llmTrace.model(), llmTrace.promptRendered(),
                    llmTrace.rawResponse(), null, null, llmTrace.latencyMs(), "JSON parse: " + e.getMessage());
            return fail(rule, CheckStatus.UNABLE_TO_VERIFY,
                    "Response was not valid JSON: " + e.getMessage(), withErr, start);
        }

        boolean compliant = asBoolean(parsed.get("compliant"));
        String reason = asString(parsed.get("reason"));
        String lcValue = asString(parsed.get("lc_quantity_claimed"));
        String invValue = asString(parsed.get("invoice_quantity_stated"));
        CheckStatus status = compliant ? CheckStatus.PASS : CheckStatus.DISCREPANT;

        long duration = System.currentTimeMillis() - start;
        CheckResult result = new CheckResult(
                rule.id(), rule.name(), CheckType.B, status,
                status == CheckStatus.DISCREPANT ? rule.severityOnFail() : null,
                firstFieldOrNull(rule.invoiceFields()),
                lcValue,
                invValue,
                rule.ucpRef(),
                rule.isbpRef(),
                reason != null ? reason : (compliant ? rule.name() + " satisfied" : rule.name() + " failed"));

        CheckTrace trace = new CheckTrace(
                rule.id(), CheckType.B, status,
                variablesForTrace(vars),
                null, llmTrace, duration, null);
        return new StrategyOutcome(result, trace);
    }

    // -----------------------------------------------------------------------
    // prompt binding
    // -----------------------------------------------------------------------

    /**
     * Build the full variable bag a prompt might want. Prompts reference by name via
     * {@code {variableName}}; unused variables are ignored harmlessly. Keeping a single
     * builder means adding a new Type-B rule typically requires only a new prompt file.
     */
    private Map<String, Object> buildVariables(LcDocument lc, InvoiceDocument inv) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("lcGoodsDescription", nullToEmpty(lc == null ? null : lc.field45ARaw()));
        v.put("lcGoodsStructured", stringify(lc == null ? null : lc.goodsDetail()));
        v.put("lcAmount", lc == null ? "" : String.valueOf(lc.amount()));
        v.put("lcCurrency", lc == null ? "" : nullToEmpty(lc.currency()));
        v.put("lcBeneficiaryName", lc == null ? "" : nullToEmpty(lc.beneficiaryName()));
        v.put("lcApplicantName", lc == null ? "" : nullToEmpty(lc.applicantName()));

        v.put("invoiceGoodsDescription", inv == null ? "" : nullToEmpty(inv.goodsDescription()));
        v.put("invoiceQuantity", inv == null || inv.quantity() == null ? "" : inv.quantity().toPlainString());
        v.put("invoiceUnit", inv == null ? "" : nullToEmpty(inv.unit()));
        v.put("invoiceTotalAmount",
                inv == null || inv.totalAmount() == null ? "" : inv.totalAmount().toPlainString());
        v.put("invoiceCurrency", inv == null ? "" : nullToEmpty(inv.currency()));
        v.put("invoiceSellerName", inv == null ? "" : nullToEmpty(inv.sellerName()));
        v.put("invoiceBuyerName", inv == null ? "" : nullToEmpty(inv.buyerName()));
        return v;
    }

    private String stringify(Object o) {
        if (o == null) return "null";
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    /**
     * Single-brace {variable} substitution. Does NOT try to be a full templating engine —
     * prompts are authored deliberately so this straightforward replace is sufficient
     * and predictable for tests.
     */
    private static String render(String template, Map<String, Object> vars) {
        String out = template;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue().toString());
        }
        return out;
    }

    private static Map<String, Object> variablesForTrace(Map<String, Object> vars) {
        // The full bag is fine — prompts are small enough and the trace is forensic.
        Map<String, Object> copy = new LinkedHashMap<>(vars);
        return copy;
    }

    private String loadTemplate(String filename) {
        return templateCache.computeIfAbsent(filename, f -> {
            try {
                return StreamUtils.copyToString(
                        new ClassPathResource("prompts/" + f).getInputStream(),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load prompt prompts/" + f, e);
            }
        });
    }

    // -----------------------------------------------------------------------
    // value coercion
    // -----------------------------------------------------------------------

    private static boolean asBoolean(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        return switch (v.toString().trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "1" -> true;
            default -> false;
        };
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String firstFieldOrNull(java.util.List<String> fields) {
        return (fields == null || fields.isEmpty()) ? null : fields.get(0);
    }

    private StrategyOutcome fail(Rule rule, CheckStatus status, String description, LlmTrace llmTrace, long start) {
        long duration = System.currentTimeMillis() - start;
        CheckResult result = new CheckResult(
                rule.id(), rule.name(), CheckType.B, status,
                status == CheckStatus.DISCREPANT ? rule.severityOnFail() : null,
                firstFieldOrNull(rule.invoiceFields()),
                null, null,
                rule.ucpRef(), rule.isbpRef(),
                description);
        CheckTrace trace = new CheckTrace(
                rule.id(), CheckType.B, status,
                Map.of(),
                null, llmTrace, duration, description);
        return new StrategyOutcome(result, trace);
    }
}
