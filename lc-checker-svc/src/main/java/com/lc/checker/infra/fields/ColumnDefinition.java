package com.lc.checker.infra.fields;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.common.FieldType;
import java.util.List;

/**
 * One column inside a {@link FieldType#TABLE} field — a row schema element.
 *
 * <p>Scoped narrower than {@link FieldDefinition}: no {@code applies_to} (the
 * parent TABLE field carries that), no {@code source_tags} (TABLE is invoice-
 * side only today). Each column carries its own
 * {@link #invoiceAliases()} dict, resolved row-locally by
 * {@link com.lc.checker.stage.extract.InvoiceFieldMapper} so that
 * column key {@code description} never collides with the top-level
 * {@code goods_description} alias.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ColumnDefinition(
        String key,
        String nameEn,
        String nameZh,
        FieldType type,
        List<String> invoiceAliases,
        List<String> enumValues,
        boolean ruleRelevant
) {

    public ColumnDefinition {
        invoiceAliases = invoiceAliases == null ? List.of() : List.copyOf(invoiceAliases);
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }
}
