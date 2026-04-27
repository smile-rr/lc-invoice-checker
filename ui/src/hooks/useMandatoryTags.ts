import { useEffect, useState } from 'react';
import { listTagMeta } from '../api/client';
import type { TagMeta } from '../types';

let cachedTags: TagMeta[] | null = null;
let inflight: Promise<TagMeta[]> | null = null;

/**
 * Module-scoped cache so the metadata fetch happens at most once per
 * page load. Components don't need to re-trigger it; the upload form,
 * help text, and any other consumer share the same list.
 */
async function ensureTags(): Promise<TagMeta[]> {
  if (cachedTags) return cachedTags;
  if (!inflight) {
    inflight = listTagMeta()
      .then((tags) => {
        cachedTags = tags;
        return tags;
      })
      .finally(() => {
        inflight = null;
      });
  }
  return inflight;
}

export function useMandatoryTags(): {
  tags: TagMeta[];
  mandatory: string[];
  loaded: boolean;
} {
  const [tags, setTags] = useState<TagMeta[]>(cachedTags ?? []);
  const [loaded, setLoaded] = useState<boolean>(cachedTags != null);

  useEffect(() => {
    if (cachedTags) return;
    let cancelled = false;
    ensureTags()
      .then((t) => {
        if (cancelled) return;
        setTags(t);
        setLoaded(true);
      })
      .catch(() => {
        // /lc-meta failure is non-fatal — validation falls back to a
        // hard-coded canonical set.
        if (!cancelled) setLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const mandatory = tags.filter((t) => t.mandatory).map((t) => t.tag);
  return { tags, mandatory, loaded };
}
