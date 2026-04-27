package com.lc.checker.infra.queue;

import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.persistence.CheckSessionStore.QueueSnapshot;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Tiny in-memory cache over {@link CheckSessionStore#queueSnapshot()}.
 *
 * <p>Reads (UI poll, history dropdown, TopNav chip) hit memory; the underlying
 * indexed SELECT runs at most once per second regardless of poll volume.
 * {@link #invalidate} is called from the dispatcher on every enqueue / dequeue
 * / cancel so the next read returns fresh state without waiting out the TTL.
 */
@Component
public class QueueSnapshotCache {

    private static final long TTL_NANOS = 1_000_000_000L; // 1 second

    private final CheckSessionStore store;
    private final AtomicReference<QueueSnapshot> snapshot =
            new AtomicReference<>(QueueSnapshot.EMPTY);
    private volatile long lastRefreshNanos;

    public QueueSnapshotCache(CheckSessionStore store) {
        this.store = store;
    }

    public QueueSnapshot get() {
        if (System.nanoTime() - lastRefreshNanos > TTL_NANOS) {
            refresh();
        }
        return snapshot.get();
    }

    public void invalidate() {
        lastRefreshNanos = 0L;
    }

    private synchronized void refresh() {
        if (System.nanoTime() - lastRefreshNanos <= TTL_NANOS) return;
        snapshot.set(store.queueSnapshot());
        lastRefreshNanos = System.nanoTime();
    }
}
