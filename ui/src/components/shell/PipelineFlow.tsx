import { Fragment } from 'react';
import {
  STEPS,
  type StepKey,
  type StepStatus,
  type StepView,
  viewForStep,
} from './steps';
import type { SessionState } from '../../hooks/useCheckSession';

interface Props {
  state: SessionState | null;
  active: StepKey;
  onSelect: (k: StepKey) => void;
  /** Names of pipeline stages currently wired to run, from `/api/v1/pipeline`. */
  configuredStages?: Set<string> | null;
}

/**
 * Compact horizontal pipeline. Six clickable step buttons with status dots,
 * connectors between them, and a single-line metric per step. Total height
 * ~64px so the body gets the vertical space.
 *
 * Status vocabulary on the dot:
 *   ●  done       (green)
 *   ◉  running    (gold, animated)
 *   ●  pending    (muted)
 *   ●  error      (red)
 *   ◌  skipped    (dashed grey ring — stage is wired off in current build)
 */
export function PipelineFlow({ state, active, onSelect, configuredStages = null }: Props) {
  return (
    <div className="bg-paper border-b border-line px-6 py-1">
      <div className="flex items-stretch gap-0">
        {STEPS.map((s, i) => {
          const view = viewForStep(s.key, state, configuredStages);
          const enabled = enabledFor(s.key, state) && view.status !== 'skipped';
          const nextView = i < STEPS.length - 1
            ? viewForStep(STEPS[i + 1].key, state, configuredStages)
            : null;
          return (
            <Fragment key={s.key}>
              <StepNode
                n={s.n}
                label={s.label}
                view={view}
                active={s.key === active}
                enabled={enabled}
                onClick={() => enabled && onSelect(s.key)}
              />
              {nextView && (
                <Connector
                  leftDone={view.status === 'done'}
                  rightDone={nextView.status === 'done'}
                  skippedAdj={view.status === 'skipped' || nextView.status === 'skipped'}
                />
              )}
            </Fragment>
          );
        })}
      </div>
    </div>
  );
}

function StepNode({
  n,
  label,
  view,
  active,
  enabled,
  onClick,
}: {
  n: string;
  label: string;
  view: StepView;
  active: boolean;
  enabled: boolean;
  onClick: () => void;
}) {
  const skipped = view.status === 'skipped';
  return (
    <button
      onClick={onClick}
      disabled={!enabled}
      title={skipped ? 'Stage is disabled in this build (commented in pipeline/LcCheckPipeline.java)' : undefined}
      className={[
        'flex-1 grid grid-rows-[auto_1fr_auto] items-center justify-items-center',
        'text-center px-1.5 py-1 min-h-[60px] rounded min-w-0',
        skipped
          ? 'cursor-not-allowed opacity-50 line-through decoration-muted/40 decoration-1'
          : enabled
            ? active
              ? 'bg-teal-1/10'
              : 'hover:bg-slate2'
            : 'cursor-not-allowed opacity-60',
      ].join(' ')}
    >
      <Dot status={view.status} active={active} />
      <div className="leading-none min-w-0 max-w-full">
        <span className="font-mono text-[9px] uppercase tracking-[0.18em] text-muted">{n}</span>
        <span
          className={[
            'font-mono text-[10px] uppercase tracking-[0.12em] ml-1.5',
            skipped
              ? 'text-muted'
              : active
                ? 'text-teal-1 font-semibold'
                : enabled
                  ? 'text-navy-1 font-medium'
                  : 'text-muted',
          ].join(' ')}
        >
          {label}
        </span>
      </div>
      <div
        className={[
          'font-mono text-[9px] h-3 leading-none truncate max-w-full',
          skipped ? 'text-muted/70 italic' : 'text-muted',
        ].join(' ')}
      >
        {view.metric ?? ''}
      </div>
    </button>
  );
}

function Connector({
  leftDone,
  rightDone,
  skippedAdj,
}: {
  leftDone: boolean;
  rightDone: boolean;
  skippedAdj: boolean;
}) {
  // Position the line at the same vertical offset as the dots inside StepNode
  // (py-1 + 2px to centre on the dot which is ~10px tall).
  let cls: string;
  if (skippedAdj) {
    cls = 'border-t border-dashed border-muted/40 bg-transparent';
  } else if (leftDone && rightDone) {
    cls = 'bg-status-green/40';
  } else if (leftDone) {
    cls = 'bg-status-gold/40';
  } else {
    cls = 'bg-line';
  }
  return (
    <div className="flex items-start pt-[10px] w-6 shrink-0">
      <span className={`block w-full h-px ${cls}`} />
    </div>
  );
}

function Dot({ status, active }: { status: StepStatus; active: boolean }) {
  const base = 'rounded-full shrink-0';
  const sizing = active ? 'w-3 h-3' : 'w-2.5 h-2.5';
  if (status === 'skipped') {
    return (
      <span
        className={`${base} ${sizing} bg-transparent border border-dashed border-muted/50`}
      />
    );
  }
  // Active + done → teal (matches label color) so the current step is visually
  // distinct from completed-but-not-selected steps (which stay green).
  const tone =
    active && status === 'done'
      ? 'bg-teal-1'
      : ({
          done: 'bg-status-green',
          running: active ? 'bg-status-gold' : 'bg-status-gold animate-blink',
          pending: 'bg-line',
          error: 'bg-status-red',
        } as Record<string, string>)[status];
  const ring = active ? 'ring-2 ring-teal-1 ring-offset-2 ring-offset-paper' : '';
  return <span className={`${base} ${sizing} ${tone} ${ring}`} />;
}

function enabledFor(key: StepKey, state: SessionState | null): boolean {
  if (!state || !state.sessionId) return key === 'upload';
  return true;
}
