import type { SessionState } from '../../hooks/useCheckSession';
import { useNavigate } from 'react-router-dom';

interface Props {
  state: SessionState;
  onOpenTrace: () => void;
}

/**
 * Single-line session header. Verdict + LC info + count chips + tools, all
 * crammed into ~40px so the body gets the vertical real estate. Counts come
 * from {@code report.summary} when the report is final (backend source of
 * truth) and only fall back to a live count derived from {@code state.checks}
 * during streaming, when {@code summary} hasn't been assembled yet.
 */
export function SessionStrip({ state, onOpenTrace }: Props) {
  const lc = state.lc;
  const report = state.report;
  const idle = !state.sessionId;
  // "running" means we have a session but no final report yet — distinct from
  // "idle" (no session at all, e.g. on the landing page).
  const running = !idle && !report && !state.error;

  const nav = useNavigate();
  const goToRules = (filter: 'fail' | 'pass' | 'unable' | 'na') => {
    if (!state.sessionId) return;
    nav(`/session/${state.sessionId}?step=rules&filter=${filter}`);
  };

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
        {/* Disabled chip placeholders preserve horizontal balance with the
            populated strip — same widths, same baseline, just dim. */}
        <div className="flex items-center gap-3 font-mono text-[11px] opacity-30 select-none">
          <span className="px-2 py-0.5">0 Failed</span>
          <span className="px-2 py-0.5">0 Passed</span>
          <span className="px-2 py-0.5">0 Unable</span>
          <span className="px-2 py-0.5">0 N/A</span>
        </div>
      </div>
    );
  }

  // Prefer authoritative summary from the report; fall back to live derivation.
  const counts = report?.summary
    ? {
        pass: report.summary.passed,
        fail: report.summary.failed,
        unable: report.summary.doubts,
        na: report.summary.not_required,
      }
    : {
        pass: state.checks.filter((c) => c.status === 'PASS').length,
        fail: state.checks.filter((c) => c.status === 'FAIL').length,
        unable: state.checks.filter((c) => c.status === 'DOUBTS').length,
        na: state.checks.filter((c) => c.status === 'NOT_REQUIRED').length,
      };

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

      {/* Count chips */}
      <div className="flex items-center gap-3 font-mono text-[11px]">
        <Chip
          label="Failed" n={counts.fail} tone="red"
          highlight={counts.fail > 0}
          onClick={() => goToRules('fail')}
        />
        <Chip label="Passed" n={counts.pass} tone="green" onClick={() => goToRules('pass')} />
        <Chip
          label="Unable" n={counts.unable} tone="gold"
          highlight={counts.unable > 0}
          onClick={() => goToRules('unable')}
        />
        <Chip label="N/A" n={counts.na} tone="muted" onClick={() => goToRules('na')} />
      </div>

      {/* Session id + tools */}
      <span className="font-mono text-[10px] text-muted">
        {state.sessionId?.slice(0, 8) ?? '—'}
      </span>
      <button
        onClick={onOpenTrace}
        disabled={!state.sessionId}
        className="text-[10px] px-2 py-1 rounded border border-line hover:border-teal-2 disabled:opacity-40 font-mono"
        title="Open forensic trace"
      >
        🔍
      </button>
    </div>
  );
}

function Chip({
  label,
  n,
  tone,
  highlight,
  onClick,
}: {
  label: string;
  n: number;
  tone: 'red' | 'green' | 'gold' | 'muted';
  highlight?: boolean;
  onClick: () => void;
}) {
  const cls: Record<string, string> = {
    red: 'text-status-red',
    green: 'text-status-green',
    gold: 'text-status-gold',
    muted: 'text-muted',
  };
  return (
    <button
      onClick={onClick}
      disabled={n === 0}
      title={`Show ${label.toLowerCase()} rules`}
      className={[
        'inline-flex items-baseline gap-1.5 px-2 py-0.5 rounded transition whitespace-nowrap',
        n === 0 ? 'opacity-40 cursor-default' : 'hover:bg-slate2 cursor-pointer',
        highlight ? 'underline underline-offset-2' : '',
      ].join(' ')}
    >
      <span className={`font-bold ${cls[tone]}`}>{n}</span>
      <span className="text-muted">{label}</span>
    </button>
  );
}

function fmtNum(v: string | number) {
  const n = typeof v === 'number' ? v : Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString('en-US', { minimumFractionDigits: 2 });
}
