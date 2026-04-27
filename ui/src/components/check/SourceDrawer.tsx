import { useEffect, useMemo, useRef, useState } from 'react';
import { getLcRaw } from '../../api/client';
import type { InvoiceDocument, LcDocument } from '../../types';
import { InvoiceViewer } from '../invoice/InvoiceViewer';

export type DrawerTarget = {
  ruleId: string;
  ruleName: string;
  /** Newline-separated `key: value` evidence pairs the agent reported. */
  lcEvidence: string | null;
  invoiceEvidence: string | null;
} | null;

type TabKey = 'lc-raw' | 'lc-parsed' | 'invoice-pdf' | 'invoice-json';

interface Props {
  target: DrawerTarget;
  sessionId: string | null;
  lc: LcDocument | null | undefined;
  invoice: InvoiceDocument | null | undefined;
  onClose: () => void;
}

/**
 * Right-hand slide-over drawer that lets a reviewer cross-check a single
 * compliance result against the original LC and invoice WITHOUT navigating
 * away from the compliance screen. Closing the drawer returns the reviewer
 * to the same scroll position they were at — no state loss.
 *
 * <p>LC tab: fetches the raw MT700 and highlights the {@code :tag:} blocks
 * referenced by the agent's {@code lc_value} evidence. Invoice tab: renders
 * {@code raw_markdown} (or {@code raw_text} fallback) with a best-effort
 * case-insensitive substring highlight of each value the agent reported in
 * {@code presented_value}. If a value isn't found in the source, the source
 * still renders without highlight — no failure mode.
 */
