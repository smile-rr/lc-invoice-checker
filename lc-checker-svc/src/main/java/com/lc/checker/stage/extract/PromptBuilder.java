package com.lc.checker.stage.extract;

import com.lc.checker.domain.common.FieldType;
import com.lc.checker.infra.fields.ColumnDefinition;
import com.lc.checker.infra.fields.FieldDefinition;
import com.lc.checker.infra.fields.FieldPoolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Single source of truth for the invoice-extraction LLM prompt across all
 * four extractor lanes (local_llm_vl, cloud_llm_vl, docling, mineru).
 *
 * <p>Loads the unified template {@code prompts/invoice-extract.st} once at
 * boot, fills the {@code {{fields}}} placeholder from {@link FieldPoolRegistry}
 * (canonical invoice schema), and substitutes {@code {{modality_directive}}}
 * per-lane (vision vs text). The rendered prompt is cached for the lifetime
 * of the JVM — the field-pool is treated as a build-time constant after
 * Spring context init.
 *
 * <p>Vision lanes embed the rendered prompt in their {@code chat/completions}
 * `content[0].text`. Sidecar lanes (docling, mineru) ship it to the Python
 * sidecar as a multipart {@code prompt} part so the sidecar's text-LLM call
 * uses the same instructions — eliminating the previously-divergent
 * {@code _SYSTEM_PROMPT} hardcoded in {@code llm_field_extractor.py}.
 */
