import { useSearchParams } from 'react-router-dom';
import type { SessionState } from '../../hooks/useCheckSession';
import type { StageName } from '../../types';

export type StepKey = 'upload' | 'lc' | 'invoice' | 'compare' | 'rules' | 'review';

export const STEPS: Array<{ key: StepKey; n: string; label: string }> = [
  { key: 'upload',  n: '00', label: 'Upload' },
  { key: 'lc',      n: '01', label: 'LC Parse' },
  { key: 'invoice', n: '02', label: 'Invoice' },
  { key: 'compare', n: '03', label: 'Compare' },
  { key: 'rules',   n: '04', label: 'Rules' },
  { key: 'review',  n: '05', label: 'Review' },
];

const KEYS: StepKey[] = STEPS.map((s) => s.key);

/**
 * Maps each UI step to the backend stage(s) it depends on. A step is
 * "skipped" if any of its required backend stages is missing from the
 * pipeline-config endpoint — every required stage must be wired in for
 * the step to render meaningfully.
 *
 * `compare` has no backend stage of its own, but it cross-references
 * lc / invoice output AGAINST the rule outcomes — i.e. it's the
 * "what did rule-check find" view layered on the LC↔invoice diff. So it
 * inherits all three (lc_parse, invoice_extract, rule_check) as
 * requirements; until rule_check is wired in, compare stays skipped and
 * auto-advance lands on `invoice` instead.
 *
 * `upload` has no backend stage and stays always-enabled.
 */
const STEP_TO_STAGES: Record<StepKey, string[]> = {
  upload: [],
  lc: ['lc_parse'],
  invoice: ['invoice_extract'],
  compare: ['lc_parse', 'invoice_extract', 'rule_check'],
  rules: ['rule_activation', 'rule_check'],
  review: ['report_assembly'],
};

export function isStepSkipped(key: StepKey, configured: Set<string> | null): boolean {
  if (!configured || configured.size === 0) return false;   // config unknown — assume enabled
  const required = STEP_TO_STAGES[key];
  if (required.length === 0) return false;                  // UI-only step (upload)
  // Skip iff ANY required stage is missing — a step needs every dependency wired
  // in to render meaningfully (compare needs both LC and invoice, rules needs
  // both activation and check, etc.).
  return required.some((stage) => !configured.has(stage));
}

/**
 * Active step is URL-driven (`?step=...`). When the URL omits step, fall back
 * to the furthest-progressed stage in {@link state}, so a fresh load lands on
 * the most useful view automatically.
 *
 * If the pipeline halted (e.g. `.endHere()` debug trim) or stages are skipped,
 * derived auto-advance refuses to land on a skipped step — it sticks at the
 * last *executed* step instead.
 */
export function useStep(
  state: SessionState | null,
  configured: Set<string> | null = null,
): [StepKey, (k: StepKey) => void] {
  const [params, setParams] = useSearchParams();
  const raw = params.get('step') as StepKey | null;
  const explicit: StepKey | null = raw && KEYS.includes(raw) ? raw : null;
  const derived = state ? derivedStep(state, configured) : 'upload';
  const active = explicit ?? derived;
  const set = (next: StepKey) => {
    const p = new URLSearchParams(params);
    p.set('step', next);
    setParams(p, { replace: false });
  };
  return [active, set];
}

/**
 * Latest step that has *content* to show. Used as the auto-advance target
 * when the user hasn't manually picked a step yet.
 *
 * Halted sessions (`s.haltedAfter`) NEVER auto-advance to `review` — there's
 * no real review to show. They stick at the last step the pipeline actually
 * ran.
 */
