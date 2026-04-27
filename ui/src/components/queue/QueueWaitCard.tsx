import { useState } from 'react';
import { ApiError, cancelSession } from '../../api/client';
import type { QueueContext } from '../../types';

interface Props {
  sessionId: string;
  context: QueueContext;
  /** Called after a successful cancel — the parent navigates back to the upload page. */
  onCancelled?: () => void;
}

/**
 * "Waiting in the queue" card shown on a session page while the run is
 * QUEUED. Position is pushed by the dispatcher on every queue change so the
 * UI updates without polling. Cancel transitions QUEUED → FAILED on the
 * server; the row remains visible in history with status=FAILED.
 */
export function QueueWaitCard({ sessionId, context, onCancelled }: Props) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const ahead = Math.max(0, context.position - 1);

  async function cancel() {
    setBusy(true);
    setErr(null);
    try {
      await cancelSession(sessionId);
      onCancelled?.();
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        // Already running or finished — nothing to cancel; treat as success.
        onCancelled?.();
        return;
      }
      setErr((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="rounded-card border border-line bg-paper px-6 py-5 max-w-xl">
      <div className="flex items-baseline justify-between mb-3">
        <span className="font-mono text-[10px] uppercase tracking-[0.22em] text-muted">
          Pipeline queue
        </span>
        <span className="font-mono text-[10px] text-muted">
          depth {context.depth}
        </span>
      </div>
      <div className="flex items-baseline gap-3">
        <span className="font-serif text-4xl text-navy-1 leading-none">#{context.position}</span>
        <span className="text-sm text-muted">
          {ahead === 0
            ? 'next up — waiting for an open slot'
            : `${ahead} run${ahead === 1 ? '' : 's'} ahead of you`}
        </span>
      </div>
      {context.running_session_id && (
        <div className="mt-3 text-xs text-muted">
          currently running:{' '}
          <span className="font-mono text-navy-1">
            {context.running_session_id.slice(0, 8)}
          </span>
        </div>
      )}
      <div className="mt-4 flex items-center gap-3">
        <button
          onClick={cancel}
          disabled={busy}
          className="text-xs font-medium text-status-red hover:bg-status-redSoft/40 px-3 py-1.5 rounded-btn disabled:opacity-40"
        >
          {busy ? 'Cancelling…' : 'Cancel run'}
        </button>
        <span className="text-[10px] font-mono text-muted">
          The page updates automatically when your slot opens.
        </span>
      </div>
      {err && (
        <div className="mt-3 p-2 rounded-btn bg-status-redSoft text-status-red text-xs">
          {err}
        </div>
      )}
    </div>
  );
}
