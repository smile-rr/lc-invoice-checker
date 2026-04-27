import { useEffect, useRef, useState } from 'react';
import { apiFetch } from '../../lib/apiClient';
import { healthBus, type HealthState } from '../../lib/healthBus';

const POLL_MS = 15000;
// Tiny debounce so a single transient blip (network hiccup) doesn't flash an
// overlay in and back out. Anything beyond ~2s is a real outage from the
// user's perspective.
const OVERLAY_DELAY_MS = 1500;

/**
 * Backend health surface.
 *
 * <p>While the backend is reachable the dot in the TopNav is the only visible
 * surface. As soon as a request fails (or the 15s background ping fails), a
 * full-app overlay appears so the user gets a single, unambiguous "the API is
 * down" signal instead of N per-pane error messages stacked across tabs.
 *
 * <p>The overlay carries: the last error string, an explicit "Retry now"
 * button (immediate ping, doesn't wait for the next 15s tick), and a clear
 * note that auto-retry is happening. Dismissing the overlay is intentionally
 * not allowed — there's nothing useful the user can do behind it.
 */
export function HealthIndicator() {
  const [state, setState] = useState<HealthState>(() => healthBus.get());
  const [retrying, setRetrying] = useState(false);
  // Debounce: only flip the overlay on once we've been down for OVERLAY_DELAY_MS.
  // Status that recovers within the window is treated as a non-event.
  const [overlayUp, setOverlayUp] = useState(false);
  const debounceRef = useRef<number | null>(null);

  useEffect(() => healthBus.subscribe(setState), []);

  useEffect(() => {
    if (debounceRef.current != null) {
      window.clearTimeout(debounceRef.current);
      debounceRef.current = null;
    }
    if (state.status === 'down') {
      debounceRef.current = window.setTimeout(() => setOverlayUp(true), OVERLAY_DELAY_MS);
    } else {
      setOverlayUp(false);
    }
    return () => {
      if (debounceRef.current != null) {
        window.clearTimeout(debounceRef.current);
        debounceRef.current = null;
      }
    };
  }, [state.status, state.changedAt]);

  // Background poll — kicks /actuator/health every 15s so an idle browser
  // still notices server recovery.
  useEffect(() => {
    let cancelled = false;
    async function ping() {
      try {
        await apiFetch('/actuator/health', { skipHealth: true, timeoutMs: 5000 });
        if (!cancelled) healthBus.markUp();
      } catch (e) {
        if (!cancelled) healthBus.markDown((e as Error).message);
      }
    }
    ping();
    const id = window.setInterval(ping, POLL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, []);

  async function retryNow() {
    setRetrying(true);
    try {
      await apiFetch('/actuator/health', { skipHealth: true, timeoutMs: 5000 });
      healthBus.markUp();
    } catch (e) {
      healthBus.markDown((e as Error).message);
    } finally {
      setRetrying(false);
    }
  }

  if (!overlayUp) return null;

  return (
    // z-[60] sits above the existing z-50 ConfirmDialog so the overlay wins
    // even if a confirm modal happened to be open when the backend went down.
    <div
      role="alertdialog"
      aria-live="assertive"
      aria-label="Backend unreachable"
      className="fixed inset-0 z-[60] bg-navy-1/70 backdrop-blur-sm flex items-center justify-center p-6 animate-fadein"
    >
      <div className="bg-paper text-navy-1 rounded-card border border-status-red/30 shadow-2xl max-w-md w-full p-6">
        <div className="flex items-center gap-3 mb-3">
          <span className="inline-block w-3 h-3 rounded-full bg-status-red animate-pulse" />
          <span className="font-mono text-[10px] uppercase tracking-[0.22em] text-status-red">
            API unreachable
          </span>
        </div>
        <h2 className="font-serif text-xl mb-2">We can&rsquo;t reach the server</h2>
        <p className="text-sm text-muted leading-relaxed mb-4">
          All pages stop loading new data while this is happening. Your in-progress
          sessions stay safe — they&rsquo;ll resume on the server once it&rsquo;s back.
        </p>
        {state.lastError && (
          <div className="mb-4 p-2.5 rounded-btn bg-slate2 font-mono text-[11px] text-muted break-all">
            {state.lastError}
          </div>
        )}
        <div className="flex items-center gap-3">
          <button
            onClick={retryNow}
            disabled={retrying}
            className="px-4 py-2 rounded-btn bg-navy-1 text-white text-sm font-medium hover:bg-navy-2 disabled:opacity-40 inline-flex items-center gap-2"
          >
            {retrying && (
              <span className="inline-block w-3 h-3 rounded-full border-2 border-white/30 border-t-white animate-spin" />
            )}
            {retrying ? 'Checking…' : 'Retry now'}
          </button>
          <span className="text-[11px] font-mono text-muted">
            auto-retrying every 15 s
          </span>
        </div>
      </div>
    </div>
  );
}

/**
 * Status dot for inline placement in TopNav. Real-time (no debounce) so the
 * ambient indicator reflects current state even while the overlay is still
 * inside its debounce window.
 */
export function HealthDot() {
  const [state, setState] = useState<HealthState>(() => healthBus.get());
  useEffect(() => healthBus.subscribe(setState), []);
  const cls =
    state.status === 'up'   ? 'bg-status-green'
    : state.status === 'down' ? 'bg-status-red'
    : 'bg-status-gold';
  const label =
    state.status === 'up'   ? 'API healthy'
    : state.status === 'down' ? `API unreachable${state.lastError ? `: ${state.lastError}` : ''}`
    : 'API status unknown';
  return (
    <span
      className={`inline-block w-2 h-2 rounded-full ${cls} ${state.status === 'down' ? 'animate-pulse' : ''}`}
      title={label}
      aria-label={label}
    />
  );
}
