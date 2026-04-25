package com.lc.checker.stage.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.stage.extract.vision.VisionLlmExtractor;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.infra.persistence.CheckSessionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.lc.checker.domain.result.LlmTrace;
import com.lc.checker.domain.rule.Rule;

/**
 * Stage 1b orchestrator. Runs every enabled extractor sequentially against the same PDF,
 * persists one {@code stage_invoice_extract} row per source, then marks exactly one row
 * as {@code is_selected=true} to drive downstream checks.
 *
 * <p>Selection rule: first source in priority order that returned {@code SUCCESS} AND
 * {@code confidence >= extractor.confidence-threshold}. If none meets the threshold, the
 * highest-confidence successful source is selected. If all sources failed, throws.
 *
 * <p>Per user direction we run sources in sequence (not parallel) for simplicity; the
 * per-source results land in the DB so an operator can compare them via
 * {@code v_invoice_extracts} without re-running.
 */
@Component
@Primary
public class InvoiceExtractionOrchestrator implements InvoiceExtractor {

    private static final Logger log = LoggerFactory.getLogger(InvoiceExtractionOrchestrator.class);

    private final List<InvoiceExtractor> chain = new ArrayList<>();
    private final CheckSessionStore store;
    private final double confidenceThreshold;
    private volatile String lastUsed = "vision";

    public InvoiceExtractionOrchestrator(
            VisionLlmExtractor visionExtractor,
            @Qualifier(ExtractorClientConfig.DOCLING_REST_CLIENT) RestClient doclingClient,
            @Qualifier(ExtractorClientConfig.MINERU_REST_CLIENT)  RestClient mineruClient,
            ExtractorResponseMapper responseMapper,
            ObjectMapper json,
            CheckSessionStore store,
            @Value("${extractor.retries:1}") int retries,
            @Value("${extractor.retry-backoff-ms:500}") long retryBackoffMs,
            @Value("${extractor.confidence-threshold:0.80}") double confidenceThreshold,
            @Value("${extractor.vision-enabled:true}")  boolean visionEnabled,
            @Value("${extractor.docling-enabled:false}") boolean doclingEnabled,
            @Value("${extractor.mineru-enabled:false}")  boolean mineruEnabled) {

        this.store = store;
        this.confidenceThreshold = confidenceThreshold;

        if (visionEnabled) {
            chain.add(visionExtractor);
        }
        if (doclingEnabled) {
            chain.add(new HttpInvoiceExtractor(doclingClient, "docling", responseMapper, json, retries, retryBackoffMs));
        }
        if (mineruEnabled) {
            chain.add(new HttpInvoiceExtractor(mineruClient, "mineru", responseMapper, json, retries, retryBackoffMs));
        }
        if (chain.isEmpty()) {
            throw new IllegalStateException(
                    "At least one extractor must be enabled (vision/docling/mineru).");
        }
        log.info("InvoiceExtractionOrchestrator initialised: sources={} threshold={}",
                chain.stream().map(InvoiceExtractor::extractorName).toList(),
                confidenceThreshold);
    }

    /**
     * Simple single-result flow for callers that don't have a session (debug / tests).
     * Runs the first enabled source and returns its document.
     */
    @Override
    public ExtractionResult extract(byte[] pdfBytes, String filename) {
        ExtractionException last = null;
        for (InvoiceExtractor e : chain) {
            try {
                ExtractionResult r = e.extract(pdfBytes, filename);
                lastUsed = e.extractorName();
                return r;
            } catch (ExtractionException ex) {
                last = ex;
                log.warn("Source {} failed: {}", e.extractorName(), ex.getMessage());
            }
        }
        throw last != null ? last : new ExtractionException("none", ExtractorErrorCode.UNKNOWN,
                "No extractor configured");
    }

