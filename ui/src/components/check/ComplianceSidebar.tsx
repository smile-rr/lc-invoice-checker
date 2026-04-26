import type { BusinessPhase, CheckResult } from '../../types';

interface Props {
  /** All completed rule outcomes — sidebar projects per-phase tallies. */
  checks: CheckResult[];
  activeId: BusinessPhase | null;
  onJumpPhase: (phase: BusinessPhase) => void;
}

const PHASE_ORDER: BusinessPhase[] = [
  'PARTIES',
  'MONEY',
  'GOODS',
  'LOGISTICS',
  'PROCEDURAL',
  'HOLISTIC',
];

const PHASE_LABEL: Record<BusinessPhase, string> = {
  PARTIES:    'Parties',
  MONEY:      'Money',
  GOODS:      'Goods',
  LOGISTICS:  'Logistics',
  PROCEDURAL: 'Procedural',
  HOLISTIC:   'Holistic',
};

const PHASE_HINT: Record<BusinessPhase, string> = {
  PARTIES:    'Issuer · applicant · countries',
  MONEY:      'Currency · amount · math',
  GOODS:      'Description · quantity · origin',
  LOGISTICS:  'Trade term · ports',
  PROCEDURAL: 'Dates · signature · LC#',
  HOLISTIC:   'Cross-document non-contradiction',
};

type PhaseTally = {
  ran: number;
  passed: number;
  discrepant: number;
};

function tallyByPhase(checks: CheckResult[]): Record<BusinessPhase, PhaseTally> {
  const out = {} as Record<BusinessPhase, PhaseTally>;
  for (const p of PHASE_ORDER) out[p] = { ran: 0, passed: 0, discrepant: 0 };
  for (const c of checks) {
    const p = (c.business_phase ?? 'PROCEDURAL') as BusinessPhase;
    const slot = out[p] ?? out.PROCEDURAL;
    slot.ran += 1;
    if (c.status === 'PASS') slot.passed += 1;
    if (c.status === 'DISCREPANT') slot.discrepant += 1;
  }
  return out;
}

/**
 * Left navigation rail — phases as wayfinding only. Rules are grouped by
 * business_phase in the main panel; this sidebar tallies and lets the
 * operator jump to a phase. Sub-stage progress lives in the page stepper.
 */
export function ComplianceSidebar({ checks, activeId, onJumpPhase }: Props) {
  const tallies = tallyByPhase(checks);

  return (
    <aside
      aria-label="Compliance Check phase navigation"
      className="w-[240px] shrink-0 border-r border-line bg-paper h-full overflow-y-auto"
    >
      <div className="px-4 py-3 border-b border-line">
        <div className="font-sans text-[11px] uppercase tracking-[0.10em] text-muted font-medium">
          Phase navigation
        </div>
        <div className="font-sans text-sm font-semibold text-navy-1 mt-0.5">
          Stage 03 · Compliance Check
        </div>
      </div>

      <ol className="py-2">
        {PHASE_ORDER.map((phase) => {
          const t = tallies[phase];
          const active = activeId === phase;
          const hasIssue = t.discrepant > 0;
          return (
            <li key={phase}>
              <button
                type="button"
                onClick={() => onJumpPhase(phase)}
                className={[
                  'w-full grid grid-cols-[16px_1fr_auto] items-baseline gap-2 px-4 py-2.5 text-left',
                  'border-l-[3px] transition-colors group',
                  active
                    ? 'bg-teal-1/10 border-l-teal-1 text-navy-1'
                    : hasIssue
                      ? 'border-l-status-red text-navy-1 hover:bg-slate2'
                      : 'border-l-transparent text-navy-1 hover:bg-slate2 hover:border-l-line',
                ].join(' ')}
              >
                <PhaseDot tally={t} active={active} />
                <div className="min-w-0">
                  <div
                    className={[
                      'font-sans text-[13px] truncate',
                      active ? 'font-semibold' : 'font-medium',
                    ].join(' ')}
                  >
                    {PHASE_LABEL[phase]}
                  </div>
                  <div className="font-sans text-[11px] text-muted truncate group-hover:text-navy-1/70">
                    {PHASE_HINT[phase]}
                  </div>
                </div>
                <Tally t={t} />
              </button>
            </li>
          );
        })}
      </ol>
    </aside>
  );
}

function PhaseDot({ tally, active }: { tally: PhaseTally; active: boolean }) {
  const base = 'w-2.5 h-2.5 rounded-full shrink-0 mt-1';
  let tone: string;
  if (tally.ran === 0) tone = 'bg-line';
  else if (tally.discrepant > 0) tone = 'bg-status-red';
  else tone = 'bg-status-green';
  const ring = active ? 'ring-2 ring-teal-1/40 ring-offset-1 ring-offset-paper' : '';
  return <span className={`${base} ${tone} ${ring}`} />;
}

function Tally({ t }: { t: PhaseTally }) {
  if (t.ran === 0) {
    return <span className="font-mono text-[11px] text-muted shrink-0 mt-0.5">·</span>;
  }
  return (
    <span className="font-mono text-[11px] shrink-0 tabular-nums mt-0.5">
      <span className="text-status-green">{t.passed}</span>
      <span className="text-muted">/</span>
      <span className={t.discrepant > 0 ? 'text-status-red font-semibold' : 'text-muted'}>
        {t.ran}
      </span>
    </span>
  );
}
