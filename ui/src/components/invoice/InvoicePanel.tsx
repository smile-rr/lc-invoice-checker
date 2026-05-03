import { useState } from 'react';
import { MarkdownView } from './MarkdownView';
import { fmtMs } from '../../lib/formatDuration';
import type { ExtractAttempt, InvoiceDocument, ParsedRow } from '../../types';

interface Props {
  /** Every visible extractor attempt — drives the top tab strip. May include
   *  attempts in RUNNING / SUCCESS / FAILED state; disabled sources are absent. */
  attempts: ExtractAttempt[];
  /** Source name of the attempt currently being displayed. */
  activeSource: string;
  onActiveSource: (source: string) => void;
  /** The {@link InvoiceDocument} for {@link activeSource}, or null while no
   *  attempt has produced a document yet (all sources still RUNNING / FAILED). */
  invoice: InvoiceDocument | null;
  onFieldHover: (value: string | null) => void;
}


/**
 * Unified extractor inspector. Two stacked tab strips frame the body:
 *
 *   row 1 — extractor tabs    (Vision · Docling · Mineru …)
 *   row 2 — view tabs          (Fields / [Markdown] / JSON)
 *
 * Markdown tab is shown only for text-layout sources (docling, mineru).
 * Vision LLMs (_local / _cloud) get Fields + JSON only.
 */
export function InvoicePanel({
  attempts,
  activeSource,
  onActiveSource,
  invoice,
  onFieldHover,
}: Props) {
  const [view, setView] = useState<'fields' | 'markdown' | 'json'>('fields');
  const [mdMode, setMdMode] = useState<'rendered' | 'raw'>('rendered');

  // Build the extractor tabs. Even during live streaming (before /extracts
  // returns) we render at least one tab from the current invoice prop.
  const tabs: ExtractAttempt[] =
    attempts.length > 0
      ? attempts
      : invoice
        ? [
            {
              source: invoice.extractor_used,
              status: 'SUCCESS',
              is_selected: true,
              document: invoice,
              duration_ms: invoice.extraction_ms,
              started_at: null,
              error: null,
            },
          ]
        : [];

  const showMarkdown = !isVisionSource(activeSource);
  // If user was on Markdown and switches to a vision source, fall back silently.
  const effectiveView = view === 'markdown' && !showMarkdown ? 'fields' : view;

  const presentCount = invoice
    ? (invoice.parsed_rows ?? []).filter(
        (r) => r.display_value != null && r.display_value !== '',
      ).length
    : 0;

  return (
    <div className="rounded-card border border-line overflow-hidden bg-paper flex flex-col min-h-[76vh] max-h-[76vh]">
      {/* Top zone — extractor selector as a row of stat cards.
           Confidence is the primary number; meta sits underneath. The active
           card gets a teal border + soft tint so the selection is obvious at
           a glance, and "✓ USED" marks the canonical attempt regardless of
           which card is currently active. */}
      <div className="px-4 pt-3 pb-3 border-b border-line bg-slate2/50">
        <div className="flex items-baseline justify-between mb-2">
          <span className="font-mono text-[10px] uppercase tracking-[0.22em] text-muted font-semibold">
            Extractor attempts
          </span>
          <span className="font-mono text-[10px] text-muted">
            {tabs.length} attempt{tabs.length === 1 ? '' : 's'}
          </span>
        </div>
        <div
          className="grid gap-2"
          style={{ gridTemplateColumns: `repeat(${Math.max(tabs.length, 1)}, minmax(0, 1fr))` }}
        >
          {tabs.map((a) => (
            <ExtractorCard
              key={a.source}
              attempt={a}
              active={a.source === activeSource}
              onClick={() => a.document && onActiveSource(a.source)}
            />
          ))}
        </div>
      </div>

      {/* Reference state is conveyed entirely by ExtractorCard styling
           (gold tint, REF pill, opacity) — no banner row, so toggling
           between cards never shifts vertical layout. */}

      {/* Row 2 — view tabs (Fields / [Markdown] / JSON) on the white body */}
      <div className="flex items-center border-b border-line px-4">
        <ViewTab on={effectiveView === 'fields'} onClick={() => setView('fields')}>
          Fields <span className="text-muted">({presentCount})</span>
        </ViewTab>
        {showMarkdown && (
          <ViewTab on={effectiveView === 'markdown'} onClick={() => setView('markdown')}>
            Markdown
          </ViewTab>
        )}
        <ViewTab on={effectiveView === 'json'} onClick={() => setView('json')}>
          JSON
        </ViewTab>
        <div className="ml-auto py-2 text-[10px] font-mono text-muted">
          {invoice
            ? `${Math.round((invoice.extractor_confidence ?? 0) * 100)}% conf · ${invoice.pages ?? 1}p · ${fmtMs(invoice.extraction_ms)}`
            : 'awaiting first extractor result'}
        </div>
      </div>

      {/* Body — fills remaining height, single scroll region */}
      <div className="flex-1 overflow-auto min-h-0">
        {!invoice ? (
          <BodyPlaceholder attempts={tabs} />
        ) : effectiveView === 'fields' ? (
          <FieldsTable invoice={invoice} onFieldHover={onFieldHover} />
        ) : effectiveView === 'markdown' ? (
          <MarkdownBody
            source={invoice.raw_markdown ?? ''}
            mode={mdMode}
            setMode={setMdMode}
          />
        ) : (
          <JsonBody invoice={invoice} />
        )}
      </div>
    </div>
  );
}

