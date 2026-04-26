package com.lc.checker.api.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.pipeline.LcCheckPipeline;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.persistence.CheckSessionStore.ExtractAttempt;
import com.lc.checker.infra.stream.CheckEventBus;
import com.lc.checker.infra.stream.CheckEventPublisher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.lc.checker.infra.observability.MdcFilter;

/**
 * Streaming counterpart to {@link LcCheckController}. The UI flow is:
 *
 * <ol>
 *   <li>{@code POST /api/v1/lc-check/start} — multipart {lc, invoice}; returns the
 *       {@code sessionId} immediately and submits the pipeline to a background
 *       executor.</li>
 *   <li>{@code GET /api/v1/lc-check/{sessionId}/stream} — SSE, fed by
 *       {@link CheckEventBus}; replays buffered events then streams live until
 *       {@code report.complete}.</li>
 *   <li>{@code GET /api/v1/lc-check/{sessionId}/lc-raw} and
 *       {@code GET /api/v1/lc-check/{sessionId}/invoice} — serve the original
 *       uploaded files back for side-by-side rendering in the UI.</li>
 * </ol>
 *
 * <p>Files are held in an in-memory map keyed by sessionId. The existing
 * {@code CheckSessionStore} TTL (60 min) is the de-facto eviction bound — we
 * clean up on pipeline completion too.
 */
@RestController
@RequestMapping("/api/v1/lc-check")
public class LcCheckStreamController {

    private static final Logger log = LoggerFactory.getLogger(LcCheckStreamController.class);
    /** SseEmitter timeout — give a very long window; the bus closes cleanly on completion. */
    private static final long SSE_TIMEOUT_MS = 30L * 60 * 1000; // 30 min

    private final LcCheckPipeline pipeline;
    private final CheckEventBus bus;
    private final Executor executor;
    private final CheckSessionStore store;

    /**
     * Session-scoped upload bundle. Held in memory only while the session is alive
     * so the UI can render the PDF + raw MT700 side-by-side.
     */
    public record SessionFiles(String lcText, String invoiceFilename, byte[] invoiceBytes) {}

    private final Map<String, SessionFiles> filesBySession = new ConcurrentHashMap<>();

    public LcCheckStreamController(LcCheckPipeline pipeline,
                                    CheckEventBus bus,
                                    @Qualifier("lcCheckExecutor") Executor executor,
                                    CheckSessionStore store) {
        this.pipeline = pipeline;
        this.bus = bus;
        this.executor = executor;
        this.store = store;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StartResponse(String sessionId,
                                 String invoiceFilename,
                                 long invoiceBytes,
                                 int lcLength) {}

    @PostMapping(path = "/start", consumes = "multipart/form-data")
    public ResponseEntity<StartResponse> start(
            @RequestPart("lc") MultipartFile lc,
            @RequestPart("invoice") MultipartFile invoice) throws IOException {
        String sessionId = MDC.get(MdcKeys.SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            // Fallback — MdcFilter normally populates this. Keeps us safe if a client
            // bypasses the filter chain (e.g. integration test).
            sessionId = UUID.randomUUID().toString();
        }

        String lcText = new String(lc.getBytes(), StandardCharsets.UTF_8);
        byte[] pdfBytes = invoice.getBytes();
        String pdfName = invoice.getOriginalFilename();

        filesBySession.put(sessionId, new SessionFiles(lcText, pdfName, pdfBytes));

        log.info("lc-check/start: session={} lcBytes={} invoiceBytes={} invoiceName={}",
                sessionId, lc.getSize(), invoice.getSize(), pdfName);

        final String sid = sessionId;
        final CheckEventPublisher publisher = bus.publisherFor(sid);
        publisher.status("session", "started",
                "Run started for invoice " + (pdfName == null ? "" : pdfName),
                Map.of("sessionId", sid,
                        "invoiceFilename", pdfName == null ? "" : pdfName,
                        "invoiceBytes", pdfBytes.length));

        executor.execute(() -> {
            MDC.put(MdcKeys.SESSION_ID, sid);
            try {
                pipeline.run(sid, lcText, pdfBytes, pdfName, publisher);
            } catch (RuntimeException e) {
                // The pipeline already emits per-stage error events and persists
                // the failure. We just need to close out the stream.
                log.error("Streaming pipeline failed for session={}: {}", sid, e.getMessage());
            } finally {
                bus.complete(sid);
                MDC.remove(MdcKeys.SESSION_ID);
            }
        });

        return ResponseEntity.ok(new StartResponse(
                sessionId, pdfName, pdfBytes.length, lcText.length()));
    }

    @GetMapping(path = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sessionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        return bus.register(sessionId, emitter);
    }

    @GetMapping(path = "/{sessionId}/lc-raw", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> lcRaw(@PathVariable String sessionId) {
        SessionFiles f = filesBySession.get(sessionId);
        if (f == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(f.lcText());
    }

    /**
     * Per-extractor attempts for a session. Includes failed attempts and the
     * non-selected ones so the UI can let an operator compare extractor
     * performance side-by-side. The {@code is_selected=true} attempt is the
     * one downstream rule checks consumed.
     */
    @GetMapping(path = "/{sessionId}/extracts")
    public ResponseEntity<List<ExtractAttempt>> extracts(@PathVariable String sessionId) {
        return ResponseEntity.ok(store.findInvoiceExtracts(sessionId));
    }

    @GetMapping(path = "/{sessionId}/invoice")
    public ResponseEntity<byte[]> invoice(@PathVariable String sessionId) {
        SessionFiles f = filesBySession.get(sessionId);
        if (f == null) return ResponseEntity.notFound().build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(f.invoiceBytes().length);
        // inline so <embed>/pdf.js can render in-browser rather than force download
        String name = f.invoiceFilename() == null ? "invoice.pdf" : f.invoiceFilename();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"");
        return ResponseEntity.ok().headers(headers).body(f.invoiceBytes());
    }
}