export function SourceDrawer({ target, sessionId, lc, invoice, onClose }: Props) {
  const open = target !== null;
  const [tab, setTab] = useState<TabKey>('lc-raw');
  const [lcText, setLcText] = useState<string>('');
  const [lcErr, setLcErr] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // Reset to LC raw tab whenever a new target opens.
  useEffect(() => {
    if (open) setTab('lc-raw');
  }, [open, target?.ruleId]);

  // Fetch LC raw text once per session.
  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    getLcRaw(sessionId)
      .then((t) => !cancelled && (setLcText(t), setLcErr(null)))
      .catch((e) => !cancelled && setLcErr((e as Error).message));
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  // ESC closes the drawer.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const lcHighlights = useMemo(
    () => extractValues(target?.lcEvidence ?? null),
    [target?.lcEvidence],
  );
  const invoiceHighlights = useMemo(
    () => extractValues(target?.invoiceEvidence ?? null),
    [target?.invoiceEvidence],
  );

  const lcTagsToHighlight = useMemo(() => extractLcTags(target?.lcEvidence ?? null), [target?.lcEvidence]);

  // Auto-scroll the highlighted region into view when the tab changes or
  // target changes. Done after the next paint so refs are populated.
  useEffect(() => {
    if (!open || !containerRef.current) return;
    const id = requestAnimationFrame(() => {
      const mark = containerRef.current?.querySelector('mark[data-first="true"]');
      mark?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
    return () => cancelAnimationFrame(id);
  }, [open, tab, target?.ruleId, lcText]);

  if (!open || !target) return null;

  return (
    <>
      {/* Backdrop — click to close. Subtle wash so the compliance screen
          remains visible behind. */}
      <div
        className="fixed inset-0 bg-navy-1/20 z-40 transition-opacity"
        onClick={onClose}
        aria-hidden
      />

      {/* Resizable centered dialog — drag the bottom-right corner to resize.
           CSS `resize: both` is the simplest way to support resizing without
           a JS handler; it works on every modern browser. Move-by-drag is
           intentionally not implemented — keeps the component dependency-free. */}
      <aside
        className="fixed left-1/2 top-[8vh] z-50 bg-paper rounded-card border border-line shadow-2xl flex flex-col overflow-hidden"
        style={{
          transform: 'translateX(-50%)',
          width: 'min(960px, 92vw)',
          height: 'min(82vh, 760px)',
          minWidth: 480,
          minHeight: 320,
          maxWidth: '95vw',
          maxHeight: '90vh',
          resize: 'both',
        }}
        role="dialog"
        aria-label="Original document viewer"
      >
        {/* Header */}
        <header className="flex items-center justify-between px-4 py-3 border-b border-line bg-slate2/50">
          <div className="min-w-0">
            <div className="font-mono text-[10px] uppercase tracking-[0.18em] text-muted font-semibold">
              Source viewer · {target.ruleId}
            </div>
            <div className="font-sans text-sm font-medium text-navy-1 truncate">
              {target.ruleName}
            </div>
          </div>
          <button
            onClick={onClose}
            className="font-mono text-xs uppercase tracking-wide px-2 py-1 rounded-sm border border-line text-muted hover:text-navy-1 hover:border-navy-1/40"
            aria-label="Close drawer (Esc)"
          >
            ✕ Close
          </button>
        </header>

        {/* Tabs */}
        <div className="flex border-b border-line bg-paper shrink-0">
          <DrawerTab on={tab === 'lc-raw'}      onClick={() => setTab('lc-raw')}>LC · raw MT700</DrawerTab>
          <DrawerTab on={tab === 'lc-parsed'}   onClick={() => setTab('lc-parsed')}>LC · parsed</DrawerTab>
          <DrawerTab on={tab === 'invoice-pdf'} onClick={() => setTab('invoice-pdf')}>Invoice · PDF</DrawerTab>
          <DrawerTab on={tab === 'invoice-json'} onClick={() => setTab('invoice-json')}>Invoice · parsed JSON</DrawerTab>
        </div>

        {/* Body */}
        <div ref={containerRef} className="flex-1 min-h-0 overflow-auto bg-paper">
          {tab === 'lc-raw' && (
            lcErr ? (
              <div className="p-4 text-sm text-status-red">Failed to load MT700: {lcErr}</div>
            ) : !lcText ? (
              <div className="p-4 text-sm text-muted italic">Loading…</div>
            ) : (
              <LcRawView text={lcText} tagHighlights={lcTagsToHighlight} valueHighlights={lcHighlights} />
            )
          )}
          {tab === 'lc-parsed' && <LcParsedView lc={lc} />}
          {tab === 'invoice-pdf' && (
            sessionId ? (
              // Same pdf.js renderer used in the Invoice audit tab and upload
              // preview — keeps the look clean (no browser PDF toolbar, no
              // grey gutter) and shares the toolbar/zoom UX. Pass a maxHeight
              // class that fills the drawer body so the inner sticky toolbar
              // and page list size to the available space.
              <div className="p-3">
                <InvoiceViewer sessionId={sessionId} maxHeightClass="max-h-[calc(82vh-8rem)]" />
              </div>
            ) : (
              <div className="p-4 text-sm text-muted italic">No session id — cannot load PDF.</div>
            )
          )}
          {tab === 'invoice-json' && <InvoiceJsonView invoice={invoice} />}
        </div>

        {/* Footer evidence summary */}
        <footer className="border-t border-line bg-slate2/40 px-4 py-2 text-[11px] font-mono text-muted shrink-0">
          {tab === 'lc-raw'
            ? lcTagsToHighlight.length > 0
              ? `Highlighting tags: ${lcTagsToHighlight.map((t) => `:${t}:`).join(' · ')}`
              : `Highlighting ${lcHighlights.length} value${lcHighlights.length === 1 ? '' : 's'} from agent evidence`
            : tab === 'invoice-pdf' || tab === 'invoice-json'
            ? `Highlighting ${invoiceHighlights.length} value${invoiceHighlights.length === 1 ? '' : 's'} from agent evidence (JSON tab only)`
            : `Parsed rows projected from envelope.fields`}
        </footer>
      </aside>
    </>
  );
}

// ---------------------------------------------------------------------------
// LC raw view — renders MT700 with `:tag:` and value highlighting
// ---------------------------------------------------------------------------

function LcRawView({
  text,
  tagHighlights,
  valueHighlights,
}: {
  text: string;
  tagHighlights: string[];
  valueHighlights: string[];
}) {
  // Build a normalised set so we can match tags case-insensitively and
  // handle both ":32B:" and "32B" forms in the highlight list.
  const tagSet = useMemo(() => new Set(tagHighlights.map((t) => t.toUpperCase())), [tagHighlights]);

  // Slice the text into spans: tag-block headers (":XX:") get a coloured
  // background when in tagSet; value-substring highlights are layered on top.
  const blocks = useMemo(() => splitByTags(text), [text]);

  let firstAssigned = false;

  return (
    <pre className="font-mono text-[12px] leading-relaxed text-navy-1 whitespace-pre-wrap p-4">
      {blocks.map((b, i) => {
        const isHighlightedTag = tagSet.has(b.tag.toUpperCase());
        if (isHighlightedTag) {
          const isFirst = !firstAssigned;
          firstAssigned = true;
          return (
            <mark
              key={i}
              data-first={isFirst ? 'true' : undefined}
              data-tag={b.tag}
              className="bg-status-goldSoft/70 rounded-sm px-0.5"
            >
              <span className="text-status-gold font-bold">:{b.tag}:</span>
              {highlightValues(b.body, valueHighlights, !firstAssigned)}
            </mark>
          );
        }
        // Untagged segment OR non-highlighted tag block — colour the tag
        // pattern lightly so structure is visible, then layer value
        // highlights on the body.
        return (
          <span key={i}>
            {b.tag !== '' ? <span className="text-teal-2">:{b.tag}:</span> : null}
            {highlightValues(b.body, valueHighlights, !firstAssigned, () => (firstAssigned = true))}
          </span>
        );
      })}
    </pre>
  );
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

interface TagBlock {
  tag: string; // "" for the prefix/suffix outside any tag
  body: string;
}

/**
 * Split MT700 text into blocks at every `:XX:` tag header (line-anchored).
 * Returns alternating segments so we can render tags coloured AND values
 * highlighted within their bodies.
 */
function splitByTags(text: string): TagBlock[] {
  const out: TagBlock[] = [];
  const re = /(^|\n)(:([^:\s]{1,5}):)/g;
  let lastIndex = 0;
  let lastTag = '';
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    const headerStart = m.index + (m[1] ? m[1].length : 0);
    const body = text.slice(lastIndex, headerStart);
    out.push({ tag: lastTag, body });
    lastTag = m[3];
    lastIndex = headerStart + m[2].length;
  }
  out.push({ tag: lastTag, body: text.slice(lastIndex) });
  return out;
}

/**
 * Replace every case-insensitive occurrence of any string in {@code values}
 * with a {@code <mark>} element. Skips empty strings and strings shorter
 * than 2 chars (too noisy).
 */
function highlightValues(
  source: string,
  values: string[],
  alreadyAssigned: boolean,
  onAssignedFirst?: () => void,
): React.ReactNode {
  const cleaned = values
    .map((v) => v.trim())
    .filter((v) => v.length >= 2);
  if (cleaned.length === 0) return source;

  // Build one big alternation regex, escaping each literal.
  const pattern = new RegExp(
    `(${cleaned.map(escapeRegex).join('|')})`,
    'gi',
  );
  const parts: Array<string | { match: string }> = [];
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = pattern.exec(source)) !== null) {
    if (m.index > last) parts.push(source.slice(last, m.index));
    parts.push({ match: m[0] });
    last = m.index + m[0].length;
    if (m[0].length === 0) pattern.lastIndex++; // safety against zero-length
  }
  if (last < source.length) parts.push(source.slice(last));

  let firstSeen = alreadyAssigned;

  return parts.map((p, i) => {
    if (typeof p === 'string') return p;
    const isFirst = !firstSeen;
    firstSeen = true;
    if (isFirst) onAssignedFirst?.();
    return (
      <mark
        key={i}
        data-first={isFirst ? 'true' : undefined}
        className="bg-status-goldSoft text-navy-1 rounded-sm px-0.5"
      >
        {p.match}
      </mark>
    );
  });
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Pull the value side from an evidence string of newline-separated
 * `key: value` pairs. Empty / "null" / "—" values are dropped.
 */
