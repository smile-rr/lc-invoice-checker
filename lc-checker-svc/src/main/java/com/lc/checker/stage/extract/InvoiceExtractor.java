package com.lc.checker.stage.extract;

/**
 * Common interface for all invoice extraction sources (vision LLM, Docling, MiniRU).
 * Each implementation runs in its own parallel lane inside
 * {@link InvoiceExtractionOrchestrator} and persists one {@code pipeline_steps} row.
 */
public interface InvoiceExtractor {

    ExtractionResult extract(byte[] pdfBytes, String filename);

    String extractorName();
}
