import type { SessionState } from '../../hooks/useCheckSession';

interface Props {
  state: SessionState;
}

/**
 * Single-line session header. Verdict + LC info + session id,
 * crammed into ~40px so the body gets the vertical real estate.
 *
 * Forensic tracing now lives in Langfuse — the local trace modal was removed
 * because it dumped raw JSON that Langfuse renders as a proper timeline.
 */
export function SessionStrip({ state }: Props) {
  const lc = state.lc;
  const report = state.report;
  const idle = !state.sessionId;
  // "running" means we have a session but no final report yet — distinct from
  // "idle" (no session at all, e.g. on the landing page).
  const running = !idle && !report && !state.error;

  // Idle render: same vertical footprint as the populated strip so the page
  // shell stays identical between '/' and '/session/:id'. No counts, no trace
  // button — there's nothing yet to count or trace.
  if (idle) {
    return (
      <div className="bg-paper px-6 h-10 border-b border-line flex items-center gap-4 text-xs">
        <span className="px-2 py-0.5 rounded font-mono text-[10px] font-bold bg-slate2 text-muted">
          READY
        </span>
        <div className="flex items-baseline gap-3 min-w-0 flex-1">
          <span className="font-serif text-[13px] text-muted">
            Start a new compliance check below
          </span>
        </div>
        {/* Spacer preserves horizontal balance with the populated strip. */}
        <div className="flex-1" />
      </div>
    );
  }

  return (
    <div className="bg-paper px-6 h-10 border-b border-line flex items-center gap-4 text-xs">
      {/* Verdict */}
      {report ? (
        <span
          className={[
            'px-2 py-0.5 rounded font-mono text-[10px] font-bold',
            report.compliant
              ? 'bg-status-greenSoft text-status-green'
              : 'bg-status-redSoft text-status-red',
          ].join(' ')}
        >
          {report.compliant ? 'PASS' : 'FAIL'}
        </span>
      ) : running ? (
        <span className="px-2 py-0.5 rounded font-mono text-[10px] font-bold bg-status-goldSoft text-status-gold inline-flex items-center gap-1.5">
          <span className="w-1.5 h-1.5 rounded-full bg-status-gold animate-blink" />
          RUNNING
        </span>
      ) : state.error ? (
        <span className="px-2 py-0.5 rounded font-mono text-[10px] font-bold bg-status-redSoft text-status-red">
          ERROR
        </span>
      ) : (
        <span className="px-2 py-0.5 rounded font-mono text-[10px] text-muted bg-slate2">—</span>
      )}

      {/* Compact session line */}
      <div className="flex items-baseline gap-3 min-w-0 flex-1">
        <span className="font-serif text-[13px] text-navy-1 truncate">
          {lc?.lc_number ?? <span className="text-muted">Loading…</span>}
        </span>
        {lc?.beneficiary_name && lc?.applicant_name && (
          <span className="font-sans text-[11px] text-muted truncate">
            {lc.beneficiary_name} → {lc.applicant_name}
          </span>
        )}
        <span className="font-mono text-[10px] text-muted whitespace-nowrap">
          {lc?.currency && lc?.amount != null && `${lc.currency} ${fmtNum(lc.amount)}`}
          {lc?.expiry_date && <span>{lc?.currency ? ' · ' : ''}exp {lc.expiry_date}</span>}
        </span>
      </div>

      {/* Session id — short label; click to copy the full UUID for log /
          Langfuse correlation. */}
      <button
        onClick={() => {
          if (state.sessionId) {
            void navigator.clipboard.writeText(state.sessionId);
          }
        }}
        disabled={!state.sessionId}
        className="font-mono text-[10px] text-muted hover:text-navy-1 disabled:cursor-default"
        title={state.sessionId ? `Copy session id: ${state.sessionId}` : ''}
      >
        {state.sessionId?.slice(0, 8) ?? '—'}
      </button>
    </div>
  );
}

function fmtNum(v: string | number) {
  const n = typeof v === 'number' ? v : Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString('en-US', { minimumFractionDigits: 2 });
}
