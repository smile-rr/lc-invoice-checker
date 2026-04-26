import { useCallback, useEffect, useState } from 'react';
import { LcSidebar } from '../lc/LcSidebar';
import { LcSourceView, type SortMode } from '../lc/LcSourceView';
import type { LcDocument } from '../../types';

interface Props {
  sessionId: string;
  lc: LcDocument | undefined;
}

type Side = 'source' | 'parsed';

/**
 * Parse-vs-source audit for the LC. Both panes share a single pinned-tag
 * selection AND a single sort mode. Keyboard:
 *   ↑ / ↓ — step through the rows of whichever pane was last clicked
 *           (the "leading" pane gets a teal accent so it's obvious which
 *           sequence the keys are following).
 *   Esc   — clear pin and the leading-pane indicator.
 *
 * Each pane reports its own rendered tag order back via `onOrderChange`,
 * so navigation respects each pane's natural sequence (e.g. declared mode
 * = MT700 source order on the left, registry order on the right).
 */
export function LcAuditTab({ sessionId, lc }: Props) {
  const [selectedTag, setSelectedTag] = useState<string | null>(null);
  // Default to tag-ascending so the MT700 source and the parsed fields line
  // up row-for-row out of the box — same tag at the same vertical position
  // on both sides. Operators can flip to "Declared order" via the toggle if
  // they want to see the raw MT700 sequence.
  const [sortMode, setSortMode] = useState<SortMode>('tag-asc');
  const [activeSide, setActiveSide] = useState<Side | null>(null);
  const [sourceOrder, setSourceOrder] = useState<string[]>([]);
  const [parsedOrder, setParsedOrder] = useState<string[]>([]);

  const onSourceSelect = useCallback((t: string | null) => {
    setSelectedTag(t);
    setActiveSide(t === null ? null : 'source');
  }, []);
  const onParsedSelect = useCallback((t: string | null) => {
    setSelectedTag(t);
    setActiveSide(t === null ? null : 'parsed');
  }, []);

  // Arrow-key navigation, anchored on the side the user last clicked.
  useEffect(() => {
    function handler(e: KeyboardEvent) {
      const target = e.target as HTMLElement | null;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
        return;
      }
      if (e.key === 'Escape') {
        if (selectedTag !== null || activeSide !== null) {
          e.preventDefault();
          setSelectedTag(null);
          setActiveSide(null);
        }
        return;
      }
      if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;

      const list = activeSide === 'source' ? sourceOrder : activeSide === 'parsed' ? parsedOrder : [];
      if (list.length === 0) return;

      e.preventDefault();
      const idx = selectedTag ? list.indexOf(selectedTag) : -1;
      let next: number;
      if (e.key === 'ArrowDown') {
        next = idx < 0 ? 0 : Math.min(list.length - 1, idx + 1);
      } else {
        next = idx < 0 ? list.length - 1 : Math.max(0, idx - 1);
      }
      setSelectedTag(list[next]);
    }
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [activeSide, sourceOrder, parsedOrder, selectedTag]);

  if (!lc) {
    return (
      <EmptyState
        heading="Parsing MT700…"
        body="The LC source will appear here once Stage 1a finishes."
      />
    );
  }

  return (
    <div className="px-6 py-5">
      <div className="max-w-[1280px] mx-auto">
        <div className="flex items-center justify-between mb-3">
          <KeyboardHint activeSide={activeSide} />
          <SortToggle mode={sortMode} onChange={setSortMode} />
        </div>
        <div className="grid grid-cols-2 gap-5">
          <section className="min-w-0">
            <SectionHead
              title="MT700 source"
              sub={
                activeSide === 'source'
                  ? 'leading · ↑/↓ steps through this pane'
                  : selectedTag
                    ? `pinned · :${selectedTag}:`
                    : 'click a field to pin it'
              }
              active={activeSide === 'source'}
            />
            <LcSourceView
              sessionId={sessionId}
              selectedTag={selectedTag}
              onSelect={onSourceSelect}
              sortMode={sortMode}
              onOrderChange={setSourceOrder}
              active={activeSide === 'source'}
            />
          </section>
          <section className="min-w-0">
            <SectionHead
              title="Parsed fields"
              sub={
                activeSide === 'parsed'
                  ? 'leading · ↑/↓ steps through this pane'
                  : 'click any row · Esc clears'
              }
              active={activeSide === 'parsed'}
            />
            <LcSidebar
              lc={lc}
              selectedTag={selectedTag}
              onSelect={onParsedSelect}
              sortMode={sortMode}
              onOrderChange={setParsedOrder}
              active={activeSide === 'parsed'}
            />
          </section>
        </div>
      </div>
    </div>
  );
}

function SortToggle({ mode, onChange }: { mode: SortMode; onChange: (m: SortMode) => void }) {
  return (
    <div className="inline-flex border border-line rounded-input overflow-hidden text-[11px] font-mono">
      <button
        type="button"
        onClick={() => onChange('declared')}
        className={[
          'px-3 py-1 transition-colors',
          mode === 'declared' ? 'bg-teal-1 text-white' : 'bg-paper text-muted hover:text-navy-1',
        ].join(' ')}
      >
        Declared order
      </button>
      <button
        type="button"
        onClick={() => onChange('tag-asc')}
        className={[
          'px-3 py-1 transition-colors border-l border-line',
          mode === 'tag-asc' ? 'bg-teal-1 text-white' : 'bg-paper text-muted hover:text-navy-1',
        ].join(' ')}
      >
        Sort by tag
      </button>
    </div>
  );
}

function KeyboardHint({ activeSide }: { activeSide: Side | null }) {
  const live = activeSide !== null;
  return (
    <div
      className={[
        'text-[10px] font-mono text-muted/70 transition-opacity',
        live ? 'opacity-100' : 'opacity-50',
      ].join(' ')}
    >
      {live ? (
        <>
          following <span className="text-teal-1 font-semibold">{activeSide}</span> ·{' '}
        </>
      ) : (
        'click any row to start · '
      )}
      <kbd className="px-1.5 py-0.5 rounded border border-line bg-paper">↑</kbd>{' '}
      <kbd className="px-1.5 py-0.5 rounded border border-line bg-paper">↓</kbd>{' '}
      step ·{' '}
      <kbd className="px-1.5 py-0.5 rounded border border-line bg-paper">Esc</kbd>{' '}
      clear
    </div>
  );
}

function SectionHead({
  title,
  sub,
  active = false,
}: {
  title: string;
  sub?: string;
  active?: boolean;
}) {
  return (
    <div className="mb-2 flex items-baseline gap-2">
      <div className={['font-serif text-sm', active ? 'text-teal-1 font-semibold' : 'text-navy-1'].join(' ')}>
        {title}
      </div>
      {active ? (
        <span className="inline-block w-1.5 h-1.5 rounded-full bg-teal-1 animate-pulse" />
      ) : null}
      {sub ? <div className="text-[11px] text-muted ml-auto">{sub}</div> : null}
    </div>
  );
}

function EmptyState({ heading, body }: { heading: string; body: string }) {
  return (
    <div className="px-6 py-10 text-center text-muted">
      <div className="font-serif text-lg text-navy-1 mb-1">{heading}</div>
      <div className="text-sm">{body}</div>
    </div>
  );
}
