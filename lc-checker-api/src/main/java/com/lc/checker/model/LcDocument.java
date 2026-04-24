package com.lc.checker.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Structured view of an MT700 credit. Produced in two passes:
 *
 * <ol>
 *   <li><b>Part A</b> — deterministic regex parsing of tag-value fields (Mt700Parser).
 *       Populates the scalar getters and {@link #rawFields()}.</li>
 *   <li><b>Part B</b> — LLM-backed structuring of the free-text tags :45A: :46A: :47A:
 *       (Mt700LlmParser). Populates {@link #goodsDetail()}, {@link #documentsRequired()},
 *       {@link #conditions47A()} and the {@code *_raw} verbatim fields.</li>
 * </ol>
 *
 * <p>The record is immutable; the two-pass build happens via a mutable builder in the
 * parser package. All LLM-structured fields default to {@code null} / empty lists so
 * Stage-2 activation and Stage-3 execution can fall through safely when Part B is
 * skipped or fails.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LcDocument(
        // --- Part A ---------------------------------------------------------
        String lcNumber,                // :20:
        LocalDate issueDate,            // :31C:
        LocalDate expiryDate,           // :31D:
        String expiryPlace,             // :31D:
        String currency,                // :32B: leading 3 chars
        BigDecimal amount,              // :32B: remainder (European decimal supported)
        int tolerancePlus,              // :39A: first number, percent
        int toleranceMinus,             // :39A: second number, percent
        String maxAmountFlag,           // :39B:
        String partialShipment,         // :43P:
        String transhipment,            // :43T:
        String placeOfReceipt,          // :44A:
        String placeOfDelivery,         // :44B:
        LocalDate latestShipmentDate,   // :44C:
        String shipmentPeriod,          // :44D:
        String portOfLoading,           // :44E:
        String portOfDischarge,         // :44F:
        int presentationDays,           // :48:  default 21 per UCP 600 Art. 14(c)
        String applicableRules,         // :40E:
        String applicantName,           // :50:
        String applicantAddress,
        String beneficiaryName,         // :59:
        String beneficiaryAddress,

        // --- Part B raw preservation ---------------------------------------
        String field45ARaw,
        String field46ARaw,
        String field47ARaw,

        // --- Part B structured results -------------------------------------
        GoodsDetail goodsDetail,
        List<DocumentRequirement> documentsRequired,
        List<Condition47A> conditions47A,

        // --- Trace / forensic ----------------------------------------------
        Map<String, String> rawFields
) {

    public LcDocument {
        documentsRequired = documentsRequired == null ? List.of() : List.copyOf(documentsRequired);
        conditions47A = conditions47A == null ? List.of() : List.copyOf(conditions47A);
        rawFields = rawFields == null ? Map.of() : Map.copyOf(rawFields);
    }
}
