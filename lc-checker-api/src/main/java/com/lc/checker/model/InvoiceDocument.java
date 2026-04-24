package com.lc.checker.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Parsed commercial invoice, populated from the Docling (or MiniRU fallback) extractor
 * response. All fields are nullable — the extractor contract (CONTRACT.md) guarantees
 * keys are present but values may be {@code null} when the underlying library could not
 * confidently extract them.
 *
 * <p>Checkers MUST tolerate {@code null}; missing fields are mapped to a status per the
 * rule's {@code missing_invoice_action} policy (DISCREPANT / UNABLE_TO_VERIFY / NOT_APPLICABLE).
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
        String rawText
) {
}
