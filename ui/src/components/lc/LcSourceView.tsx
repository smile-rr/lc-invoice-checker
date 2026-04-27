import { useEffect, useMemo, useRef, useState } from 'react';
import { getLcRaw } from '../../api/client';
import { FieldBlockRow } from './FieldBlockRow';

export type SortMode = 'declared' | 'tag-asc';

interface Props {
  sessionId: string;
  selectedTag: string | null;
  onSelect: (tag: string | null) => void;
  sortMode?: SortMode;
  /** Called whenever the rendered row order changes — feeds keyboard nav. */
  onOrderChange?: (tags: string[]) => void;
  /** When true, this pane "owns" arrow-key traversal — render an active accent. */
  active?: boolean;
}

/**
 * Block-level rendering of MT700.
 *
 * Source rows include:
 *   – SWIFT envelope blocks: {1:...}, {2:...}, {3:{108:...}} as `block1`,
 *     `block2`, `block3:108` so they line up with the parsed pane and
 *     participate in the shared sort/selection.
 *   – Block-4 body tags `:XX:` as plain `XX`.
 *
 * Tag-asc mode sorts envelope blocks before body tags using the same key
 * scheme as {@code ParsedRowProjector} on the backend, so row N of source
 * == row N of parsed.
 */
export function LcSourceView({
  sessionId,
  selectedTag,
  onSelect,
  sortMode = 'declared',
  onOrderChange,
  active = false,
}: Props) {
  const [text, setText] = useState<string>('');
  const [err, setErr] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let cancelled = false;
    getLcRaw(sessionId)
      .then((t) => !cancelled && setText(t))
      .catch((e) => !cancelled && setErr((e as Error).message));
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  const blocks = useMemo(() => parseBlocks(text), [text]);

  const ordered = useMemo(() => {
    if (sortMode !== 'tag-asc') return blocks;
    return [...blocks].sort((a, b) => sortKey(a.tag).localeCompare(sortKey(b.tag), 'en', { numeric: true }));
  }, [blocks, sortMode]);

  // Push current rendered order up so the parent can drive arrow-key traversal.
  useEffect(() => {
    onOrderChange?.(ordered.map((b) => b.tag));
    // Intentionally DO NOT include onOrderChange in deps — its identity changes
    // every parent render and would cause a notify loop. The parent's setter
    // is stable.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ordered]);

  // Scroll the pinned block into view when selection changes.
  useEffect(() => {
    if (!selectedTag || !containerRef.current) return;
    const el = containerRef.current.querySelector(`[data-tag="${selectedTag}"]`);
    el?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [selectedTag]);

  if (err) {
    return <div className="p-4 text-sm text-status-red">Failed to load MT700: {err}</div>;
  }

  return (
    <div
      ref={containerRef}
      className={[
        'bg-slate2 rounded-card overflow-auto max-h-[72vh] transition-colors',
        active ? 'ring-2 ring-teal-1 border border-teal-1' : 'border border-line',
      ].join(' ')}
    >
      {ordered.map((b) => (
        <FieldBlockRow
          key={b.tag}
          tag={b.tag}
          value={b.text}
          pinned={selectedTag === b.tag}
          tone="source"
          malformed={!b.tag.startsWith('block') && !isStrictTag(b.tag)}
          onClick={() => onSelect(selectedTag === b.tag ? null : b.tag)}
        />
      ))}
    </div>
  );
}

interface Block {
  tag: string;
  text: string;
}

/**
 * Mirror the backend's {@code ParsedRowProjector#buildSortKey}: envelope
 * blocks (block1/2/3:nnn) sort before body tags (1_<tag>). Malformed tags
 * (e.g. ":40Ad:") still get a key so they appear at their natural alphanumeric
 * position next to their valid neighbours instead of being shoved to the end.
 */
function sortKey(tag: string): string {
  if (tag.startsWith('block')) return '0_' + tag;
  return '1_' + tag;
}

/**
 * Decompose raw MT700 text into rows: one envelope block per `{N:...}` (with
 * Block 3's inner `{108:...}` etc broken out as `block3:108`), then one
 * body-tag block per `:XX:` in Block 4.
 */
function parseBlocks(text: string): Block[] {
  if (!text) return [];
  const out: Block[] = [];

  // ── Envelope blocks 1 / 2 / 3 ──────────────────────────────────────────
  const envRe = /\{(\d):([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}/g;
  let m: RegExpExecArray | null;
  while ((m = envRe.exec(text)) !== null) {
    const num = m[1];
    if (num === '4') continue; // body — handled below
    const inner = m[2];
    if (num === '3') {
      // Block 3 nests user-header tags: {108:LC2024REF001}{431:N}…
      const sub = /\{(\d+):([^{}]*)\}/g;
      let s: RegExpExecArray | null;
      while ((s = sub.exec(inner)) !== null) {
        out.push({ tag: `block3:${s[1]}`, text: s[2] });
      }
    } else {
      out.push({ tag: `block${num}`, text: inner });
    }
  }

  // ── Block 4 body tags ──────────────────────────────────────────────────
  // Slice out just the body so the regex doesn't pick up false matches
  // inside envelope content.
  const bodyMatch = text.match(/\{4:([\s\S]*?)-?\}\s*$/);
  const body = bodyMatch ? bodyMatch[1] : text;
  // Line-anchored tag detector — accepts ANY ":xxx:" at line start (up to 5
  // chars between colons) so malformed tags like ":40Ad:" become their own
  // row instead of being silently glued into the previous tag's value.
  // The strict-format check (and a `malformed` flag for the row UI) lives in
  // the consumer.
  const tagRe = /^:([^:\s]{1,5}):/gm;
  const matches: Array<{ tag: string; index: number }> = [];
  let t: RegExpExecArray | null;
  while ((t = tagRe.exec(body)) !== null) {
    matches.push({ tag: t[1], index: t.index });
  }
  for (let i = 0; i < matches.length; i++) {
    const start = matches[i].index;
    const end = i + 1 < matches.length ? matches[i + 1].index : body.length;
    const slice = body.slice(start, end);
    const tagStr = `:${matches[i].tag}:`;
    const value = slice.startsWith(tagStr) ? slice.slice(tagStr.length) : slice;
    out.push({ tag: matches[i].tag, text: value.replace(/^\s*\n?/, '').trimEnd() });
  }

  return out;
}

/** True for tags that fit strict SWIFT format (2 digits + optional uppercase letter). */
export function isStrictTag(tag: string): boolean {
  return /^\d{2}[A-Z]?$/.test(tag);
}
