package com.lc.checker.infra.stream;

import com.lc.checker.infra.persistence.CheckSessionStore;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory fan-out of {@link CheckEvent}s to {@link SseEmitter}s registered for
 * a given session id, with a bounded ring buffer per session so a late-arriving
 * subscriber still sees the recent history.
 *
 * <p>Each event is also appended to {@link CheckSessionStore} so the trace API
 * can replay the full sequence after a restart or after the in-memory buffer
 * has rolled over.
 */
@Component
public class CheckEventBus {

    private static final Logger log = LoggerFactory.getLogger(CheckEventBus.class);
    private static final int BUFFER_CAP = 1024;

    private final Map<String, SessionChannel> channels = new ConcurrentHashMap<>();
    private final CheckSessionStore store;

    public CheckEventBus(CheckSessionStore store) {
        this.store = store;
    }

    /** Publishes an event: persists, buffers, fans out to live subscribers. */
    public void publish(String sessionId, CheckEvent event) {
        try {
            store.appendEvent(sessionId, event);
        } catch (Exception e) {
            log.warn("Persisting event failed for session={}: {}", sessionId, e.getMessage());
        }
        deliver(sessionId, event, /*buffer=*/true);
    }

    /**
     * Fan-out to live subscribers without persisting or buffering. Use for
     * transient signals (e.g. queue-position updates) that don't belong in the
     * audit trail or trace replay.
     */
    public void publishTransient(String sessionId, CheckEvent event) {
        deliver(sessionId, event, /*buffer=*/false);
    }

    private void deliver(String sessionId, CheckEvent event, boolean buffer) {
        SessionChannel ch = channels.computeIfAbsent(sessionId, k -> new SessionChannel());
        if (buffer) ch.append(event);
        for (SseEmitter emitter : ch.emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type().wireName())
                        .data(event));
            } catch (IOException e) {
                log.debug("SSE send failed for session={} — client likely disconnected", sessionId);
                ch.emitters.remove(emitter);
            }
        }
    }

    /**
     * Attach a fresh {@link SseEmitter}; replays the in-memory ring buffer first
     * so a late subscriber sees what they missed. (For full history beyond the
     * ring buffer, clients query {@code GET /trace} instead.)
     */
    public SseEmitter register(String sessionId, SseEmitter emitter) {
        SessionChannel ch = channels.computeIfAbsent(sessionId, k -> new SessionChannel());
        for (CheckEvent event : ch.snapshot()) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type().wireName())
                        .data(event));
            } catch (IOException e) {
                log.warn("Failed to replay history to new subscriber: {}", e.getMessage());
                emitter.completeWithError(e);
                return emitter;
            }
        }
        ch.emitters.add(emitter);
        emitter.onCompletion(() -> ch.emitters.remove(emitter));
        emitter.onTimeout(() -> ch.emitters.remove(emitter));
        emitter.onError(e -> ch.emitters.remove(emitter));
        return emitter;
    }

    /** Closes all subscribers for the given session and drops the channel. */
    public void complete(String sessionId) {
        SessionChannel ch = channels.remove(sessionId);
        if (ch == null) return;
        for (SseEmitter emitter : ch.emitters) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("Error completing emitter: {}", e.getMessage());
            }
        }
    }

    /** Returns a publisher that routes everything to this bus under {@code sessionId}. */
    public CheckEventPublisher publisherFor(String sessionId) {
        return new CheckEventPublisher() {
            @Override
            protected void send(CheckEvent event) {
                publish(sessionId, event);
            }
        };
    }

    private static final class SessionChannel {
        final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        final Deque<CheckEvent> buffer = new ArrayDeque<>(BUFFER_CAP);

        synchronized void append(CheckEvent e) {
            if (buffer.size() >= BUFFER_CAP) buffer.removeFirst();
            buffer.addLast(e);
        }

        synchronized CheckEvent[] snapshot() {
            return buffer.toArray(new CheckEvent[0]);
        }
    }
}
