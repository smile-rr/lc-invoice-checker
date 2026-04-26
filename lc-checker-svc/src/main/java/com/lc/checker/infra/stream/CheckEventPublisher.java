package com.lc.checker.infra.stream;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-session event sink. Owns a monotonic {@code seq} counter so callers never
 * have to thread one through the pipeline. The four named helpers ({@link #status},
 * {@link #rule}, {@link #error}, {@link #complete}) are the only emission API —
 * they construct the unified {@link CheckEvent} envelope before delegating to
 * {@link #send(CheckEvent)}.
 */
public abstract class CheckEventPublisher {

    /** No-op publisher used by the synchronous (non-streaming) entry path. */
    public static final CheckEventPublisher NOOP = new CheckEventPublisher() {
        @Override protected void send(CheckEvent event) { /* drop */ }
    };

    private final AtomicLong seq = new AtomicLong(0);

    /** Stage transition. {@code state} is "started", "running", or "completed". */
    public final void status(String stage, String state, String message, Object data) {
        send(CheckEvent.status(seq.incrementAndGet(), stage, state, message, data));
    }

    public final void status(String stage, String state, String message) {
        status(stage, state, message, null);
    }

    /**
     * Intra-stage progress update. Emits {@code state: "running"} so the
     * frontend can keep the stage spinning while showing an updated message.
     * Use this when a long-running stage (e.g. parallel invoice extraction)
     * has sub-events worth surfacing without ending the stage.
     */
    public final void progress(String stage, String message, Object data) {
        status(stage, "running", message, data);
    }

    public final void progress(String stage, String message) {
        status(stage, "running", message, null);
    }

    /** Single completed rule outcome — payload is a {@code CheckResult}. */
    public final void rule(Object checkResult) {
        send(CheckEvent.rule(seq.incrementAndGet(), checkResult));
    }

    /** Pipeline halted. No further events follow. */
    public final void error(String stage, String message) {
        send(CheckEvent.error(seq.incrementAndGet(), stage, message));
    }

    /** Final assembled report. Triggers UI navigation to review. */
    public final void complete(Object discrepancyReport) {
        send(CheckEvent.complete(seq.incrementAndGet(), discrepancyReport));
    }

    /** Concrete sink: SSE bus, no-op, persistent log, etc. */
    protected abstract void send(CheckEvent event);
}
