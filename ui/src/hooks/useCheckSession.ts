import { useEffect, useReducer } from 'react';
import { getTrace } from '../api/client';
import { useSseEvents } from '../api/sse';
import type {
  CheckEvent,
  CheckResult,
  CheckSession,
  DiscrepancyReport,
  ExtractAttempt,
  InvoiceDocument,
  LcDocument,
  StageName,
} from '../types';

type StageInfo = {
  status: 'pending' | 'running' | 'done' | 'error';
  durationMs?: number;
};

export type SessionState = {
  sessionId: string | null;
  stages: Record<StageName, StageInfo>;
  lc?: LcDocument;
  invoice?: InvoiceDocument;
  checks: CheckResult[];
  inFlightRuleIds: Set<string>;
  activatedRuleIds?: string[];
  report?: DiscrepancyReport;
  error?: { message: string; stage?: string };
  /**
   * Set when the pipeline was deliberately halted by a `.endHere()` debug switch
   * (see `pipeline/Flow.java`). This is NOT an error — the session reached a
   * clean terminal state at the named stage. UI should show an informational
   * banner, never the red "Pipeline error" treatment.
   */
  haltedAfter?: string;
  /**
   * Live per-source invoice-extractor state, keyed by source name. Populated
   * by EXTRACT_SOURCE_STARTED / EXTRACT_SOURCE_COMPLETED events as each
   * extractor begins/finishes. Sources only appear here once they actually
   * start running on the backend — disabled sources never show.
   */
  extractorStatus: Record<string, ExtractAttempt>;
};

const initialStages: Record<StageName, StageInfo> = {
  lc_parse: { status: 'pending' },
  invoice_extract: { status: 'pending' },
  rule_activation: { status: 'pending' },
  rule_check: { status: 'pending' },
  report_assembly: { status: 'pending' },
};

function initial(sessionId: string | null): SessionState {
  return {
    sessionId,
    stages: { ...initialStages },
    checks: [],
    inFlightRuleIds: new Set(),
    extractorStatus: {},
  };
}

/**
 * Idle / no-session state used by the landing page so it can render the same
 * shell ({@code SessionStrip} + {@code PipelineFlow}) as a real session view.
 */
export const EMPTY_SESSION_STATE: SessionState = initial(null);

type HydrateAction = { kind: 'hydrate'; trace: CheckSession };
type EventAction = { kind: 'event'; event: CheckEvent };
type ResetAction = { kind: 'reset'; sessionId: string | null };
type Action = HydrateAction | EventAction | ResetAction;

function hydrateFromTrace(trace: CheckSession): Partial<SessionState> {
  const next: Partial<SessionState> = {
    sessionId: trace.session_id,
    stages: { ...initialStages },
    checks: [],
    inFlightRuleIds: new Set(),
  };
  const st = next.stages!;

  if (trace.lc_parsing) {
    st.lc_parse = {
      status: trace.lc_parsing.status === 'SUCCESS' ? 'done' : 'error',
      durationMs: trace.lc_parsing.duration_ms,
    };
    if (trace.lc_parsing.output) next.lc = trace.lc_parsing.output;
  }
  if (trace.invoice_extraction) {
    st.invoice_extract = {
      status: trace.invoice_extraction.status === 'SUCCESS' ? 'done' : 'error',
      durationMs: trace.invoice_extraction.duration_ms,
    };
    if (trace.invoice_extraction.output) next.invoice = trace.invoice_extraction.output;
  }
  if (trace.final_report) {
    next.report = trace.final_report;
    // If we have a final report, everything upstream must be done. Mark the
    // remaining stages complete so the UI doesn't look half-finished.
    st.rule_activation = { status: 'done' };
    st.rule_check = { status: 'done' };
    st.report_assembly = { status: 'done' };

    // Source of truth: backend exposes typed CheckResults for every status
    // (passed / discrepant / unable_to_verify / not_applicable). No more
    // synthesis from CheckTrace + discrepancy summaries — the report is the
    // single shape we read from.
    const merged: CheckResult[] = [];
    for (const r of trace.final_report.passed || []) merged.push(r);
    for (const r of trace.final_report.discrepant || []) merged.push(r);
    for (const r of trace.final_report.unable_to_verify || []) merged.push(r);
    for (const r of trace.final_report.not_applicable || []) merged.push(r);
    next.checks = merged;
  }
  if (trace.error) {
    // The runner uses the "halted_after:<stage>" sentinel to mark a debug
    // .endHere() halt. That's a deliberate stop — surface it as a soft
    // banner, never as a pipeline error.
    if (trace.error.startsWith('halted_after:')) {
      next.haltedAfter = trace.error.substring('halted_after:'.length);
    } else {
      next.error = { message: trace.error };
    }
  }
  return next;
}

