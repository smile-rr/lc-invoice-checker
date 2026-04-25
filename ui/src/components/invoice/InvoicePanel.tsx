import { useState } from 'react';
import { MarkdownView } from './MarkdownView';
import type { ExtractAttempt, InvoiceDocument } from '../../types';

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
  /** Source the orchestrator marked as canonical (used by rule checks). May
      differ from {@link activeSource} when the operator is browsing alternatives. */
  selectedSource: string | null;
  onFieldHover: (value: string | null) => void;
}

const FIELDS: Array<[keyof InvoiceDocument, string]> = [
  ['invoice_number', 'Invoice #'],
  ['invoice_date', 'Invoice Date'],
  ['seller_name', 'Seller'],
  ['seller_address', 'Seller Address'],
  ['buyer_name', 'Buyer'],
  ['buyer_address', 'Buyer Address'],
  ['goods_description', 'Goods Description'],
  ['quantity', 'Quantity'],
  ['unit', 'Unit'],
  ['unit_price', 'Unit Price'],
  ['total_amount', 'Total Amount'],
  ['currency', 'Currency'],
  ['lc_reference', 'LC Reference'],
  ['trade_terms', 'Trade Terms'],
  ['port_of_loading', 'Port of Loading'],
  ['port_of_discharge', 'Port of Discharge'],
  ['country_of_origin', 'Country of Origin'],
  ['signed', 'Signed'],
];

/**
 * Unified extractor inspector. Two stacked tab strips frame the body:
 *
 *   row 1 — extractor tabs    (Vision · Docling · Mineru …)
 *   row 2 — view tabs          (Fields / Markdown)
 *
 * The body shows the currently-selected view of the currently-selected
 * extractor's parse. When the operator picks a non-canonical extractor,
 * a soft gold band warns that rule checks ran against a different attempt.
 */
export function InvoicePanel({
  attempts,
  activeSource,
  onActiveSource,
  invoice,
  selectedSource,
  onFieldHover,
}: Props) {
  const [view, setView] = useState<'fields' | 'markdown'>('fields');
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

  const isAlternativeView =
    selectedSource != null && activeSource !== selectedSource;

  const presentCount = invoice
    ? FIELDS.filter(([k]) => invoice[k] != null && invoice[k] !== '').length
    : 0;

  return (
    <div className="rounded-card border border-line overflow-hidden bg-paper">
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

      {/* Alternative-view banner */}
      {isAlternativeView && (
        <div className="px-4 py-2 bg-status-goldSoft border-b border-status-gold/20 text-[12px] text-status-gold flex items-center gap-2">
          <span className="font-mono font-bold uppercase text-[10px] tracking-widest">
            Alternative view
          </span>
          <span>·</span>
          <span>
            Rule checks ran against{' '}
            <span className="font-mono font-bold">{selectedSource}</span>.
          </span>
        </div>
      )}

      {/* Row 2 — view tabs (Fields / Markdown) on the white body */}
      <div className="flex items-center border-b border-line px-4">
        <ViewTab on={view === 'fields'} onClick={() => setView('fields')}>
          Fields <span className="text-muted">({presentCount})</span>
        </ViewTab>
        <ViewTab on={view === 'markdown'} onClick={() => setView('markdown')}>
          Markdown
        </ViewTab>
        <div className="ml-auto py-2 text-[10px] font-mono text-muted">
          {invoice
            ? `${Math.round((invoice.extractor_confidence ?? 0) * 100)}% conf · ${invoice.pages ?? 1}p · ${invoice.extraction_ms}ms`
            : 'awaiting first extractor result'}
        </div>
      </div>

      {/* Body */}
      {!invoice ? (
        <BodyPlaceholder attempts={tabs} />
      ) : view === 'fields' ? (
        <FieldsTable invoice={invoice} onFieldHover={onFieldHover} />
      ) : (
        <MarkdownBody
          source={invoice.raw_markdown ?? ''}
          mode={mdMode}
          setMode={setMdMode}
        />
      )}
    </div>
  );
}

