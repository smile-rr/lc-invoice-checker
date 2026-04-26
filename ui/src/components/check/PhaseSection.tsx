import type { ReactNode } from 'react';
import type { BusinessPhase, CheckResult } from '../../types';

interface Props {
  id: string;
  phase: BusinessPhase;
  totalRulesInPhase: number;
  visibleRuleCount: number;
  hasFilter: boolean;
  /** All checks that landed in this phase (used for the header tally only). */
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

const HINT: Record<BusinessPhase, string> = {
  PARTIES:    'Issuer · applicant · countries',
  MONEY:      'Currency · amount · math',
  GOODS:      'Description · quantity · origin',
  LOGISTICS:  'Trade term · ports',
  PROCEDURAL: 'Dates · signature · LC#',
  HOLISTIC:   'Cross-document non-contradiction',
};

/**
 * Group container for one business phase. Header is intentionally neutral —
 * the goal is a clear visual divider between groups, not a status signal.
 * Status colours live ONLY on the per-rule cards. Generous bottom margin
 * separates one group from the next so the eye anchors easily.
 */
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
  const failed = checks.filter((c) => c.status === 'FAIL').length;
  const doubts = checks.filter((c) => c.status === 'DOUBTS').length;

  return (
    <section id={id} className="mb-10 scroll-mt-2" aria-labelledby={`${id}-title`}>
      <div className="bg-paper border border-line rounded-card overflow-hidden">
        {/* Neutral group header — typography-led divider, no status colour. */}
        <header className="px-5 py-3 border-b border-line bg-paper flex items-baseline gap-3">
          <span className="font-mono text-[10px] text-muted shrink-0 tabular-nums">
            {String(PHASE_ORDER.indexOf(phase) + 1).padStart(2, '0')}
          </span>
          <h3
            id={`${id}-title`}
            className="font-sans text-base font-semibold text-navy-1 tracking-tight"
          >
            {LABEL[phase]}
          </h3>
          <span className="font-sans text-[12px] text-muted truncate hidden sm:inline">
            {HINT[phase]}
          </span>

          <div className="flex-1" />

          <span className="font-mono text-[11px] tabular-nums shrink-0 text-muted">
            <span className="text-navy-1 font-semibold">{ran}</span>
            <span> / {totalRulesInPhase || ran}</span>
            {passed > 0 && <span className="ml-2 text-status-green">{passed} pass</span>}
            {failed > 0 && <span className="ml-2 text-status-red">{failed} fail</span>}
            {doubts > 0 && <span className="ml-2 text-status-gold">{doubts} doubts</span>}
          </span>
        </header>

        <div className="px-5 py-4 bg-slate2/30">
          {hasFilter && totalRulesInPhase > 0 && visibleRuleCount === 0 ? (
            <div className="font-sans text-xs text-muted italic px-1">
              No rules match the current filter in this phase.
            </div>
          ) : totalRulesInPhase === 0 ? (
            <div className="font-sans text-xs text-muted italic px-1">
              No rules in this phase yet.
            </div>
          ) : (
            <div>{children}</div>
          )}
        </div>
      </div>
    </section>
  );
}
