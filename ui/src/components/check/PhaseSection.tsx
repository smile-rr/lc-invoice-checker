import type { ReactNode } from 'react';
import type { BusinessPhase, CheckResult } from '../../types';

interface Props {
  id: string;
  phase: BusinessPhase;
  totalRulesInPhase: number;
  visibleRuleCount: number;
  hasFilter: boolean;
  /** All checks in this phase (used for the header tally). */
  checks: CheckResult[];
  children?: ReactNode;
}

const PHASE_ORDER: BusinessPhase[] = [
  'PARTIES',
  'MONEY',
  'GOODS',
  'LOGISTICS',
  'PROCEDURAL',
  'HOLISTIC',
];

const LABEL: Record<BusinessPhase, string> = {
  PARTIES:    'Parties',
  MONEY:      'Money',
  GOODS:      'Goods',
  LOGISTICS:  'Logistics',
  PROCEDURAL: 'Procedural',
  HOLISTIC:   'Holistic',
};

export function PhaseSection({
  id,
  phase,
  totalRulesInPhase,
  visibleRuleCount,
  hasFilter,
  checks,
  children,
}: Props) {
  const ran = checks.length;
  const passed = checks.filter((c) => c.status === 'PASS').length;
  const discrepant = checks.filter((c) => c.status === 'DISCREPANT').length;
  const review = checks.filter(
    (c) => c.status === 'REQUIRES_HUMAN_REVIEW' || c.status === 'HUMAN_REVIEW',
  ).length;

  const headerBg = discrepant > 0 ? 'bg-status-redSoft' : 'bg-slate2';
  const headerBorder = discrepant > 0 ? 'border-l-status-red' : 'border-l-line';

  return (
    <section id={id} className="mb-6 scroll-mt-2" aria-labelledby={`${id}-title`}>
      <div className="bg-paper border border-line shadow-sm rounded-sm overflow-hidden">
        <header
          className={[
            'flex items-baseline gap-3 px-4 py-2.5 border-b border-line border-l-[4px]',
            headerBg,
            headerBorder,
          ].join(' ')}
        >
          <span className="font-mono text-[11px] text-muted shrink-0 tabular-nums">
            {String(PHASE_ORDER.indexOf(phase)).padStart(2, '0')}
          </span>
          <h3
            id={`${id}-title`}
            className="font-sans text-sm font-semibold text-navy-1 uppercase tracking-[0.07em]"
          >
            {LABEL[phase]}
          </h3>

          <div className="flex-1" />

          <span className="font-mono text-[11px] tabular-nums shrink-0">
            <span className="text-navy-1">{ran}</span>
            <span className="text-muted"> of {totalRulesInPhase || ran}</span>
            {passed > 0 && <span className="text-status-green ml-2">{passed} pass</span>}
            {discrepant > 0 && (
              <span className="text-status-red ml-2 font-semibold">{discrepant} fail</span>
            )}
            {review > 0 && <span className="text-status-gold ml-2">{review} review</span>}
          </span>
        </header>

        <div className="px-4 py-4">
          {hasFilter && ran > 0 && visibleRuleCount === 0 ? (
            <div className="font-sans text-xs text-muted italic px-1">
              No rules match the current filter in this phase.
            </div>
          ) : ran === 0 ? (
            <div className="font-sans text-xs text-muted italic px-1">
              No results in this phase yet.
            </div>
          ) : (
            <div>{children}</div>
          )}
        </div>
      </div>
    </section>
  );
}