function derivedStep(s: SessionState, configured: Set<string> | null): StepKey {
  const enabled = (k: StepKey) => !isStepSkipped(k, configured);
  // A step is "visible" the moment its backing stage is active OR there's
  // any live signal for it — even before the stage has produced its output.
  // Without this, a session that has just started invoice_extract (s.lc set,
  // s.invoice still null) would auto-advance to LC and the user would miss
  // watching the live extractor cards on the Invoice tab.
  const stageActive = (n: StageName) =>
    s.stages[n].status === 'running' || s.stages[n].status === 'done';
  const lcVisible = !!s.lc || stageActive('lc_parse');
  const invoiceVisible =
    !!s.invoice ||
    stageActive('invoice_extract') ||
    Object.keys(s.extractorStatus ?? {}).length > 0;

  // Honour the .endHere() debug halt: don't pretend a final report exists for
  // navigation purposes even though earlyFinalize synthesised a placeholder.
  if (s.haltedAfter) {
    if (s.checks.length > 0 && enabled('rules')) return 'rules';
    if (s.invoice && s.lc && enabled('compare')) return 'compare';
    if (invoiceVisible && enabled('invoice')) return 'invoice';
    if (lcVisible && enabled('lc')) return 'lc';
    return 'upload';
  }
  // Don't auto-advance to a step whose required backend stages aren't wired in.
  const candidates: StepKey[] = [];
  if (s.report && enabled('review')) candidates.push('review');
  if ((s.checks.length > 0 || s.inFlightRuleIds.size > 0) && enabled('rules')) candidates.push('rules');
  if (s.invoice && s.lc && enabled('compare')) candidates.push('compare');
  if (invoiceVisible && enabled('invoice')) candidates.push('invoice');
  if (lcVisible && enabled('lc')) candidates.push('lc');
  // Fallback. If a session exists but no progress is visible yet (the race
  // window between Run-clicked and the first SSE event), prefer the earliest
  // configured content step over 'upload' — otherwise the user briefly sees
  // a blank upload form on a session that is already streaming. With no
  // session at all we still land on 'upload' as before.
  if (s.sessionId) {
    if (enabled('lc')) candidates.push('lc');
    else if (enabled('invoice')) candidates.push('invoice');
    else candidates.push('upload');
  } else {
    candidates.push('upload');
  }
  return candidates[0];
}

/** Per-step status driver — used by {@link PipelineFlow} for visuals. */
export type StepStatus = 'pending' | 'running' | 'done' | 'error' | 'skipped';
export interface StepView {
  status: StepStatus;
  metric: string | null;
}

export function viewForStep(
  key: StepKey,
  s: SessionState | null,
  configured: Set<string> | null = null,
): StepView {
  if (isStepSkipped(key, configured)) {
    return { status: 'skipped', metric: 'disabled' };
  }
  if (!s) {
    return key === 'upload' ? { status: 'running', metric: null } : { status: 'pending', metric: null };
  }
  switch (key) {
    case 'upload':
      return { status: 'done', metric: s.sessionId ? '2 files' : null };
    case 'lc': {
      const stg = s.stages.lc_parse;
      const metric = s.lc?.lc_number ? s.lc.lc_number : stg.status === 'done' ? 'parsed' : null;
      return { status: stg.status, metric };
    }
    case 'invoice': {
      const stg = s.stages.invoice_extract;
      const metric = s.invoice
        ? `${s.invoice.extractor_used} · ${(s.invoice.extractor_confidence * 100).toFixed(0)}%`
        : stg.status === 'running'
        ? 'extracting…'
        : null;
      return { status: stg.status, metric };
    }
    case 'compare': {
      // Compare has no backend stage of its own — derive from invoice + lc availability.
      if (s.invoice && s.lc) {
        return { status: 'done', metric: `${countMatches(s)} compared` };
      }
      return { status: 'pending', metric: null };
    }
    case 'rules': {
      const stg = s.stages.rule_check;
      const total = s.checks.length;
      const fail = s.checks.filter((c) => c.status === 'DISCREPANT').length;
      const pass = s.checks.filter((c) => c.status === 'PASS').length;
      const metric =
        stg.status === 'running'
          ? `${total} running…`
          : total > 0
          ? `${pass}✓ ${fail}✗`
          : null;
      return { status: stg.status, metric };
    }
    case 'review': {
      // Halted runs have a synthetic placeholder report, not a real one.
      if (s.report && !s.haltedAfter) {
        const verdict = s.report.compliant ? 'pass' : 'fail';
        return { status: 'done', metric: verdict };
      }
      return { status: 'pending', metric: null };
    }
  }
}

function countMatches(s: SessionState): number {
  // Rough — exact pivot scoring lives in CompareTab. This is just for the metric.
  return Object.keys(s.lc?.raw_fields ?? {}).length;
}
