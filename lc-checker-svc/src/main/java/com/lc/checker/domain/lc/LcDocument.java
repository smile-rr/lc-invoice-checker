package com.lc.checker.domain.lc;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.common.DocumentRequirement;
import com.lc.checker.domain.common.FieldEnvelope;
import com.lc.checker.domain.common.ParsedRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Structured view of an MT700 credit. Produced in a single pass by {@code Mt700Parser}
 * using deterministic regex / Prowide — no LLM is involved at this stage. The free-text
 * tags :45A:, :46A:, :47A: are preserved verbatim as {@code field45ARaw / field46ARaw /
 * field47ARaw} so downstream rule checks (Type B / AB) can feed them directly to the
 * semantic LLM where needed.
 *
 * <p>Two parallel views of the parsed data are exposed:
 * <ul>
 *   <li>The typed scalar accessors below ({@code lcNumber()}, {@code amount()}, ...) —
 *       the legacy contract used by existing SpEL rules ({@code lc.amount}, {@code lc.lcNumber}).</li>
 *   <li>{@link #envelope} — the generic, registry-driven map keyed by canonical names from
 *       {@code field-pool.yaml}. New rules and the API/UI layer should prefer this view.</li>
 * </ul>
 *
 * <p>{@link #documentsRequired} is the structured form of :46A: produced by
 * {@code DocumentListParser}; rules that previously had to grep {@code field46ARaw} can
 * now query a typed list.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LcDocument(
        String lcNumber,                // :20:
        LocalDate issueDate,            // :31C:
        LocalDate expiryDate,           // :31D:
        String expiryPlace,             // :31D:
        String currency,                // :32B: leading 3 chars
        BigDecimal amount,              // :32B: remainder (European decimal supported)
        boolean aboutCreditAmount,      // :32B: raw text contains ABOUT / APPROXIMATELY
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

        // Raw free-text preservation — fed directly to LLM rule checks when needed
        String field45ARaw,
        String field46ARaw,
        String field47ARaw,

        // Every Block-4 tag exactly as the sender wrote it (key = tag name incl. option
        // letter e.g. "50F", "59A", "71B"); a whitelist is never applied, so novel or
        // SRU-added tags are always reachable here.
        Map<String, String> rawFields,

        // SWIFT user-header Block 3 tags (:108: msg reference, :103: service id,
        // :111: / :119: flags). Diagnostic / audit only — no rule reads these today.
        Map<String, String> headerFields,

        // ── Generic envelope (canonical-keyed map) — the contract for API/UI/new rules ──
        FieldEnvelope envelope,

        // ── Structured :46A: — the highest-value parse upgrade over raw text ─────────────
        List<DocumentRequirement> documentsRequired,

        // ── Display-ready row list for the parsed pane (computed by ParsedRowProjector) ──
        // The frontend renders these 1:1; no per-tag grouping or value formatting in UI.
        List<ParsedRow> parsedRows
) {

    public LcDocument {
        rawFields = rawFields == null ? Map.of() : Map.copyOf(rawFields);
        headerFields = headerFields == null ? Map.of() : Map.copyOf(headerFields);
        documentsRequired = documentsRequired == null ? List.of() : List.copyOf(documentsRequired);
        parsedRows = parsedRows == null ? List.of() : List.copyOf(parsedRows);
        if (envelope == null) {
            envelope = FieldEnvelope.empty("LC");
        }
    }
}
