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
      className="fixed inset-x-0 top-10 z-100 h-0.5 overflow-hidden"
      aria-hidden="true"
      style={{ pointerEvents: 'none' }}
    >
      {/* Background track */}
      <div className="absolute inset-0 bg-[#22c55e] opacity-10" />

      {/* Bright green wave sweeping left to right */}
      <div
        className="absolute inset-y-0 left-0 transition-opacity duration-200"
        style={{
          backgroundColor: '#22c55e',
          width: active ? '70%' : '100%',
          opacity: active ? 1 : 0,
          animation: active ? 'api-progress-shimmer 1.2s ease-in-out infinite' : 'none',
        }}
      />
    </div>
  );
}
