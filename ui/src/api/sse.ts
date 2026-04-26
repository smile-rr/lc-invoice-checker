import { useEffect, useRef } from 'react';
import type { Event } from '../types';

const EVENT_NAMES: Array<Event['type']> = ['status', 'rule', 'error', 'complete'];

/**
 * Subscribe to the unified SSE stream for a session. Every message arrives as
 * one {@link Event}; the consumer's reducer decides what to do with it.
 *
 * <p>Reconnects are not attempted — the backend buffers recent events and the
 * trace API replays the full history, so a manual remount restores state.
 */
export function useSseEvents(
  sessionId: string | null,
  onEvent: (e: Event) => void,
) {
  const handlerRef = useRef(onEvent);
  handlerRef.current = onEvent;

  useEffect(() => {
    if (!sessionId) return;
    const url = `/api/v1/lc-check/${sessionId}/stream`;
    const es = new EventSource(url);

    const listeners: Array<[string, (e: MessageEvent) => void]> = [];
    for (const name of EVENT_NAMES) {
      const listener = (ev: MessageEvent) => {
        try {
          const data = JSON.parse(ev.data) as Event;
          handlerRef.current(data);
        } catch (e) {
          // eslint-disable-next-line no-console
          console.error('SSE parse error', name, e);
        }
      };
      es.addEventListener(name, listener as EventListener);
      listeners.push([name, listener]);
    }
    es.onerror = () => {
      if (es.readyState === EventSource.CLOSED) return;
    };

    return () => {
      for (const [type, listener] of listeners) {
        es.removeEventListener(type, listener as EventListener);
      }
      es.close();
    };
  }, [sessionId]);
}
