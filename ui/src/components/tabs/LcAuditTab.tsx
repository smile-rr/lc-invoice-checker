import { useState } from 'react';
import { LcSidebar } from '../lc/LcSidebar';
import { LcSourceView } from '../lc/LcSourceView';
import type { LcDocument } from '../../types';

interface Props {
  sessionId: string;
  lc: LcDocument | undefined;
}

/**
 * Parse-vs-source audit for the LC. Both panes share a single pinned-tag
 * selection: click any field on either side and the corresponding row on the
 * other side highlights and scrolls into view. The selection persists until
 * you click another field or click the same field again to release.
 *
 * Outer container caps at 1280px so on wide monitors the eye doesn't have to
 * sweep across half the screen to correlate a tag to its parsed value.
 * Source pane gets 3fr, parsed pane 2fr — MT700 mono lines need more room
 * than the structured field rows.
 */
export function LcAuditTab({ sessionId, lc }: Props) {
  const [selectedTag, setSelectedTag] = useState<string | null>(null);

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
      <div className="max-w-[1280px] mx-auto grid grid-cols-[3fr_2fr] gap-5">
        <section className="min-w-0">
          <SectionHead
            title="MT700 source"
            sub={selectedTag ? `pinned · :${selectedTag}:` : 'click a field to pin it'}
          />
          <LcSourceView
            sessionId={sessionId}
            selectedTag={selectedTag}
            onSelect={setSelectedTag}
          />
        </section>
        <section className="min-w-0">
          <SectionHead title="Parsed fields" sub="clicking either side keeps the field pinned" />
          <LcSidebar lc={lc} selectedTag={selectedTag} onSelect={setSelectedTag} />
        </section>
      </div>
    </div>
  );
}

function SectionHead({ title, sub }: { title: string; sub: string }) {
  return (
    <div className="flex items-baseline justify-between mb-2">
      <h3 className="font-serif text-lg text-navy-1">{title}</h3>
      <div className="text-xs text-muted font-mono">{sub}</div>
    </div>
  );
}

function EmptyState({ heading, body }: { heading: string; body: string }) {
  return (
    <div className="mx-8 my-10 bg-paper rounded-card border border-dashed border-line py-12 text-center">
      <div className="font-serif text-xl text-navy-1 mb-1 animate-pulse">{heading}</div>
      <div className="text-sm text-muted">{body}</div>
    </div>
  );
}
