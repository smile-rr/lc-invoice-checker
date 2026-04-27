package com.lc.checker.infra.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single-worker queue knobs.
 *
 * <p>{@code concurrency} caps how many sessions the dispatcher will run in
 * parallel. Default 1 — POC posture is "one at a time, no resource bursts".
 * Bump to 2/3 if the host can comfortably run multiple extractor pipelines
 * concurrently.
 *
 * <p>{@code pollDelayMs} is the {@code @Scheduled fixedDelay} between dequeue
 * attempts. Default 2000ms (2s) — bounds user-perceived "queued → started"
 * latency to ~2s while keeping DB churn from {@code SELECT FOR UPDATE SKIP
 * LOCKED} negligible. Drop to 1000ms if UX latency matters; raise to 5000ms+
 * if the queue is mostly idle.
 */
@ConfigurationProperties(prefix = "pipeline")
public record QueueProperties(int concurrency, long pollDelayMs) {

    public QueueProperties {
        if (concurrency <= 0) concurrency = 1;
        if (pollDelayMs <= 0) pollDelayMs = 2000L;
    }
}