function BodyPlaceholder({ attempts }: { attempts: ExtractAttempt[] }) {
  const pending = attempts.filter((a) => a.status === 'PENDING').length;
  const running = attempts.filter((a) => a.status === 'RUNNING').length;
  const failed = attempts.filter((a) => a.status === 'FAILED').length;
  const parts: string[] = [];
  if (running > 0) parts.push(`${running} running`);
  if (pending > 0) parts.push(`${pending} queued`);
  if (failed > 0) parts.push(`${failed} failed`);
  const headline = running > 0 ? 'Extraction in progress…' : pending > 0 ? 'Waiting for extractors to start…' : 'Awaiting extracted document…';
  return (
    <div className="px-6 py-12 text-center text-muted text-sm">
      <div className="font-sans text-base font-semibold text-navy-1 mb-1 animate-pulse">
        {headline}
      </div>
      <div className="font-mono text-[11px] text-muted">
        {parts.length > 0 ? parts.join(' · ') : 'no extractor results yet'}
      </div>
    </div>
  );
}

// ─── extractor tab ──────────────────────────────────────────────────────────

function ExtractorCard({
  attempt,
  active,
  onClick,
}: {
  attempt: ExtractAttempt;
  active: boolean;
  onClick: () => void;
}) {
  const pending = attempt.status === 'PENDING';
  const running = attempt.status === 'RUNNING';
  const failed  = attempt.status === 'FAILED';
  const success = attempt.status === 'SUCCESS';
  // Reference card: succeeded but the orchestrator did NOT pick this output
  // for downstream rule checks. Visually demoted (gold instead of teal,
  // slight opacity) so the canonical winner is unambiguous at a glance.
  const isReference = success && !attempt.is_selected;
  // confidence: SSE COMPLETED carries it directly; document fills it once /extracts loads
  const conf = attempt.document?.extractor_confidence ?? attempt.confidence ?? 0;
  const pct = Math.round(conf * 100);
  // only clickable once the document has loaded (confidence may arrive before it)
  const clickable = success && attempt.document != null;

  // Card surface treatment:
  //   selected canonical (active or not) → teal accents
  //   reference (active)                  → gold tint + ring (matches REF banner)
  //   reference (inactive)                → default surface, dimmed 90%
  let cls: string;
  if (pending) {
    cls = 'border-line border-dashed bg-slate2/40 cursor-default opacity-70';
  } else if (running) {
    cls = 'border-status-gold/50 bg-status-goldSoft/40 cursor-progress';
  } else if (failed) {
    cls = 'border-status-red/30 bg-status-redSoft/30 cursor-not-allowed opacity-75';
  } else if (active && isReference) {
    cls = 'border-status-gold bg-status-goldSoft/40 ring-1 ring-status-gold/30';
  } else if (active) {
    cls = 'border-teal-1 bg-teal-1/5 ring-1 ring-teal-1/30';
  } else if (isReference) {
    cls = 'border-line bg-paper hover:border-status-gold/40 opacity-90';
  } else {
    cls = 'border-line bg-paper hover:border-teal-2/60';
  }

  return (
    <button
      onClick={clickable ? onClick : undefined}
      disabled={!clickable}
      className={`relative text-left rounded-btn border-2 px-3 py-2.5 transition-colors ${cls}`}
      title={
        pending
          ? `${fmtSource(attempt.source)} · queued — will run when its lane is ready`
          : running
            ? `${fmtSource(attempt.source)} · extracting…`
            : failed
              ? attempt.error ?? 'failed'
              : `${fmtSource(attempt.source)} · ${pct}% confidence`
      }
    >
      {/* Source label + status badge.
           Long labels (`qwen3-vl:4b-instruct (Local)`) truncate with ellipsis
           rather than wrap to a 2nd line, which used to push the badge below
           the label on cards with longer source names. `min-w-0` lets the
           label shrink in the flex track; `shrink-0` pins the badge to the
           top-right corner regardless of label length. */}
      <div className="flex items-baseline justify-between gap-2">
        <span
          className={[
            'font-mono text-[10px] uppercase tracking-[0.22em] font-semibold truncate min-w-0',
            pending
              ? 'text-muted'
              : running
                ? 'text-status-gold'
                : active && isReference
                  ? 'text-status-gold'
                  : active && success
                    ? 'text-teal-1'
                    : failed
                      ? 'text-status-red'
                      : 'text-muted',
          ].join(' ')}
          title={fmtSource(attempt.source)}
        >
          {fmtSource(attempt.source)}
        </span>
        {pending && (
          <span className="font-mono text-[11px] uppercase tracking-[0.10em] text-muted font-bold inline-flex items-center gap-1 shrink-0">
            <span className="w-1.5 h-1.5 rounded-full border border-dashed border-muted" />
            queued
          </span>
        )}
        {running && (
          <span className="font-mono text-[11px] uppercase tracking-[0.10em] text-status-gold font-bold inline-flex items-center gap-1 shrink-0">
            <span className="w-1.5 h-1.5 rounded-full bg-status-gold animate-pulse" />
            running
          </span>
        )}
        {success && attempt.is_selected && (
          <span className="font-mono text-[11px] uppercase tracking-[0.10em] text-status-green font-bold shrink-0">
            ✓ used
          </span>
        )}
        {isReference && (
          <span
            className="font-mono text-[11px] uppercase tracking-[0.10em] text-status-gold font-bold inline-flex items-center gap-1 shrink-0"
            title="Reference output — extracted in parallel for comparison; rule checks did not use this result"
          >
            <span className="w-1.5 h-1.5 rounded-full bg-status-gold" />
            ref
          </span>
        )}
        {failed && (
          <span className="font-mono text-[11px] uppercase tracking-[0.10em] text-status-red font-bold shrink-0">
            failed
          </span>
        )}
      </div>

      {/* Body — primary metric */}
      <div className="mt-1.5 flex items-baseline gap-1.5 flex-wrap">
        {pending ? (
          <span className="font-mono text-xs text-muted italic">
            waiting for lane…
          </span>
        ) : running ? (
          <span className="font-mono text-xs text-status-gold animate-pulse">
            extracting…
          </span>
        ) : failed ? (
          <span className="text-xs text-status-red">
            {truncate(attempt.error ?? 'error', 56)}
          </span>
        ) : (
          <>
            <span className="font-mono text-xl font-bold text-navy-1 leading-none">
              {pct}%
            </span>
            <span className="font-mono text-[11px] uppercase tracking-[0.10em] text-muted ml-0.5">
              conf
            </span>
            <Sep />
            <span className="font-mono text-[10px] text-muted">
              {fmtMs(attempt.duration_ms)}
            </span>
            {attempt.document?.image_based && (
              <>
                <Sep />
                <span className="font-mono text-[11px] uppercase tracking-[0.10em] text-status-gold">
                  image
                </span>
              </>
            )}
          </>
        )}
      </div>
    </button>
  );
}

