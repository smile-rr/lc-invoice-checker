import type { CheckResult, CheckStatus, RuleSummary } from '../../types';

interface Props {
  /** Enabled rules in catalog order (already filtered to exclude disabled). */
  rules: RuleSummary[];
  /** Map of rule_id → completed result. Missing entry means "no result yet". */
  resultByRuleId: Map<string, CheckResult>;
  /** Currently focused rule (filter pin). Null = no filter. */
  focusedRuleId: string | null;
  onRuleClick: (ruleId: string) => void;
}

/**
 * Per-rule chip strip. Each chip is colour-coded by the rule's current
 * status; click to set the rule-id filter (or clear if already focused).
 */
export function RuleCatalogStrip({ rules, resultByRuleId, focusedRuleId, onRuleClick }: Props) {
  if (rules.length === 0) {
    return (
      <div className="px-4 py-2 border-b border-line bg-paper text-[11px] text-muted">
        Loading rule catalog…
      </div>
    );
  }
  return (
    <div className="px-4 py-2 border-b border-line bg-paper flex items-center gap-1.5 overflow-x-auto">
      {rules.map((r) => {
        const result = resultByRuleId.get(r.id);
        const status: CheckStatus | null = result?.status ?? null;
        const isFocused = focusedRuleId === r.id;
        const tone = chipTone(status, isFocused);
        const tip = result
          ? `${r.name} — ${status}` + (result.description ? ` · ${result.description}` : '')
          : `${r.name} — pending`;
        return (
          <button
            key={r.id}
            type="button"
            title={tip}
            onClick={() => onRuleClick(r.id)}
            className={[
              'inline-flex items-baseline gap-1 px-2 py-0.5 border whitespace-nowrap shrink-0 transition-colors',
              'font-mono text-[10px] uppercase tracking-[0.05em]',
              tone,
            ].join(' ')}
          >
            <span>{r.id}</span>
          </button>
        );
      })}
    </div>
  );
}

function chipTone(status: CheckStatus | null, focused: boolean): string {
  const ring = focused ? 'ring-2 ring-teal-1/60 ring-offset-1 ring-offset-paper font-bold' : '';
  if (status === 'PASS') return `border-status-green/40 text-status-green bg-status-greenSoft/30 ${ring}`;
  if (status === 'FAIL') return `border-status-red/50 text-status-red bg-status-redSoft ${ring}`;
  if (status === 'DOUBTS') return `border-status-gold/50 text-status-gold bg-status-goldSoft/40 ${ring}`;
  if (status === 'NOT_REQUIRED') return `border-line text-muted bg-slate2 italic ${ring}`;
  // No result yet — pulsing thin border.
  return `border-line text-muted bg-paper animate-pulse ${ring}`;
}
