// Display formatter for backend-stored durations (always milliseconds).
//
// Storage stays canonical (ms) — see logic-flow rationale: ms preserves
// sub-second precision for SQL aggregates / observability tooling, and
// formatting the user-facing string lives here at the render edge.
//
// To switch the whole UI back to raw ms, flip DURATION_UNIT to 'ms'.
// Two render call sites (InvoicePanel card body, InvoicePanel footer)
// pick up the change automatically.

const DURATION_UNIT: 'seconds' | 'ms' = 'seconds';

/**
 * Format a duration (in milliseconds) for human display.
 *
 * Defaults to seconds with one decimal — extractor wall-clock times sit
 * in the 1–30 s range, where ms granularity adds no signal but doubles
 * the digit count. Sub-100 ms collapses to "<0.1s" to keep one unit
 * across the whole card row (no mixed `850ms` / `15.2s` columns).
 */
export function fmtMs(ms: number | null | undefined): string {
  if (ms == null) return '—';
  if (DURATION_UNIT === 'ms') return `${ms}ms`;
  if (ms < 100) return '<0.1s';
  return `${(ms / 1000).toFixed(1)}s`;
}
