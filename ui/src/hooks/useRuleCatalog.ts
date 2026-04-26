import { useEffect, useMemo, useState } from 'react';
import { listRules } from '../api/client';
import type { RuleSummary } from '../types';

interface CatalogState {
  rules: RuleSummary[];
  byId: Map<string, RuleSummary>;
  ready: boolean;
  error?: string;
}

const EMPTY_CATALOG: CatalogState = {
  rules: [],
  byId: new Map(),
  ready: false,
};

let cache: CatalogState | null = null;
let inflight: Promise<CatalogState> | null = null;

/**
 * Fetch the rule catalog from {@code GET /api/v1/rules} once per page load and
 * cache it in module scope. The catalog is read-only metadata that drives the
 * Compliance Check panel's authority paraphrases, business-phase chips, and
 * check-method badges. Rules rarely change in a session, so re-fetching would
 * be wasteful.
 *
 * <p>Returns a stable reference between renders; consumers can use the
 * {@code byId} map directly without memoising again.
 */
export function useRuleCatalog(): CatalogState {
  const [state, setState] = useState<CatalogState>(() => cache ?? EMPTY_CATALOG);

  useEffect(() => {
    if (cache) {
      if (state !== cache) setState(cache);
      return;
    }
    if (!inflight) {
      inflight = listRules()
        .then((rules) => {
          const byId = new Map(rules.map((r) => [r.id, r]));
          cache = { rules, byId, ready: true };
          return cache;
        })
        .catch((err: unknown) => {
          const fallback: CatalogState = {
            ...EMPTY_CATALOG,
            ready: true,
            error: err instanceof Error ? err.message : String(err),
          };
          cache = fallback;
          return fallback;
        })
        .finally(() => {
          inflight = null;
        });
    }
    let cancelled = false;
    inflight.then((next) => {
      if (!cancelled) setState(next);
    });
    return () => {
      cancelled = true;
    };
  }, [state]);

  return useMemo(() => state, [state]);
}
