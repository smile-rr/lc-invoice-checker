package com.lc.checker.api.controller;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lc.checker.domain.common.FieldEnvelope;
import com.lc.checker.domain.common.ParsedRow;
import com.lc.checker.domain.lc.LcDocument;
import com.lc.checker.infra.persistence.CheckSessionStore;
import com.lc.checker.infra.persistence.CheckSessionStore.QueueSnapshot;
import com.lc.checker.infra.queue.QueueSnapshotCache;
import com.lc.checker.infra.stream.CheckEvent;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public V1 trace API.
 *
 * <p>{@code GET /api/v1/lc-check/{sessionId}/trace} returns the unified envelope
 * event log plus, when the session is still {@code QUEUED}, the queue context
 * (position, depth, currently-running session id) so the UI can render its
 * waiting state on first load without a separate {@code /queue/status} call.
 *
 * <p>The synchronous {@code POST /api/v1/lc-check} entry point was removed when
 * the queue dispatcher landed; all submits go through {@code POST /lc-check/start}.
 */
@RestController
@RequestMapping("/api/v1/lc-check")
public class LcCheckController {

    private final CheckSessionStore store;
    private final QueueSnapshotCache queueCache;

    public LcCheckController(CheckSessionStore store, QueueSnapshotCache queueCache) {
        this.store = store;
        this.queueCache = queueCache;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record QueueContext(int position, int depth, String runningSessionId) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TraceResponse(String sessionId, List<CheckEvent> events, QueueContext queueContext) {}

    /**
     * Replay the full envelope event log for the session. {@code queue_context}
     * is populated only while the session is still QUEUED — null otherwise.
     */
    @GetMapping("/{sessionId}/trace")
    public ResponseEntity<TraceResponse> trace(@PathVariable String sessionId) {
        List<CheckEvent> events = store.findEvents(sessionId);
        QueueContext qc = computeQueueContext(sessionId);
        return ResponseEntity.ok(new TraceResponse(sessionId, events, qc));
    }

    private QueueContext computeQueueContext(String sessionId) {
        QueueSnapshot snap = queueCache.get();
        int idx = snap.queued().indexOf(sessionId);
        if (idx < 0) return null;
        String running = snap.running().isEmpty() ? null : snap.running().get(0);
        return new QueueContext(idx + 1, snap.queued().size(), running);
    }
}
