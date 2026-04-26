import type { CheckStatus, CheckTypeEnum } from '../../types';

export type StatusFilter = CheckStatus | null;
export type TypeFilter = CheckTypeEnum | null;

interface Props {
  /** Counts per status across the enabled-rule set. The four numbers always
   *  sum to {@code totalCompleted} (rules with a result so far). */
  counts: Record<CheckStatus, number>;
  /** Counts per check_type across enabled rules — for the debug-side chips. */
  typeCounts: Record<CheckTypeEnum, number>;
  /** Per-type completion counts (rules with a result). Used for the
   *  "4/8 PROG · 2/9 AGENT · 6/17 total" progress strip. */
  typeCompleted: Record<CheckTypeEnum, number>;
  /** Total enabled rules — denominator on the progress strip. */
  totalEnabled: number;
  /** Total completed (rules with any result). */
  totalCompleted: number;
  /** Active filters; either may be null. AND together at apply time. */
  status: StatusFilter;
  type: TypeFilter;
  /** Active rule-id filter, if any (driven by the catalog strip). When set,
   *  the bar shows it in the summary banner with a click-to-clear. */
  ruleId: string | null;
  onStatusChange: (next: StatusFilter) => void;
  onTypeChange: (next: TypeFilter) => void;
  onClearAll: () => void;
}

const STATUS_ORDER: CheckStatus[] = ['PASS', 'FAIL', 'DOUBTS', 'NOT_REQUIRED'];
const TYPE_ORDER: CheckTypeEnum[] = ['PROGRAMMATIC', 'AGENT'];

const STATUS_LABEL: Record<CheckStatus, string> = {
  PASS:         'Pass',
  FAIL:         'Fail',
  DOUBTS:       'Doubts',
  NOT_REQUIRED: 'Not required',
};

const TYPE_LABEL: Record<CheckTypeEnum, string> = {
  PROGRAMMATIC: 'Programmatic',
  AGENT:        'Agent',
};

// Stronger active state: filled background, bolder border, leading check-mark.
// Inactive is the same as before.
const STATUS_TONE: Record<CheckStatus, { active: string; inactive: string }> = {
  PASS: {
    active:   'bg-status-green text-white border-status-green ring-2 ring-status-green/30 shadow-sm',
    inactive: 'border-line text-status-green hover:bg-status-greenSoft/40',
  },
  FAIL: {
    active:   'bg-status-red text-white border-status-red ring-2 ring-status-red/30 shadow-sm',
    inactive: 'border-line text-status-red hover:bg-status-redSoft/40',
  },
  DOUBTS: {
    active:   'bg-status-gold text-white border-status-gold ring-2 ring-status-gold/30 shadow-sm',
    inactive: 'border-line text-status-gold hover:bg-status-goldSoft/40',
  },
  NOT_REQUIRED: {
    active:   'bg-navy-1 text-white border-navy-1 ring-2 ring-navy-1/25 shadow-sm',
    inactive: 'border-line text-muted hover:bg-slate2',
  },
};

const TYPE_TONE: Record<CheckTypeEnum, { active: string; inactive: string }> = {
  PROGRAMMATIC: {
    active:   'bg-teal-2 text-white border-teal-2 ring-2 ring-teal-2/25 shadow-sm',
    inactive: 'border-line text-teal-2 hover:bg-teal-1/10',
  },
  AGENT: {
    active:   'bg-navy-1 text-white border-navy-1 ring-2 ring-navy-1/25 shadow-sm',
    inactive: 'border-line text-navy-1 hover:bg-navy-1/5',
  },
};

/**
 * Status + type filter chip bar.
 *
 * Layout (left → right):
 *   [All] | [Pass] [Fail] [Doubts] [Not req] | [PROG] [AGENT] | progress
 *
 * The leading {@code All} chip resets every active filter at once and is
 * always visible (matches the chip vocabulary instead of a separate Reset
 * button). Active chips use a stronger filled-background style with a ring
 * and leading check-mark so the user knows which dimension is narrowing
 * the list.
 *
 * A summary banner appears below the chip row only when at least one filter
 * is active — it names the active filters and exposes a single Clear action.
 *
 * The trailing progress strip shows
 * {@code <progCompleted>/<progTotal> PROG · <agentCompleted>/<agentTotal> AGENT · <total>/<totalEnabled> total}
 * and updates on every rule.completed event.
 */