function extractValues(evidence: string | null): string[] {
  if (!evidence) return [];
  const out: string[] = [];
  for (const line of evidence.split(/\r?\n/)) {
    const colon = line.indexOf(':');
    if (colon < 0) continue;
    const value = line.slice(colon + 1).trim();
    if (!value || value.toLowerCase() === 'null' || value === '—') continue;
    out.push(value);
  }
  return out;
}

/**
 * Heuristic: when the agent's evidence references known LC field_keys whose
 * canonical mapping is to a SWIFT tag (e.g. {@code credit_amount} → 32B), pull
 * those tags out so the LC view can highlight the whole tag block. We don't
 * have the field-pool registry on the client, so this is a small explicit
 * map — extend as more rules ship.
 */
function extractLcTags(evidence: string | null): string[] {
  if (!evidence) return [];
  const tags = new Set<string>();
  const lc = evidence.toLowerCase();
  if (lc.includes('credit_amount') || lc.includes('credit_currency')) tags.add('32B');
  if (lc.includes('tolerance_plus') || lc.includes('tolerance_minus')) tags.add('39A');
  if (lc.includes('lc_number') || lc.includes('lc_reference')) tags.add('20');
  if (lc.includes('beneficiary_name') || lc.includes('beneficiary_address')) tags.add('59');
  if (lc.includes('applicant_name') || lc.includes('applicant_address')) tags.add('50');
  if (lc.includes('goods_description')) tags.add('45A');
  if (lc.includes('documents_required')) tags.add('46A');
  if (lc.includes('additional_conditions')) tags.add('47A');
  if (lc.includes('expiry_date')) tags.add('31D');
  if (lc.includes('port_of_loading')) tags.add('44E');
  if (lc.includes('port_of_discharge')) tags.add('44F');
  return Array.from(tags);
}

