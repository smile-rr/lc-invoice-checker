import type { StageName } from '../../types';

const STAGES: Array<{ key: StageName; label: string }> = [
  { key: 'lc_parse', label: 'Parsing SWIFT MT700 fields' },
  { key: 'invoice_extract', label: 'Extracting invoice text from PDF' },
  { key: 'rule_activation', label: 'Activating UCP 600 / ISBP 821 rules' },
  { key: 'rule_check', label: 'Running compliance checks' },
  { key: 'report_assembly', label: 'Assembling discrepancy report' },
];

type Status = 'pending' | 'running' | 'done' | 'error';
interface Props {
  stages: Record<StageName, { status: Status; durationMs?: number }>;
}

export function ProcessBar({ stages }: Props) {
  return (
    <ol className="bg-navy-1 text-white rounded-card px-6 py-5 space-y-3">
      {STAGES.map((s, idx) => {
        const st = stages[s.key];
        return (
          <li key={s.key} className="flex items-center gap-3 text-sm">
            <span className="font-mono text-muted w-6">{(idx + 1).toString().padStart(2, '0')}</span>
            <Dot status={st.status} />
            <span
              className={[
                'flex-1',
                st.status === 'done' ? 'text-white' : '',
                st.status === 'running' ? 'text-teal-2 font-medium' : '',
                st.status === 'pending' ? 'text-muted' : '',
                st.status === 'error' ? 'text-status-red' : '',
              ].join(' ')}
            >
              {s.label}
            </span>
            {st.status === 'done' && st.durationMs !== undefined && (
              <span className="font-mono text-xs text-muted">{st.durationMs}ms</span>
            )}
            {st.status === 'error' && (
              <span className="font-mono text-xs text-status-red">FAILED</span>
            )}
          </li>
        );
      })}
    </ol>
  );
}

function Dot({ status }: { status: Status }) {
  const base = 'w-3 h-3 rounded-full shrink-0';
  if (status === 'done') return <span className={`${base} bg-status-green`} />;
  if (status === 'running') return <span className={`${base} bg-status-gold animate-blink`} />;
  if (status === 'error') return <span className={`${base} bg-status-red`} />;
  return <span className={`${base} bg-navy-3`} />;
}
