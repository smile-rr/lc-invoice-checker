import { useSearchParams } from 'react-router-dom';
import type { SessionState } from '../../hooks/useCheckSession';

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
 * Maps each UI step to the backend stage(s) it depends on. A UI step is
 * considered "skipped" iff every backing stage is absent from the
 * pipeline-config endpoint. Steps with NO backing stage (`upload`, `compare`)
 * are never skipped — they're UI-only views.
 */
const STEP_TO_STAGES: Record<StepKey, string[]> = {
  upload: [],
  lc: ['lc_parse'],
  invoice: ['invoice_extract'],
  compare: [],                   // derived from lc + invoice presence; no backend stage
  rules: ['rule_activation', 'rule_check'],
  review: ['report_assembly'],
};

export function isStepSkipped(key: StepKey, configured: Set<string> | null): boolean {
  if (!configured || configured.size === 0) return false;   // config unknown — assume enabled
  const required = STEP_TO_STAGES[key];
  if (required.length === 0) return false;                  // UI-only step
  return required.every((stage) => !configured.has(stage));
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
  // Honour the .endHere() debug halt: don't pretend a final report exists for
  // navigation purposes even though earlyFinalize synthesised a placeholder.
  if (s.haltedAfter) {
    if (s.checks.length > 0) return 'rules';
    if (s.invoice && s.lc) return 'compare';
    if (s.invoice) return 'invoice';
    if (s.lc) return 'lc';
    return 'upload';
  }
  // Don't auto-advance to a step whose backend stages are all skipped.
  const candidates: StepKey[] = [];
  if (s.report && !isStepSkipped('review', configured)) candidates.push('review');
  if ((s.checks.length > 0 || s.inFlightRuleIds.size > 0) && !isStepSkipped('rules', configured)) candidates.push('rules');
  if (s.invoice && s.lc) candidates.push('compare');
  if (s.invoice) candidates.push('invoice');
  if (s.lc) candidates.push('lc');
  candidates.push('upload');
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
