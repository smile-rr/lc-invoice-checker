import type { CheckStatus } from '../../types';

export type StatusFilter = CheckStatus | null;

interface Props {
  /** Counts per status across the enabled-rule set. */
  counts: Record<CheckStatus, number>;
  /** Total enabled rules — denominator on the N/M badge. */
  totalEnabled: number;
  /** Total completed (rules with any result). */
  totalCompleted: number;
  /** Active status filter. */
  status: StatusFilter;
  /** Active rule-id (rule view) or fieldId (field view) filter. */
  ruleId: string | null;
  /** Which view mode — drives the banner label ("rule" vs "field"). */
  viewMode: 'rule' | 'field';
  onStatusChange: (next: StatusFilter) => void;
  onClearAll: () => void;
}

const STATUS_ORDER: CheckStatus[] = ['PASS', 'FAIL', 'DOUBTS', 'NOT_REQUIRED'];

const STATUS_LABEL: Record<CheckStatus, string> = {
  PASS:         'Pass',
  FAIL:         'Fail',
  DOUBTS:       'Doubts',
  NOT_REQUIRED: 'Not required',
};

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

/**
 * Status filter chip bar — simplified.
 *
 * Layout (left → right):
 *   [All] | [Pass] [Fail] [Doubts] [Not req]          N/M total
 *
 * A summary banner appears below the chip row only when a filter is active —
 * it names the active filters and exposes a single Clear action.
 */
export function StatusFilterBar({
  counts,
  totalEnabled,
  totalCompleted,
  status,
  ruleId,
  viewMode,
  onStatusChange,
  onClearAll,
}: Props) {
  const anyFilter = status !== null || ruleId !== null;

  return (
    <div>
      <div className="px-4 py-2 flex items-center gap-2 overflow-x-auto">
        {/* All — always visible. Filled when no filter is active. */}
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

        <span className="w-px h-5 bg-line shrink-0" />

        {/* Status chips. */}
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

        {/* Single N/M total badge — right-aligned. */}
        <span className="ml-auto font-mono text-[11px] text-muted whitespace-nowrap shrink-0">
          <span className="font-bold text-navy-1">{totalCompleted}</span>
          <span className="text-muted mx-0.5">/</span>
          <span className="font-bold text-navy-1">{totalEnabled}</span>
          <span className="text-muted ml-1">{viewMode === 'rule' ? 'total rules' : 'total fields'}</span>
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
          {ruleId !== null && (
            <span className="font-mono">
              {viewMode === 'field' ? 'field' : 'rule'} = <span className="text-navy-1 font-bold">{ruleId}</span>
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
