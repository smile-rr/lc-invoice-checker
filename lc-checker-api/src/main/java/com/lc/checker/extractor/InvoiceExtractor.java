package com.lc.checker.extractor;

import com.lc.checker.model.InvoiceDocument;

/**
 * Abstraction over a PDF → {@code InvoiceDocument} conversion. One implementation per
 * external service (Docling in V1, MiniRU in V1.5). Keeps the pipeline agnostic of
 * which extractor served the request — the service name and confidence live on the
 * returned document for {@code /trace} and routing decisions.
 */
public interface InvoiceExtractor {

    /**
     * Extract an invoice from raw PDF bytes.
     *
     * @param pdfBytes raw PDF content (magic-byte-validated upstream by {@code InputValidator})
     * @param filename original filename — passed through as the multipart part filename so
     *                 downstream logs/tools can correlate; null falls back to {@code "invoice.pdf"}
     * @return fully populated {@link InvoiceDocument}
     * @throws ExtractionException on any non-recoverable failure
     */
    InvoiceDocument extract(byte[] pdfBytes, String filename);

    /** Stable identifier used in traces, logs, and metrics tags. */
    String extractorName();
}
