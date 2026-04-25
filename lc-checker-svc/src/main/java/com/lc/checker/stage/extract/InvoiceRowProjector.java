package com.lc.checker.stage.extract;

import com.lc.checker.domain.common.FieldEnvelope;
import com.lc.checker.domain.common.FieldType;
import com.lc.checker.domain.common.ParsedRow;
import com.lc.checker.infra.fields.ColumnDefinition;
import com.lc.checker.infra.fields.FieldDefinition;
import com.lc.checker.infra.fields.FieldPoolRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Converts an invoice {@link FieldEnvelope} into the display-ready {@link ParsedRow} list
 * consumed by the UI's invoice fields panel — mirrors {@code ParsedRowProjector} on the LC side.
 *
 * <p>Declaration order from {@code field-pool.yaml} is preserved; groups stay together.
 * Every field that {@code applies_to: [INVOICE]} produces a row — even when the extractor
 * returned no value — so each tab in the UI shows the full canonical schema and missing
 * cells are obvious when comparing tabs side-by-side.
 *
 * <p>Legacy scalar aggregates ({@code quantity}, {@code unit}, {@code unit_price}) are skipped
 * when {@code line_items} is present — the TABLE row is the canonical source for multi-line
 * invoices.
 */
@Component
public class InvoiceRowProjector {

    /** Scalars that duplicate line_items data on multi-product invoices. */
    private static final Set<String> LEGACY_AGGREGATES = Set.of("quantity", "unit", "unit_price");

    private final FieldPoolRegistry registry;

    public InvoiceRowProjector(FieldPoolRegistry registry) {
        this.registry = registry;
    }

    public List<ParsedRow> project(FieldEnvelope envelope) {
        if (envelope == null) return List.of();
        Map<String, Object> fields = envelope.fields();
        boolean hasLineItems = fields.containsKey("line_items");

        List<ParsedRow> rows = new ArrayList<>();
        int idx = 0;
        for (FieldDefinition def : registry.appliesToInvoice()) {
            idx++;
            // Hide null legacy aggregates when line_items carries the canonical data —
            // showing both would be noise. Keep them visible if the extractor actually
            // populated them (single-line invoice path).
            if (LEGACY_AGGREGATES.contains(def.key()) && hasLineItems && !fields.containsKey(def.key())) {
                continue;
            }

            Object value = fields.get(def.key());
            ParsedRow row = (def.type() == FieldType.TABLE)
                    ? projectTable(def, value, idx)
                    : projectFlat(def, value, idx);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private ParsedRow projectFlat(FieldDefinition def, Object value, int idx) {
        String displayValue = formatValue(def.type(), value);   // null when extractor missed it
        return new ParsedRow(
                def.key(),
                def.group(),
                def.nameEn() != null ? def.nameEn() : def.key(),
                displayValue,
                List.of(),
                Map.of(),
                sortKey(def.group(), idx));
    }

    @SuppressWarnings("unchecked")
    private ParsedRow projectTable(FieldDefinition def, Object value, int idx) {
        List<Map<String, Object>> tableRows = (value instanceof List<?> list)
                ? (List<Map<String, Object>>) list
                : List.of();

        List<String> colKeys = def.columns().stream()
                .map(ColumnDefinition::key)
                .toList();
        Map<String, String> colLabels = new LinkedHashMap<>();
        for (ColumnDefinition col : def.columns()) {
            colLabels.put(col.key(), col.nameEn() != null ? col.nameEn() : col.key());
        }

        int rowCount = tableRows.size();
        // displayValue: keep null when the extractor returned nothing so the
        // section renders the same "missing" affordance as scalar fields and
        // doesn't claim "0 items" before extraction has happened.
        String displayValue = (value == null)
                ? null
                : rowCount + (rowCount == 1 ? " item" : " items");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "TABLE");
        meta.put("columns", colKeys);
        meta.put("column_labels", colLabels);
        meta.put("rows", tableRows);
        meta.put("row_count", rowCount);

        return new ParsedRow(
                def.key(),
                def.group(),
                def.nameEn() != null ? def.nameEn() : def.key(),
                displayValue,
                List.of(),
                meta,
                sortKey(def.group(), idx));
    }

    private static String formatValue(FieldType type, Object value) {
        if (value == null) return null;
        return switch (type) {
            case AMOUNT -> {
                if (value instanceof BigDecimal bd) yield bd.toPlainString();
                if (value instanceof Number n) yield BigDecimal.valueOf(n.doubleValue()).toPlainString();
                yield value.toString();
            }
            case INTEGER -> String.valueOf(value);
            case DATE -> value.toString();
            case MULTILINE_TEXT, STRING, ENUM, CURRENCY_CODE -> {
                String s = value.toString().trim();
                yield s.isEmpty() ? null : s;
            }
            default -> value.toString();
        };
    }

    private static String sortKey(String group, int idx) {
        String prefix = switch (group != null ? group : "") {
            case "header"    -> "1";
            case "amount"    -> "2";
            case "parties"   -> "3";
            case "shipment"  -> "4";
            case "documents" -> "5";
            case "items"     -> "6";
            case "meta"      -> "7";
            default          -> "8";
        };
        return prefix + "_" + String.format("%03d", idx);
    }
}
