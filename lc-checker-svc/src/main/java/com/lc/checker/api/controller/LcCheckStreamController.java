package com.lc.checker.api.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.pipeline.LcCheckPipeline;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.persistence.CheckSessionStore.ExtractAttempt;
import com.lc.checker.infra.persistence.CheckSessionStore.SessionFileRefs;
import com.lc.checker.infra.storage.InvoiceFileStore;
import com.lc.checker.infra.storage.Sha256;
import com.lc.checker.infra.stream.CheckEventBus;
import com.lc.checker.infra.stream.CheckEventPublisher;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
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
    private final InvoiceFileStore fileStore;

    /**
     * Session-scoped upload bundle. The in-memory map is the hot-path cache —
     * MinIO is the durable backing store keyed by SHA-256. Both are populated
     * synchronously on {@code /start}; reads prefer memory and fall back to
     * MinIO via {@link CheckSessionStore#findSessionFiles}.
     */
    public record SessionFiles(String lcText, String invoiceFilename, byte[] invoiceBytes) {}

    private final Map<String, SessionFiles> filesBySession = new ConcurrentHashMap<>();

    public LcCheckStreamController(LcCheckPipeline pipeline,
                                    CheckEventBus bus,
                                    @Qualifier("lcCheckExecutor") Executor executor,
                                    CheckSessionStore store,
                                    InvoiceFileStore fileStore) {
        this.pipeline = pipeline;
        this.bus = bus;
        this.executor = executor;
        this.store = store;
        this.fileStore = fileStore;
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

        byte[] lcBytes = lc.getBytes();
        String lcText = new String(lcBytes, StandardCharsets.UTF_8);
        byte[] pdfBytes = invoice.getBytes();
        String pdfName = invoice.getOriginalFilename();
        String lcName  = lc.getOriginalFilename();

        filesBySession.put(sessionId, new SessionFiles(lcText, pdfName, pdfBytes));

        // Content-address by SHA-256 so identical re-uploads hit the same blob.
        // Hashes also become the session row's lc_sha256 / invoice_sha256, which
        // /lc-raw + /invoice use to recover bytes from MinIO after restart.
        String lcSha      = Sha256.hex(lcBytes);
        String invoiceSha = Sha256.hex(pdfBytes);

        // Persist filenames + hashes BEFORE the pipeline runs. The session row
        // may not exist yet (LcParseStage creates it later) — recordSessionFiles
        // upserts a stub if needed.
        store.recordSessionFiles(sessionId, lcName, lcSha, pdfName, invoiceSha);

        // Sync put with bounded timeout (configured on the S3Client). Failures
        // are logged inside the file store; we don't 502 the request because
        // the pipeline can still complete and live render works from memory.
        fileStore.putLcIfAbsent(lcSha, lcName, lcBytes);
        fileStore.putInvoiceIfAbsent(invoiceSha, pdfName, pdfBytes);

        log.info("lc-check/start: session={} lcBytes={} invoiceBytes={} invoiceName={} lcSha={} invSha={}",
                sessionId, lc.getSize(), invoice.getSize(), pdfName,
                lcSha.substring(0, 12), invoiceSha.substring(0, 12));

        final String sid = sessionId;
        final CheckEventPublisher publisher = bus.publisherFor(sid);
        publisher.status("session", "started",
                "Run started for invoice " + (pdfName == null ? "" : pdfName),
                Map.of("sessionId", sid,
                        "invoiceFilename", pdfName == null ? "" : pdfName,
                        "invoiceBytes", pdfBytes.length));

        // Capture the OTel context (which contains the current HTTP request
        // span). Restoring it inside the executor task means the session span
        // we create in pipeline.run() becomes a child of the request span,
        // and every subsequent rule / extractor / LLM span lives in the SAME
        // trace tree. Without this, the pipeline runs in a thread that has
        // no current OTel context and starts an entirely separate trace.
        Context capturedOtelContext = Context.current();
        executor.execute(() -> {
            MDC.put(MdcKeys.SESSION_ID, sid);
            try (Scope scope = capturedOtelContext.makeCurrent()) {
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
        // Hot path: in-process cache populated on /start.
        SessionFiles f = filesBySession.get(sessionId);
        if (f != null) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(f.lcText());
        }
        // Cold path: server restarted (or session never registered here, e.g.
        // synchronous /lc-check entry point). Reach into MinIO via the hash.
        return store.findSessionFiles(sessionId)
                .map(SessionFileRefs::lcSha256)
                .filter(s -> s != null && !s.isBlank())
                .flatMap(fileStore::getLc)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(new String(bytes, StandardCharsets.UTF_8)))
                .orElseGet(() -> ResponseEntity.notFound().build());
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
        // Hot path: in-process cache.
        SessionFiles f = filesBySession.get(sessionId);
        if (f != null) {
            return pdfResponse(f.invoiceBytes(), f.invoiceFilename());
        }
        // Cold path: rehydrate from MinIO using the recorded hash. Filename
        // comes from the session row (preserved across restarts) so the user
        // still sees the name they uploaded under.
        return store.findSessionFiles(sessionId)
                .filter(refs -> refs.invoiceSha256() != null && !refs.invoiceSha256().isBlank())
                .flatMap(refs -> fileStore.getInvoice(refs.invoiceSha256())
                        .map(bytes -> pdfResponse(bytes, refs.invoiceFilename())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] bytes, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(bytes.length);
        // inline so <embed>/pdf.js can render in-browser rather than force download
        String name = filename == null ? "invoice.pdf" : filename;
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"");
        return ResponseEntity.ok().headers(headers).body(bytes);
    }
}
