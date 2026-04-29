package com.lc.checker.stage.extract;

import com.lc.checker.domain.common.FieldCoercion;
import com.lc.checker.domain.common.FieldEnvelope;
import com.lc.checker.domain.common.FieldType;
import com.lc.checker.domain.common.ParseWarning;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.infra.fields.ColumnDefinition;
import com.lc.checker.infra.fields.FieldDefinition;
import com.lc.checker.infra.fields.FieldPoolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** Extractor-meta keys that bypass the field-pool entirely (handled by callers). */
    public static final Set<String> META_KEYS = Set.of("confidence", "raw_text", "rawtext");

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
        var parsedRows = rowProjector.project(envelope);
        return new InvoiceDocument(
                envelope,
                extractorName,
                confidence,
                imageBased,
                pages,
                extractionMs,
                rawMarkdown,
                rawText,
                parsedRows);
    }

    // ─── coercion ──────────────────────────────────────────────────────────

    private Object coerce(Object v, FieldType type) {
        if (v == null) return null;
        return switch (type) {
            case AMOUNT -> FieldCoercion.asDecimal(v);
            case DATE -> FieldCoercion.asDate(v);
            case INTEGER -> FieldCoercion.asInteger(v);
            case CURRENCY_CODE, ENUM, STRING, MULTILINE_TEXT, DOCUMENT_LIST -> FieldCoercion.asString(v);
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

}
