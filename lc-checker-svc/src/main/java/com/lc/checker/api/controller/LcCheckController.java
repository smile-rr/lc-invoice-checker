package com.lc.checker.api.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.domain.result.DiscrepancyReport;
import com.lc.checker.domain.session.CheckSession;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.storage.InvoiceFileStore;
import com.lc.checker.infra.storage.Sha256;
import com.lc.checker.infra.stream.CheckEvent;
import com.lc.checker.pipeline.LcCheckPipeline;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Public V1 API surface.
 *
 * <ul>
 *   <li>{@code POST /api/v1/lc-check} — sync multipart endpoint: returns a
 *       {@link DiscrepancyReport} when the pipeline finishes.</li>
 *   <li>{@code GET /api/v1/lc-check/{sessionId}/trace} — returns the unified
 *       envelope event log: {@code {sessionId, events: Event[]}}. The frontend
 *       reducer consumes both this and the live SSE stream identically.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/lc-check")
public class LcCheckController {

    private static final Logger log = LoggerFactory.getLogger(LcCheckController.class);

    private final LcCheckPipeline pipeline;
    private final CheckSessionStore store;
    private final InvoiceFileStore fileStore;

    public LcCheckController(LcCheckPipeline pipeline, CheckSessionStore store, InvoiceFileStore fileStore) {
        this.pipeline = pipeline;
        this.store = store;
        this.fileStore = fileStore;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<DiscrepancyReport> check(
            @RequestPart("lc") MultipartFile lc,
            @RequestPart("invoice") MultipartFile invoice) throws IOException {
        String sessionId = MDC.get(MdcKeys.SESSION_ID);
        log.info("Received lc-check: lcBytes={} invoiceBytes={} invoiceName={}",
                lc.getSize(), invoice.getSize(), invoice.getOriginalFilename());

        byte[] lcBytes = lc.getBytes();
        String lcText = new String(lcBytes, StandardCharsets.UTF_8);
        byte[] pdfBytes = invoice.getBytes();
        String pdfName = invoice.getOriginalFilename();
        String lcName  = lc.getOriginalFilename();

        // Persist file metadata + content-addressed bytes so the session URL
        // remains usable after restart (same contract as the streaming /start).
        String lcSha      = Sha256.hex(lcBytes);
        String invoiceSha = Sha256.hex(pdfBytes);
        store.recordSessionFiles(sessionId, lcName, lcSha, pdfName, invoiceSha);
        fileStore.putLcIfAbsent(lcSha, lcName, lcBytes);
        fileStore.putInvoiceIfAbsent(invoiceSha, pdfName, pdfBytes);

        CheckSession session = pipeline.run(sessionId, lcText, pdfBytes, pdfName);
        return ResponseEntity.ok(session.finalReport());
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TraceResponse(String sessionId, List<CheckEvent> events) {}

    /**
     * Replay the full envelope event log for the session — same shape and order
     * as the live SSE stream. Empty {@code events} when the session is unknown.
     */
    @GetMapping("/{sessionId}/trace")
    public ResponseEntity<TraceResponse> trace(@PathVariable String sessionId) {
        List<CheckEvent> events = store.findEvents(sessionId);
        return ResponseEntity.ok(new TraceResponse(sessionId, events));
    }
}
