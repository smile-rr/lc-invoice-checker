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
import com.lc.checker.infra.fields.FieldDefinition;
import com.lc.checker.infra.fields.FieldPoolRegistry;
import com.lc.checker.infra.observability.LangfuseTags;
import com.lc.checker.tools.ToolRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * One LLM call per AGENT rule. Each call combines two prompts:
 * <ul>
 *   <li><b>System</b> — {@code prompts/system/check-system.st}: verdict
 *       semantics, standing rules (always populate evidence; both-sides-blank
 *       → DOUBTS; clear mismatch → FAIL not NOT_REQUIRED; whole-document
 *       mismatch → FAIL via UCP 14(d)), ISBP cross-cutting tolerances, and
 *       the strict JSON output schema. Identical for every rule, bound via
 *       {@code ChatClient.prompt().system(...)}.</li>
 *   <li><b>User</b> — {@code prompts/check/<rule>.st}: rule identity,
 *       authority excerpts, full LC and invoice payload, rule-specific
 *       applicability, and verdict criteria. Bound via
 *       {@code .user(...)}.</li>
 * </ul>
 * The agent decides BOTH applicability and verdict in a single round-trip —
 * there is no separate activation gate.
 *
 * <p>Output schema (strict):
 * <pre>{@code
 * {
 *   "verdict":         "PASS" | "FAIL" | "DOUBTS" | "NOT_REQUIRED",
 *   "lc_value":        string|null,
 *   "presented_value": string|null,
 *   "reason":          "one-line plain-English"
 * }
 * }</pre>
 *
 * <ul>
 *   <li>verdict {@code PASS} → {@link CheckStatus#PASS}.</li>
 *   <li>verdict {@code FAIL} → {@link CheckStatus#FAIL}.</li>
 *   <li>verdict {@code DOUBTS} → {@link CheckStatus#DOUBTS} — agent is not
 *       confident; must not guess.</li>
 *   <li>verdict {@code NOT_REQUIRED} → {@link CheckStatus#NOT_REQUIRED};
 *       reason MUST cite the UCP article or ISBP paragraph that governs why.</li>
 *   <li>Missing prompt template, LLM failure, or unparseable JSON →
 *       {@link CheckStatus#DOUBTS} (a broken agent must not silently PASS).</li>
 * </ul>
 */
@Component
public class AgentStrategy implements CheckStrategy {

    private static final Logger log = LoggerFactory.getLogger(AgentStrategy.class);

    /**
     * Standing instructions applied to every AGENT rule via {@code .system(...)}:
     * verdict semantics, evidence-always rule, both-empty→DOUBTS rule,
     * mismatch→FAIL rule, output schema, and ISBP cross-cutting tolerances.
     * Loaded once and bound as the chat system message, so each rule's
     * user prompt only carries rule-specific content.
     */
    private static final String SYSTEM_PROMPT_PATH = "system/check-system.st";
    private static final String CHECK_PROMPT_DIR = "check/";

    private final ChatClient chatClient;
    private final ObjectMapper json;
    private final FieldPoolRegistry fieldPool;
    private final Tracer tracer;
    private final ToolRegistry toolRegistry;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();
    private volatile String systemPrompt;

    public AgentStrategy(ChatClient chatClient, ObjectMapper json,
                         FieldPoolRegistry fieldPool, Tracer tracer,
                         ToolRegistry toolRegistry) {
        this.chatClient = chatClient;
        this.json = json;
        this.fieldPool = fieldPool;
        this.tracer = tracer;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public CheckType type() {
        return CheckType.AGENT;
    }

    @Override
    public StrategyOutcome execute(Rule rule, LcDocument lc, InvoiceDocument inv) {
        // Per-rule child span — parent is the lc.check.session span set by the
        // pipeline. The Spring AI ChatClient.call below emits its own
        // "gen_ai.client.operation" span underneath this one (with prompt /
        // completion / token attributes), so Langfuse renders this rule's
        // entry as: rule.<id> [Span] → ChatClient call [Generation].
        Span span = LangfuseTags.applySession(tracer.nextSpan())
                .name("rule." + rule.id())
                // type=generation tells Langfuse this is an LLM call (not a
                // generic span), so it shows model + tokens + cost in the UI.
                .tag("langfuse.observation.type", "generation")
                .tag("rule.id", rule.id())
                .tag("rule.type", "AGENT")
                .tag("rule.ucp_ref", nullToEmpty(rule.ucpRef()))
                .tag("rule.isbp_ref", nullToEmpty(rule.isbpRef()))
                .tag("rule.severity_on_fail", String.valueOf(rule.severityOnFail()))
                .tag("rule.prompt_template", nullToEmpty(rule.promptTemplate()))
                .start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            StrategyOutcome outcome = doExecute(rule, lc, inv);
            CheckStatus status = outcome.result().status();
            span.tag("rule.verdict", String.valueOf(status));
            return outcome;
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private StrategyOutcome doExecute(Rule rule, LcDocument lc, InvoiceDocument inv) {
        long start = System.currentTimeMillis();

        if (rule.promptTemplate() == null || rule.promptTemplate().isBlank()) {
            return fail(rule, CheckStatus.DOUBTS,
                    "AGENT rule " + rule.id() + " has no prompt_template", null, start);
        }

        String template;
        try {
            template = loadTemplate(rule.promptTemplate());
        } catch (Exception e) {
            log.warn("Cannot load prompt {}: {}", rule.promptTemplate(), e.getMessage());
            return fail(rule, CheckStatus.DOUBTS,
                    "Prompt template missing: " + rule.promptTemplate(), null, start);
        }

        Map<String, Object> vars = buildVariables(rule, lc, inv);
        String userRendered = render(template, vars);
        String systemRendered = systemPrompt();

        // Pin both system and user content on the rule span so Langfuse shows
        // the exact prompt the LLM saw. The system message is identical for
        // every rule, but recording it per-call keeps the trace self-contained.
        Span ruleSpan = tracer.currentSpan();
        if (ruleSpan != null) {
            ruleSpan.tag("langfuse.observation.input",
                    "[SYSTEM]\n" + systemRendered + "\n\n[USER]\n" + userRendered);
        }

        // Tier 3 — bind the rule's declared tools so the LLM can call them.
        // Tier 2 rules (no tools declared) get the raw prompt path. Per-rule
        // narrow toolset is intentional — never the union of all tools.
        List<ToolCallback> resolvedTools = toolRegistry.resolve(rule.tools());

        String rawResponse;
        long llmStart = System.currentTimeMillis();
        try {
            // Per-call .system(...) overrides ChatClientConfig's defaultSystem,
            // so the standing rules document is the single source of truth for
            // every AGENT verdict.
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .system(systemRendered)
                    .user(userRendered);
            if (!resolvedTools.isEmpty()) {
                spec = spec.toolCallbacks(resolvedTools);
            }
            rawResponse = spec.call().content();
        } catch (Exception e) {
            long llmElapsed = System.currentTimeMillis() - llmStart;
            log.warn("Rule {} LLM call failed: {}", rule.id(), e.getMessage());
            LlmTrace trace = new LlmTrace("rule." + rule.id() + ".check", null,
                    userRendered, null, null, null, llmElapsed, e.getMessage());
            if (ruleSpan != null) {
                ruleSpan.tag("langfuse.observation.output",
                        "{\"error\": \"LLM call failed: " + e.getMessage().replace("\"", "'") + "\"}");
            }
            return fail(rule, CheckStatus.DOUBTS,
                    "LLM call failed: " + e.getMessage(), trace, start);
        }
        long llmElapsed = System.currentTimeMillis() - llmStart;
        LlmTrace llmTrace = new LlmTrace("rule." + rule.id() + ".check", null,
                userRendered, rawResponse, null, null, llmElapsed, null);

        // Capture the raw model response — this is what Langfuse should show
        // as the rule's output. The structured fields (verdict, lc_value,
        // presented_value, reason) are also rendered into rule.* tags below.
        if (ruleSpan != null) {
            ruleSpan.tag("langfuse.observation.output", rawResponse);
        }

        Map<String, Object> parsed;
        try {
            parsed = json.readValue(stripFences(rawResponse), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Rule {} response JSON parse failed: {}", rule.id(), e.getMessage());
            LlmTrace withErr = new LlmTrace(llmTrace.purpose(), llmTrace.model(), llmTrace.promptRendered(),
                    llmTrace.rawResponse(), null, null, llmTrace.latencyMs(), "JSON parse: " + e.getMessage());
            return fail(rule, CheckStatus.DOUBTS,
                    "Response was not valid JSON: " + e.getMessage(), withErr, start);
        }

        String verdict = asString(parsed.get("verdict"));
        String lcValue = asString(parsed.get("lc_value"));
        String invValue = asString(parsed.get("presented_value"));
        String reason = asString(parsed.get("reason"));
        // Tier-3 only — Tier-2 rules omit this key; null is the right default.
        String equationUsed = asString(parsed.get("equation_used"));

        CheckStatus status = mapStatus(verdict);

        long duration = System.currentTimeMillis() - start;
        CheckResult result = new CheckResult(
                rule.id(), rule.name(), CheckType.AGENT, rule.businessPhase(), status,
                status == CheckStatus.FAIL ? rule.severityOnFail() : null,
                rule.outputField() != null ? rule.outputField() : firstFieldOrNull(rule.fieldKeys()),
                lcValue,
                invValue,
                rule.ucpRef(),
                rule.isbpRef(),
                reason != null ? reason : defaultDescription(rule, status),
                equationUsed);

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
        v.put("lcRelevantFields", renderRelevantFields(rule, true));
        v.put("invoiceRelevantFields", renderRelevantFields(rule, false));
        return v;
    }

    /**
     * Render the rule's field_keys filtered by which side they apply to. Each
     * line is "canonical_key (English label)" so the prompt can reason about
     * the same concept on both LC and invoice without coupling to MT700 tags
     * or extractor-specific aliases.
     */
    private String renderRelevantFields(Rule rule, boolean lcSide) {
        if (rule.fieldKeys().isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (String key : rule.fieldKeys()) {
            FieldDefinition def = fieldPool.byKey(key).orElse(null);
            if (def == null) continue;
            boolean applies = lcSide ? def.appliesToLc() : def.appliesToInvoice();
            if (!applies) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(key);
            if (def.nameEn() != null && !def.nameEn().isBlank()) {
                sb.append(" (").append(def.nameEn()).append(")");
            }
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    private String systemPrompt() {
        String cached = systemPrompt;
        if (cached != null) return cached;
        try {
            String body = StreamUtils.copyToString(
                    new ClassPathResource("prompts/" + SYSTEM_PROMPT_PATH).getInputStream(),
                    StandardCharsets.UTF_8);
            systemPrompt = body;
            return body;
        } catch (IOException e) {
            // A missing system prompt would silently degrade every AGENT
            // verdict's quality and remove the standing-rule guarantees the
            // catalog is designed around. Fail loud so the deployment is
            // visibly misconfigured rather than quietly weaker.
            throw new IllegalStateException(
                    "Required system prompt missing at prompts/" + SYSTEM_PROMPT_PATH, e);
        }
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
        sb.append("BENEFICIARY: ").append(nullToEmpty(inv.sellerName()))
                .append(" | ").append(nullToEmpty(inv.sellerAddress())).append('\n');
        sb.append("APPLICANT: ").append(nullToEmpty(inv.buyerName()))
                .append(" | ").append(nullToEmpty(inv.buyerAddress())).append('\n');
        sb.append("INCOTERMS: ").append(nullToEmpty(inv.tradeTerms())).append('\n');
        sb.append("PORT OF LOADING: ").append(nullToEmpty(inv.portOfLoading())).append('\n');
        sb.append("PORT OF DISCHARGE: ").append(nullToEmpty(inv.portOfDischarge())).append('\n');
        sb.append("COUNTRY OF ORIGIN: ").append(nullToEmpty(inv.countryOfOrigin())).append('\n');
        sb.append("SIGNED: ").append(inv.signed() == null ? "" : inv.signed()).append('\n');
        if (inv.goodsDescription() != null) {
            sb.append("\nGOODS DESCRIPTION:\n").append(inv.goodsDescription()).append('\n');
        }
        return sb.toString();
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
            // Catalog stores leaf filenames; rule prompts live under prompts/check/.
            // A caller may pass a path-prefixed value (e.g. "fragments/foo.txt"),
            // in which case we resolve it under prompts/ directly.
            String path = f.contains("/") ? "prompts/" + f : "prompts/" + CHECK_PROMPT_DIR + f;
            try {
                return StreamUtils.copyToString(
                        new ClassPathResource(path).getInputStream(),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load prompt " + path, e);
            }
        });
    }

    // -----------------------------------------------------------------------
    // verdict mapping & coercion
    // -----------------------------------------------------------------------

    private static CheckStatus mapStatus(String verdict) {
        if (verdict == null) return CheckStatus.DOUBTS;
        return switch (verdict.trim().toUpperCase(Locale.ROOT)) {
            case "PASS" -> CheckStatus.PASS;
            case "FAIL", "DISCREPANT" -> CheckStatus.FAIL;          // accept legacy DISCREPANT
            case "DOUBTS", "UNABLE_TO_VERIFY", "UNABLE", "UNKNOWN" -> CheckStatus.DOUBTS;
            case "NOT_REQUIRED", "NOT_APPLICABLE" -> CheckStatus.NOT_REQUIRED;  // accept legacy
            default -> CheckStatus.DOUBTS;
        };
    }

    private static String defaultDescription(Rule rule, CheckStatus status) {
        return switch (status) {
            case PASS -> rule.name() + " satisfied";
            case FAIL -> rule.name() + " failed";
            case NOT_REQUIRED -> rule.name() + " not required by this LC";
            case DOUBTS -> rule.name() + " could not be verified with confidence";
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
                status == CheckStatus.FAIL ? rule.severityOnFail() : null,
                rule.outputField() != null ? rule.outputField() : firstFieldOrNull(rule.fieldKeys()),
                null, null,
                rule.ucpRef(), rule.isbpRef(),
                description,
                null);
        CheckTrace trace = new CheckTrace(
                rule.id(), CheckType.AGENT, status,
                Map.of(),
                null, llmTrace, duration, description);
        return new StrategyOutcome(result, trace);
    }
}