export function StatusFilterBar({
  counts,
  typeCounts,
  typeCompleted,
  totalEnabled,
  totalCompleted,
  status,
  type,
  ruleId,
  onStatusChange,
  onTypeChange,
  onClearAll,
}: Props) {
  const anyFilter = status !== null || type !== null || ruleId !== null;

  return (
    <div className="border-b border-line bg-paper">
      <div className="px-4 py-2 flex items-center gap-2 overflow-x-auto">
        {/* All — always visible. Filled when no filter is active so users see
            the current state ("everything is shown"); when a filter is active,
            it becomes the call-to-action to reset. */}
        <button
          type="button"
          onClick={onClearAll}
          aria-pressed={!anyFilter}
          className={[
            'inline-flex items-baseline gap-1.5 px-2.5 py-1 rounded-sm border whitespace-nowrap transition-colors',
            'font-mono text-[11px] uppercase tracking-[0.07em] font-bold',
            !anyFilter
              ? 'bg-navy-1 text-white border-navy-1 ring-2 ring-navy-1/25 shadow-sm'
              : 'border-line text-navy-1 hover:bg-slate2',
          ].join(' ')}
          title={anyFilter ? 'Clear every active filter' : 'Showing every enabled rule'}
        >
          {!anyFilter && <span aria-hidden>✓</span>}
          <span className="font-sans normal-case font-medium tracking-normal">All</span>
        </button>

        <span className="w-px h-5 bg-line mx-1.5 shrink-0" aria-hidden />

        {/* Status chips */}
        {STATUS_ORDER.map((s) => {
          const isActive = status === s;
          const tone = STATUS_TONE[s];
          return (
            <button
              key={s}
              type="button"
              onClick={() => onStatusChange(isActive ? null : s)}
              aria-pressed={isActive}
              className={[
                'inline-flex items-baseline gap-1.5 px-2.5 py-1 rounded-sm border whitespace-nowrap transition-colors',
                'font-mono text-[11px] uppercase tracking-[0.07em] font-bold',
                isActive ? tone.active : tone.inactive,
              ].join(' ')}
            >
              {isActive && <span aria-hidden>✓</span>}
              <span>{counts[s]}</span>
              <span className="font-sans normal-case font-medium tracking-normal">{STATUS_LABEL[s]}</span>
            </button>
          );
        })}

        <span className="w-px h-5 bg-line mx-1.5 shrink-0" aria-hidden />

        {/* Type chips — debug aid, also reflects the inline category badge on
            each rule card so the same colour means the same thing. */}
        {TYPE_ORDER.map((t) => {
          const isActive = type === t;
          const tone = TYPE_TONE[t];
          return (
            <button
              key={t}
              type="button"
              onClick={() => onTypeChange(isActive ? null : t)}
              aria-pressed={isActive}
              title={`Show only ${TYPE_LABEL[t]} rules`}
              className={[
                'inline-flex items-baseline gap-1.5 px-2.5 py-1 rounded-sm border whitespace-nowrap transition-colors',
                'font-mono text-[11px] uppercase tracking-[0.07em] font-bold',
                isActive ? tone.active : tone.inactive,
              ].join(' ')}
            >
              {isActive && <span aria-hidden>✓</span>}
              <span>{typeCounts[t]}</span>
              <span className="font-sans normal-case font-medium tracking-normal">{TYPE_LABEL[t]}</span>
            </button>
          );
        })}

        {/* Progress strip — completed / total per category and overall. */}
        <span className="ml-auto font-sans text-[11px] text-muted whitespace-nowrap font-mono">
          <span className="text-teal-2 font-bold">
            {typeCompleted.PROGRAMMATIC}/{typeCounts.PROGRAMMATIC} PROG
          </span>
          <span className="mx-1.5 text-line">·</span>
          <span className="text-navy-1 font-bold">
            {typeCompleted.AGENT}/{typeCounts.AGENT} AGENT
          </span>
          <span className="mx-1.5 text-line">·</span>
          <span className="text-navy-1 font-bold">
            {totalCompleted}/{totalEnabled} total
          </span>
        </span>
      </div>

      {anyFilter && (
        <div className="px-4 py-1.5 border-t border-line bg-slate2/50 flex items-center gap-3 font-sans text-[11px] text-muted">
          <span className="font-semibold uppercase tracking-[0.08em] text-navy-1">Filtered:</span>
          {status !== null && (
            <span className="font-mono">
              status = <span className="text-navy-1 font-bold">{STATUS_LABEL[status]}</span>
            </span>
          )}
          {type !== null && (
            <span className="font-mono">
              category = <span className="text-navy-1 font-bold">{TYPE_LABEL[type]}</span>
            </span>
          )}
          {ruleId !== null && (
            <span className="font-mono">
              rule = <span className="text-navy-1 font-bold">{ruleId}</span>
            </span>
          )}
          <button
            type="button"
            onClick={onClearAll}
            className="ml-auto font-sans text-[11px] uppercase tracking-[0.07em] font-semibold text-navy-1 hover:underline"
          >
            Clear
          </button>
        </div>
      )}
    </div>
  );
}