function Sep() {
  return <span className="text-muted select-none">·</span>;
}

function ViewTab({
  on,
  onClick,
  children,
}: {
  on: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={[
        'px-3 py-2.5 text-sm border-b-2 -mb-px',
        on
          ? 'border-teal-1 text-navy-1 font-semibold'
          : 'border-transparent text-muted hover:text-navy-1',
      ].join(' ')}
    >
      {children}
    </button>
  );
}

// ─── fields body ────────────────────────────────────────────────────────────

function FieldsTable({
  invoice,
  onFieldHover,
}: {
  invoice: InvoiceDocument;
  onFieldHover: (v: string | null) => void;
}) {
  const rows = invoice.parsed_rows ?? [];
  if (rows.length === 0) {
    return (
      <div className="px-6 py-8 text-center text-muted text-sm italic">
        No fields extracted.
      </div>
    );
  }
  return (
    <table className="w-full text-sm" onMouseLeave={() => onFieldHover(null)}>
      <colgroup>
        <col className="w-44" />
        <col />
      </colgroup>
      <tbody>
        {rows.map((row) =>
          row.meta?.type === 'TABLE' ? (
            <LineItemsSection key={row.tag} row={row} onFieldHover={onFieldHover} />
          ) : (
            <FieldValueRow key={row.tag} row={row} onFieldHover={onFieldHover} />
          ),
        )}
      </tbody>
    </table>
  );
}

