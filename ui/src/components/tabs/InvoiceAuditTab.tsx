import { useEffect, useMemo, useState } from 'react';
import { listExtracts } from '../../api/client';
import { InvoicePanel } from '../invoice/InvoicePanel';
import { InvoiceViewer } from '../invoice/InvoiceViewer';
import { useCheckSession } from '../../hooks/useCheckSession';
import type { ExtractAttempt, InvoiceDocument } from '../../types';

interface Props {
  sessionId: string;
  invoice: InvoiceDocument | undefined;
}

/**
 * Stage 1b inspection. The PDF render and the extractor inspector live
 * side-by-side; the inspector itself owns the extractor tabs and the inner
 * view tabs (Fields vs. Markdown). Picking a non-canonical attempt surfaces
 * a banner inside the inspector.
 *
 * Source-of-truth precedence for the cards row:
 *   1. SSE-driven `state.extractorStatus` — live RUNNING / SUCCESS / FAILED.
 *      Each source appears as soon as its `extract.source.started` lands.
 *   2. `/extracts` (one-shot fetch) — fills in missing details (raw_markdown,
 *      raw_text, full document) once the stage has persisted.
 * Disabled sources never appear in either source, so they don't render.
 */
export function InvoiceAuditTab({ sessionId, invoice }: Props) {
  const [hover, setHover] = useState<string | null>(null);
  const [persisted, setPersisted] = useState<ExtractAttempt[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const live = useCheckSession(sessionId);

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    listExtracts(sessionId)
      .then((rows) => {
        if (cancelled) return;
        setPersisted(rows);
      })
      .catch(() => {
        // Empty list during streaming is fine — live state covers the gap.
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId, invoice]);

  // Merge live + persisted, preserving chain order. Persisted wins for
  // documents (it has full raw_markdown / raw_text); live wins for status
  // (it knows about RUNNING and the freshest completion result).
  const attempts = useMemo<ExtractAttempt[]>(() => {
    const liveMap = live.extractorStatus ?? {};
    const merged = new Map<string, ExtractAttempt>();
    // Persisted first — preserves backend-defined chain order.
    for (const row of persisted) merged.set(row.source, row);
    // Live overlays per source — promote RUNNING, fresh confidence/error,
    // but keep the persisted document if we have one.
    for (const [source, liveRow] of Object.entries(liveMap)) {
      const prior = merged.get(source);
      merged.set(source, {
        ...liveRow,
        document: liveRow.document ?? prior?.document ?? null,
        is_selected: prior?.is_selected ?? liveRow.is_selected,
      });
    }
    return Array.from(merged.values());
  }, [persisted, live.extractorStatus]);

  // Auto-pick an active source: prefer the orchestrator's selected one;
  // else first SUCCESS; else first attempt with any state at all.
  useEffect(() => {
    if (active) return;
    const cand =
      attempts.find((a) => a.is_selected) ??
      attempts.find((a) => a.status === 'SUCCESS') ??
      attempts[0];
    if (cand) setActive(cand.source);
  }, [attempts, active]);

  const activeAttempt = useMemo(
    () => attempts.find((a) => a.source === active) ?? null,
    [attempts, active],
  );
  const currentDoc: InvoiceDocument | null = activeAttempt?.document ?? invoice ?? null;
  const currentSource = activeAttempt?.source ?? invoice?.extractor_used ?? '';
  const selectedSource = attempts.find((a) => a.is_selected)?.source ?? null;

  // No source has even started — wait for the very first SSE event.
  if (attempts.length === 0 && !currentDoc) {
    return (
      <div className="mx-8 my-10 bg-paper rounded-card border border-dashed border-line py-12 text-center">
        <div className="font-serif text-xl text-navy-1 mb-1 animate-pulse">Extracting invoice…</div>
        <div className="text-sm text-muted">
          The PDF render and extractor fields will appear once Stage 1b begins.
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
          <SectionHead
            title="Extracted output"
            sub={
              currentDoc
                ? `${currentSource} · ${(currentDoc.extractor_confidence * 100).toFixed(0)}% conf`
                : `${attempts.length} source${attempts.length === 1 ? '' : 's'} running`
            }
          />
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
