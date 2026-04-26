import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { listSessions } from '../../api/client';
import type { SessionSummary } from '../../types';

/**
 * "Recent Sessions" popover anchored to the TopNav. Opens on click, closes on
 * outside click / Esc / row click. Each row navigates to the session URL,
 * which now reliably renders even after a server restart because the LC text +
 * invoice PDF are persisted in MinIO content-addressed by SHA-256.
 *
 * Display name resolution: lc_reference → invoice_filename → short session id.
 * The first non-null wins, so sessions that failed before LC parse still get a
 * recognisable label.
 */
export function HistoryDropdown() {
  const [open, setOpen] = useState(false);
  const [sessions, setSessions] = useState<SessionSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const nav = useNavigate();

  // Single source of truth for "fetch + update" — used both by the on-open
  // effect and the refresh button. Reuses the running-list state on refresh
  // so the list doesn't blank out while a new fetch is in flight.
  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const rows = await listSessions(20);
      setSessions(rows);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  // Fetch on first open AND on every subsequent open — fresh data is cheap
  // (20 rows on a LAN). The refresh button covers the "open and forget" case
  // where the user is rapidly stacking runs and wants to see new ones without
  // closing the popover.
  useEffect(() => {
    if (!open) return;
    void load();
  }, [open, load]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    const onClick = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('mousedown', onClick);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('mousedown', onClick);
    };
  }, [open]);

  function handleRowClick(s: SessionSummary) {
    setOpen(false);
    nav(`/session/${s.session_id}`);
  }

  return (
    <div ref={wrapRef} className="relative">
      <button
        onClick={() => setOpen((v) => !v)}
        className={`px-2.5 py-1 rounded text-xs ${open ? 'bg-navy-3' : 'hover:bg-navy-3'}`}
        aria-haspopup="menu"
        aria-expanded={open}
      >
        History
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 top-full mt-1.5 w-[420px] max-h-[70vh] overflow-y-auto bg-paper text-navy-1 rounded-card border border-line shadow-xl animate-fadein"
        >
          <div className="px-3 py-2 border-b border-line flex items-center justify-between">
            <span className="text-xs uppercase tracking-caps text-muted">Recent sessions</span>
            <button
              onClick={(e) => {
                // Stop the popover's outside-click listener from closing it
                // when the user clicks the refresh icon inside the popover.
                e.stopPropagation();
                void load();
              }}
              disabled={loading}
              aria-label="Refresh sessions"
              title="Refresh"
              className="text-xs text-muted hover:text-navy-1 disabled:opacity-40 disabled:cursor-wait p-1 -m-1"
            >
              {/* SVG keeps the button glyph crisp at any zoom. The
                  refresh icon spins while a fetch is in flight. */}
              <svg
                xmlns="http://www.w3.org/2000/svg"
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                className={loading ? 'animate-spin' : ''}
              >
                <path d="M21 12a9 9 0 1 1-3-6.7" />
                <polyline points="21 3 21 9 15 9" />
              </svg>
            </button>
          </div>

          {sessions === null && !error && (
            <div className="px-3 py-6 text-sm text-muted text-center">Loading…</div>
          )}

          {error && (
            <div className="px-3 py-6 text-sm text-status-red text-center">
              Failed to load: {error}
            </div>
          )}

          {sessions?.length === 0 && (
            <div className="px-3 py-6 text-sm text-muted text-center">
              No previous sessions yet. Run a check from the upload page.
            </div>
          )}

          {sessions?.map((s) => (
            <button
              key={s.session_id}
              role="menuitem"
              onClick={() => handleRowClick(s)}
              className="w-full text-left px-3 py-2.5 border-b border-line/60 last:border-0 hover:bg-slate2 flex items-start gap-2"
            >
              <StatusPill status={s.status} compliant={s.compliant} discrepancies={s.discrepancies} />
              <div className="flex-1 min-w-0">
                <div className="text-sm font-medium truncate">{displayName(s)}</div>
                <div className="text-xs text-muted truncate mt-0.5">
                  {[s.beneficiary, s.applicant].filter(Boolean).join(' → ') ||
                    (s.invoice_filename ?? '—')}
                </div>
                <div className="text-xs text-muted mt-1 flex items-center gap-2">
                  <span>{relativeTime(s.created_at)}</span>
                  {s.rules_run > 0 && (
                    <span>
                      · {s.rules_run} rule{s.rules_run === 1 ? '' : 's'}
                      {s.discrepancies > 0 && (
                        <span className="text-status-red"> · {s.discrepancies} fail</span>
                      )}
                    </span>
                  )}
                </div>
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function displayName(s: SessionSummary): string {
  if (s.lc_reference && s.lc_reference.trim()) return s.lc_reference;
  if (s.invoice_filename && s.invoice_filename.trim()) return s.invoice_filename;
  return `#${s.session_id.slice(0, 8)}`;
}

function StatusPill({
  status,
  compliant,
  discrepancies,
}: {
  status: string;
  compliant: boolean | null;
  discrepancies: number;
}) {
  // Keep the dot small — it's a leading glyph next to the title.
  let cls = 'bg-muted';
  let label = status;
  if (status === 'RUNNING') {
    cls = 'bg-status-blue animate-blink';
    label = 'Running';
  } else if (status === 'FAILED') {
    cls = 'bg-status-red';
    label = 'Failed';
  } else if (status === 'COMPLETED') {
    if (compliant === true && discrepancies === 0) {
      cls = 'bg-status-green';
      label = 'Pass';
    } else {
      cls = 'bg-status-gold';
      label = 'Issues';
    }
  }
  return (
    <span
      title={label}
      aria-label={label}
      className={`flex-none w-2 h-2 rounded-full mt-2 ${cls}`}
    />
  );
}

function relativeTime(iso: string): string {
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return iso;
  const diffSec = Math.max(0, (Date.now() - t) / 1000);
  if (diffSec < 60) return 'just now';
  const min = Math.round(diffSec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.round(hr / 24);
  if (day < 7) return `${day}d ago`;
  return new Date(iso).toLocaleDateString();
}
