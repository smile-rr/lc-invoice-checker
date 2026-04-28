import { useApiProgress } from '../../hooks/useApiProgress';

/**
 * A thin, fixed progress bar at the very top of the viewport.
 * Animates in when any API request is in-flight and fades out when all
 * requests complete — giving the user immediate feedback that their action
 * triggered a backend call.
 *
 * Placed inside App.tsx so it covers the entire app.
 */
export function ApiProgressBar() {
  const active = useApiProgress();

  return (
    <div
      className="fixed inset-x-0 top-0 z-[9999] h-[3px] overflow-hidden"
      aria-hidden="true"
      style={{ pointerEvents: 'none' }}
    >
      {/* Background track — always present, barely visible */}
      <div className="absolute inset-0 bg-navy-1 opacity-[0.06]" />

      {/* Active bar with left-to-right shimmer */}
      <div
        className="absolute inset-y-0 left-0 bg-navy-1 transition-opacity duration-300"
        style={{
          width: active ? '60%' : '100%',
          opacity: active ? 1 : 0,
          // Indeterminate shimmer: width animates from 0→100% when active
          animation: active ? 'api-progress-shimmer 1.4s ease-in-out infinite' : 'none',
        }}
      />
    </div>
  );
}
