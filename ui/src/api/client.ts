import type { ExtractAttempt, FieldDefinition, RuleSummary, SessionSummary, StartResponse, TraceResponse } from '../types';

const API_BASE = '/api/v1/lc-check';

/** Names of pipeline stages currently wired to run. Drives "skipped" badges in UI. */
export async function getPipelineConfig(): Promise<{
  configured_stages: string[];
  extractor_sources: string[];
}> {
  const res = await fetch('/api/v1/pipeline');
  if (!res.ok) throw new Error(`pipeline failed: ${res.status}`);
  return res.json();
}

/** Rule catalog — read-only metadata used by the Compliance Check panel. */
export async function listRules(): Promise<RuleSummary[]> {
  const res = await fetch('/api/v1/rules');
  if (!res.ok) throw new Error(`rules failed: ${res.status}`);
  const body = (await res.json()) as { rules: RuleSummary[] };
  return body.rules;
}

/** Field-pool registry — single source of truth for canonical field metadata. */
export async function listFields(appliesTo?: 'LC' | 'INVOICE'): Promise<FieldDefinition[]> {
  const url = appliesTo
    ? `/api/v1/fields?applies_to=${appliesTo}`
    : '/api/v1/fields';
  const res = await fetch(url);
  if (!res.ok) throw new Error(`fields failed: ${res.status}`);
  const body = (await res.json()) as { fields: FieldDefinition[]; total: number };
  return body.fields;
}

/** Starts a streaming lc-check run. Returns immediately with the sessionId. */
export async function startCheck(lc: File, invoice: File): Promise<StartResponse> {
  const body = new FormData();
  body.append('lc', lc);
  body.append('invoice', invoice);
  const res = await fetch(`${API_BASE}/start`, { method: 'POST', body });
  if (!res.ok) throw new Error(`start failed: ${res.status} ${await res.text()}`);
  return res.json();
}

/** Fetches the raw MT700 text uploaded for a session. */
export async function getLcRaw(sessionId: string): Promise<string> {
  const res = await fetch(`${API_BASE}/${sessionId}/lc-raw`);
  if (!res.ok) throw new Error(`lc-raw failed: ${res.status}`);
  return res.text();
}

/** URL for the uploaded invoice PDF — pass to <embed> or pdf.js. */
export function invoiceUrl(sessionId: string): string {
  return `${API_BASE}/${sessionId}/invoice`;
}

/**
 * Full envelope event log for a session — same shape as the live SSE stream.
 * The frontend reducer consumes both this and live events identically, which
 * lets a page reload mid-run rebuild the exact state it would have had if the
 * SSE connection had stayed open.
 */
export async function getTrace(sessionId: string): Promise<TraceResponse> {
  const res = await fetch(`${API_BASE}/${sessionId}/trace`);
  if (!res.ok) throw new Error(`trace failed: ${res.status}`);
  return res.json();
}

/** Recent sessions for the "Last Results" list. */
export async function listSessions(limit = 20): Promise<SessionSummary[]> {
  const res = await fetch(`/api/v1/sessions?limit=${limit}`);
  if (!res.ok) throw new Error(`sessions failed: ${res.status}`);
  return res.json();
}

/**
 * All extractor attempts for a session, including failed ones. The
 * {@code is_selected=true} attempt is the one downstream rule checks ran
 * against; the others are exposed so an operator can compare extractor
 * performance side-by-side.
 */
export async function listExtracts(sessionId: string): Promise<ExtractAttempt[]> {
  const res = await fetch(`${API_BASE}/${sessionId}/extracts`);
  if (!res.ok) throw new Error(`extracts failed: ${res.status}`);
  return res.json();
}
