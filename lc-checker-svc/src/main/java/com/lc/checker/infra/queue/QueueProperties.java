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
 * attempts. 500ms keeps queue latency tight with negligible DB load (single
 * indexed query per pickup).
 */
@ConfigurationProperties(prefix = "pipeline")
public record QueueProperties(int concurrency, long pollDelayMs) {

    public QueueProperties {
        if (concurrency <= 0) concurrency = 1;
        if (pollDelayMs <= 0) pollDelayMs = 500L;
    }
}
