package com.lc.checker.api.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.persistence.CheckSessionStore.ExtractAttempt;
import com.lc.checker.infra.persistence.CheckSessionStore.SessionFileRefs;
import com.lc.checker.infra.queue.JobDispatcher;
import com.lc.checker.infra.samples.SampleRefStore;
import com.lc.checker.infra.storage.InvoiceFileStore;
import com.lc.checker.infra.storage.Sha256;
import com.lc.checker.infra.stream.CheckEventBus;
import com.lc.checker.infra.validation.InputValidator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

/**
 * Upload + SSE entry points for the LC check.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@code POST /api/v1/lc-check/start} — multipart {lc, invoice};
 *       validates the input, persists files to MinIO, INSERTs the session row
 *       in {@code QUEUED} state, and returns the session id immediately.
 *       The actual pipeline runs later, claimed by {@link JobDispatcher}.</li>
 *   <li>{@code GET /api/v1/lc-check/{sessionId}/stream} — SSE, fed by
 *       {@link CheckEventBus}; while QUEUED, the dispatcher emits queue
 *       position events; once dequeued, normal pipeline events flow.</li>
 *   <li>{@code GET /api/v1/lc-check/{sessionId}/lc-raw} and
 *       {@code GET /api/v1/lc-check/{sessionId}/invoice} — serve the original
 *       uploaded files back for side-by-side rendering in the UI.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/lc-check")
public class LcCheckStreamController {

    private static final Logger log = LoggerFactory.getLogger(LcCheckStreamController.class);
    /** SseEmitter timeout — give a very long window; the bus closes cleanly on completion. */
    private static final long SSE_TIMEOUT_MS = 30L * 60 * 1000; // 30 min

    private final CheckEventBus bus;
    private final CheckSessionStore store;
    private final InvoiceFileStore fileStore;
    private final SampleRefStore sampleRef;
    private final InputValidator validator;
    private final JobDispatcher dispatcher;

    /**
     * Session-scoped upload bundle, kept hot for the {@code /lc-raw} +
     * {@code /invoice} endpoints. MinIO is the durable backing store keyed by
     * SHA-256; this is just a cache to avoid a round-trip on the request that
     * just uploaded.
     */
    public record SessionFiles(String lcText, String invoiceFilename, byte[] invoiceBytes) {}

    private final java.util.Map<String, SessionFiles> filesBySession = new ConcurrentHashMap<>();

    public LcCheckStreamController(CheckEventBus bus,
                                    CheckSessionStore store,
                                    InvoiceFileStore fileStore,
                                    SampleRefStore sampleRef,
                                    InputValidator validator,
                                    JobDispatcher dispatcher) {
        this.bus = bus;
        this.store = store;
        this.fileStore = fileStore;
        this.sampleRef = sampleRef;
        this.validator = validator;
        this.dispatcher = dispatcher;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StartResponse(String sessionId,
                                 String status,
                                 int queuePosition,
                                 String invoiceFilename,
                                 long invoiceBytes,
                                 int lcLength) {}

    @PostMapping(path = "/start", consumes = "multipart/form-data")
    public ResponseEntity<StartResponse> start(
            @RequestPart("lc") MultipartFile lc,
            @RequestPart("invoice") MultipartFile invoice) throws IOException {
        String sessionId = MDC.get(MdcKeys.SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        byte[] lcBytes = lc.getBytes();
        byte[] pdfBytes = invoice.getBytes();
        String pdfName = invoice.getOriginalFilename();
        String lcName  = lc.getOriginalFilename();

        // Pre-pipeline validation. Throws on bad input — GlobalExceptionHandler
        // converts to a 400 ApiError before we touch storage or the queue.
        String lcText = new String(lcBytes, StandardCharsets.UTF_8);
        validator.validateLcText(lcText);
        validator.validatePdf(pdfBytes);

        // Cache uploaded bytes for the immediate /lc-raw + /invoice reads.
        filesBySession.put(sessionId, new SessionFiles(lcText, pdfName, pdfBytes));

        // Content-address by SHA-256 so identical re-uploads hit the same blob.
        // Hashes also become the session row's lc_sha256 / invoice_sha256, which
        // /lc-raw + /invoice use to recover bytes from MinIO after restart.
        String lcSha      = Sha256.hex(lcBytes);
        String invoiceSha = Sha256.hex(pdfBytes);

        // Durable persistence: bytes to MinIO first (so the dispatcher can later
        // re-read them), then INSERT the session row directly in QUEUED state,
        // then attach file metadata. Enqueue-first avoids a momentary RUNNING
        // phantom row that would briefly show up in queueSnapshot().
        boolean lcStored      = fileStore.putLcIfAbsent(lcSha, lcName, lcBytes);
        boolean invoiceStored = fileStore.putInvoiceIfAbsent(invoiceSha, pdfName, pdfBytes);
        if (fileStore.isEnabled() && (!lcStored || !invoiceStored)) {
            log.error("MinIO upload failed: lcStored={} invoiceStored={} sessionId={}",
                    lcStored, invoiceStored, sessionId);
            return ResponseEntity.status(503)
                    .header("X-Error-Code", "STORAGE_UNAVAILABLE")
                    .body(new StartResponse(
                            sessionId, "STORAGE_ERROR", 0, pdfName, pdfBytes.length, lcText.length()));
        }
        store.enqueueSession(sessionId);
        store.recordSessionFiles(sessionId, lcName, lcSha, pdfName, invoiceSha);
        dispatcher.onEnqueued(sessionId);

        // Compute queue position for the just-enqueued session so the UI's
        // first render of the session page can show "you're #N" without a
        // round-trip to /queue/status.
        var snap = store.queueSnapshot();
        int position = snap.queued().indexOf(sessionId) + 1;

        log.info("lc-check/start: session={} lcBytes={} invoiceBytes={} invoiceName={} lcSha={} invSha={} queuePos={}",
                sessionId, lc.getSize(), invoice.getSize(), pdfName,
                lcSha.substring(0, 12), invoiceSha.substring(0, 12), position);

        return ResponseEntity.ok(new StartResponse(
                sessionId, "QUEUED", position, pdfName, pdfBytes.length, lcText.length()));
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

    // ── Predefined sample flow ────────────────────────────────────────────────

    /**
     * Start a check using a pre-defined sample LC paired with a custom invoice.
     *
     * <p>Skips the MinIO upload step for the LC — the sample bytes are resolved
     * via {@link SampleRefStore} which lazily uploads them to MinIO (content-
     * addressed, deduplicated). The invoice is either provided as a multipart
     * upload or taken from the sample pair.
     *
     * <p>This endpoint is the preferred path when the operator selects a sample
     * from the picker — no round-trip to fetch + re-upload the LC bytes.
     *
     * <pre>
     * POST /api/v1/lc-check/start-by-sample
     * Content-Type: multipart/form-data
     *
     * Part "body" (JSON):
     *   { "sampleId": "painting-01", "variant": "pass" }
     *
     * Part "invoice" (optional, multipart file):
     *   The invoice PDF. If absent, the sample's own invoice is used.
     * </pre>
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StartBySampleRequest(String sampleId, String variant) {}

    @PostMapping(path = "/start-by-sample", consumes = "multipart/form-data")
    public ResponseEntity<StartResponse> startBySample(
            @RequestPart("body") StartBySampleRequest body,
            @RequestPart(value = "invoice", required = false) MultipartFile invoice) throws IOException {

        String sessionId = MDC.get(MdcKeys.SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        String sampleId = body.sampleId();
        String variant  = (body.variant() == null || body.variant().isBlank()) ? "pass" : body.variant();
        String lcFilename = sampleRef.getLcFilename(sampleId, variant).orElse("lc.txt");

        // Resolve sample LC:
        // - MinIO up:  lazily upload to MinIO, use SHA-256 key (dedup + durable).
        // - MinIO down: cache raw bytes in-process so the hot-path /lc-raw and
        //   the dispatcher can still serve the session without MinIO.
        String lcSha;
        if (fileStore.isEnabled()) {
            lcSha = sampleRef.getLcSha256(sampleId, variant)
                    .orElseThrow(() -> new IllegalStateException(
                            "Sample LC not available: sampleId=" + sampleId + " variant=" + variant));
        } else {
            byte[] lcBytes = sampleRef.getLcBytes(sampleId, variant)
                    .orElseThrow(() -> new IllegalStateException(
                            "Sample LC not available: sampleId=" + sampleId + " variant=" + variant));
            lcSha = Sha256.hex(lcBytes);
            // Cache so /lc-raw + dispatcher can read it without MinIO.
            filesBySession.compute(sessionId, (key, existing) ->
                    existing == null
                            ? new SessionFiles(new String(lcBytes, StandardCharsets.UTF_8), null, null)
                            : new SessionFiles(new String(lcBytes, StandardCharsets.UTF_8), existing.invoiceFilename(), existing.invoiceBytes()));
            log.warn("MinIO unavailable — sample LC cached in-process for sessionId={}", sessionId);
        }

        // Invoice: custom upload or sample default.
        String invoiceSha;
        String invoiceFilename;
        long   invoiceBytes;
        if (invoice != null && !invoice.isEmpty()) {
            // Custom invoice — validate, upload, and record like /start.
            byte[] pdfBytes = invoice.getBytes();
            validator.validatePdf(pdfBytes);
            invoiceSha      = Sha256.hex(pdfBytes);
            invoiceFilename = invoice.getOriginalFilename();
            invoiceBytes    = pdfBytes.length;
            filesBySession.compute(sessionId, (key, existing) ->
                    existing == null
                            ? new SessionFiles(null, invoiceFilename, pdfBytes)
                            : new SessionFiles(existing.lcText(), invoiceFilename, pdfBytes));
            if (fileStore.isEnabled() && !fileStore.putInvoiceIfAbsent(invoiceSha, invoiceFilename, pdfBytes)) {
                log.error("MinIO upload failed for custom invoice sessionId={}", sessionId);
                return ResponseEntity.status(503)
                        .header("X-Error-Code", "STORAGE_UNAVAILABLE")
                        .body(new StartResponse(sessionId, "STORAGE_ERROR", 0, invoiceFilename, invoiceBytes, 0));
            }
        } else {
            // Use sample invoice — resolve via SampleRefStore.
            invoiceFilename = sampleRef.getInvoiceFilename(sampleId).orElse("invoice.pdf");
            if (fileStore.isEnabled()) {
                invoiceSha = sampleRef.getInvoiceSha256(sampleId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Sample invoice not available: sampleId=" + sampleId));
                invoiceBytes = fileStore.getInvoice(invoiceSha)
                        .map(bytes -> (long) bytes.length)
                        .orElseThrow(() -> new IllegalStateException(
                                "Sample invoice bytes missing in storage: sampleId=" + sampleId));
            } else {
                // MinIO down: cache sample invoice bytes in-process.
                invoiceSha = sampleRef.getInvoiceSha256(sampleId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Sample invoice not available: sampleId=" + sampleId));
                // Store the SHA so the session row is consistent; actual bytes come from hot cache.
                var cachedInvoiceBytes = sampleRef.getInvoiceBytes(sampleId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Sample invoice bytes not on classpath: sampleId=" + sampleId));
                invoiceBytes = cachedInvoiceBytes.length;
                filesBySession.compute(sessionId, (k, existing) ->
                        existing == null
                                ? new SessionFiles(null, invoiceFilename, cachedInvoiceBytes)
                                : new SessionFiles(existing.lcText(), invoiceFilename, cachedInvoiceBytes));
            }
        }

        store.enqueueSession(sessionId);
        store.recordSessionFiles(sessionId, lcFilename, lcSha, invoiceFilename, invoiceSha);
        dispatcher.onEnqueued(sessionId);

        var snap = store.queueSnapshot();
        int position = snap.queued().indexOf(sessionId) + 1;

        log.info("lc-check/start-by-sample: session={} sampleId={} variant={} invoice={} queuePos={}",
                sessionId, sampleId, variant, invoiceFilename, position);

        return ResponseEntity.ok(new StartResponse(
                sessionId, "QUEUED", position, invoiceFilename, invoiceBytes, 0));
    }
}