@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private static final String VISION_TEMPLATE_PATH = "prompts/invoice-extract-vision.st";
    private static final String TEXT_TEMPLATE_PATH   = "prompts/invoice-extract-text.st";

    private final FieldPoolRegistry fieldPool;

    private volatile String cachedFieldList;
    private volatile String cachedVision;
    private volatile String cachedText;

    public PromptBuilder(FieldPoolRegistry fieldPool) {
        this.fieldPool = fieldPool;
    }

    /**
     * Vision-lane prompt (rendered images, qwen-vl etc.).
     * Loaded from {@code invoice-extract-vision.st} — tuned for image input
     * (handles "blurry / partially occluded", visual stamp/letterhead cues,
     * multi-page reads).
     */
    public String forVision() {
        String c = cachedVision;
        if (c != null) return c;
        c = loadTemplate(VISION_TEMPLATE_PATH)
                .replace("{{fields}}", fieldList())
                .replace("{{rules}}", rules());
        cachedVision = c;
        return c;
    }

    /**
     * Sidecar-lane prompt (markdown text, docling/mineru).
     * Loaded from {@code invoice-extract-text.st} — tuned for high-fidelity
     * markdown input. Tells the LLM to trust what's literally printed
     * (no visual ambiguity to resolve), and adjusts signed / stamp_present /
     * letterhead_present rules since markdown rarely preserves those visual
     * cues. Keeps the same field list, schema, and line-item rules so vision
     * and text outputs remain comparable.
     */
    public String forText() {
        String c = cachedText;
        if (c != null) return c;
        c = loadTemplate(TEXT_TEMPLATE_PATH)
                .replace("{{fields}}", fieldList())
                .replace("{{rules}}", rules());
        cachedText = c;
        return c;
    }

    private String loadTemplate(String path) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(path).getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load {}; falling back to a minimal hardcoded prompt", path, e);
            return FALLBACK_TEMPLATE;
        }
    }

    private String fieldList() {
        String c = cachedFieldList;
        if (c != null) return c;
        c = fieldPool.appliesToInvoice().stream()
                .map(PromptBuilder::renderFieldLine)
                .collect(Collectors.joining("\n"));
        cachedFieldList = c;
        return c;
    }

    private static String renderFieldLine(FieldDefinition f) {
        if (f.type() == FieldType.TABLE) {
            return renderTableField(f);
        }
        // Type placeholder ONLY inside the quoted value — kept short so the
        // skeleton looks like the JSON the LLM should produce, not a wall
        // of guidance text. Aliases ride as a JSON-style trailing comment
        // (`// aka …`); per-field hints go into the "Rules:" footer block
        // emitted by `renderRulesBlock()`. Putting hints inside the quoted
        // placeholder regressed extraction quality for small models.
        String typePlaceholder = switch (f.type()) {
            case DATE -> "YYYY-MM-DD|null";
            case CURRENCY_CODE -> "ISO 4217|null";
            case ENUM -> String.join("/", f.enumValues()) + "|null";
            case AMOUNT -> "number|null";
            case INTEGER -> "integer|null";
            case MULTILINE_TEXT -> "text|null";
            default -> "string|null";
        };
        StringBuilder sb = new StringBuilder("    \"")
                .append(f.key()).append("\": \"")
                .append(typePlaceholder).append("\",");
        if (!f.invoiceAliases().isEmpty()) {
            sb.append(pad(sb.length(), 56)).append("// aka ")
              .append(String.join(", ", f.invoiceAliases()));
        }
        return sb.toString();
    }

    /** Pad with spaces so trailing `//` comments line up across rows. */
    private static String pad(int currentLen, int targetCol) {
        int n = Math.max(1, targetCol - currentLen);
        return " ".repeat(n);
    }

    private static String renderTableField(FieldDefinition f) {
        // TABLE rendered as an array literal with one row-object skeleton.
        // Like scalar fields, no hint inside the placeholder — hint moves
        // to the "Rules:" footer.
        //
        //   "line_items": [
        //     {"description": "text|null", "quantity": "number|null", ...}
        //   ],                                              // aka items, lines, products
        StringBuilder sb = new StringBuilder("    \"").append(f.key()).append("\": [");
        if (!f.columns().isEmpty()) {
            sb.append("\n      {");
            boolean first = true;
            for (ColumnDefinition c : f.columns()) {
                if (!first) sb.append(", ");
                first = false;
                String colPlaceholder = switch (c.type()) {
                    case DATE -> "YYYY-MM-DD|null";
                    case CURRENCY_CODE -> "ISO 4217|null";
                    case ENUM -> String.join("/", c.enumValues()) + "|null";
                    case AMOUNT -> "number|null";
                    case INTEGER -> "integer|null";
                    case MULTILINE_TEXT -> "text|null";
                    default -> "string|null";
                };
                sb.append("\"").append(c.key()).append("\": \"").append(colPlaceholder).append("\"");
            }
            sb.append("}\n    ");
        }
        sb.append("],");
        if (!f.invoiceAliases().isEmpty()) {
            sb.append(pad(sb.length() - sb.lastIndexOf("\n") - 1, 56))
              .append("// aka ").append(String.join(", ", f.invoiceAliases()));
        }
        return sb.toString();
    }

    /**
     * Compact footer block of per-field rules that don't fit as one-line
     * JSON comments. Generated from each field's `extraction_hint` in
     * field-pool.yaml — single source of truth, no duplication into the
     * prompt template.
     */
    public String rules() {
        StringBuilder sb = new StringBuilder();
        for (FieldDefinition f : fieldPool.appliesToInvoice()) {
            if (f.extractionHint() == null || f.extractionHint().isBlank()) continue;
            String hint = f.extractionHint().strip().replaceAll("\\s+", " ");
            sb.append("- ").append(f.key()).append(": ").append(hint).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** Last-resort prompt if the .st template fails to load (build-classpath issue). */
    private static final String FALLBACK_TEMPLATE = """
            You are extracting structured fields from a commercial invoice for downstream
            Letter-of-Credit compliance checking. Output is consumed by a rule engine, not
            humans. Optimize for machine-parseable exactness.

            Return ONE JSON object — no preamble, no code fences, no commentary.
            Schema: { "fields": { /* canonical key → value */ }, "extraction_warnings": [], "confidence": 0.0 }

            Field reference:
            {{fields}}

            HARD RULE: if line_items has more than one row, set quantity = unit = unit_price = null.
            """;
}
