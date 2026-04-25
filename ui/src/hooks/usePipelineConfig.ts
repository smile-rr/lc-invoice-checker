import { useEffect, useState } from 'react';
import { getPipelineConfig } from '../api/client';

interface PipelineConfig {
  /** Stage names wired in the current build (lc_parse, invoice_extract, ...). */
  configuredStages: Set<string>;
  /** Invoice-extractor source names in chain priority order
   *  (e.g. qwen3-vl:4b-instruct_local, qwen-vl-plus_cloud, docling, mineru).
   *  Use fmtSource() to render the human-readable label in the UI. */
  extractorSources: string[];
  loading: boolean;
  error: string | null;
}

const EMPTY: PipelineConfig = {
  configuredStages: new Set<string>(),
  extractorSources: [],
  loading: true,
  error: null,
};

/**
 * Reads `/api/v1/pipeline` once. The result is the SoT for "what's wired in
 * this build" — drives both the pipeline-flow `skipped` styling AND the
 * extractor-card pre-population.
 *
 * Pipeline configuration is fixed at API process start (`.endHere()` /
 * commented `.then()` lines in `LcCheckPipeline.java`, plus the
 * `extractor.*-enabled` env vars), so per-session refetching is unnecessary.
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
          extractorSources: res.extractor_sources ?? [],
          loading: false,
          error: null,
        });
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setState({
          configuredStages: new Set(),
          extractorSources: [],
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
