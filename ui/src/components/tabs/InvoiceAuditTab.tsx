import { useEffect, useMemo, useState } from 'react';
import { listExtracts } from '../../api/client';
import { InvoicePanel, fmtSource } from '../invoice/InvoicePanel';
import { InvoiceViewer } from '../invoice/InvoiceViewer';
import { useCheckSession } from '../../hooks/useCheckSession';
import { usePipelineConfig } from '../../hooks/usePipelineConfig';
import type { ExtractAttempt, InvoiceDocument } from '../../types';

interface Props {
  sessionId: string;
  invoice: InvoiceDocument | undefined;
}

type SourceState = { status: string; durationMs?: number; error?: string };

/**
 * Stage 1b inspection. PDF render + extractor inspector. Per-source live
 * status is driven by SSE running events; full document detail comes from
 * the persistent {@code /extracts} endpoint, re-fetched on every per-source
 * completion (so docs/markdown appear inline as soon as a source persists).
 */
export function InvoiceAuditTab({ sessionId, invoice }: Props) {
  const [hover, setHover] = useState<string | null>(null);
  const [persisted, setPersisted] = useState<ExtractAttempt[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const pipeline = usePipelineConfig();
  const session = useCheckSession(sessionId);

  // Per-source live state — accumulated across the invoice_extract stage's
  // status events (the reducer in useCheckSession only stores the LATEST
  // data field, so we'd lose earlier-event sources without local merge).
  // The orchestrator emits two flavours of running events:
  //   1. A "kick-off" event with {sources: [..all..], done: 0, total: N} —
  //      seeds every parallel source as RUNNING.
  //   2. One per parallel-source completion: {source, status: 'ok'|'failed',
  //      durationMs} — flips that one to SUCCESS / FAILED.
  const [liveSources, setLiveSources] = useState<Record<string, SourceState>>({});
  const stageMessage = session.stages.invoice_extract?.message ?? '';
  const stageData = session.stages.invoice_extract?.data;

  useEffect(() => {
    const data = stageData as
      | { sources?: string[]; source?: string; status?: string; durationMs?: number }
      | undefined;
    if (!data) return;
    setLiveSources((prev) => {
      const next = { ...prev };
      if (Array.isArray(data.sources)) {
        for (const s of data.sources) {
          if (!next[s]) next[s] = { status: 'RUNNING' };
        }
      }
      if (data.source && data.status) {
        const wire = data.status === 'ok' ? 'SUCCESS' : 'FAILED';
        next[data.source] = { status: wire, durationMs: data.durationMs };
      }
      return next;
    });
  }, [stageMessage, stageData]);

  // Reset per-source state whenever the session changes — different runs
  // shouldn't bleed into each other's live cards.
  useEffect(() => {
    setLiveSources({});
  }, [sessionId]);

  // Re-fetch /extracts on every per-source completion (each running event
  // updates stageMessage). /extracts returns the persisted document + raw
  // markdown so the user can inspect each result inline as soon as it lands.
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
  }, [sessionId, invoice, stageMessage]);

  const attempts = useMemo<ExtractAttempt[]>(() => {
    const order = pipeline.extractorSources.length > 0
      ? pipeline.extractorSources
      : Array.from(new Set([
          ...persisted.map((p) => p.source),
          ...Object.keys(liveSources),
        ]));

    const persistedMap = new Map(persisted.map((p) => [p.source, p]));
    const result: ExtractAttempt[] = [];
    for (const source of order) {
      const row = persistedMap.get(source);
      const live = liveSources[source];
      if (row) {
        // Persisted row wins — carries the document + markdown.
        result.push(row);
      } else if (live) {
        // SSE-only status: bumps PENDING → RUNNING → SUCCESS|FAILED before
        // /extracts has caught up.
        result.push({
          source,
          status: live.status,
          is_selected: false,
          document: null,
          duration_ms: live.durationMs ?? 0,
          started_at: null,
          error: live.error ?? null,
        });
      } else {
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
    return result;
  }, [pipeline.extractorSources, persisted, liveSources]);

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

  return (
    <div className="px-6 py-5">
      <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.35fr)] gap-5 max-w-[1600px] mx-auto">
        <section className="min-w-0">
          <SectionHead title="Original PDF" sub="zoom and pan to inspect" />
          <InvoiceViewer sessionId={sessionId} highlight={hover} />
        </section>
        <section className="min-w-0">
          <SectionHead
            title="Extracted output"
            sub={
              currentDoc
                ? `${expandPipelineLabel(currentSource)} · ${(currentDoc.extractor_confidence * 100).toFixed(0)}% conf`
                : extractorSummary(attempts)
            }
          />
          <InvoicePanel
            attempts={attempts}
            activeSource={currentSource}
            onActiveSource={setActive}
            invoice={currentDoc}
            onFieldHover={setHover}
          />
        </section>
      </div>
    </div>
  );
}

/**
 * Expand the bare source name into a "pipeline kind" label for the
 * "Extracted output" section header. Vision LLMs (`*_local`, `*_cloud`)
 * keep the standard {@link fmtSource} rendering. Docling and MinerU are
 * tagged `(+llm)` because their sidecars run an LLM-first, regex-fallback
 * field-extraction pipeline (see `extractors/{docling,mineru}/app/main.py`
 * — `llm_extract_fields()` is tried first, with `extract_fields()` as the
 * fallback when LLM is unconfigured or fails).
 *
 * The text LLM the sidecars hit is `LLM_BASE_URL` / `LLM_MODEL` from .env
 * — currently the cloud DashScope model, not the local Ollama vision LLM.
 */
function expandPipelineLabel(source: string): string {
  if (source === 'docling' || source === 'mineru') {
    return `${source} (+ qwen3:4b)`;
  }
  return fmtSource(source);
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
