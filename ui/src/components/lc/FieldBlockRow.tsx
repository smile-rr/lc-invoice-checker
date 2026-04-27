import type { ReactNode } from 'react';

interface Subline {
  label: string;
  value: string;
}

interface Props {
  /** Tag string, e.g. "20", "45A", "block1". Empty/undefined hides the chip. */
  tag?: string | null;
  /** Optional field-name shown next to the chip ("Goods Description"). */
  label?: string | null;
  /** Pre-formatted body. Renders inline with the chip + label; wraps only when
   *  the value contains literal newlines or overflows the row width. */
  value: string;
  /** Optional secondary key-value pairs (derived fields, e.g. Incoterms beneath :45A:). */
  sublines?: Subline[];
  /** Optional one-line note shown muted below the body (e.g. "parsed: 6 items"). */
  note?: string | null;
  pinned?: boolean;
  tone?: 'source' | 'parsed';
  onClick?: () => void;
  dataTag?: string;
  /** When true, renders a yellow ⚠ glyph and tooltip — used by the source
   *  pane for SWIFT-malformed tags ({@code :40Ad:} etc). */
  malformed?: boolean;
}

/**
 * One display row, shared between {@code LcSourceView} and {@code LcSidebar}.
 * Chip + label + value flow inline on a single line and wrap only when the
 * content forces it (long single line) or contains literal {@code \n} (e.g.
 * :45A: goods description). Sublines stack underneath; the muted "note" sits
 * at the bottom for parsed-side meta (parsed count, etc).
 *
 * Tone differentiates the two panes: source is on a slate-tinted surface
 * (raw input feel), parsed is on paper white (structured deliverable).
 */
export function FieldBlockRow({
  tag,
  label,
  value,
  sublines,
  note,
  pinned = false,
  tone = 'source',
  onClick,
  dataTag,
  malformed = false,
}: Props) {
  const isSource = tone === 'source';
  return (
    <div
      data-tag={dataTag ?? tag ?? undefined}
      onClick={onClick}
      className={[
        'relative px-4 py-1.5 transition-colors',
        'border-b border-line/50 last:border-b-0',
        isSource ? 'bg-slate2' : 'bg-paper',
        pinned
          ? (isSource ? 'bg-teal-1/15' : 'bg-teal-1/10')
          : (isSource ? 'hover:bg-[#e9eef4]' : 'hover:bg-slate2'),
        onClick ? 'cursor-pointer' : 'cursor-default',
      ].join(' ')}
    >
      {pinned && <span className="absolute left-0 top-0 bottom-0 w-[3px] bg-teal-1" />}

      {/* Inline row: chip + label + value flow on one line, wrap only when
          forced. The value uses min-w-0 + whitespace-pre-wrap so:
            • short text stays beside the label,
            • long single-line text wraps at the row's right edge,
            • text containing literal "\n" still produces line breaks. */}
      <div className="flex items-baseline gap-2 flex-wrap">
        {tag ? (
          <span
            className={[
              'inline-block px-1.5 py-0.5 rounded font-mono text-[11px] font-semibold shrink-0',
              malformed
                ? 'bg-status-goldSoft text-status-gold ring-1 ring-status-gold/60'
                : pinned
                ? 'bg-teal-1 text-white'
                : 'bg-slate2 text-teal-1 ring-1 ring-line',
            ].join(' ')}
            title={malformed
              ? `Malformed SWIFT tag — expected :NN[L]: (2 digits + optional uppercase letter). ":${tag}:" will be rejected by the pipeline.`
              : undefined}
          >
            {malformed ? '⚠ ' : ''}
            {tag.startsWith('block') ? `{${tag.replace('block', '')}}` : `:${tag}:`}
          </span>
        ) : null}
        {label ? (
          <span className="text-muted text-[11px] shrink-0">{label}</span>
        ) : null}
        <pre
          className={[
            'font-mono text-[11px] whitespace-pre-wrap leading-relaxed m-0',
            'flex-1 min-w-0 basis-0 break-words text-navy-1',
          ].join(' ')}
        >
          {value || <span className="text-muted/70 italic">—</span>}
        </pre>
      </div>

      {sublines && sublines.length > 0 ? (
        <div className="mt-1 pl-2 border-l-2 border-line">
          {sublines.map((s) => (
            <div key={s.label} className="flex items-baseline gap-2 text-[11px]">
              <span className="text-muted shrink-0">{s.label}:</span>
              <span className="font-mono text-navy-1">{s.value}</span>
            </div>
          ))}
        </div>
      ) : null}

      {note ? (
        <div className="mt-1 text-[10px] font-mono text-muted/70 italic">{note}</div>
      ) : null}
    </div>
  );
}

/** Helper for ad-hoc consumer-side rendering of formatted values that aren't ParsedRow. */
export function asNode(value: ReactNode): ReactNode {
  return value;
}
