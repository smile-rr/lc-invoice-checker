package com.lc.checker.api.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.persistence.CheckSessionStore.QueueSnapshot;
import com.lc.checker.infra.queue.JobDispatcher;
import com.lc.checker.infra.queue.QueueProperties;
import com.lc.checker.infra.queue.QueueSnapshotCache;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-session queue visibility + cancel.
 *
 * <ul>
 *   <li>{@code GET /api/v1/queue/status} — running + queued session ids,
 *       served from a 1-second TTL cache so any number of UI pollers cost
 *       the DB &lt;= 1 query/sec.</li>
 *   <li>{@code DELETE /api/v1/lc-check/{id}} — cancel a QUEUED session
 *       (transitions to FAILED with {@code error="cancelled_by_user"}).
 *       No-op if already RUNNING / COMPLETED / FAILED.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class QueueController {

    private final QueueSnapshotCache cache;
    private final QueueProperties props;
    private final CheckSessionStore store;
    private final JobDispatcher dispatcher;

    public QueueController(QueueSnapshotCache cache,
                           QueueProperties props,
                           CheckSessionStore store,
                           JobDispatcher dispatcher) {
        this.cache = cache;
        this.props = props;
        this.store = store;
        this.dispatcher = dispatcher;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record QueuedEntry(String sessionId, int position) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record QueueStatus(int concurrency, List<String> running, List<QueuedEntry> queued) {}

    @GetMapping("/queue/status")
    public ResponseEntity<QueueStatus> status() {
        QueueSnapshot snap = cache.get();
        List<QueuedEntry> queued = new ArrayList<>(snap.queued().size());
        for (int i = 0; i < snap.queued().size(); i++) {
            queued.add(new QueuedEntry(snap.queued().get(i), i + 1));
        }
        return ResponseEntity.ok(new QueueStatus(props.concurrency(), snap.running(), queued));
    }

    @DeleteMapping("/lc-check/{sessionId}")
    public ResponseEntity<Void> cancel(@PathVariable String sessionId) {
        boolean cancelled = store.cancelQueued(sessionId, "cancelled_by_user");
        if (!cancelled) {
            return ResponseEntity.notFound().build();
        }
        dispatcher.onCancelled(sessionId);
        return ResponseEntity.noContent().build();
    }
}
