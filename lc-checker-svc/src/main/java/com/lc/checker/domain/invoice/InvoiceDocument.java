package com.lc.checker.domain.invoice;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.common.FieldEnvelope;
import com.lc.checker.domain.common.ParsedRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Parsed commercial invoice, populated by the vision LLM extractor or one of the
 * HTTP extractors (docling/mineru). All scalar fields are nullable — extractors
 * guarantee keys but not values.
 *
 * <p>Two parallel views, mirroring the LC side:
 * <ul>
 *   <li>The typed scalar accessors below ({@code invoiceNumber()}, {@code totalAmount()}, ...)
 *       — kept for back-compat with rules that reference {@code inv.totalAmount},
 *       {@code inv.currency}, etc.</li>
 *   <li>{@link #envelope} — generic registry-keyed map from {@code field-pool.yaml}.
 *       New rules and the API/UI layer should prefer this view; unknown extractor keys
 *       (a Bangladeshi exporter's {@code chamber_seal_no}, an Italian invoice's
 *       {@code partita_iva}) land in {@code envelope.extras} verbatim instead of
 *       being silently dropped.</li>
 * </ul>
 *
 * <p>{@link #rawMarkdown()} and {@link #rawText()} are preserved verbatim so Type-B
 * semantic checks can re-read the source when structured fields are insufficient.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record InvoiceDocument(
        String invoiceNumber,
        LocalDate invoiceDate,
        String sellerName,
        String sellerAddress,
        String buyerName,
        String buyerAddress,
        String goodsDescription,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal totalAmount,
        String currency,
        String lcReference,
        String tradeTerms,
        String portOfLoading,
        String portOfDischarge,
        String countryOfOrigin,
        Boolean signed,

        // --- Extractor provenance (for /trace + fallback decisions) ---
        String extractorUsed,
        double extractorConfidence,
        boolean imageBased,
        int pages,
        long extractionMs,
        String rawMarkdown,
        String rawText,

        // --- Generic envelope (canonical-keyed map; extras preserve unknown keys) ---
        FieldEnvelope envelope,

        // --- Display-ready rows for the invoice fields panel ---
        List<ParsedRow> parsedRows
) {

    public InvoiceDocument {
        if (envelope == null) {
            envelope = FieldEnvelope.empty("INVOICE");
        }
        parsedRows = parsedRows == null ? List.of() : List.copyOf(parsedRows);
    }
}
