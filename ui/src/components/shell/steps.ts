import { useSearchParams } from 'react-router-dom';
import type { SessionState } from '../../hooks/useCheckSession';
import type { StageName } from '../../types';

export type StepKey = 'upload' | 'lc' | 'invoice' | 'check' | 'review';

export const STEPS: Array<{ key: StepKey; n: string; label: string }> = [
  { key: 'upload',  n: '00', label: 'Upload' },
  { key: 'lc',      n: '01', label: 'LC Parse' },
  { key: 'invoice', n: '02', label: 'Invoice' },
  { key: 'check',   n: '03', label: 'Compliance Check' },
  { key: 'review',  n: '04', label: 'Review' },
];

const KEYS: StepKey[] = STEPS.map((s) => s.key);

/**
 * Maps each UI step to the backend stage(s) it depends on. The check step
 * waits for both check sub-stages (programmatic + agent) to be wired in.
 */
const STEP_TO_STAGES: Record<StepKey, string[]> = {
  upload: [],
  lc: ['lc_parse'],
  invoice: ['invoice_extract'],
  check: ['programmatic', 'agent'],
  review: [],
};

export function isStepSkipped(key: StepKey, configured: Set<string> | null): boolean {
  if (!configured || configured.size === 0) return false;
  const required = STEP_TO_STAGES[key];
  if (required.length === 0) return false;
  return required.some((stage) => !configured.has(stage));
}

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

function derivedStep(s: SessionState, configured: Set<string> | null): StepKey {
  // Queued sessions belong on the Upload view (the QueueWaitCard renders
  // there, side-by-side with the file preview the user already uploaded).
  if (s.queueContext) return 'upload';
  const enabled = (k: StepKey) => !isStepSkipped(k, configured);
  const stageActive = (n: StageName) =>
    s.stages[n].status === 'running' || s.stages[n].status === 'done';
  const lcVisible = !!s.lc || stageActive('lc_parse');
  const invoiceVisible = !!s.invoice || stageActive('invoice_extract');
  const checkActive =
    s.checks.length > 0 || stageActive('programmatic') || stageActive('agent');

  const candidates: StepKey[] = [];
  if (s.report && enabled('review')) candidates.push('review');
  if (checkActive && enabled('check')) candidates.push('check');
  if (invoiceVisible && enabled('invoice')) candidates.push('invoice');
  if (lcVisible && enabled('lc')) candidates.push('lc');
  if (s.sessionId) {
    if (enabled('lc')) candidates.push('lc');
    else if (enabled('invoice')) candidates.push('invoice');
    else candidates.push('upload');
  } else {
    candidates.push('upload');
  }
  return candidates[0];
}

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
      const status: StepStatus = s.lc ? 'done' : stg.status;
      const metric = s.lc?.lc_number ? s.lc.lc_number : status === 'done' ? 'parsed' : null;
      return { status, metric };
    }
    case 'invoice': {
      const stg = s.stages.invoice_extract;
      const status: StepStatus = s.invoice ? 'done' : stg.status;
      const metric = s.invoice
        ? `${s.invoice.extractor_used} · ${(s.invoice.extractor_confidence * 100).toFixed(0)}%`
        : stg.status === 'running'
        ? 'extracting…'
        : null;
      return { status, metric };
    }
    case 'check': {
      const prog = s.stages.programmatic;
      const agent = s.stages.agent;
      const total = s.checks.length;
      const fail = s.checks.filter((c) => c.status === 'FAIL').length;
      const pass = s.checks.filter((c) => c.status === 'PASS').length;

      // Both sub-stages done → step done. Either running → step running.
      let status: StepStatus = 'pending';
      if (prog.status === 'error' || agent.status === 'error') status = 'error';
      else if (agent.status === 'done') status = 'done';
      else if (prog.status === 'running' || agent.status === 'running') status = 'running';
      else if (prog.status === 'done') status = 'running';

      let metric: string | null;
      if (status === 'running') {
        metric = total > 0 ? `${total} ran…` : 'checking…';
      } else if (total > 0) {
        metric = `${pass}✓ ${fail}✗`;
      } else {
        metric = null;
      }
      return { status, metric };
    }
    case 'review': {
      if (s.report) {
        const verdict = s.report.compliant ? 'pass' : 'fail';
        return { status: 'done', metric: verdict };
      }
      return { status: 'pending', metric: null };
    }
  }
}
