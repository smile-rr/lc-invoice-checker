import { useEffect, useState } from 'react';
import { getPipelineConfig } from '../api/client';

interface PipelineConfig {
  configuredStages: Set<string>;   // backend stage names: "lc_parse", "invoice_extract", ...
  loading: boolean;
  error: string | null;
}

const EMPTY: PipelineConfig = {
  configuredStages: new Set<string>(),
  loading: true,
  error: null,
};

/**
 * Reads `/api/v1/pipeline` once. The set is the SoT for "is this stage wired
 * to run in the current build?" — used by the UI to mark stages as `skipped`
 * (not to be confused with `pending` / `running` / `done`).
 *
 * Pipeline configuration is fixed at API process start (`.endHere()` /
 * commented `.then()` lines in `LcCheckPipeline.java`), so per-session
 * refetching is unnecessary.
 */
export function usePipelineConfig(): PipelineConfig {
  const [state, setState] = useState<PipelineConfig>(EMPTY);

  useEffect(() => {
    let cancelled = false;
    getPipelineConfig()
      .then((res) => {
        if (cancelled) return;
        setState({
          configuredStages: new Set(res.configured_stages ?? []),
          loading: false,
          error: null,
        });
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setState({
          configuredStages: new Set(),
          loading: false,
          error: e instanceof Error ? e.message : String(e),
        });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return state;
}
