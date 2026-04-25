package com.lc.checker.infra.fields;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.common.FieldType;
import java.util.List;

/**
 * One row of {@code field-pool.yaml}. The canonical record of a single named
 * concept ("credit_amount", "invoice_date", "documents_required") and where it
 * is sourced from on each document side.
 *
 * <p>{@link #appliesTo} is the discriminator that lets the same registry serve
 * LC parsing and invoice extraction. {@link #invoiceAliases} are the variant
 * names extractors might emit for the same concept (e.g. {@code total} or
 * {@code total_amount} for {@code credit_amount}); the alias table replaces
 * the previous hand-written {@code normaliseKey} switch.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldDefinition(
        String key,
        String nameEn,
        String nameZh,
        FieldType type,
        String descriptionZh,
        List<String> appliesTo,        // any of: "LC", "INVOICE"
        List<String> sourceTags,       // MT700 tags this field is sourced from on the LC side
        List<String> invoiceAliases,   // extractor key variants for the invoice side
        List<String> enumValues,       // populated only when type == ENUM
        boolean ruleRelevant,
        Object defaultValue,           // optional default if absent
        String group,                  // optional UI grouping label (e.g. "header", "amount")
        List<ColumnDefinition> columns,// populated only when type == TABLE; row schema
        String extractionHint          // optional per-field guidance rendered into the LLM prompt
) {

    public FieldDefinition {
        appliesTo = appliesTo == null ? List.of() : List.copyOf(appliesTo);
        sourceTags = sourceTags == null ? List.of() : List.copyOf(sourceTags);
        invoiceAliases = invoiceAliases == null ? List.of() : List.copyOf(invoiceAliases);
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    public boolean appliesToLc() {
        return appliesTo.contains("LC");
    }

    public boolean appliesToInvoice() {
        return appliesTo.contains("INVOICE");
    }
}