function BodyPlaceholder({ attempts }: { attempts: ExtractAttempt[] }) {
  const running = attempts.filter((a) => a.status === 'RUNNING').length;
  const failed = attempts.filter((a) => a.status === 'FAILED').length;
  return (
    <div className="px-6 py-12 text-center text-muted text-sm">
      <div className="font-serif text-base text-navy-1 mb-1 animate-pulse">
        Awaiting extracted document…
      </div>
      <div className="font-mono text-[11px] text-muted/80">
        {running > 0 && `${running} running`}
        {running > 0 && failed > 0 && ' · '}
        {failed > 0 && `${failed} failed`}
        {running === 0 && failed === 0 && 'no extractor results yet'}
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
  const running = attempt.status === 'RUNNING';
  const failed = !running && (attempt.status === 'FAILED' || attempt.document === null);
  const success = !running && !failed;
  const conf = attempt.document?.extractor_confidence ?? attempt.confidence ?? 0;
  const pct = Math.round(conf * 100);
  const clickable = success;

  const cls = running
    ? 'border-status-gold/50 bg-status-goldSoft/40 cursor-progress'
    : active && success
      ? 'border-teal-1 bg-teal-1/5 ring-1 ring-teal-1/30'
      : failed
        ? 'border-status-red/30 bg-status-redSoft/30 cursor-not-allowed opacity-75'
        : 'border-line bg-paper hover:border-teal-2/60';

  return (
    <button
      onClick={clickable ? onClick : undefined}
      disabled={!clickable}
      className={`relative text-left rounded-btn border-2 px-3 py-2.5 transition-colors ${cls}`}
      title={
        running
          ? `${attempt.source} · extracting…`
          : failed
            ? attempt.error ?? 'failed'
            : `${attempt.source} · ${pct}% confidence`
      }
    >
      {/* Source label + USED / FAILED / RUNNING badge */}
      <div className="flex items-baseline justify-between gap-2">
        <span
          className={[
            'font-mono text-[10px] uppercase tracking-[0.22em] font-semibold',
            running
              ? 'text-status-gold'
              : active && success
                ? 'text-teal-1'
                : failed
                  ? 'text-status-red'
                  : 'text-muted',
          ].join(' ')}
        >
          {attempt.source}
        </span>
        {running && (
          <span className="font-mono text-[8px] uppercase tracking-[0.2em] text-status-gold font-bold inline-flex items-center gap-1">
            <span className="w-1.5 h-1.5 rounded-full bg-status-gold animate-pulse" />
            running
          </span>
        )}
        {success && attempt.is_selected && (
          <span className="font-mono text-[8px] uppercase tracking-[0.2em] text-status-green font-bold">
            ✓ used
          </span>
        )}
        {failed && (
          <span className="font-mono text-[8px] uppercase tracking-[0.2em] text-status-red font-bold">
            failed
          </span>
        )}
      </div>

      {/* Body — primary metric */}
      <div className="mt-1.5 flex items-baseline gap-1.5 flex-wrap">
        {running ? (
          <span className="font-mono text-xs text-status-gold animate-pulse">
            extracting…
          </span>
        ) : failed ? (
          <span className="text-xs text-status-red/80">
            {truncate(attempt.error ?? 'error', 36)}
          </span>
        ) : (
          <>
            <span className="font-mono text-xl font-bold text-navy-1 leading-none">
              {pct}%
            </span>
            <span className="font-mono text-[9px] uppercase tracking-widest text-muted ml-0.5">
              conf
            </span>
            <Sep />
            <span className="font-mono text-[10px] text-muted">
              {attempt.duration_ms}ms
            </span>
            {attempt.document?.image_based && (
              <>
                <Sep />
                <span className="font-mono text-[9px] uppercase tracking-widest text-status-gold">
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
  return <span className="text-muted/40 select-none">·</span>;
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
  return (
    <table
      className="w-full text-sm"
      onMouseLeave={() => onFieldHover(null)}
    >
      <tbody>
        {FIELDS.map(([k, label]) => {
          const raw = invoice[k];
          const val = formatVal(raw);
          return (
            <tr
              key={k}
              className="border-b border-line last:border-0 hover:bg-slate2 cursor-help"
              onMouseEnter={() => onFieldHover(val)}
            >
              <td className="px-4 py-1.5 text-muted w-44">{label}</td>
              <td className="px-4 py-1.5 font-mono text-xs">
                {raw == null || raw === '' ? (
                  <em className="text-muted italic">missing</em>
                ) : (
                  val
                )}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
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
      <div className="px-4 py-3 max-h-[68vh] overflow-auto">
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

// ─── helpers ────────────────────────────────────────────────────────────────

function formatVal(v: unknown): string {
  if (v === null || v === undefined || v === '') return '';
  if (typeof v === 'boolean') return v ? 'yes' : 'no';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}
