import { useEffect, useReducer } from 'react';
import { getTrace } from '../api/client';
import { useSseEvents } from '../api/sse';
import type {
  CheckResult,
  DiscrepancyReport,
  Event,
  InvoiceDocument,
  LcDocument,
  QueueContext,
  StageName,
} from '../types';

export type StageInfo = {
  status: 'pending' | 'running' | 'done' | 'error';
  message: string;
  data?: unknown;
};

export const STAGE_ORDER: StageName[] = [
  'session',
  'lc_parse',
  'invoice_extract',
  'programmatic',
  'agent',
];

export type SessionState = {
  sessionId: string | null;
  /** Anchor stages — pending → running → done | error. */
  stages: Record<StageName, StageInfo>;
  /** Extracted LC, populated on `status{stage:'lc_parse', state:'completed'}`. */
  lc?: LcDocument;
  /** Extracted invoice, populated on `status{stage:'invoice_extract', state:'completed'}`. */
  invoice?: InvoiceDocument;
  /** Append-only rule outcomes in arrival order. */
  checks: CheckResult[];
  /** Final report — set by the `complete` event. UI redirects to review on this. */
  report?: DiscrepancyReport;
  /** Pipeline halt — backend stops emitting after this. */
  error?: { message: string; stage?: string };
  /** Set while the session is QUEUED. Cleared once any non-queue stage starts. */
  queueContext?: QueueContext | null;
};

type QueuePayload = {
  position?: number;
  depth?: number;
  running_session_id?: string | null;
};

function buildInitialStages(): Record<StageName, StageInfo> {
  const out = {} as Record<StageName, StageInfo>;
  for (const s of STAGE_ORDER) out[s] = { status: 'pending', message: '' };
  return out;
}

function initial(sessionId: string | null): SessionState {
  return {
    sessionId,
    stages: buildInitialStages(),
    checks: [],
  };
}

export const EMPTY_SESSION_STATE: SessionState = initial(null);

type ResetAction = { kind: 'reset'; sessionId: string | null };
type EventAction = { kind: 'event'; event: Event };
type Action = ResetAction | EventAction;

/** Pure reducer used by both the live SSE handler and trace replay. */
export function reduce(state: SessionState, action: Action): SessionState {
  if (action.kind === 'reset') {
    return initial(action.sessionId);
  }
  const ev = action.event;
  switch (ev.type) {
    case 'status': {
      // Queue position events use a synthetic stage="queue" that's not part
      // of the pipeline stage chain; intercept before touching stages map.
      if ((ev.stage as string) === 'queue') {
        const p = (ev.data ?? {}) as QueuePayload;
        return {
          ...state,
          queueContext: {
            position: typeof p.position === 'number' ? p.position : 0,
            depth: typeof p.depth === 'number' ? p.depth : 0,
            running_session_id: p.running_session_id ?? null,
          },
        };
      }
      const stage = ev.stage as StageName;
      // started / running both keep the spinner active and just refresh the
      // message; completed flips to done. Anything else falls back to running.
      const status: StageInfo['status'] =
        ev.state === 'completed' ? 'done'
        : ev.state === 'started' || ev.state === 'running' ? 'running'
        : 'running';
      const next: SessionState = {
        ...state,
        // Any pipeline stage starting clears the queue context — we've left
        // the waiting room. (Idempotent: replays of an already-running session
        // don't bring back queueContext from earlier queue events.)
        queueContext: null,
        stages: {
          ...state.stages,
          [stage]: { status, message: ev.message, data: ev.data },
        },
      };
      if (stage === 'lc_parse' && ev.state === 'completed' && ev.data) {
        next.lc = ev.data as LcDocument;
      } else if (stage === 'invoice_extract' && ev.state === 'completed' && ev.data) {
        next.invoice = ev.data as InvoiceDocument;
      } else if (stage === 'session' && ev.state === 'started' && ev.data) {
        const p = ev.data as { sessionId?: string };
        if (p.sessionId) next.sessionId = p.sessionId;
      }
      return next;
    }
    case 'rule': {
      const result = ev.data;
      // Replace if rule already present (idempotent replay), else append.
      const idx = state.checks.findIndex((c) => c.rule_id === result.rule_id);
      const checks =
        idx >= 0
          ? state.checks.map((c, i) => (i === idx ? result : c))
          : [...state.checks, result];
      return { ...state, checks };
    }
    case 'error': {
      const stage = ev.stage as StageName | undefined;
      return {
        ...state,
        error: { message: ev.message, stage: ev.stage },
        stages: stage
          ? { ...state.stages, [stage]: { status: 'error', message: ev.message } }
          : state.stages,
      };
    }
    case 'complete': {
      return { ...state, report: ev.data };
    }
    default:
      return state;
  }
}

export function useCheckSession(sessionId: string | null) {
  const [state, dispatch] = useReducer(reduce, sessionId, initial);

  // Reset on sessionId change so reused mounts don't carry stale state.
  useEffect(() => {
    dispatch({ kind: 'reset', sessionId });
  }, [sessionId]);

  // Replay the trace once on mount (and after sessionId changes). Live SSE
  // events that arrive concurrently land in the same reducer; the rule-event
  // dedup-by-rule_id keeps replays idempotent.
  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    getTrace(sessionId)
      .then((trace) => {
        if (cancelled) return;
        for (const event of trace.events) {
          dispatch({ kind: 'event', event });
        }
        if (trace.queue_context) {
          // Synthesize a queue status event so the reducer's existing handler
          // populates state without a new action shape.
          dispatch({
            kind: 'event',
            event: {
              type: 'status',
              seq: 0,
              ts: new Date().toISOString(),
              stage: 'queue' as StageName,
              state: 'running',
              message: '',
              data: trace.queue_context,
            },
          });
        }
      })
      .catch(() => {
        // Session may not exist yet (fresh run). Live stream will populate state.
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  useSseEvents(sessionId, (event) => dispatch({ kind: 'event', event }));
  return state;
}
