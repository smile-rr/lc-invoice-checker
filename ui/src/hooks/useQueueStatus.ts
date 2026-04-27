import { useEffect, useState } from 'react';
import { getQueueStatus } from '../api/client';
import type { QueueStatus } from '../types';

const POLL_MS = 2000;
let listenerCount = 0;
let timer: number | null = null;
let lastValue: QueueStatus | null = null;
const subscribers = new Set<(s: QueueStatus | null) => void>();
let inflight: Promise<unknown> | null = null;

function broadcast(value: QueueStatus | null) {
  lastValue = value;
  for (const fn of subscribers) fn(value);
}

async function refresh() {
  if (inflight) return;
  inflight = getQueueStatus()
    .then((s) => broadcast(s))
    .catch(() => { /* surfaced via healthBus */ })
    .finally(() => { inflight = null; });
  await inflight;
}

function ensureRunning() {
  if (timer != null) return;
  refresh();
  timer = window.setInterval(refresh, POLL_MS);
}

function maybeStop() {
  if (listenerCount === 0 && timer != null) {
    window.clearInterval(timer);
    timer = null;
  }
}

/**
 * Subscribe to /api/v1/queue/status. The poller is shared across all
 * subscribers — refcount keeps it active only while at least one component
 * mounts the hook with `enabled=true`. Pass `enabled=false` from views that
 * don't currently need the data so the polling stops cleanly.
 */
export function useQueueStatus(enabled: boolean = true): QueueStatus | null {
  const [value, setValue] = useState<QueueStatus | null>(lastValue);

  useEffect(() => {
    if (!enabled) return;
    listenerCount += 1;
    subscribers.add(setValue);
    ensureRunning();
    return () => {
      subscribers.delete(setValue);
      listenerCount = Math.max(0, listenerCount - 1);
      maybeStop();
    };
  }, [enabled]);

  return value;
}
