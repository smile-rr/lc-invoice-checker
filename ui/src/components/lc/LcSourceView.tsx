import { useEffect, useMemo, useRef, useState } from 'react';
import { getLcRaw } from '../../api/client';

interface Props {
  sessionId: string;
  selectedTag: string | null;
  onSelect: (tag: string | null) => void;
}

/**
 * Block-level rendering of MT700.
 *
 * Each {@code :XX:} field becomes its own clickable block spanning from the
 * tag to the next tag — so the *value* is part of the highlight target, not
 * just the tag chip. Click toggles selection: click again to release, click
 * another to switch. Selection is bidirectional with {@link LcSidebar}; the
 * parent owns the {@code selectedTag} state.
 */
export function LcSourceView({ sessionId, selectedTag, onSelect }: Props) {
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
      className="bg-paper rounded-card border border-line overflow-auto max-h-[72vh]"
    >
      {blocks.map((b, i) =>
        b.tag === null ? (
          <pre
            key={`raw-${i}`}
            className="font-mono text-[11px] text-muted whitespace-pre-wrap px-4 py-1 leading-relaxed"
          >
            {b.text}
          </pre>
        ) : (
          <FieldBlock
            key={`tag-${i}-${b.tag}`}
            block={b}
            selected={selectedTag === b.tag}
            onClick={() => onSelect(selectedTag === b.tag ? null : b.tag)}
          />
        ),
      )}
    </div>
  );
}

interface Block {
  tag: string | null;
  text: string;
}

function FieldBlock({
  block,
  selected,
  onClick,
}: {
  block: Block;
  selected: boolean;
  onClick: () => void;
}) {
  const tag = block.tag!;
  const tagStr = `:${tag}:`;
  // The block text starts with the literal :XX: prefix; split it off so we can
  // style tag and value distinctly.
  const value = block.text.startsWith(tagStr)
    ? block.text.slice(tagStr.length)
    : block.text;
  return (
    <div
      data-tag={tag}
      onClick={onClick}
      className={[
        'relative cursor-pointer transition-colors px-4 py-1.5',
        'border-b border-line/50 last:border-b-0',
        selected ? 'bg-teal-1/10' : 'hover:bg-slate2',
      ].join(' ')}
    >
      {selected && (
        <span className="absolute left-0 top-0 bottom-0 w-[3px] bg-teal-1" />
      )}
      <pre className="font-mono text-[11px] whitespace-pre-wrap leading-relaxed m-0">
        <span
          className={[
            'inline-block px-1.5 py-0.5 rounded mr-2 font-semibold',
            selected ? 'bg-teal-1 text-white' : 'bg-slate2 text-teal-1',
          ].join(' ')}
        >
          {tagStr}
        </span>
        <span className={selected ? 'text-navy-1 font-semibold' : 'text-navy-1'}>
          {value}
        </span>
      </pre>
    </div>
  );
}

function parseBlocks(text: string): Block[] {
  const re = /:(\d{2}[A-Z]?):/g;
  const matches: Array<{ tag: string; index: number }> = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    matches.push({ tag: m[1], index: m.index });
  }
  if (matches.length === 0) return text ? [{ tag: null, text }] : [];

  const blocks: Block[] = [];
  if (matches[0].index > 0) {
    blocks.push({ tag: null, text: text.slice(0, matches[0].index) });
  }
  for (let i = 0; i < matches.length; i++) {
    const start = matches[i].index;
    const end = i + 1 < matches.length ? matches[i + 1].index : text.length;
    blocks.push({ tag: matches[i].tag, text: text.slice(start, end) });
  }
  return blocks;
}
