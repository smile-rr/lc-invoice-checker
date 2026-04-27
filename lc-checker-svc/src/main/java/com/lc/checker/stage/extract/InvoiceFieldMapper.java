package com.lc.checker.stage.extract;

import com.lc.checker.domain.common.FieldEnvelope;
import com.lc.checker.domain.common.FieldType;
import com.lc.checker.domain.common.ParseWarning;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.infra.fields.ColumnDefinition;
import com.lc.checker.infra.fields.FieldDefinition;
import com.lc.checker.infra.fields.FieldPoolRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Generic invoice mapper — replaces the three hand-written variants
 * ({@code VisionLlmExtractor.normaliseKey()}, {@code VisionInvoiceMapper.toDocument()},
 * {@code ExtractorResponseMapper.toDocument()}) with a single registry-driven path.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Walk every key the extractor emitted.</li>
 *   <li>Look it up via {@link FieldPoolRegistry#resolveInvoiceAlias(String)}.
 *       Hit → write coerced value under the canonical key in {@code fields}.
 *       Miss (or extractor-meta key like {@code confidence}, {@code raw_text}) →
 *       drop into the appropriate bucket (extras or meta).</li>
 *   <li>Build {@link FieldEnvelope} for the trace + new-style rule access.</li>
 *   <li>Build the legacy {@link InvoiceDocument} typed scalars from the same map
 *       so old SpEL expressions ({@code inv.totalAmount}, {@code inv.currency})
 *       keep working unchanged.</li>
 * </ol>
 */
@Component
public class InvoiceFieldMapper {

    private static final Logger log = LoggerFactory.getLogger(InvoiceFieldMapper.class);

    /** Extractor-meta keys that bypass the field-pool entirely (handled by callers). */
    public static final Set<String> META_KEYS = Set.of("confidence", "raw_text", "rawtext");

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH));

    private final FieldPoolRegistry registry;
    private final InvoiceRowProjector rowProjector;

    public InvoiceFieldMapper(FieldPoolRegistry registry, InvoiceRowProjector rowProjector) {
        this.registry = registry;
        this.rowProjector = rowProjector;
    }

    /** The key path: any extractor → InvoiceDocument. */
    public InvoiceDocument toDocument(
            Map<String, Object> rawFromExtractor,
            String extractorName,
            double confidence,
            boolean imageBased,
            int pages,
            long extractionMs,
            String rawMarkdown,
            String rawText) {

        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Object> extras = new LinkedHashMap<>();
        Map<String, String> rawSource = new LinkedHashMap<>();
        List<ParseWarning> warnings = new ArrayList<>();

        for (Map.Entry<String, Object> entry : rawFromExtractor.entrySet()) {
            String rawKey = entry.getKey();
            Object value = entry.getValue();
            if (rawKey == null || rawKey.isBlank()) continue;
            String lower = rawKey.toLowerCase();
            rawSource.put(rawKey, value == null ? "" : value.toString());

            if (META_KEYS.contains(lower)) {
                continue;   // confidence / raw_text were handled by the caller already
            }

            Optional<String> canonical = registry.resolveInvoiceAlias(lower);
            if (canonical.isEmpty()) {
                extras.put(lower, value);
                warnings.add(new ParseWarning("UNKNOWN_ALIAS", rawKey,
                        "extractor key '" + rawKey + "' is not registered in field-pool.yaml; "
                        + "preserved in envelope.extras"));
                continue;
            }
            FieldDefinition def = registry.byKey(canonical.get())
                    .orElseThrow(); // resolved alias must point to a real field
            Object coerced = (def.type() == FieldType.TABLE)
                    ? coerceTable(value, def, warnings)
                    : coerce(value, def.type());
            if (coerced != null) {
                fields.put(def.key(), coerced);
            }
        }

        // Single-line invoice fallback: vision LLMs sometimes leave the
        // top-level scalar quantity/unit_price/unit as null while still
        // populating line_items with one row. Without this projection the
        // typed InvoiceDocument loses those values and downstream rules
        // (notably UCP-18b-math) see nulls and degrade to DOUBTS even though
        // the math IS computable. PromptBuilder's instruction is "scalar
        // when single-line, line_items when multi-line" — this enforces it
        // post-hoc when the LLM doesn't follow.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> singleRow = (fields.get("line_items") instanceof List<?> li && li.size() == 1)
                ? (List<Map<String, Object>>) li
                : null;
        if (singleRow != null) {
            Map<String, Object> row = singleRow.get(0);
            fields.computeIfAbsent("quantity",   k -> row.get("quantity"));
            fields.computeIfAbsent("unit_price", k -> row.get("unit_price"));
            fields.computeIfAbsent("unit",       k -> row.get("unit"));
        }

        FieldEnvelope envelope = new FieldEnvelope("INVOICE", fields, extras, rawSource, warnings);

        // Build the legacy typed-scalar facade from the same canonical map.
        var parsedRows = rowProjector.project(envelope);
        return new InvoiceDocument(
                asString(fields.get("invoice_number")),
                asDate(fields.get("invoice_date")),
                asString(fields.get("beneficiary_name")),
                asString(fields.get("beneficiary_address")),
                asString(fields.get("applicant_name")),
                asString(fields.get("applicant_address")),
                asString(fields.get("goods_description")),
                asDecimal(fields.get("quantity")),
                asString(fields.get("unit")),
                asDecimal(fields.get("unit_price")),
                asDecimal(fields.get("credit_amount")),
                asString(fields.get("credit_currency")),
                asString(fields.get("lc_number")),
                asString(fields.get("incoterms")),
                asString(fields.get("port_of_loading")),
                asString(fields.get("port_of_discharge")),
                asString(fields.get("country_of_origin")),
                asBoolean(fields.get("signed")),
                extractorName,
                confidence,
                imageBased,
                pages,
                extractionMs,
                rawMarkdown,
                rawText,
                envelope,
                parsedRows);
    }

    // ─── coercion ──────────────────────────────────────────────────────────

    private Object coerce(Object v, FieldType type) {
        if (v == null) return null;
        return switch (type) {
            case AMOUNT -> asDecimal(v);
            case DATE -> asDate(v);
            case INTEGER -> asInteger(v);
            case CURRENCY_CODE, ENUM, STRING, MULTILINE_TEXT, DOCUMENT_LIST -> asString(v);
            case TABLE -> v;   // handled separately by coerceTable() — reaching here is a wiring bug
        };
    }

    /**
     * Coerce a TABLE-typed value: expects a {@code List<Map<String,Object>>}
     * (or anything that looks list-shaped). Each row is walked with the
     * column-scoped alias dict; cells coerce per their column type. Unknown
     * row keys land in a per-row {@code _extras} map and emit an UNKNOWN_COLUMN
     * warning so nothing is silently dropped.
     */
    private List<Map<String, Object>> coerceTable(Object raw, FieldDefinition def, List<ParseWarning> warnings) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> list)) {
            warnings.add(new ParseWarning("TABLE_NOT_LIST", def.key(),
                    "expected an array of rows for '" + def.key() + "' but got "
                    + raw.getClass().getSimpleName() + "; field skipped"));
            return null;
        }
        if (list.isEmpty()) return null;

        // Build the column-scoped alias dict once.
        Map<String, ColumnDefinition> colByAlias = new LinkedHashMap<>();
        Map<String, ColumnDefinition> colByKey = new LinkedHashMap<>();
        for (ColumnDefinition col : def.columns()) {
            colByKey.put(col.key(), col);
            colByAlias.put(col.key().toLowerCase(), col);
            for (String a : col.invoiceAliases()) {
                if (a != null && !a.isBlank()) {
                    colByAlias.put(a.toLowerCase(), col);
                }
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int rowIdx = 0;
        for (Object rowObj : list) {
            rowIdx++;
            if (!(rowObj instanceof Map<?, ?> rowMap)) {
                warnings.add(new ParseWarning("TABLE_ROW_NOT_MAP", def.key(),
                        "row " + rowIdx + " of '" + def.key() + "' is not an object; skipped"));
                continue;
            }
            Map<String, Object> outRow = new LinkedHashMap<>();
            Map<String, Object> rowExtras = new LinkedHashMap<>();
            for (Map.Entry<?, ?> cell : rowMap.entrySet()) {
                String rawKey = String.valueOf(cell.getKey());
                if (rawKey == null || rawKey.isBlank()) continue;
                ColumnDefinition col = colByAlias.get(rawKey.toLowerCase());
                if (col == null) {
                    rowExtras.put(rawKey, cell.getValue());
                    warnings.add(new ParseWarning("UNKNOWN_COLUMN", def.key() + "[" + rowIdx + "]." + rawKey,
                            "column '" + rawKey + "' is not declared on table '" + def.key()
                            + "'; preserved in row._extras"));
                    continue;
                }
                Object coerced = coerce(cell.getValue(), col.type());
                if (coerced != null) {
                    outRow.put(col.key(), coerced);
                }
            }
            if (!rowExtras.isEmpty()) {
                outRow.put("_extras", rowExtras);
            }
            if (!outRow.isEmpty()) {
                rows.add(outRow);
            }
        }
        return rows.isEmpty() ? null : rows;
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = v instanceof String str ? str.trim() : v.toString().trim();
        if (s.isEmpty()) return null;
        // LLM sometimes emits the literal string "null" for absent fields.
        if (s.equalsIgnoreCase("null")) return null;
        // Also treat explicit "n/a" / "n.a." / "—" / "–" as blank.
        if (s.equalsIgnoreCase("n/a") || s.equalsIgnoreCase("n.a.") || s.equals("—") || s.equals("–")) return null;
        return s;
    }

    private static BigDecimal asDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = asString(v);
        if (s == null) return null;
        String cleaned = s.replace(",", "").replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            log.debug("Could not coerce '{}' to BigDecimal", s);
            return null;
        }
    }

    private static Integer asInteger(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        String s = asString(v);
        if (s == null) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate asDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        String s = asString(v);
        if (s == null) return null;
        for (DateTimeFormatter fmt : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(s, fmt);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        log.debug("Extractor returned unparseable date '{}'; leaving null", s);
        return null;
    }

    private static Boolean asBoolean(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase();
        return switch (s) {
            case "true", "yes", "y", "1", "signed" -> Boolean.TRUE;
            case "false", "no", "n", "0" -> Boolean.FALSE;
            default -> null;
        };
    }
}
