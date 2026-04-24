package com.lc.checker.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;

/**
 * Structured extraction of MT700 field :45A: (goods description) produced by the Part-B
 * LLM parser. The verbatim raw text lives on {@link LcDocument#field45ARaw()} so downstream
 * semantic checks can reason against both the structure and the original wording — per
 * ISBP 821 C3, the invoice description "must not conflict" with the LC wording, which
 * sometimes requires access to the exact phrasing.
 *
 * <p>All fields are nullable — the LLM is instructed to emit {@code null} rather than
 * guess when a value is not present in :45A:.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GoodsDetail(
        String goodsName,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        String currency,
        String incoterms,
        String incotermsPlace,
        String origin,
        String packing,
        String weight,
        String modelNumber
) {
}
