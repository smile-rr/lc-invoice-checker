// Thin fetch wrapper. Adds:
//   - 15s default timeout (AbortSignal.timeout)
//   - Error classification (NETWORK / HTTP_4XX / HTTP_5XX / TIMEOUT / PARSE)
//   - Health-bus signalling so HealthIndicator reacts within a single request
//     instead of waiting for the next 15s background poll.
// All UI fetches should go through this instead of raw fetch.

import { healthBus } from './healthBus';

export type ApiErrorCode = 'NETWORK' | 'HTTP_4XX' | 'HTTP_5XX' | 'TIMEOUT' | 'PARSE';

export class ApiError extends Error {
  code: ApiErrorCode;
  status?: number;
  body?: unknown;
  constructor(message: string, code: ApiErrorCode, status?: number, body?: unknown) {
    super(message);
    this.code = code;
    this.status = status;
    this.body = body;
  }
}

interface RequestOpts extends RequestInit {
  /** Override the 15s default. */
  timeoutMs?: number;
  /** Skip the health-bus mark on this request (e.g. health-check itself). */
  skipHealth?: boolean;
}

async function readBody(res: Response): Promise<unknown> {
  const text = await res.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function classifyHttp(status: number): ApiErrorCode {
  return status >= 500 ? 'HTTP_5XX' : 'HTTP_4XX';
}

export async function apiFetch(input: string, opts: RequestOpts = {}): Promise<Response> {
  const { timeoutMs = 15000, skipHealth, ...rest } = opts;
  let res: Response;
  try {
    res = await fetch(input, { ...rest, signal: AbortSignal.timeout(timeoutMs) });
  } catch (e: unknown) {
    const err = e as Error;
    const isAbort = err.name === 'TimeoutError' || err.name === 'AbortError';
    const code: ApiErrorCode = isAbort ? 'TIMEOUT' : 'NETWORK';
    const message = isAbort ? `Request timed out (${timeoutMs}ms)` : `Network error: ${err.message}`;
    if (!skipHealth) healthBus.markDown(message);
    throw new ApiError(message, code);
  }
  if (!res.ok) {
    if (res.status >= 500 && !skipHealth) {
      healthBus.markDown(`HTTP ${res.status}`);
    } else if (!skipHealth) {
      // 4xx responses still mean the backend is up.
      healthBus.markUp();
    }
    const body = await readBody(res);
    throw new ApiError(
      `HTTP ${res.status}`,
      classifyHttp(res.status),
      res.status,
      body,
    );
  }
  if (!skipHealth) healthBus.markUp();
  return res;
}

export async function apiJson<T>(input: string, opts: RequestOpts = {}): Promise<T> {
  const res = await apiFetch(input, opts);
  try {
    return (await res.json()) as T;
  } catch (e) {
    throw new ApiError(`Failed to parse JSON: ${(e as Error).message}`, 'PARSE');
  }
}

export async function apiText(input: string, opts: RequestOpts = {}): Promise<string> {
  const res = await apiFetch(input, opts);
  return res.text();
}