    /**
     * Stage 1b main entry. Runs every enabled source, persists each to DB, selects one.
     *
     * @return the selected {@link InvoiceDocument} — the one downstream rule checks consume.
     */
    public InvoiceDocument runAndPersist(String sessionId, byte[] pdfBytes, String filename) {
        record Attempt(String source, boolean success, InvoiceDocument doc,
                       List<com.lc.checker.domain.result.LlmTrace> traces, String error, long durationMs) {}

        List<Attempt> attempts = new ArrayList<>(chain.size());

        for (InvoiceExtractor e : chain) {
            String source = e.extractorName();
            Instant stepStart = Instant.now();
            long t0 = System.currentTimeMillis();
            try {
                ExtractionResult r = e.extract(pdfBytes, filename);
                long dur = System.currentTimeMillis() - t0;
                Instant stepEnd = Instant.now();
                attempts.add(new Attempt(source, true, r.document(), r.llmCalls(), null, dur));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("document", r.document());
                result.put("llm_calls", r.llmCalls());
                result.put("is_selected", false);  // markExtractSelected flips one later
                store.putStep(sessionId, "invoice_extract", source, "SUCCESS",
                        stepStart, stepEnd, result, null);
                log.info("Stage 1b source={} SUCCESS confidence={} duration={}ms",
                        source, r.document().extractorConfidence(), dur);
            } catch (ExtractionException ex) {
                long dur = System.currentTimeMillis() - t0;
                Instant stepEnd = Instant.now();
                List<com.lc.checker.domain.result.LlmTrace> trc = ex.getLlmTrace() == null
                        ? List.of() : List.of(ex.getLlmTrace());
                attempts.add(new Attempt(source, false, null, trc, ex.getMessage(), dur));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("document", null);
                result.put("llm_calls", trc);
                result.put("is_selected", false);
                store.putStep(sessionId, "invoice_extract", source, "FAILED",
                        stepStart, stepEnd, result, ex.getMessage());
                log.warn("Stage 1b source={} FAILED duration={}ms error={}",
                        source, dur, ex.getMessage());
            }
        }

        Attempt selected = null;
        // Rule 1: first SUCCESS in priority order whose confidence >= threshold
        for (Attempt a : attempts) {
            if (a.success() && a.doc().extractorConfidence() >= confidenceThreshold) {
                selected = a;
                break;
            }
        }
        // Rule 2: fallback — highest-confidence successful attempt
        if (selected == null) {
            for (Attempt a : attempts) {
                if (!a.success()) continue;
                if (selected == null || a.doc().extractorConfidence() > selected.doc().extractorConfidence()) {
                    selected = a;
                }
            }
        }
        if (selected == null) {
            throw new ExtractionException("none", ExtractorErrorCode.UNKNOWN,
                    "All extractors failed; see stage_invoice_extract rows for details");
        }

        store.markExtractSelected(sessionId, selected.source());
        lastUsed = selected.source();
        log.info("Stage 1b selected source={} (confidence={})",
                selected.source(), selected.doc().extractorConfidence());
        return selected.doc();
    }

    @Override
    public String extractorName() {
        return lastUsed;
    }

    /**
     * Debug helper — runs every enabled source in chain order and returns a plain-text
     * dump. Used by {@code /api/v1/debug/invoice/compare}. Does NOT persist to DB.
     */
    public String compareAllAsText(byte[] pdfBytes, String filename) {
        StringBuilder sb = new StringBuilder();
        for (InvoiceExtractor e : chain) {
            long start = System.currentTimeMillis();
            sb.append("=== ").append(e.extractorName()).append(" ===\n");
            try {
                ExtractionResult r = e.extract(pdfBytes, filename);
                InvoiceDocument doc = r.document();
                long ms = System.currentTimeMillis() - start;
                sb.append("status: OK\n");
                sb.append("confidence: ").append(doc.extractorConfidence()).append("\n");
                sb.append("image_based: ").append(doc.imageBased()).append("\n");
                sb.append("pages: ").append(doc.pages()).append("\n");
                sb.append("extraction_ms: ").append(ms).append("\n");
                sb.append("invoice_number: ").append(doc.invoiceNumber()).append("\n");
                sb.append("invoice_date: ").append(doc.invoiceDate()).append("\n");
                sb.append("seller: ").append(doc.sellerName()).append("\n");
                sb.append("seller_address: ").append(doc.sellerAddress()).append("\n");
                sb.append("buyer: ").append(doc.buyerName()).append("\n");
                sb.append("buyer_address: ").append(doc.buyerAddress()).append("\n");
                sb.append("goods: ").append(doc.goodsDescription()).append("\n");
                sb.append("quantity: ").append(doc.quantity())
                  .append(" ").append(doc.unit()).append("\n");
                sb.append("unit_price: ").append(doc.unitPrice()).append("\n");
                sb.append("total_amount: ").append(doc.totalAmount())
                  .append(" ").append(doc.currency()).append("\n");
                sb.append("lc_reference: ").append(doc.lcReference()).append("\n");
                sb.append("trade_terms: ").append(doc.tradeTerms()).append("\n");
                sb.append("port_of_loading: ").append(doc.portOfLoading()).append("\n");
                sb.append("port_of_discharge: ").append(doc.portOfDischarge()).append("\n");
                sb.append("country_of_origin: ").append(doc.countryOfOrigin()).append("\n");
                sb.append("signed: ").append(doc.signed()).append("\n");
                sb.append("llm_calls: ").append(r.llmCalls().size()).append("\n");
                if (doc.rawText() != null && !doc.rawText().isBlank()) {
                    sb.append("\nraw_text:\n").append(doc.rawText()).append("\n");
                }
            } catch (Exception ex) {
                long ms = System.currentTimeMillis() - start;
                sb.append("status: FAILED\n");
                sb.append("extraction_ms: ").append(ms).append("\n");
                sb.append("error: ").append(ex.getMessage()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
