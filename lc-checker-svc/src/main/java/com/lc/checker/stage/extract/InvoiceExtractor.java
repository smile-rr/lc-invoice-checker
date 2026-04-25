package com.lc.checker.stage.extract;

import com.lc.checker.infra.validation.InputValidator;

/**
 * Abstraction over a PDF → {@link ExtractionResult} conversion. One implementation per
 * source (Vision LLM in Java, Docling / MinerU as HTTP clients). The orchestrator
 * persists each extractor's result as a separate {@code stage_invoice_extract} row.
 */
public interface InvoiceExtractor {

    /**
     * Extract an invoice from raw PDF bytes.
     *
     * @param pdfBytes raw PDF content (magic-byte-validated upstream by {@code InputValidator})
     * @param filename original filename — passed through as the multipart part filename so
     *                 downstream logs/tools can correlate; null falls back to {@code "invoice.pdf"}
     * @return {@link ExtractionResult} wrapping the mapped document + any LLM traces captured
     * @throws ExtractionException on any non-recoverable failure
     */
    ExtractionResult extract(byte[] pdfBytes, String filename);

    /** Stable identifier used in DB column, traces, logs, and metrics tags. */
    String extractorName();
}