function reduce(state: SessionState, action: Action): SessionState {
  if (action.kind === 'reset') {
    return initial(action.sessionId);
  }
  if (action.kind === 'hydrate') {
    // Merge hydrated state with whatever live events have populated. Live data
    // wins on every field; hydrated data fills gaps. Checks are merged with
    // dedup-by-rule_id so the trace's seed plus subsequent live events for the
    // same rule never double up (the bug that turned 3 discrepancies into 6).
    const hydrated = hydrateFromTrace(action.trace);
    const existingIds = new Set(state.checks.map((c) => c.rule_id));
    const newFromHydrate = (hydrated.checks ?? []).filter(
      (c) => !existingIds.has(c.rule_id),
    );
    return {
      ...hydrated,
      ...state,
      stages: { ...hydrated.stages!, ...state.stages },
      lc: state.lc ?? hydrated.lc,
      invoice: state.invoice ?? hydrated.invoice,
      report: state.report ?? hydrated.report,
      checks: [...state.checks, ...newFromHydrate],
      inFlightRuleIds: state.inFlightRuleIds,
    };
  }

  const ev = action.event;
  switch (ev.type) {
    case 'session.started': {
      const p = ev.payload as { sessionId: string };
      return { ...state, sessionId: p.sessionId };
    }
    case 'stage.started': {
      if (!ev.stage) return state;
      return {
        ...state,
        stages: { ...state.stages, [ev.stage]: { status: 'running' } },
      };
    }
    case 'stage.completed': {
      if (!ev.stage) return state;
      const p = ev.payload as { durationMs: number; output?: unknown };
      const next: SessionState = {
        ...state,
        stages: {
          ...state.stages,
          [ev.stage]: { status: 'done', durationMs: p.durationMs },
        },
      };
      if (ev.stage === 'lc_parse' && p.output) {
        next.lc = p.output as LcDocument;
      } else if (ev.stage === 'invoice_extract' && p.output) {
        next.invoice = p.output as InvoiceDocument;
      } else if (ev.stage === 'rule_activation' && p.output) {
        const o = p.output as { activatedRuleIds?: string[] };
        next.activatedRuleIds = o.activatedRuleIds;
      }
      return next;
    }
    case 'check.started': {
      const p = ev.payload as { ruleId: string };
      const s = new Set(state.inFlightRuleIds);
      s.add(p.ruleId);
      return { ...state, inFlightRuleIds: s };
    }
    case 'check.completed': {
      const result = ev.payload as CheckResult;
      // Dedup by rule_id: if an entry already exists (e.g. hydrate seeded it
      // before this event arrived), replace rather than append. Without this
      // guard, a hydrate-then-stream race produces duplicate rule rows.
      const idx = state.checks.findIndex((c) => c.rule_id === result.rule_id);
      const newChecks =
        idx >= 0
          ? state.checks.map((c, i) => (i === idx ? result : c))
          : [...state.checks, result];
      const s = new Set(state.inFlightRuleIds);
      s.delete(result.rule_id);
      return { ...state, checks: newChecks, inFlightRuleIds: s };
    }
    case 'extract.source.started': {
      const p = ev.payload as { source: string };
      const prior = state.extractorStatus[p.source];
      const next: ExtractAttempt = {
        source: p.source,
        status: 'RUNNING',
        is_selected: prior?.is_selected ?? false,
        document: null,
        duration_ms: 0,
        started_at: ev.timestamp ?? new Date().toISOString(),
        error: null,
      };
      return {
        ...state,
        extractorStatus: { ...state.extractorStatus, [p.source]: next },
      };
    }
    case 'extract.source.completed': {
      const p = ev.payload as {
        source: string;
        success: boolean;
        confidence?: number;
        durationMs?: number;
        imageBased?: boolean;
        pages?: number;
        error?: string;
      };
      const prior = state.extractorStatus[p.source];
      const next: ExtractAttempt = {
        source: p.source,
        status: p.success ? 'SUCCESS' : 'FAILED',
        is_selected: prior?.is_selected ?? false,
        document: prior?.document ?? null,
        duration_ms: p.durationMs ?? prior?.duration_ms ?? 0,
        started_at: prior?.started_at ?? null,
        error: p.error ?? null,
        confidence: p.confidence,
      };
      return {
        ...state,
        extractorStatus: { ...state.extractorStatus, [p.source]: next },
      };
    }
    case 'report.complete': {
      const r = ev.payload as DiscrepancyReport;
      return { ...state, report: r };
    }
    case 'error': {
      const p = ev.payload as { message: string };
      return {
        ...state,
        error: { message: p.message, stage: ev.stage ?? undefined },
        stages: ev.stage
          ? { ...state.stages, [ev.stage]: { status: 'error' } }
          : state.stages,
      };
    }
    default:
      return state;
  }
}

export function useCheckSession(sessionId: string | null) {
  const [state, dispatch] = useReducer(reduce, sessionId, initial);

  // Reset state any time the sessionId actually changes — keeps the hook safe
  // when the SessionPage is reused across navigations (the URL param changes
  // but the component doesn't unmount).
  useEffect(() => {
    dispatch({ kind: 'reset', sessionId });
  }, [sessionId]);

  // Hydrate from /trace once per sessionId so a late-arriving tab (or a refresh
  // after the run completed and the SSE channel was torn down) still gets full
  // state. The cancelled flag drops the dispatch if sessionId changes mid-flight.
  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    getTrace(sessionId)
      .then((trace) => {
        if (!cancelled) dispatch({ kind: 'hydrate', trace });
      })
      .catch(() => {
        // Session may not be persisted yet (brand-new run). Ignore — the stream
        // will populate state as events arrive.
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  useSseEvents(sessionId, (event) => dispatch({ kind: 'event', event }));
  return state;
}
