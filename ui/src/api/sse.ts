import { useEffect, useRef } from 'react';
import type { CheckEvent, CheckEventType } from '../types';

const EVENT_TYPES: CheckEventType[] = [
  'session.started',
  'stage.started',
  'stage.completed',
  'check.started',
  'check.completed',
  'report.complete',
  'error',
];

/**
 * Subscribes to the SSE stream for a session and dispatches every event to the
 * supplied handler. Reconnects are not attempted — the backend bus buffers
 * recent events so a manual remount will replay history.
 */
export function useSseEvents(
  sessionId: string | null,
  onEvent: (e: CheckEvent) => void,
) {
  const handlerRef = useRef(onEvent);
  handlerRef.current = onEvent;

  useEffect(() => {
    if (!sessionId) return;
    const url = `/api/v1/lc-check/${sessionId}/stream`;
    const es = new EventSource(url);

    const listeners: Array<[CheckEventType, (e: MessageEvent) => void]> = [];
    for (const type of EVENT_TYPES) {
      const listener = (ev: MessageEvent) => {
        try {
          const data = JSON.parse(ev.data);
          handlerRef.current({ ...data, type });
        } catch (e) {
          // eslint-disable-next-line no-console
          console.error('SSE parse error', type, e);
        }
      };
      es.addEventListener(type, listener as EventListener);
      listeners.push([type, listener]);
    }
    es.onerror = () => {
      // readyState CLOSED ⇒ backend completed the stream normally. Don't retry.
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
