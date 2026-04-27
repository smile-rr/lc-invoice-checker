package com.lc.checker.infra.queue;

import com.lc.checker.infra.observability.MdcKeys;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.persistence.CheckSessionStore.QueuedJob;
import com.lc.checker.infra.persistence.CheckSessionStore.QueueSnapshot;
import com.lc.checker.infra.storage.InvoiceFileStore;
import com.lc.checker.infra.stream.CheckEventBus;
import com.lc.checker.infra.stream.CheckEventPublisher;
import com.lc.checker.pipeline.LcCheckPipeline;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Single-worker job dispatcher backed by Postgres.
 *
 * <p>Every {@code pipeline.poll-delay-ms} (default 2000ms) the scheduled tick
 * tries to claim a slot on the {@link Semaphore} (capacity =
 * {@code pipeline.concurrency}, default 1) and atomically dequeue one
 * {@code QUEUED} session via {@code SELECT … FOR UPDATE SKIP LOCKED}, which
 * transitions it to {@code RUNNING}.
 *
 * <p>The pipeline runs on the existing virtual-thread executor so we don't
 * monopolise the scheduler thread. Failures are caught, persisted as
 * {@code FAILED} on the session row, and emitted as an SSE error event;
 * the slot is always released.
 *
 * <p>After every state change (enqueue / dequeue / cancel) the queue snapshot
 * cache is invalidated and queue-position events are pushed to every still-
 * QUEUED session's SSE channel so UI clients see their position update without
 * polling. POC posture — no retry / orphan recovery / circuit breaker.
 */
@Component
public class JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    private final CheckSessionStore store;
    private final InvoiceFileStore fileStore;
    private final LcCheckPipeline pipeline;
    private final CheckEventBus bus;
    private final Executor executor;
    private final QueueSnapshotCache cache;
    private final Semaphore slots;

    /** Heartbeat: emit one INFO line every minute so operators see the dispatcher is alive
     *  without per-tick log spam. Counters are reset after each heartbeat emission. */
    private static final long HEARTBEAT_INTERVAL_MS = 60_000L;
    private final AtomicLong pollsSinceHeartbeat = new AtomicLong();
    private final AtomicLong dispatchedSinceHeartbeat = new AtomicLong();
    private volatile long lastHeartbeatMs = System.currentTimeMillis();

    public JobDispatcher(CheckSessionStore store,
                         InvoiceFileStore fileStore,
                         LcCheckPipeline pipeline,
                         CheckEventBus bus,
                         @Qualifier("lcCheckExecutor") Executor executor,
                         QueueSnapshotCache cache,
                         QueueProperties props) {
        this.store = store;
        this.fileStore = fileStore;
        this.pipeline = pipeline;
        this.bus = bus;
        this.executor = executor;
        this.cache = cache;
        this.slots = new Semaphore(props.concurrency());
        log.info("JobDispatcher started: concurrency={} pollDelayMs={}",
                props.concurrency(), props.pollDelayMs());
    }

    /** Called by the upload controller after inserting a QUEUED row. */
    public void onEnqueued(String sessionId) {
        cache.invalidate();
        broadcastQueuePositions();
    }

    /** Called by the cancel controller after flipping QUEUED → FAILED. */
    public void onCancelled(String sessionId) {
        cache.invalidate();
        broadcastQueuePositions();
    }

    @Scheduled(fixedDelayString = "${pipeline.poll-delay-ms:2000}")
    public void pickup() {
        pollsSinceHeartbeat.incrementAndGet();
        try {
            if (!slots.tryAcquire()) return;
            boolean dispatched = false;
            try {
                Optional<QueuedJob> claimed = store.dequeueOne();
                if (claimed.isEmpty()) return;
                QueuedJob job = claimed.get();
                cache.invalidate();
                broadcastQueuePositions();
                dispatched = true;
                dispatchedSinceHeartbeat.incrementAndGet();
                log.info("Dispatching session={} invoiceFilename={}",
                        job.sessionId(), safe(job.invoiceFilename()));
                executor.execute(() -> runJob(job));
            } catch (RuntimeException e) {
                log.error("Dispatcher dequeue failed: {}", e.getMessage(), e);
            } finally {
                if (!dispatched) slots.release();
            }
        } finally {
            emitHeartbeatIfDue();
        }
    }

    private void emitHeartbeatIfDue() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastHeartbeatMs;
        if (elapsed < HEARTBEAT_INTERVAL_MS) return;
        long polls = pollsSinceHeartbeat.getAndSet(0);
        long dispatched = dispatchedSinceHeartbeat.getAndSet(0);
        lastHeartbeatMs = now;
        log.info("JobDispatcher alive: polls={} dispatched={} elapsedMs={}",
                polls, dispatched, elapsed);
    }

    private void runJob(QueuedJob job) {
        String sessionId = job.sessionId();
        MDC.put(MdcKeys.SESSION_ID, sessionId);
        CheckEventPublisher publisher = bus.publisherFor(sessionId);
        try {
            byte[] lcBytes = fileStore.getLc(job.lcSha256())
                    .orElseThrow(() -> new IllegalStateException(
                            "LC bytes missing in storage for session " + sessionId));
            byte[] pdfBytes = fileStore.getInvoice(job.invoiceSha256())
                    .orElseThrow(() -> new IllegalStateException(
                            "Invoice bytes missing in storage for session " + sessionId));
            String lcText = new String(lcBytes, StandardCharsets.UTF_8);
            publisher.status("session", "started",
                    "Run started for invoice " + safe(job.invoiceFilename()),
                    Map.of("sessionId", sessionId,
                            "invoiceFilename", safe(job.invoiceFilename()),
                            "invoiceBytes", pdfBytes.length));
            pipeline.run(sessionId, lcText, pdfBytes, job.invoiceFilename(), publisher);
        } catch (RuntimeException e) {
            log.error("Pipeline failed for session={}: {}", sessionId, e.getMessage(), e);
            store.markFailed(sessionId, e.getMessage());
            publisher.error("pipeline", e.getMessage());
        } finally {
            try { bus.complete(sessionId); } catch (Exception ignored) { /* SSE close best-effort */ }
            cache.invalidate();
            broadcastQueuePositions();
            slots.release();
            MDC.remove(MdcKeys.SESSION_ID);
        }
    }

    /**
     * Push a queue-position event to every still-QUEUED session's SSE channel
     * so the UI can update without polling. Encoded as a {@code STATUS} event
     * with {@code stage="queue"}, {@code state="waiting"} and {@code data}
     * carrying the position / depth / running session id. Transient — not
     * persisted to {@code pipeline_events} (positions are not audit-worthy).
     */
    private void broadcastQueuePositions() {
        QueueSnapshot snap = store.queueSnapshot();
        List<String> queued = snap.queued();
        String runningId = snap.running().isEmpty() ? null : snap.running().get(0);
        for (int i = 0; i < queued.size(); i++) {
            String sid = queued.get(i);
            int position = i + 1;
            try {
                bus.publishTransient(sid, com.lc.checker.infra.stream.CheckEvent.status(
                        0L, "queue", "waiting",
                        "Waiting in queue (position " + position + ")",
                        Map.of(
                                "position", position,
                                "depth", queued.size(),
                                "running_session_id", runningId == null ? "" : runningId)));
            } catch (Exception e) {
                log.debug("Failed to emit queue event for session={}: {}", sid, e.getMessage());
            }
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
