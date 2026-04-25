package com.lc.checker.infra.stream;

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
 * In-memory fan-out of {@link CheckEvent}s to any {@link SseEmitter}s registered
 * for a given session id. Also keeps a bounded ring buffer per session so a
 * late-arriving subscriber (e.g. UI that navigates to /session/:id a beat after
 * the pipeline kicked off) still sees the history.
 *
 * <p>The bus is intentionally single-JVM only. V2 can swap this for Redis pub/sub
 * or a distributed SSE gateway without touching the engine/publisher API.
 */
@Component
public class CheckEventBus {

    private static final Logger log = LoggerFactory.getLogger(CheckEventBus.class);
    private static final int BUFFER_CAP = 256;

    /** One entry per in-flight session. Evicted on completion. */
    private final Map<String, SessionChannel> channels = new ConcurrentHashMap<>();

    /** Publishes an event to subscribers and appends it to the replay buffer. */
    public void publish(String sessionId, CheckEvent event) {
        SessionChannel ch = channels.computeIfAbsent(sessionId, k -> new SessionChannel());
        ch.append(event);
        for (SseEmitter emitter : ch.emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type().wireName())
                        .data(event));
            } catch (IOException e) {
                log.debug("SSE send failed for session={} — client likely disconnected", sessionId);
                emitter.completeWithError(e);
                ch.emitters.remove(emitter);
            }
        }
    }

    /**
     * Attaches a fresh {@link SseEmitter} for the given session. The caller-supplied
     * emitter is returned (after receiving any buffered history). On disconnect /
     * completion the emitter is removed from the subscriber list automatically.
     */
    public SseEmitter register(String sessionId, SseEmitter emitter) {
        SessionChannel ch = channels.computeIfAbsent(sessionId, k -> new SessionChannel());
        // Replay history first so a late subscriber sees the full timeline.
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

    /** Returns a publisher that routes everything to this bus under the given sessionId. */
    public CheckEventPublisher publisherFor(String sessionId) {
        return event -> publish(sessionId, event);
    }

    /** One per session. Small holder around the subscriber list + replay buffer. */
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
