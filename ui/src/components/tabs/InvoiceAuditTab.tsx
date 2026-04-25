import { useEffect, useMemo, useState } from 'react';
import { listExtracts } from '../../api/client';
import { InvoicePanel } from '../invoice/InvoicePanel';
import { InvoiceViewer } from '../invoice/InvoiceViewer';
import type { ExtractAttempt, InvoiceDocument } from '../../types';

interface Props {
  sessionId: string;
  invoice: InvoiceDocument | undefined;
}

/**
 * Stage 1b inspection. The PDF render and the extractor inspector live
 * side-by-side; the inspector itself owns the extractor tabs (which attempt
 * to view) and the inner view tabs (Fields vs. Markdown). Picking a
 * non-canonical attempt surfaces a banner inside the inspector.
 *
 * Data sourcing: when the session is in flight, the live SSE stream gives us
 * the selected attempt via {@link Props#invoice}. Once the stage row is
 * persisted, we hit {@code /extracts} for the full list. Until that returns,
 * we synthesize a single tab from the streamed prop so the panel always
 * renders something.
 */
export function InvoiceAuditTab({ sessionId, invoice }: Props) {
  const [hover, setHover] = useState<string | null>(null);
  const [attempts, setAttempts] = useState<ExtractAttempt[]>([]);
  const [active, setActive] = useState<string | null>(null);

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    listExtracts(sessionId)
      .then((rows) => {
        if (cancelled) return;
        setAttempts(rows);
        if (rows.length > 0) {
          const selected =
            rows.find((r) => r.is_selected) ??
            rows.find((r) => r.status === 'SUCCESS') ??
            rows[0];
          setActive((cur) => cur ?? selected.source);
        }
      })
      .catch(() => {
        // Empty list during streaming is fine — fall back to live invoice prop.
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId, invoice]);

  // The InvoiceDocument we currently render. Prefer the active attempt; fall
  // back to the live SSE-streamed selected doc until /extracts has loaded.
  const activeAttempt = useMemo(
    () => attempts.find((a) => a.source === active && a.document) ?? null,
    [attempts, active],
  );
  const currentDoc: InvoiceDocument | null = activeAttempt?.document ?? invoice ?? null;
  const currentSource = activeAttempt?.source ?? invoice?.extractor_used ?? '';
  const selectedSource = attempts.find((a) => a.is_selected)?.source ?? null;

  if (!currentDoc) {
    return (
      <div className="mx-8 my-10 bg-paper rounded-card border border-dashed border-line py-12 text-center">
        <div className="font-serif text-xl text-navy-1 mb-1 animate-pulse">Extracting invoice…</div>
        <div className="text-sm text-muted">
          The PDF render and extractor fields will appear once Stage 1b finishes.
        </div>
      </div>
    );
  }

  return (
    <div className="px-6 py-5">
      <div className="grid grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)] gap-5 max-w-[1400px] mx-auto">
        <section className="min-w-0">
          <SectionHead title="Original PDF" sub="zoom and pan to inspect" />
          <InvoiceViewer sessionId={sessionId} highlight={hover} />
        </section>
        <section className="min-w-0">
          <SectionHead title="Extracted output" sub={`${currentSource} · ${(currentDoc.extractor_confidence * 100).toFixed(0)}% conf`} />
          <InvoicePanel
            attempts={attempts}
            activeSource={currentSource}
            onActiveSource={setActive}
            invoice={currentDoc}
            selectedSource={selectedSource}
            onFieldHover={setHover}
          />
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
