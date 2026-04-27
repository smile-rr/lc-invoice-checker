package com.lc.checker.infra.fields;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.common.ParserType;
import java.util.List;
import java.util.Map;

/**
 * One row of {@code lc-tag-mapping.yaml}: how to turn an MT700 tag's raw value
 * into one or more canonical field-pool keys, and which sub-field parser handles it.
 *
 * <p>{@link #fieldKeys} can have more than one entry — :32B: produces both
 * {@code credit_currency} and {@code credit_amount}; :39A: produces both
 * {@code tolerance_plus} and {@code tolerance_minus}; :45A: produces both
 * {@code goods_description} and {@code incoterms}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TagMapping(
        String tag,
        List<String> fieldKeys,
        ParserType parser,
        boolean mandatory,
        Map<String, Object> defaults,
        Validation validation
) {

    public TagMapping {
        fieldKeys = fieldKeys == null ? List.of() : List.copyOf(fieldKeys);
        defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Validation(String pattern, Integer maxLength, Integer minLength) {
    }
}