function FieldValueRow({
  row,
  onFieldHover,
}: {
  row: ParsedRow;
  onFieldHover: (v: string | null) => void;
}) {
  const val = row.display_value ?? '';
  return (
    <tr
      className="border-b border-line last:border-0 hover:bg-slate2 cursor-help"
      onMouseEnter={() => onFieldHover(val)}
    >
      <td className="px-4 py-1.5 text-muted w-44">{row.label}</td>
      <td className="px-4 py-1.5 font-mono text-xs">
        {val ? (
          <span className="whitespace-pre-wrap">{val}</span>
        ) : (
          <em className="text-muted italic">missing</em>
        )}
      </td>
    </tr>
  );
}

function LineItemsSection({
  row,
  onFieldHover,
}: {
  row: ParsedRow;
  onFieldHover: (v: string | null) => void;
}) {
  const columns = (row.meta?.columns ?? []) as string[];
  const columnLabels = (row.meta?.column_labels ?? {}) as Record<string, string>;
  const tableRows = (row.meta?.rows ?? []) as Array<Record<string, unknown>>;
  const rowCount = (row.meta?.row_count ?? 0) as number;
  // display_value is null when the extractor never returned the table at all,
  // distinct from "extractor returned an empty array" (rowCount === 0). The
  // first should look like a missing scalar field; the second should say "0 items".
  const wasExtracted = row.display_value != null;

  return (
    <tr className="border-b border-line">
      <td colSpan={2} className="px-0 py-0">
        {/* Section header */}
        <div className="px-4 py-2 bg-slate2 border-b border-line flex items-center gap-2">
          <span className="text-sm text-muted">{row.label}</span>
          {wasExtracted ? (
            <span className="font-mono text-[10px] bg-teal-1/10 text-teal-1 px-1.5 py-0.5 rounded">
              {rowCount} {rowCount === 1 ? 'item' : 'items'}
            </span>
          ) : (
            <span className="font-mono text-[10px] bg-slate2 text-muted italic px-1.5 py-0.5 rounded">
              missing
            </span>
          )}
        </div>
        {tableRows.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-line bg-slate2">
                  {columns.map((col) => (
                    <th
                      key={col}
                      className="px-3 py-1.5 text-left font-mono text-[10px] text-muted uppercase tracking-wider font-semibold whitespace-nowrap"
                    >
                      {columnLabels[col] ?? col}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {tableRows.map((rowData, i) => (
                  <tr
                    key={i}
                    className="border-b border-line last:border-0 hover:bg-slate2"
                  >
                    {columns.map((col) => {
                      const cell = rowData[col] != null ? String(rowData[col]) : null;
                      return (
                        <td
                          key={col}
                          className="px-3 py-1.5 font-mono"
                          onMouseEnter={() => cell != null && onFieldHover(cell)}
                        >
                          {cell ?? <span className="text-muted">—</span>}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="px-4 py-3 text-muted text-xs italic">
            {wasExtracted ? 'Extractor returned an empty table.' : 'Not extracted.'}
          </div>
        )}
      </td>
    </tr>
  );
}

// ─── markdown body ──────────────────────────────────────────────────────────

function MarkdownBody({
  source,
  mode,
  setMode,
}: {
  source: string;
  mode: 'rendered' | 'raw';
  setMode: (m: 'rendered' | 'raw') => void;
}) {
  return (
    <div>
      <div className="px-4 py-2 border-b border-line bg-slate2/40 flex items-center gap-2 text-xs">
        <span className="font-mono text-[10px] uppercase tracking-widest text-muted mr-1">
          View
        </span>
        <ModeBtn on={mode === 'rendered'} onClick={() => setMode('rendered')}>
          Rendered
        </ModeBtn>
        <ModeBtn on={mode === 'raw'} onClick={() => setMode('raw')}>
          Raw
        </ModeBtn>
        <div className="ml-auto font-mono text-[10px] text-muted">
          {source.length} chars
        </div>
      </div>
      <div className="px-4 py-3">
        {mode === 'rendered' ? (
          <MarkdownView source={source} />
        ) : (
          <pre className="font-mono text-[12px] whitespace-pre-wrap leading-relaxed text-navy-1">
            {source || <em className="text-muted italic">No markdown emitted.</em>}
          </pre>
        )}
      </div>
    </div>
  );
}

function ModeBtn({
  on,
  onClick,
  children,
}: {
  on: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={[
        'px-2 py-0.5 rounded font-mono text-[11px] border transition',
        on
          ? 'bg-paper text-navy-1 border-teal-1'
          : 'text-muted border-transparent hover:text-navy-1',
      ].join(' ')}
    >
      {children}
    </button>
  );
}

// ─── json body ──────────────────────────────────────────────────────────────

function JsonBody({ invoice }: { invoice: InvoiceDocument }) {
  // Strip parsed_rows / envelope (UI-render scaffolding, noisy in JSON view).
  // BUT: the line_items array doesn't live as a top-level field on
  // InvoiceDocument — it sits inside parsed_rows[tag=line_items].meta.rows.
  // Surface it back as a top-level `line_items` so the JSON view shows the
  // array of row objects (matches what the LLM actually returned and what
  // SQL queries against `result.document.fields.line_items` would expect).
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const { parsed_rows, envelope: _env, ...rest } = invoice;
  const lineItemsRow = (parsed_rows ?? []).find((r) => r.tag === 'line_items');
  const lineItems = (lineItemsRow?.meta as { rows?: unknown } | undefined)?.rows;
  const display = lineItems !== undefined ? { ...rest, line_items: lineItems } : rest;
  return (
    <div className="p-4">
      <pre className="font-mono text-xs text-navy-1 whitespace-pre leading-relaxed">
        {JSON.stringify(display, null, 2)}
      </pre>
    </div>
  );
}

// ─── helpers ────────────────────────────────────────────────────────────────

function isVisionSource(source: string): boolean {
  return /_[1-4]$/.test(source);
}

/** Format extractor source name for display.
 *  "qwen3-vl:4b-instruct_1" → "qwen3-vl:4b-instruct (Slot 1)"
 *  "qwen3.6-plus_2"         → "qwen3.6-plus (Slot 2)"
 */
export function fmtSource(s: string): string {
  const m = s.match(/^(.+)_([1-4])$/);
  if (m) return `${m[1]} (Slot ${m[2]})`;
  return s;
}

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}
