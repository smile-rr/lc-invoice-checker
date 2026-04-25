import { useEffect, useMemo, useRef } from 'react';
import { useFieldRegistry } from '../../hooks/useFieldRegistry';
import { fieldLabel, formatFieldValue, primaryTag } from '../../lib/formatField';
import type { FieldDefinition, LcDocument } from '../../types';

interface Props {
  lc: LcDocument;
  selectedTag: string | null;
  onSelect: (tag: string | null) => void;
}

/**
 * Parsed-LC panel paired with {@link LcSourceView}. Rendered entirely from the
 * field-pool registry (`/api/v1/fields`) + the parsed envelope (`lc.envelope.fields`).
 *
 * Adding a new field requires editing only `field-pool.yaml` — this component
 * does not bind to any specific field name.
 */

const GROUP_ORDER = ['header', 'amount', 'parties', 'shipment', 'documents', 'conditions', 'meta', 'other'] as const;
const GROUP_LABEL: Record<string, string> = {
  header: 'Credit Header',
  amount: 'Amount',
  parties: 'Parties',
  shipment: 'Shipment',
  documents: 'Documents',
  conditions: 'Conditions',
  meta: 'Meta',
  other: 'Other',
};

const TONE_BY_KEY: Record<string, 'g' | 'r' | 'y'> = {
  lc_number: 'g',
  expiry_date: 'y',
  credit_amount: 'g',
  max_amount_flag: 'r',
  latest_shipment_date: 'y',
};

export function LcSidebar({ lc, selectedTag, onSelect }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const { byGroup, loading } = useFieldRegistry('LC');
  const envelope = lc.envelope?.fields ?? {};

  // Build groups in declared order; only include fields the parser populated
  // OR fields with a default so the user sees the full canonical model.
  const groups = useMemo(() => {
    const out: Array<{ title: string; rows: Array<Row> }> = [];
    for (const groupKey of GROUP_ORDER) {
      const fields = byGroup.get(groupKey);
      if (!fields || fields.length === 0) continue;
      const rows = fields
        .map((f) => buildRow(f, envelope))
        .filter((r): r is Row => r !== null);
      if (rows.length === 0) continue;
      out.push({ title: GROUP_LABEL[groupKey] ?? groupKey, rows });
    }
    return out;
  }, [byGroup, envelope]);

  useEffect(() => {
    if (!selectedTag || !containerRef.current) return;
    const el = containerRef.current.querySelector(`[data-tag="${selectedTag}"]`);
    el?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [selectedTag]);

  if (loading && groups.length === 0) {
    return (
      <aside className="bg-navy-1 text-white rounded-card p-4 text-sm text-muted">
        Loading field registry…
      </aside>
    );
  }

  return (
    <aside
      ref={containerRef}
      className="bg-navy-1 text-white rounded-card p-4 space-y-4 text-sm overflow-auto max-h-[72vh]"
    >
      {groups.map((g) => (
        <div key={g.title}>
          <div className="uppercase tracking-[0.2em] text-[10px] text-muted mb-1.5 px-2">
            {g.title}
          </div>
          <div className="space-y-0.5">
            {g.rows.map((r) => {
              const selected = r.tag !== null && selectedTag === r.tag;
              const clickable = r.tag !== null;
              return (
                <button
                  key={r.key}
                  data-tag={r.tag ?? r.key}
                  onClick={() => clickable && onSelect(selected ? null : r.tag)}
                  disabled={!clickable}
                  className={[
                    'relative w-full flex items-baseline gap-3 text-left rounded-input px-2 py-1.5 transition-colors',
                    selected
                      ? 'bg-teal-1/15 ring-1 ring-inset ring-teal-1/40'
                      : clickable
                        ? 'hover:bg-navy-3'
                        : '',
                  ].join(' ')}
                >
                  {selected && (
                    <span className="absolute left-0 top-1 bottom-1 w-[3px] bg-teal-2 rounded" />
                  )}
                  <span
                    className={[
                      'font-mono text-[10px] w-10 shrink-0 transition-colors',
                      selected ? 'text-teal-2 font-semibold' : 'text-teal-2',
                    ].join(' ')}
                  >
                    {r.tag ? `:${r.tag}:` : ''}
                  </span>
                  <span className="text-muted w-28 shrink-0 text-[11px]">{r.label}</span>
                  <span
                    className={[
                      'font-mono text-[11px] flex-1 truncate',
                      r.tone === 'g' ? 'text-status-green' : '',
                      r.tone === 'r' ? 'text-status-red' : '',
                      r.tone === 'y' ? 'text-status-gold' : '',
                      r.tone ? '' : selected ? 'text-white font-semibold' : 'text-white',
                    ].join(' ')}
                    title={r.value ?? ''}
                  >
                    {r.value || <em className="text-muted/70 italic">—</em>}
                  </span>
                </button>
              );
            })}
          </div>
        </div>
      ))}
    </aside>
  );
}

type Row = {
  key: string;
  tag: string | null;
  label: string;
  value: string | null;
  tone?: 'g' | 'r' | 'y';
};

function buildRow(field: FieldDefinition, envelope: Record<string, unknown>): Row | null {
  const raw = envelope[field.key];
  let display = formatFieldValue(raw, field.type);

  // Special-case composites that read better merged: expiry_place beside
  // expiry_date, tolerance ± collapsed to one line.
  if (field.key === 'expiry_date') {
    const place = envelope['expiry_place'];
    const date = display ?? '';
    const placeStr = typeof place === 'string' && place.trim() ? ` · ${place}` : '';
    display = (date + placeStr).trim() || null;
  }
  if (field.key === 'expiry_place' || field.key === 'tolerance_minus') {
    return null; // folded into the partner row above
  }
  if (field.key === 'tolerance_plus') {
    const plus = typeof raw === 'number' ? raw : Number(raw ?? 0);
    const minus = Number(envelope['tolerance_minus'] ?? 0);
    if (plus === 0 && minus === 0) return null;
    display = `+${plus}% / -${minus}%`;
  }
  if (field.key === 'credit_amount') {
    const ccy = envelope['credit_currency'];
    if (ccy && display) display = `${ccy} ${display}`;
  }
  if (field.key === 'credit_currency') {
    return null; // shown as prefix on credit_amount
  }
  if (field.key === 'goods_description' || field.key === 'additional_conditions') {
    // In sidebar context show just a snippet; full text is in the main panel.
    if (display && display.length > 80) display = display.slice(0, 80) + '…';
  }

  return {
    key: field.key,
    tag: primaryTag(field),
    label: fieldLabel(field),
    value: display,
    tone: TONE_BY_KEY[field.key],
  };
}
