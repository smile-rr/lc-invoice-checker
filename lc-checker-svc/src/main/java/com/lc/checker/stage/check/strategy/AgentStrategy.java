package com.lc.checker.stage.check.strategy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.domain.result.CheckResult;
import com.lc.checker.domain.result.CheckTrace;
import com.lc.checker.domain.result.LlmTrace;
import com.lc.checker.domain.rule.Rule;
import com.lc.checker.domain.rule.enums.CheckStatus;
import com.lc.checker.domain.rule.enums.CheckType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
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
 * One LLM call per AGENT rule. The prompt receives the full LC + full invoice
 * inline along with the rule's identity and excerpts. The agent decides BOTH
 * applicability and verdict in a single round-trip — there is no separate
 * activation gate.
 *
 * <p>Output schema (strict):
 * <pre>{@code
 * {
 *   "applicable": true | false,
 *   "verdict":    "PASS" | "DISCREPANT" | "UNABLE_TO_VERIFY",
 *   "lc_value":   string|null,
 *   "presented_value": string|null,
 *   "reason":     "one-line plain-English"
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code applicable=false} → {@link CheckStatus#NOT_APPLICABLE}.</li>
 *   <li>{@code applicable=true} → verdict mapped 1:1 to PASS / DISCREPANT /
 *       UNABLE_TO_VERIFY.</li>
 *   <li>Missing prompt template, LLM failure, or unparseable JSON →
 *       {@link CheckStatus#UNABLE_TO_VERIFY} (a broken agent must not silently PASS).</li>
 * </ul>
 */
@Component
public class AgentStrategy implements CheckStrategy {

    private static final Logger log = LoggerFactory.getLogger(AgentStrategy.class);

    private final ChatClient chatClient;
    private final ObjectMapper json;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public AgentStrategy(ChatClient chatClient, ObjectMapper json) {
        this.chatClient = chatClient;
        this.json = json;
    }

    @Override
    public CheckType type() {
        return CheckType.AGENT;
    }

    @Override
    public StrategyOutcome execute(Rule rule, LcDocument lc, InvoiceDocument inv) {
        long start = System.currentTimeMillis();

        if (rule.promptTemplate() == null || rule.promptTemplate().isBlank()) {
            return fail(rule, CheckStatus.UNABLE_TO_VERIFY,
                    "AGENT rule " + rule.id() + " has no prompt_template", null, start);
        }

        String template;
        try {
            template = loadTemplate(rule.promptTemplate());
        } catch (Exception e) {
            log.warn("Cannot load prompt {}: {}", rule.promptTemplate(), e.getMessage());
            return fail(rule, CheckStatus.UNABLE_TO_VERIFY,
                    "Prompt template missing: " + rule.promptTemplate(), null, start);
        }

        Map<String, Object> vars = buildVariables(rule, lc, inv);
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
            parsed = json.readValue(stripFences(rawResponse), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Rule {} response JSON parse failed: {}", rule.id(), e.getMessage());
            LlmTrace withErr = new LlmTrace(llmTrace.purpose(), llmTrace.model(), llmTrace.promptRendered(),
                    llmTrace.rawResponse(), null, null, llmTrace.latencyMs(), "JSON parse: " + e.getMessage());
            return fail(rule, CheckStatus.UNABLE_TO_VERIFY,
                    "Response was not valid JSON: " + e.getMessage(), withErr, start);
        }

        boolean applicable = asBoolean(parsed.get("applicable"));
        String verdict = asString(parsed.get("verdict"));
        String lcValue = asString(parsed.get("lc_value"));
        String invValue = asString(parsed.get("presented_value"));
        String reason = asString(parsed.get("reason"));

        CheckStatus status = mapStatus(applicable, verdict);

        long duration = System.currentTimeMillis() - start;
        CheckResult result = new CheckResult(
                rule.id(), rule.name(), CheckType.AGENT, rule.businessPhase(), status,
                status == CheckStatus.DISCREPANT ? rule.severityOnFail() : null,
                rule.outputField() != null ? rule.outputField() : firstFieldOrNull(rule.invoiceFields()),
                lcValue,
                invValue,
                rule.ucpRef(),
                rule.isbpRef(),
                reason != null ? reason : defaultDescription(rule, status));

        CheckTrace trace = new CheckTrace(
                rule.id(), CheckType.AGENT, status,
                variablesForTrace(vars),
                null, llmTrace, duration, null);
        return new StrategyOutcome(result, trace);
    }

    // -----------------------------------------------------------------------
    // prompt binding
    // -----------------------------------------------------------------------

    private Map<String, Object> buildVariables(Rule rule, LcDocument lc, InvoiceDocument inv) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ruleId", rule.id());
        v.put("ruleName", rule.name() == null ? "" : rule.name());
        v.put("ruleDescription", rule.description() == null ? "" : rule.description());
        v.put("ucpRef", nullToEmpty(rule.ucpRef()));
        v.put("isbpRef", nullToEmpty(rule.isbpRef()));
        v.put("ucpExcerpt", nullToEmpty(rule.ucpExcerpt()));
        v.put("isbpExcerpt", nullToEmpty(rule.isbpExcerpt()));
        v.put("lcText", renderLc(lc));
        v.put("invoiceText", renderInvoice(inv));
        v.put("lcRelevantFields", renderFieldList(rule.lcFields()));
        v.put("invoiceRelevantFields", renderFieldList(rule.invoiceFields()));
        return v;
    }

    /** Plain-text rendering of the LC — every Block-4 tag the parser preserved. */
    private static String renderLc(LcDocument lc) {
        if (lc == null) return "(no LC available)";
        StringBuilder sb = new StringBuilder();
        sb.append("LC NUMBER: ").append(nullToEmpty(lc.lcNumber())).append('\n');
        sb.append("CURRENCY: ").append(nullToEmpty(lc.currency())).append('\n');
        sb.append("AMOUNT: ").append(lc.amount() == null ? "" : lc.amount().toPlainString()).append('\n');
        sb.append("EXPIRY: ").append(lc.expiryDate() == null ? "" : lc.expiryDate()).append('\n');
        sb.append("APPLICANT: ").append(nullToEmpty(lc.applicantName()))
                .append(" | ").append(nullToEmpty(lc.applicantAddress())).append('\n');
        sb.append("BENEFICIARY: ").append(nullToEmpty(lc.beneficiaryName()))
                .append(" | ").append(nullToEmpty(lc.beneficiaryAddress())).append('\n');
        if (lc.field45ARaw() != null) {
            sb.append("\n:45A: GOODS DESCRIPTION:\n").append(lc.field45ARaw()).append('\n');
        }
        if (lc.field46ARaw() != null) {
            sb.append("\n:46A: DOCUMENTS REQUIRED:\n").append(lc.field46ARaw()).append('\n');
        }
        if (lc.field47ARaw() != null) {
            sb.append("\n:47A: ADDITIONAL CONDITIONS:\n").append(lc.field47ARaw()).append('\n');
        }
        return sb.toString();
    }

    private static String renderInvoice(InvoiceDocument inv) {
        if (inv == null) return "(invoice extraction unavailable)";
        StringBuilder sb = new StringBuilder();
        sb.append("INVOICE NUMBER: ").append(nullToEmpty(inv.invoiceNumber())).append('\n');
        sb.append("INVOICE DATE: ").append(inv.invoiceDate() == null ? "" : inv.invoiceDate()).append('\n');
        sb.append("LC REFERENCE: ").append(nullToEmpty(inv.lcReference())).append('\n');
        sb.append("CURRENCY: ").append(nullToEmpty(inv.currency())).append('\n');
        sb.append("TOTAL AMOUNT: ")
                .append(inv.totalAmount() == null ? "" : inv.totalAmount().toPlainString()).append('\n');
        sb.append("QUANTITY: ").append(inv.quantity() == null ? "" : inv.quantity().toPlainString()).append('\n');
        sb.append("UNIT: ").append(nullToEmpty(inv.unit())).append('\n');
        sb.append("UNIT PRICE: ")
                .append(inv.unitPrice() == null ? "" : inv.unitPrice().toPlainString()).append('\n');
        sb.append("SELLER: ").append(nullToEmpty(inv.sellerName()))
                .append(" | ").append(nullToEmpty(inv.sellerAddress())).append('\n');
        sb.append("BUYER: ").append(nullToEmpty(inv.buyerName()))
                .append(" | ").append(nullToEmpty(inv.buyerAddress())).append('\n');
        sb.append("TRADE TERMS: ").append(nullToEmpty(inv.tradeTerms())).append('\n');
        sb.append("PORT OF LOADING: ").append(nullToEmpty(inv.portOfLoading())).append('\n');
        sb.append("PORT OF DISCHARGE: ").append(nullToEmpty(inv.portOfDischarge())).append('\n');
        sb.append("COUNTRY OF ORIGIN: ").append(nullToEmpty(inv.countryOfOrigin())).append('\n');
        sb.append("SIGNED: ").append(inv.signed() == null ? "" : inv.signed()).append('\n');
        if (inv.goodsDescription() != null) {
            sb.append("\nGOODS DESCRIPTION:\n").append(inv.goodsDescription()).append('\n');
        }
        return sb.toString();
    }

    private static String renderFieldList(List<String> fields) {
        if (fields == null || fields.isEmpty()) return "(none)";
        return String.join(", ", fields);
    }

    private static String stripFences(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static String render(String template, Map<String, Object> vars) {
        String out = template;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue().toString());
        }
        return out;
    }

    private static Map<String, Object> variablesForTrace(Map<String, Object> vars) {
        return new LinkedHashMap<>(vars);
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
    // verdict mapping & coercion
    // -----------------------------------------------------------------------

    private static CheckStatus mapStatus(boolean applicable, String verdict) {
        if (!applicable) return CheckStatus.NOT_APPLICABLE;
        if (verdict == null) return CheckStatus.UNABLE_TO_VERIFY;
        return switch (verdict.trim().toUpperCase(Locale.ROOT)) {
            case "PASS" -> CheckStatus.PASS;
            case "DISCREPANT" -> CheckStatus.DISCREPANT;
            case "UNABLE_TO_VERIFY", "UNABLE", "UNKNOWN" -> CheckStatus.UNABLE_TO_VERIFY;
            default -> CheckStatus.UNABLE_TO_VERIFY;
        };
    }

    private static String defaultDescription(Rule rule, CheckStatus status) {
        return switch (status) {
            case PASS -> rule.name() + " satisfied";
            case DISCREPANT -> rule.name() + " failed";
            case NOT_APPLICABLE -> rule.name() + " not applicable to this LC";
            case UNABLE_TO_VERIFY -> rule.name() + " could not be verified";
            default -> rule.name();
        };
    }

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

    private static String firstFieldOrNull(List<String> fields) {
        return (fields == null || fields.isEmpty()) ? null : fields.get(0);
    }

    private StrategyOutcome fail(Rule rule, CheckStatus status, String description, LlmTrace llmTrace, long start) {
        long duration = System.currentTimeMillis() - start;
        CheckResult result = new CheckResult(
                rule.id(), rule.name(), CheckType.AGENT, rule.businessPhase(), status,
                status == CheckStatus.DISCREPANT ? rule.severityOnFail() : null,
                rule.outputField() != null ? rule.outputField() : firstFieldOrNull(rule.invoiceFields()),
                null, null,
                rule.ucpRef(), rule.isbpRef(),
                description);
        CheckTrace trace = new CheckTrace(
                rule.id(), CheckType.AGENT, status,
                Map.of(),
                null, llmTrace, duration, description);
        return new StrategyOutcome(result, trace);
    }
}
