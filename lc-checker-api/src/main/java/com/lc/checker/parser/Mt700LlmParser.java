package com.lc.checker.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.model.Condition47A;
import com.lc.checker.model.DocumentRequirement;
import com.lc.checker.model.GoodsDetail;
import com.lc.checker.model.LcDocument;
import com.lc.checker.model.LlmTrace;
import com.lc.checker.model.enums.ConditionTarget;
import com.lc.checker.model.enums.ConditionType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Stage 1 Part B — LLM-backed structuring of MT700 free-text fields :45A:, :46A:, :47A:.
 *
 * <p>Each field has a dedicated prompt under {@code src/main/resources/prompts/}. Prompts
 * are StringTemplate-based with a single {@code {fieldXXXRaw}} placeholder; they instruct
 * the model to return JSON only. Responses are robust-parsed (fence strip, array/object
 * detection) before deserialization.
 *
 * <p>Every call captures an {@link LlmTrace} (prompt verbatim, raw response, latency) so
 * {@code /trace} can show what the model saw and produced for each Part-B enrichment.
 *
 * <p>Failure policy: a prompt that fails to parse does NOT break the pipeline — the
 * field's structured slot stays null, a trace with an {@code error} is emitted, and
 * Stage 2 activators / Stage 3 checkers fall back to their missing-field policy.
 */
@Component
public class Mt700LlmParser {

    private static final Logger log = LoggerFactory.getLogger(Mt700LlmParser.class);
    private static final String MODEL_PURPOSE_45A = "mt700.parse.45A";
    private static final String MODEL_PURPOSE_46A = "mt700.parse.46A";
    private static final String MODEL_PURPOSE_47A = "mt700.parse.47A";

    private final ChatClient chatClient;
    private final ObjectMapper json;

    private final String template45A;
    private final String template46A;
    private final String template47A;

    public Mt700LlmParser(ChatClient chatClient, ObjectMapper json) {
        this.chatClient = chatClient;
        this.json = json;
        this.template45A = load("prompts/mt700-45A-parse.st");
        this.template46A = load("prompts/mt700-46A-parse.st");
        this.template47A = load("prompts/mt700-47A-parse.st");
    }

    /**
     * Run Part B against the raw :45A: / :46A: / :47A: text on {@code lc}, and return a
     * new {@code LcDocument} with the structured slots populated plus the list of
     * {@link LlmTrace}s captured for the trace endpoint. Each call is independent —
     * a failure on one field leaves the others intact.
     */
    public Enrichment enrich(LcDocument lc) {
        List<LlmTrace> traces = new ArrayList<>();

        GoodsDetail goods = null;
        if (lc.field45ARaw() != null) {
            var r = parse45A(lc.field45ARaw());
            goods = r.value();
            traces.add(r.trace());
        }

        List<DocumentRequirement> docs = List.of();
        if (lc.field46ARaw() != null) {
            var r = parse46A(lc.field46ARaw());
            if (r.value() != null) docs = r.value();
            traces.add(r.trace());
        }

        List<Condition47A> conditions = List.of();
        if (lc.field47ARaw() != null) {
            var r = parse47A(lc.field47ARaw());
            if (r.value() != null) conditions = r.value();
            traces.add(r.trace());
        }

        LcDocument enriched = new LcDocument(
                lc.lcNumber(), lc.issueDate(), lc.expiryDate(), lc.expiryPlace(),
                lc.currency(), lc.amount(), lc.tolerancePlus(), lc.toleranceMinus(), lc.maxAmountFlag(),
                lc.partialShipment(), lc.transhipment(), lc.placeOfReceipt(), lc.placeOfDelivery(),
                lc.latestShipmentDate(), lc.shipmentPeriod(), lc.portOfLoading(), lc.portOfDischarge(),
                lc.presentationDays(), lc.applicableRules(),
                lc.applicantName(), lc.applicantAddress(), lc.beneficiaryName(), lc.beneficiaryAddress(),
                lc.field45ARaw(), lc.field46ARaw(), lc.field47ARaw(),
                goods, docs, conditions,
                lc.rawFields());
        return new Enrichment(enriched, traces);
    }

    public record Enrichment(LcDocument lc, List<LlmTrace> traces) {
    }

    // -----------------------------------------------------------------------
    // per-field parsers (exposed for finer-grained testing)
    // -----------------------------------------------------------------------

    ParseResult<GoodsDetail> parse45A(String raw) {
        String prompt = template45A.replace("{field45ARaw}", raw);
        CallResult call = callLlm(prompt, MODEL_PURPOSE_45A);
        if (call.error() != null) {
            return new ParseResult<>(null, call.toTrace());
        }
        try {
            Map<String, Object> map = json.readValue(stripFences(call.response()), new TypeReference<>() {
            });
            GoodsDetail detail = new GoodsDetail(
                    asString(map.get("goods_name")),
                    asDecimal(map.get("quantity")),
                    asString(map.get("unit")),
                    asDecimal(map.get("unit_price")),
                    asString(map.get("unit_price_currency")),
                    asString(map.get("incoterm")),
                    asString(map.get("incoterm_place")),
                    asString(map.get("country_of_origin")),
                    asString(map.get("packing")),
                    firstNonNull(asString(map.get("net_weight")), asString(map.get("gross_weight"))),
                    asString(map.get("model_number"))
            );
            return new ParseResult<>(detail, call.toTrace());
        } catch (Exception e) {
            log.warn("45A JSON parse failed: {}", e.getMessage());
            return new ParseResult<>(null, call.toTraceWithError("JSON parse: " + e.getMessage()));
        }
    }

