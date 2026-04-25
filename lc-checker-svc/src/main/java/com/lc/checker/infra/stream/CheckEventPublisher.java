package com.lc.checker.infra.stream;

/**
 * Tiny functional interface the engine calls at each stage/check boundary. The
 * sync pipeline passes {@link #NOOP}; the async streaming pipeline passes a
 * bus-backed publisher that fans out to {@code SseEmitter}s.
 */
@FunctionalInterface
public interface CheckEventPublisher {

    CheckEventPublisher NOOP = event -> {};

    void emit(CheckEvent event);
}
