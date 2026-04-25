package com.lc.checker.stage.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.stage.extract.vision.VisionLlmExtractor;
import com.lc.checker.domain.invoice.InvoiceDocument;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.stream.CheckEvent;
import com.lc.checker.infra.stream.CheckEventPublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    /**
     * The full ordered chain in priority order: remote_vision (lane A) first,
     * then docling → mineru → local_vision (lane B sequential bundle). Selection
     * picks the first SUCCESS above threshold in this order; the order also
     * surfaces in {@code /extracts} response and the UI's tab order.
     */
    private final List<InvoiceExtractor> chain = new ArrayList<>();

    /** Lane A — runs in parallel with lane B. (Slice 2 wires the parallelism.) */
    private final InvoiceExtractor remoteVision;

    /** Lane B — sequential bundle of local-resource extractors. */
    private final List<InvoiceExtractor> sequentialBundle = new ArrayList<>();

    private final CheckSessionStore store;
    private final Executor executor;
    private final double confidenceThreshold;
    private volatile String lastUsed = "remote_vision";

    public InvoiceExtractionOrchestrator(
            @Qualifier("remoteVisionExtractor") VisionLlmExtractor remoteVision,
            @Qualifier("localVisionExtractor") ObjectProvider<VisionLlmExtractor> localVisionProvider,
            @Qualifier(ExtractorClientConfig.DOCLING_REST_CLIENT) RestClient doclingClient,
            @Qualifier(ExtractorClientConfig.MINERU_REST_CLIENT)  RestClient mineruClient,
            ExtractorResponseMapper responseMapper,
            ObjectMapper json,
            CheckSessionStore store,
            @Qualifier("lcCheckExecutor") Executor executor,
            @Value("${extractor.retries:1}") int retries,
            @Value("${extractor.retry-backoff-ms:500}") long retryBackoffMs,
            @Value("${extractor.confidence-threshold:0.80}") double confidenceThreshold,
            @Value("${extractor.vision-enabled:true}")        boolean visionEnabled,
            @Value("${extractor.docling-enabled:false}")      boolean doclingEnabled,
            @Value("${extractor.mineru-enabled:false}")       boolean mineruEnabled,
            @Value("${extractor.local-vision-enabled:false}") boolean localVisionEnabled) {

        this.store = store;
        this.executor = executor;
        this.confidenceThreshold = confidenceThreshold;
        this.remoteVision = visionEnabled ? remoteVision : null;

        if (visionEnabled) {
            chain.add(remoteVision);
        }
        if (doclingEnabled) {
            HttpInvoiceExtractor docling = new HttpInvoiceExtractor(
                    doclingClient, "docling", responseMapper, json, retries, retryBackoffMs);
            chain.add(docling);
            sequentialBundle.add(docling);
        }
        if (mineruEnabled) {
            HttpInvoiceExtractor mineru = new HttpInvoiceExtractor(
                    mineruClient, "mineru", responseMapper, json, retries, retryBackoffMs);
            chain.add(mineru);
            sequentialBundle.add(mineru);
        }
        if (localVisionEnabled) {
            VisionLlmExtractor local = localVisionProvider.getIfAvailable();
            if (local == null) {
                throw new IllegalStateException(
                        "extractor.local-vision-enabled=true but no localVisionExtractor bean exists. "
                        + "Check the @ConditionalOnProperty wiring and the local-vision config block.");
            }
            chain.add(local);
            sequentialBundle.add(local);
        }
        if (chain.isEmpty()) {
            throw new IllegalStateException(
                    "At least one extractor must be enabled (remote_vision / docling / mineru / local_vision).");
        }
        log.info("InvoiceExtractionOrchestrator initialised: sources={} threshold={} laneA={} laneB={}",
                chain.stream().map(InvoiceExtractor::extractorName).toList(),
                confidenceThreshold,
                this.remoteVision != null ? this.remoteVision.extractorName() : "(none)",
                sequentialBundle.stream().map(InvoiceExtractor::extractorName).toList());
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

    /** Per-source extraction outcome — produced by {@link #runAndPersistOne}. */
    private record Attempt(String source, boolean success, InvoiceDocument doc,
                           List<com.lc.checker.domain.result.LlmTrace> traces,
                           String error, long durationMs) {}

    /**
     * Stage 1b main entry. Runs the configured chain in two lanes:
     * <ul>
     *   <li>Lane A — remote_vision (if enabled). Cloud network call, no local resources.</li>
     *   <li>Lane B — sequential bundle of [docling, mineru, local_vision] (whichever are
     *       enabled). All consume local CPU/RAM so they run one at a time within the lane.</li>
     * </ul>
     * Both lanes execute concurrently on {@code lcCheckExecutor}; total wall-clock is
     * roughly {@code max(laneA, laneB)}. Each source persists its own row to
     * {@code pipeline_steps} regardless of pass/fail (inside {@link #runAndPersistOne}),
     * so a partial failure in one lane never loses the other lane's results.
     *
     * @return the selected {@link InvoiceDocument} — the one downstream rule checks consume.
     */
    public InvoiceDocument runAndPersist(String sessionId, byte[] pdfBytes, String filename) {
        return runAndPersist(sessionId, pdfBytes, filename, CheckEventPublisher.NOOP);
    }

    public InvoiceDocument runAndPersist(String sessionId, byte[] pdfBytes, String filename,
                                          CheckEventPublisher publisher) {
        // Lane A: remote_vision (single attempt).
        CompletableFuture<List<Attempt>> laneA = CompletableFuture.supplyAsync(() -> {
            if (remoteVision == null) return List.of();
            return List.of(runAndPersistOne(remoteVision, sessionId, pdfBytes, filename, publisher));
        }, executor);

        // Lane B: docling → mineru → local_vision, sequential within the lane.
        CompletableFuture<List<Attempt>> laneB = CompletableFuture.supplyAsync(() -> {
            List<Attempt> out = new ArrayList<>(sequentialBundle.size());
            for (InvoiceExtractor e : sequentialBundle) {
                out.add(runAndPersistOne(e, sessionId, pdfBytes, filename, publisher));
            }
            return out;
        }, executor);

        // Wait for both lanes; each lane's body already swallows ExtractionException
        // into a FAILED Attempt, so allOf().join() should never throw under normal
        // operation. Wrap defensively anyway.
        try {
            CompletableFuture.allOf(laneA, laneB).join();
        } catch (Exception e) {
            log.error("Unexpected lane failure in invoice extraction: {}", e.getMessage(), e);
        }

        // Concatenate attempts in CHAIN priority order (so selection logic walks them
        // in the same order regardless of which lane finished first).
        Map<String, Attempt> bySource = new LinkedHashMap<>();
        laneA.getNow(List.of()).forEach(a -> bySource.put(a.source(), a));
        laneB.getNow(List.of()).forEach(a -> bySource.put(a.source(), a));
        List<Attempt> attempts = new ArrayList<>(chain.size());
        for (InvoiceExtractor e : chain) {
            Attempt a = bySource.get(e.extractorName());
            if (a != null) attempts.add(a);
        }

        // Selection rule (unchanged): first SUCCESS above threshold in chain order;
        // fallback: highest-confidence success; if all failed, throw.
        Attempt selected = null;
        for (Attempt a : attempts) {
            if (a.success() && a.doc().extractorConfidence() >= confidenceThreshold) {
                selected = a;
                break;
            }
        }
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

    /**
     * Run one extractor: capture timing, persist a row to {@code pipeline_steps}
     * (SUCCESS or FAILED), and return the {@link Attempt} for selection.
     *
     * <p>Never throws — extractor exceptions become FAILED attempts so a single
     * source's failure doesn't abort the rest of its lane.
     */
    private Attempt runAndPersistOne(InvoiceExtractor e, String sessionId,
                                     byte[] pdfBytes, String filename,
                                     CheckEventPublisher publisher) {
        String source = e.extractorName();
        publisher.emit(CheckEvent.of(CheckEvent.Type.EXTRACT_SOURCE_STARTED,
                Map.of("source", source)));
        Instant stepStart = Instant.now();
        long t0 = System.currentTimeMillis();
        try {
            ExtractionResult r = e.extract(pdfBytes, filename);
            long dur = System.currentTimeMillis() - t0;
            Instant stepEnd = Instant.now();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("document", r.document());
            result.put("llm_calls", r.llmCalls());
            result.put("is_selected", false);  // markExtractSelected flips one later
            store.putStep(sessionId, "invoice_extract", source, "SUCCESS",
                    stepStart, stepEnd, result, null);
            log.info("Stage 1b source={} SUCCESS confidence={} duration={}ms",
                    source, r.document().extractorConfidence(), dur);
            publisher.emit(CheckEvent.of(CheckEvent.Type.EXTRACT_SOURCE_COMPLETED,
                    Map.of(
                            "source", source,
                            "success", true,
                            "confidence", r.document().extractorConfidence(),
                            "durationMs", dur,
                            "imageBased", r.document().imageBased(),
                            "pages", r.document().pages())));
            return new Attempt(source, true, r.document(), r.llmCalls(), null, dur);
        } catch (ExtractionException ex) {
            long dur = System.currentTimeMillis() - t0;
            Instant stepEnd = Instant.now();
            List<com.lc.checker.domain.result.LlmTrace> trc = ex.getLlmTrace() == null
                    ? List.of() : List.of(ex.getLlmTrace());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("document", null);
            result.put("llm_calls", trc);
            result.put("is_selected", false);
            store.putStep(sessionId, "invoice_extract", source, "FAILED",
                    stepStart, stepEnd, result, ex.getMessage());
            log.warn("Stage 1b source={} FAILED duration={}ms error={}",
                    source, dur, ex.getMessage());
            publisher.emit(CheckEvent.of(CheckEvent.Type.EXTRACT_SOURCE_COMPLETED,
                    Map.of(
                            "source", source,
                            "success", false,
                            "durationMs", dur,
                            "error", ex.getMessage() == null ? "" : ex.getMessage())));
            return new Attempt(source, false, null, trc, ex.getMessage(), dur);
        } catch (RuntimeException ex) {
            long dur = System.currentTimeMillis() - t0;
            Instant stepEnd = Instant.now();
            String msg = "unexpected: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("document", null);
            result.put("llm_calls", List.of());
            result.put("is_selected", false);
            store.putStep(sessionId, "invoice_extract", source, "FAILED",
                    stepStart, stepEnd, result, msg);
            log.error("Stage 1b source={} crashed duration={}ms", source, dur, ex);
            publisher.emit(CheckEvent.of(CheckEvent.Type.EXTRACT_SOURCE_COMPLETED,
                    Map.of(
                            "source", source,
                            "success", false,
                            "durationMs", dur,
                            "error", msg)));
            return new Attempt(source, false, null, List.of(), msg, dur);
        }
    }

    @Override
    public String extractorName() {
        return lastUsed;
    }

    /**
     * Names of the extractor sources currently configured to run, in chain
     * priority order. The UI uses this to pre-populate PENDING cards before
     * any SSE event arrives, so the user immediately sees the shape of the
     * work (e.g. "3 cards, all queued") rather than empty space.
     */
    public List<String> configuredSources() {
        return chain.stream().map(InvoiceExtractor::extractorName).toList();
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
