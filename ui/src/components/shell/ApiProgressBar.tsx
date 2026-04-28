import { useApiProgress } from '../../hooks/useApiProgress';

/**
 * An inline indeterminate progress bar rendered below the PipelineFlow section.
 * Follows the standard pattern used by Linear / Stripe / GitHub:
 * - No track — the bar itself is the signal
 * - Narrow bar flows left-to-right on active
 * - Hidden when idle
 *
 * Placed inline in UploadPage and SessionPage, immediately after PipelineFlow.
 */
export function ApiProgressBar() {
  const active = useApiProgress();

  return (
    <div
      className="relative h-[3px] w-full overflow-hidden z-[200]"
      aria-hidden="true"
    >
      <div
        className="absolute inset-y-0 left-0"
        style={{
          background: 'linear-gradient(90deg, transparent 0%, #22c55e 30%, #22c55e 70%, transparent 100%)',
          width: '40%',
          opacity: active ? 1 : 0,
          transition: 'opacity 0.25s ease',
          animation: active ? 'api-bar-flow 1.5s ease-in-out infinite' : 'none',
        }}
      />
    </div>
  );
}
