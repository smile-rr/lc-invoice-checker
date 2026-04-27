import { apiFetch, apiJson, apiText, ApiError } from '../lib/apiClient';
import type {
  ExtractAttempt,
  FieldDefinition,
  QueueStatus,
  RuleSummary,
  SessionSummary,
  StartResponse,
  TagMeta,
  TraceResponse,
} from '../types';

const API_BASE = '/api/v1/lc-check';

/** Names of pipeline stages currently wired to run. Drives "skipped" badges in UI. */
export async function getPipelineConfig(): Promise<{
  configured_stages: string[];
  extractor_sources: string[];
}> {
  return apiJson('/api/v1/pipeline');
}

/** Rule catalog — read-only metadata used by the Compliance Check panel. */
export async function listRules(): Promise<RuleSummary[]> {
  const body = await apiJson<{ rules: RuleSummary[] }>('/api/v1/rules');
  return body.rules;
}

/** Field-pool registry — single source of truth for canonical field metadata. */
export async function listFields(appliesTo?: 'LC' | 'INVOICE'): Promise<FieldDefinition[]> {
  const url = appliesTo
    ? `/api/v1/fields?applies_to=${appliesTo}`
    : '/api/v1/fields';
  const body = await apiJson<{ fields: FieldDefinition[]; total: number }>(url);
  return body.fields;
}

/** MT700 tag metadata so the UI can mirror server-side mandatory rules. */
export async function listTagMeta(): Promise<TagMeta[]> {
  return apiJson<TagMeta[]>('/api/v1/lc-meta/tags');
}

/**
 * Submit an LC + invoice run. Returns immediately with a session id while the
 * pipeline is queued; UI subscribes to SSE / polls /queue/status to track it.
 *
 * Throws ApiError on validation failures (HTTP 400) — caller should surface
 * the message and let the user fix the input.
 */
export async function startCheck(lc: File, invoice: File): Promise<StartResponse> {
  const body = new FormData();
  body.append('lc', lc);
  body.append('invoice', invoice);
  return apiJson<StartResponse>(`${API_BASE}/start`, { method: 'POST', body });
}

/** Cancel a QUEUED session (no-op once running). */
export async function cancelSession(sessionId: string): Promise<void> {
  await apiFetch(`${API_BASE}/${sessionId}`, { method: 'DELETE' });
}

/** Cross-session view: who's running, who's queued, and capacity. */
export async function getQueueStatus(): Promise<QueueStatus> {
  return apiJson<QueueStatus>('/api/v1/queue/status');
}

/** Fetches the raw MT700 text uploaded for a session. */
export async function getLcRaw(sessionId: string): Promise<string> {
  return apiText(`${API_BASE}/${sessionId}/lc-raw`);
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
  return apiJson<TraceResponse>(`${API_BASE}/${sessionId}/trace`);
}

/** Recent sessions for the "Last Results" list. */
export async function listSessions(limit = 20): Promise<SessionSummary[]> {
  return apiJson<SessionSummary[]>(`/api/v1/sessions?limit=${limit}`);
}

/**
 * All extractor attempts for a session, including failed ones. The
 * {@code is_selected=true} attempt is the one downstream rule checks ran
 * against; the others are exposed so an operator can compare extractor
 * performance side-by-side.
 */
export async function listExtracts(sessionId: string): Promise<ExtractAttempt[]> {
  return apiJson<ExtractAttempt[]>(`${API_BASE}/${sessionId}/extracts`);
}

export { ApiError };
