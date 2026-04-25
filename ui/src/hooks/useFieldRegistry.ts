import { useEffect, useMemo, useState } from 'react';
import { listFields } from '../api/client';
import type { FieldDefinition } from '../types';

interface RegistryState {
  fields: FieldDefinition[];
  byKey: Map<string, FieldDefinition>;
  byGroup: Map<string, FieldDefinition[]>;
  loading: boolean;
  error: string | null;
}

/**
 * Loads the field-pool registry once for an `applies_to` side and indexes it
 * by key + group. UI panels should source their labels and groupings from
 * here instead of hardcoding field names.
 *
 * The registry is small (≈30 entries today) and stable across a session, so
 * this is a session-lived in-memory cache — not in any global store.
 */
export function useFieldRegistry(appliesTo: 'LC' | 'INVOICE'): RegistryState {
  const [fields, setFields] = useState<FieldDefinition[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    listFields(appliesTo)
      .then((rows) => {
        if (!cancelled) {
          setFields(rows);
          setError(null);
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [appliesTo]);

  return useMemo(() => {
    const byKey = new Map<string, FieldDefinition>();
    const byGroup = new Map<string, FieldDefinition[]>();
    for (const f of fields) {
      byKey.set(f.key, f);
      const g = f.group ?? 'other';
      const arr = byGroup.get(g) ?? [];
      arr.push(f);
      byGroup.set(g, arr);
    }
    return { fields, byKey, byGroup, loading, error };
  }, [fields, loading, error]);
}
