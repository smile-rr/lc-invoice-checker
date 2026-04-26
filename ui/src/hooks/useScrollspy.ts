import { useEffect, useState } from 'react';

interface Options {
  /** Margin around the root for the IntersectionObserver. Use a negative top
   *  margin (e.g. {@code "-30% 0px -60% 0px"}) to bias the active section
   *  toward the upper portion of the viewport — feels right for a TOC that
   *  highlights "what you're reading", not "what's at the very top of the page". */
  rootMargin?: string;
  /** Optional scroll container. Defaults to the viewport. */
  root?: HTMLElement | null;
}

/**
 * Returns the id of the section currently considered "active" while the user
 * scrolls. Uses IntersectionObserver — the section whose top crossed the
 * configured rootMargin most recently wins.
 *
 * Caller passes a stable array of element ids (e.g. {@code ["phase-parties",
 * "phase-money", ...]}). The hook attaches one observer per id and tracks
 * which is currently visible. Returns null until at least one section has
 * become visible.
 */
export function useScrollspy(sectionIds: string[], opts: Options = {}): string | null {
  const [activeId, setActiveId] = useState<string | null>(null);
  const rootMargin = opts.rootMargin ?? '-30% 0px -60% 0px';
  const idsKey = sectionIds.join('|');

  useEffect(() => {
    if (sectionIds.length === 0) return;
    const elements = sectionIds
      .map((id) => document.getElementById(id))
      .filter((el): el is HTMLElement => el !== null);
    if (elements.length === 0) return;

    // Track all currently-intersecting ids; pick the topmost in document order.
    const visible = new Set<string>();
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) visible.add(entry.target.id);
          else visible.delete(entry.target.id);
        }
        // Pick the first id in original sectionIds order that is still visible.
        const next = sectionIds.find((id) => visible.has(id)) ?? null;
        if (next !== null) setActiveId(next);
      },
      { rootMargin, root: opts.root ?? null, threshold: 0 },
    );

    for (const el of elements) observer.observe(el);
    return () => observer.disconnect();
  }, [idsKey, rootMargin, opts.root, sectionIds]);

  return activeId;
}
