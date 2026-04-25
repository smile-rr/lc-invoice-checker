import { useEffect, useMemo, useState } from 'react';
import { listExtracts } from '../../api/client';
import { InvoicePanel } from '../invoice/InvoicePanel';
import { InvoiceViewer } from '../invoice/InvoiceViewer';
import { useCheckSession } from '../../hooks/useCheckSession';
import { usePipelineConfig } from '../../hooks/usePipelineConfig';
import type { ExtractAttempt, InvoiceDocument } from '../../types';

interface Props {
  sessionId: string;
  invoice: InvoiceDocument | undefined;
}

/**
 * Stage 1b inspection. PDF render and extractor inspector live side-by-side.
 *
 * Source-of-truth precedence for the cards row (most specific wins):
 *   1. SSE-driven `state.extractorStatus` — RUNNING / SUCCESS / FAILED.
 *   2. `/extracts` (one-shot fetch) — full document + raw_markdown after persist.
 *   3. `/api/v1/pipeline.extractor_sources` — pre-populated PENDING placeholders
 *      so the user sees the configured shape (3 cards / 4 cards) before any
 *      extractor has even started.
 *
 * Disabled sources never appear in any layer, so they don't render.
 *
 * Critically: the layout is rendered as soon as the session exists. The PDF
 * loads independently from /api/v1/lc-check/{sid}/invoice; the extractor pane
 * starts with PENDING cards and updates live. Nothing blocks the whole page.
 */
export function InvoiceAuditTab({ sessionId, invoice }: Props) {
  const [hover, setHover] = useState<string | null>(null);
  const [persisted, setPersisted] = useState<ExtractAttempt[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const live = useCheckSession(sessionId);
  const pipeline = usePipelineConfig();

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    listExtracts(sessionId)
      .then((rows) => {
        if (cancelled) return;
        setPersisted(rows);
      })
      .catch(() => {
        // Empty list during streaming is fine — live + config cover the gap.
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId, invoice]);

  // Merge config (PENDING placeholders) + persisted + live, in chain order.
  // Live wins on status / confidence / error; persisted wins on document.
  const attempts = useMemo<ExtractAttempt[]>(() => {
    const order = pipeline.extractorSources.length > 0
      ? pipeline.extractorSources
      : Array.from(new Set([
          ...persisted.map((p) => p.source),
          ...Object.keys(live.extractorStatus ?? {}),
        ]));

    const liveMap = live.extractorStatus ?? {};
    const persistedMap = new Map(persisted.map((p) => [p.source, p]));

    const result: ExtractAttempt[] = [];
    for (const source of order) {
      const liveRow = liveMap[source];
      const persistedRow = persistedMap.get(source);

      if (liveRow) {
        result.push({
          ...liveRow,
          document: liveRow.document ?? persistedRow?.document ?? null,
          is_selected: persistedRow?.is_selected ?? liveRow.is_selected,
        });
      } else if (persistedRow) {
        result.push(persistedRow);
      } else {
        // No live state, no persisted row — pure PENDING placeholder.
        result.push({
          source,
          status: 'PENDING',
          is_selected: false,
          document: null,
          duration_ms: 0,
          started_at: null,
          error: null,
        });
      }
    }
    // Append any sources we got from live/persisted that aren't in the
    // configured order (shouldn't happen, but defensive).
    for (const source of Object.keys(liveMap)) {
      if (!order.includes(source)) {
        const liveRow = liveMap[source];
        result.push(liveRow);
      }
    }
    return result;
  }, [pipeline.extractorSources, persisted, live.extractorStatus]);

  // Auto-pick an active source: prefer the orchestrator's selected one;
  // else first SUCCESS; else first non-PENDING; else first.
  useEffect(() => {
    if (active) return;
    const cand =
      attempts.find((a) => a.is_selected) ??
      attempts.find((a) => a.status === 'SUCCESS') ??
      attempts.find((a) => a.status !== 'PENDING') ??
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
                : extractorSummary(attempts)
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

function extractorSummary(attempts: ExtractAttempt[]): string {
  if (attempts.length === 0) return 'awaiting pipeline configuration…';
  const counts = attempts.reduce(
    (acc, a) => {
      const k = a.status as keyof typeof acc;
      if (k in acc) acc[k]++;
      return acc;
    },
    { PENDING: 0, RUNNING: 0, SUCCESS: 0, FAILED: 0 } as Record<string, number>,
  );
  const parts: string[] = [];
  if (counts.RUNNING) parts.push(`${counts.RUNNING} running`);
  if (counts.PENDING) parts.push(`${counts.PENDING} queued`);
  if (counts.SUCCESS) parts.push(`${counts.SUCCESS} done`);
  if (counts.FAILED) parts.push(`${counts.FAILED} failed`);
  return parts.join(' · ');
}

function SectionHead({ title, sub }: { title: string; sub: string }) {
  return (
    <div className="flex items-baseline justify-between mb-2">
      <h3 className="font-serif text-lg text-navy-1">{title}</h3>
      <div className="text-xs text-muted font-mono">{sub}</div>
    </div>
  );
}
