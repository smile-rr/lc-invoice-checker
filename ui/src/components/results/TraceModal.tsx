import { useEffect, useState } from 'react';
import { getTrace } from '../../api/client';
import type { CheckSession } from '../../types';

interface Props {
  sessionId: string;
  onClose: () => void;
}

export function TraceModal({ sessionId, onClose }: Props) {
  const [trace, setTrace] = useState<CheckSession | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    getTrace(sessionId).then(setTrace).catch((e) => setErr(e.message));
  }, [sessionId]);

  return (
    <div
      className="fixed inset-0 bg-navy-1/60 z-50 flex items-center justify-center p-6"
      onClick={onClose}
    >
      <div
        className="bg-paper rounded-card max-w-4xl w-full max-h-[85vh] overflow-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center px-5 py-3 border-b border-line sticky top-0 bg-paper">
          <h3 className="font-serif text-xl">Session trace</h3>
          <span className="ml-3 font-mono text-xs text-muted">{sessionId}</span>
          <button onClick={onClose} className="ml-auto text-muted hover:text-navy-1">
            ✕
          </button>
        </div>
        <div className="p-5">
          {err && <div className="text-status-red text-sm">{err}</div>}
          {!trace && !err && <div className="text-muted text-sm">Loading…</div>}
          {trace && (
            <pre className="font-mono text-[11px] whitespace-pre-wrap leading-relaxed">
              {JSON.stringify(trace, null, 2)}
            </pre>
          )}
        </div>
      </div>
    </div>
  );
}
