import { useEffect, useMemo, useRef } from 'react';
import { FieldBlockRow } from './FieldBlockRow';
import type { LcDocument, ParsedRow } from '../../types';
import type { SortMode } from './LcSourceView';

interface Props {
  lc: LcDocument;
  selectedTag: string | null;
  onSelect: (tag: string | null) => void;
  sortMode?: SortMode;
  /** Called whenever the rendered row order changes — feeds keyboard nav. */
  onOrderChange?: (tags: string[]) => void;
  /** When true, this pane "owns" arrow-key traversal — render an active accent. */
  active?: boolean;
}

const GROUP_LABEL: Record<string, string> = {
  header: 'Credit Header',
  amount: 'Amount',
  parties: 'Parties',
  shipment: 'Shipment',
  documents: 'Documents',
  conditions: 'Conditions',
  meta: 'Meta',
};

/**
 * Parsed-LC pane. Renders {@code lc.parsed_rows} verbatim — every row, every
 * subline, every label, every formatted value comes from the backend
 * (`ParsedRowProjector`). The frontend only handles selection + ordering.
 */
export function LcSidebar({
  lc,
  selectedTag,
  onSelect,
  sortMode = 'declared',
  onOrderChange,
  active = false,
}: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const rows = lc.parsed_rows ?? [];

  const grouped = useMemo(() => groupAndSort(rows, sortMode), [rows, sortMode]);

  // Push current rendered order up so the parent can drive arrow-key traversal.
  useEffect(() => {
    const flat: string[] = [];
    for (const g of grouped) for (const r of g.rows) flat.push(r.tag);
    onOrderChange?.(flat);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [grouped]);

  useEffect(() => {
    if (!selectedTag || !containerRef.current) return;
    const el = containerRef.current.querySelector(`[data-tag="${selectedTag}"]`);
    el?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [selectedTag]);

  if (rows.length === 0) {
    return (
      <aside className="bg-paper rounded-card border border-line p-4 text-sm text-muted">
        Waiting for parse output…
      </aside>
    );
  }

  return (
    <aside
      ref={containerRef}
      className={[
        'bg-paper rounded-card overflow-auto max-h-[72vh] transition-colors',
        active ? 'ring-2 ring-teal-1 border border-teal-1' : 'border border-line',
      ].join(' ')}
    >
      {grouped.map((g) => (
        <div key={g.title}>
          {g.title ? (
            <div className="uppercase tracking-[0.2em] text-[10px] text-muted px-3 py-1.5 bg-slate2/40 border-b border-line">
              {g.title}
            </div>
          ) : null}
          {g.rows.map((r) => (
            <FieldBlockRow
              key={r.tag}
              tag={r.tag}
              label={r.label}
              value={r.display_value}
              sublines={r.sublines}
              note={metaNote(r)}
              pinned={selectedTag === r.tag}
              tone="parsed"
              onClick={() => onSelect(selectedTag === r.tag ? null : r.tag)}
            />
          ))}
        </div>
      ))}
    </aside>
  );
}

function metaNote(r: ParsedRow): string | null {
  const count = r.meta?.parsed_count;
  const types = r.meta?.doc_types;
  if (typeof count !== 'number') return null;
  if (Array.isArray(types) && types.length > 0) {
    return `parsed: ${count} item${count === 1 ? '' : 's'} (${(types as string[]).join(', ')})`;
  }
  return `parsed: ${count} item${count === 1 ? '' : 's'}`;
}

function groupAndSort(
  rows: ParsedRow[],
  mode: SortMode,
): Array<{ title: string | null; rows: ParsedRow[] }> {
  // Build group map from first-encounter order so "declared" preserves source order
  // and "tag-asc" still shows meaningful sections with sorted contents.
  const order: string[] = [];
  const map: Record<string, ParsedRow[]> = {};
  for (const r of rows) {
    const g = r.group ?? 'other';
    if (!map[g]) {
      map[g] = [];
      order.push(g);
    }
    map[g].push(r);
  }

  return order.map((g) => {
    const groupRows = map[g];
    if (mode === 'tag-asc') {
      // Sort rows within group by tag number, keep group section header.
      groupRows.sort((a, b) =>
        a.sort_key.localeCompare(b.sort_key, 'en', { numeric: true }),
      );
    }
    // "declared": preserve source-declaration order (already in map order).
    return { title: GROUP_LABEL[g] ?? g, rows: groupRows };
  });
}
