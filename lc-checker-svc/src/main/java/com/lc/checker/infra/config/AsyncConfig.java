package com.lc.checker.infra.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Executor backing {@code @Async} pipeline kickoffs from the streaming
 * controller AND the {@code CompletableFuture.supplyAsync(...)} fan-out
 * inside {@link com.lc.checker.stage.extract.InvoiceExtractionOrchestrator}
 * (one future per extractor lane).
 *
 * <p><b>Virtual threads (Java 21+)</b> — every task runs on a fresh virtual
 * thread (project Loom). Blocking on the synchronous {@code RestClient} HTTP
 * call inside an extractor lane parks the lightweight carrier instead of
 * holding an OS thread. Practical effect: arbitrary concurrency for I/O-bound
 * work — 4 extractor lanes × N concurrent sessions all run truly in parallel
 * without thread-pool starvation, even though the underlying calls are
 * blocking.
 *
 * <p>Was previously a fixed {@code ThreadPoolTaskExecutor} with core=4 /
 * max=8 — fine for 1 session × 4 lanes (5 threads = 1 pipeline + 4 lanes)
 * but queued under 2+ concurrent sessions.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "lcCheckExecutor")
    public Executor lcCheckExecutor() {
        // Named virtual threads so they're identifiable in jstack / thread dumps.
        AtomicLong seq = new AtomicLong();
        ThreadFactory tf = Thread.ofVirtual()
                .name("lc-check-vt-", 0)
                .factory();
        return Executors.newThreadPerTaskExecutor(r -> {
            Thread t = tf.newThread(r);
            // Override Thread.ofVirtual()'s built-in counter with our own so
            // the IDs reset cleanly per JVM and stay readable.
            t.setName("lc-check-vt-" + seq.incrementAndGet());
            return t;
        });
    }
}