    ParseResult<List<DocumentRequirement>> parse46A(String raw) {
        String prompt = template46A.replace("{field46ARaw}", raw);
        CallResult call = callLlm(prompt, MODEL_PURPOSE_46A);
        if (call.error() != null) {
            return new ParseResult<>(List.of(), call.toTrace());
        }
        try {
            List<Map<String, Object>> list = json.readValue(stripFences(call.response()), new TypeReference<>() {
            });
            List<DocumentRequirement> out = new ArrayList<>(list.size());
            for (Map<String, Object> m : list) {
                Integer copies = firstNonNullInteger(asInteger(m.get("copies_total")), asInteger(m.get("originals_required")));
                out.add(new DocumentRequirement(
                        asString(m.get("doc_type")),
                        copies,
                        asBoolean(m.get("signed_required")),
                        Boolean.TRUE.equals(asBoolean(m.get("references_lc_number"))),
                        stringList(m.get("special_requirements")),
                        asString(m.get("raw"))
                ));
            }
            return new ParseResult<>(Collections.unmodifiableList(out), call.toTrace());
        } catch (Exception e) {
            log.warn("46A JSON parse failed: {}", e.getMessage());
            return new ParseResult<>(List.of(), call.toTraceWithError("JSON parse: " + e.getMessage()));
        }
    }

    ParseResult<List<Condition47A>> parse47A(String raw) {
        String prompt = template47A.replace("{field47ARaw}", raw);
        CallResult call = callLlm(prompt, MODEL_PURPOSE_47A);
        if (call.error() != null) {
            return new ParseResult<>(List.of(), call.toTrace());
        }
        try {
            List<Map<String, Object>> list = json.readValue(stripFences(call.response()), new TypeReference<>() {
            });
            List<Condition47A> out = new ArrayList<>(list.size());
            for (Map<String, Object> m : list) {
                out.add(new Condition47A(
                        asString(m.get("id")),
                        parseConditionType(asString(m.get("type"))),
                        parseConditionTarget(asString(m.get("target_doc"))),
                        asString(m.get("text")),
                        asString(m.get("checkable_field")),
                        asString(m.get("expected_value"))
                ));
            }
            return new ParseResult<>(Collections.unmodifiableList(out), call.toTrace());
        } catch (Exception e) {
            log.warn("47A JSON parse failed: {}", e.getMessage());
            return new ParseResult<>(List.of(), call.toTraceWithError("JSON parse: " + e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------
    // small DTO-ish helpers — kept internal to the parser
    // -----------------------------------------------------------------------

    record ParseResult<T>(T value, LlmTrace trace) {
    }

    private CallResult callLlm(String prompt, String purpose) {
        long start = System.currentTimeMillis();
        try {
            String content = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            long duration = System.currentTimeMillis() - start;
            return new CallResult(purpose, prompt, content, duration, null);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("LLM call {} failed: {}", purpose, e.getMessage());
            return new CallResult(purpose, prompt, null, duration, e.getMessage());
        }
    }

    private record CallResult(String purpose, String prompt, String response, long latencyMs, String error) {
        LlmTrace toTrace() {
            return new LlmTrace(purpose, null, prompt, response, null, null, latencyMs, error);
        }

        LlmTrace toTraceWithError(String err) {
            return new LlmTrace(purpose, null, prompt, response, null, null, latencyMs, err);
        }
    }

    // -----------------------------------------------------------------------
    // value coercion
    // -----------------------------------------------------------------------

    /** Strip ``` json … ``` fences the model may add despite instructions. */
    public static String stripFences(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static BigDecimal asDecimal(Object v) {
        String s = asString(v);
        if (s == null) return null;
        String cleaned = s.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer asInteger(Object v) {
        if (v instanceof Number n) return n.intValue();
        String s = asString(v);
        if (s == null) return null;
        try {
            return Integer.parseInt(s.replaceAll("[^0-9\\-]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean asBoolean(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return switch (v.toString().trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "1" -> Boolean.TRUE;
            case "false", "no", "n", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object v) {
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                String s = asString(o);
                if (s != null) out.add(s);
            }
            return out;
        }
        return List.of();
    }

    private static <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }

    private static Integer firstNonNullInteger(Integer a, Integer b) {
        return a != null ? a : b;
    }

    private static ConditionType parseConditionType(String s) {
        if (s == null) return ConditionType.UNKNOWN;
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "REQUIREMENT" -> ConditionType.REQUIREMENT;
            case "RESTRICTION" -> ConditionType.RESTRICTION;
            case "RELAXATION" -> ConditionType.RELAXATION;
            default -> ConditionType.UNKNOWN;
        };
    }

    private static ConditionTarget parseConditionTarget(String s) {
        if (s == null) return ConditionTarget.OTHER;
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "INVOICE" -> ConditionTarget.INVOICE;
            case "BILL_OF_LADING", "BL" -> ConditionTarget.BL;
            case "ALL" -> ConditionTarget.ALL;
            default -> ConditionTarget.OTHER;
        };
    }

    private static String load(String classpath) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(classpath).getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load prompt template " + classpath, e);
        }
    }
}