// ---------------------------------------------------------------------------

function DrawerTab({
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
        'px-4 py-2.5 text-sm border-b-2 -mb-px',
        on
          ? 'border-teal-1 text-navy-1 font-semibold'
          : 'border-transparent text-muted hover:text-navy-1',
      ].join(' ')}
    >
      {children}
    </button>
  );
}

// ---------------------------------------------------------------------------
// LC parsed — display-ready rows from LcDocument.parsed_rows
// ---------------------------------------------------------------------------

function LcParsedView({ lc }: { lc: LcDocument | null | undefined }) {
  if (!lc) {
    return <div className="p-4 text-sm text-muted italic">LC parse output not available yet.</div>;
  }
  const rows = lc.parsed_rows ?? [];
  if (rows.length === 0) {
    return <div className="p-4 text-sm text-muted italic">No parsed rows.</div>;
  }
  return (
    <div className="p-4">
      <table className="w-full text-[12px] font-mono">
        <thead className="text-left text-muted">
          <tr>
            <th className="py-1 pr-4 font-semibold uppercase tracking-[0.05em] text-[10px]">Tag</th>
            <th className="py-1 pr-4 font-semibold uppercase tracking-[0.05em] text-[10px]">Field</th>
            <th className="py-1 font-semibold uppercase tracking-[0.05em] text-[10px]">Value</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} className="border-t border-line/60 align-top">
              <td className="py-1.5 pr-4 text-teal-2 whitespace-nowrap">
                {r.tag.startsWith('block') ? `{${r.tag.replace('block', '')}}` : `:${r.tag}:`}
              </td>
              <td className="py-1.5 pr-4 text-navy-1">{r.label}</td>
              <td className="py-1.5 text-navy-1 whitespace-pre-wrap break-words">
                {r.display_value || <span className="text-muted/70 italic">—</span>}
                {r.sublines && r.sublines.length > 0 && (
                  <div className="mt-1 pl-2 border-l-2 border-line">
                    {r.sublines.map((s, j) => (
                      <div key={j} className="text-[11px]">
                        <span className="text-muted">{s.label}: </span>
                        <span>{s.value}</span>
                      </div>
                    ))}
                  </div>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Invoice parsed JSON — pretty-print the typed InvoiceDocument
// ---------------------------------------------------------------------------

function InvoiceJsonView({ invoice }: { invoice: InvoiceDocument | null | undefined }) {
  if (!invoice) {
    return <div className="p-4 text-sm text-muted italic">Invoice extraction not available yet.</div>;
  }
  // Strip raw_markdown / raw_text from the JSON view — they're large and
  // already shown in their own forms (PDF tab, separate raw view). The
  // structured fields are what reviewers want to compare against the rule.
  const slim = { ...invoice, raw_markdown: undefined, raw_text: undefined };
  const json = JSON.stringify(slim, (_k, v) => (v === undefined ? null : v), 2);
  return (
    <pre className="font-mono text-[12px] leading-relaxed text-navy-1 whitespace-pre-wrap break-words p-4">
      {json}
    </pre>
  );
}
