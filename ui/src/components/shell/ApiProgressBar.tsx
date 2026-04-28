import { useApiProgress } from '../../hooks/useApiProgress';

/**
 * An inline progress bar rendered below the PipelineFlow section.
 * Animates in when any API request is in-flight and fades out when all
 * requests complete — giving the user immediate visual feedback that their
 * action triggered a backend call.
 *
 * Placed inline in UploadPage and SessionPage, immediately after PipelineFlow.
 */
export function ApiProgressBar() {
  const active = useApiProgress();

  return (
    <div
      className="relative h-1.5 w-full overflow-hidden bg-slate2"
      aria-hidden="true"
    >
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
