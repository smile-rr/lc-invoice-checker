import { useState } from 'react';
import type { CheckResult, InvoiceDocument, LcDocument } from '../../types';
import { useNavigate, useParams } from 'react-router-dom';

interface Props {
  lc: LcDocument | undefined;
  invoice: InvoiceDocument | undefined;
  checks: CheckResult[];
}

type Tone = 'match' | 'mismatch' | 'missing' | 'unable' | 'na';

/**
 * Field-level diff between LC and invoice.
 *
 * Design notes after the wide-screen feedback:
 *   - Container is capped at ~1200px, centered. Dense data should not stretch
 *     across 1920px — the eye gives up on cross-references.
 *   - LC and invoice values are STACKED inside one cell, not side-by-side.
 *     Accountants lay out comparisons this way: aligned labels, vertical scan.
 *   - Status colour appears only on a 4px left-edge stripe per row, not as a
 *     row wash. Keeps the section header readable and avoids visual noise on
 *     the value text.
 *   - Section headers get explicit weight (uppercase, bordered top, slate
 *     band) so they remain anchors even when adjacent rows are red-stripe.
 *   - Click a row → it pins (teal stripe + outline). Click a rule chip →
 *     deep-link into the Rule Checks step with that rule pre-selected.
 */
export function CompareTab({ lc, invoice, checks }: Props) {
  const { id } = useParams<{ id: string }>();
  const nav = useNavigate();
  const [pinnedField, setPinnedField] = useState<string | null>(null);

  const rows = buildRows(lc, invoice);
  const ruleById = new Map(checks.map((c) => [c.rule_id, c]));
  const scored = rows.map((r) => scoreRow(r, ruleById));

  const totals = {
    match: scored.filter((r) => r.tone === 'match').length,
    mismatch: scored.filter((r) => r.tone === 'mismatch').length,
    missing: scored.filter((r) => r.tone === 'missing').length,
    unable: scored.filter((r) => r.tone === 'unable').length,
  };

  const focusRule = (ruleId: string) => {
    if (!id) return;
    nav(`/session/${id}?step=rules&rule=${ruleId}`);
  };

  const running = !lc || !invoice;

  return (
    <div className="px-6 py-6">
      <div className="max-w-[1200px] mx-auto">
        {/* Header */}
        <div className="flex items-baseline justify-between mb-4">
          <div>
            <h3 className="font-serif text-xl text-navy-1">LC ↔ Invoice field comparison</h3>
            <div className="text-xs text-muted mt-0.5">
              Verdict per row reflects the rule outcome when one is available;
              otherwise a normalized string compare. Click any rule chip to jump
              to its evidence.
            </div>
          </div>
          <div className="flex items-center gap-2 text-[11px] font-mono">
            <Chip tone="match"    n={totals.match}    label="Match" />
            <Chip tone="mismatch" n={totals.mismatch} label="Mismatch" />
            <Chip tone="missing"  n={totals.missing}  label="Missing" />
            <Chip tone="unable"   n={totals.unable}   label="Unverified" />
          </div>
        </div>

        {running && (
          <div className="mb-3 text-xs text-muted italic">
            {lc ? 'Waiting for invoice extraction…' : 'Waiting for LC parse…'}
          </div>
        )}

        {/* Table */}
        <div className="bg-paper rounded-card border border-line overflow-hidden">
          {groupedSections(scored).map((section) => (
            <Section
              key={section.title}
              title={section.title}
              rows={section.rows}
              pinnedField={pinnedField}
              onPin={setPinnedField}
              onRule={focusRule}
              ruleById={ruleById}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── section ────────────────────────────────────────────────────────────────

function Section({
  title,
  rows,
  pinnedField,
  onPin,
  onRule,
  ruleById,
}: {
  title: string;
  rows: ScoredRow[];
  pinnedField: string | null;
  onPin: (field: string | null) => void;
  onRule: (id: string) => void;
  ruleById: Map<string, CheckResult>;
}) {
  return (
    <section>
      <div className="bg-line px-4 py-2 flex items-center gap-3 border-t border-line first:border-t-0">
        <span className="inline-block w-1 h-3 bg-teal-1" />
        <span className="font-mono text-[11px] uppercase tracking-[0.2em] font-semibold text-navy-1">
          {title}
        </span>
        <span className="font-mono text-[10px] text-muted ml-auto">
          {rows.length} field{rows.length !== 1 ? 's' : ''}
        </span>
      </div>
      <div>
        {rows.map((row) => (
          <Row
            key={row.label}
            row={row}
            pinned={pinnedField === row.label}
            onPin={() => onPin(pinnedField === row.label ? null : row.label)}
            onRule={onRule}
            ruleById={ruleById}
          />
        ))}
      </div>
    </section>
  );
}

// ─── row ────────────────────────────────────────────────────────────────────

function Row({
  row,
  pinned,
  onPin,
  onRule,
  ruleById,
}: {
  row: ScoredRow;
  pinned: boolean;
  onPin: () => void;
  onRule: (id: string) => void;
  ruleById: Map<string, CheckResult>;
}) {
  const stripeCls = pinned ? 'bg-teal-1' : stripeFor(row.tone);
  return (
    <div
      onClick={onPin}
      className={[
        'relative border-t border-line first:border-t-0 cursor-pointer transition',
        'hover:bg-slate2',
        pinned ? 'bg-teal-1/5 ring-1 ring-inset ring-teal-1/30' : '',
      ].join(' ')}
    >
      {/* left stripe */}
      <span className={`absolute left-0 top-0 bottom-0 w-[3px] ${stripeCls}`} />

      <div className="grid grid-cols-[200px_1fr_60px_140px] gap-4 px-5 py-3">
        {/* Field label */}
        <div className="text-sm text-navy-1 self-center">
          {row.label}
          {row.lcOnly && (
            <span className="ml-2 font-mono text-[9px] uppercase text-muted">
              lc-only
            </span>
          )}
        </div>

        {/* Stacked LC / INV values */}
        <div className="space-y-1 min-w-0">
          <ValueLine prefix="LC"  value={row.lcValue}  />
          <ValueLine prefix="INV" value={row.invValue} />
        </div>

        {/* Match icon */}
        <div className="self-center text-center">
          <MatchIcon tone={row.tone} />
        </div>

        {/* Rule chips */}
        <div className="self-center flex flex-wrap gap-1">
          {row.ruleIds.length === 0 ? (
            <span className="text-muted text-xs">—</span>
          ) : (
            row.ruleIds.map((rid) => {
              const res = ruleById.get(rid);
              return (
                <button
                  key={rid}
                  onClick={(e) => {
                    e.stopPropagation();
                    onRule(rid);
                  }}
                  className={[
                    'px-2 py-0.5 rounded font-mono text-[10px] border transition',
                    res ? ruleChipTone(res) : 'text-muted border-line bg-slate2',
                    'hover:underline',
                  ].join(' ')}
                  title={res?.rule_name ?? 'Not executed yet'}
                >
                  {rid}
                </button>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}

function ValueLine({ prefix, value }: { prefix: string; value: string | null }) {
  return (
    <div className="flex items-baseline gap-3 min-w-0">
      <span className="font-mono text-[10px] uppercase text-muted w-7 shrink-0 text-right tracking-widest">
        {prefix}
      </span>
      <span className="font-mono text-xs text-navy-1 truncate" title={value ?? ''}>
        {value === null || value === '' ? (
          <em className="text-muted italic">—</em>
        ) : (
          value
        )}
      </span>
    </div>
  );
}

// ─── visual helpers ─────────────────────────────────────────────────────────

function Chip({ tone, n, label }: { tone: Tone; n: number; label: string }) {
  const cls: Record<Tone, string> = {
    match: 'text-status-green',
    mismatch: 'text-status-red',
    missing: 'text-status-gold',
    unable: 'text-status-gold',
    na: 'text-muted',
  };
  return (
    <span className="inline-flex items-center gap-1 px-2 py-1 rounded border border-line bg-paper">
      <span className={`font-bold ${cls[tone]}`}>{n}</span>
      <span className="text-muted">{label}</span>
    </span>
  );
}

function MatchIcon({ tone }: { tone: Tone }) {
  const map: Record<Tone, { sym: string; cls: string }> = {
    match:    { sym: '✓', cls: 'text-status-green' },
    mismatch: { sym: '✗', cls: 'text-status-red' },
    missing:  { sym: '!', cls: 'text-status-gold' },
    unable:   { sym: '?', cls: 'text-status-gold' },
    na:       { sym: '·', cls: 'text-muted' },
  };
  const { sym, cls } = map[tone];
  return <span className={`font-mono text-base font-bold ${cls}`}>{sym}</span>;
}

function stripeFor(tone: Tone) {
  return {
    match: 'bg-status-green/40',
    mismatch: 'bg-status-red',
    missing: 'bg-status-gold',
    unable: 'bg-status-gold/60',
    na: 'bg-line',
  }[tone];
}

function ruleChipTone(res: CheckResult): string {
  switch (res.status) {
    case 'PASS':       return 'text-status-green border-status-green/30 bg-status-greenSoft';
    case 'DISCREPANT': return 'text-status-red border-status-red/30 bg-status-redSoft';
    case 'UNABLE_TO_VERIFY': return 'text-status-gold border-status-gold/30 bg-status-goldSoft';
    case 'NOT_APPLICABLE':   return 'text-muted border-line bg-slate2';
    default:                  return 'text-muted border-line bg-slate2';
  }
}

// ─── data ───────────────────────────────────────────────────────────────────

type Row = {
  group: string;
  label: string;
  lcValue: string | null;
  invValue: string | null;
  ruleIds: string[];
  lcOnly?: boolean;
};

type ScoredRow = Row & { tone: Tone };

function groupedSections(rows: ScoredRow[]) {
  const out: Array<{ title: string; rows: ScoredRow[] }> = [];
  for (const row of rows) {
    if (out.length === 0 || out[out.length - 1].title !== row.group) {
      out.push({ title: row.group, rows: [row] });
    } else {
      out[out.length - 1].rows.push(row);
    }
  }
  return out;
}

function buildRows(lc: LcDocument | undefined, inv: InvoiceDocument | undefined): Row[] {
  const L = lc ?? ({} as LcDocument);
  const I = inv ?? ({} as InvoiceDocument);
  return [
    { group: 'Amount', label: 'Currency',
      lcValue: L.currency ?? null,
      invValue: I.currency ?? null,
      ruleIds: ['INV-001'] },
    { group: 'Amount', label: 'Total amount',
      lcValue: fmtAmount(L.amount ?? null, L.currency ?? null),
      invValue: fmtAmount(I.total_amount ?? null, I.currency ?? null),
      ruleIds: ['INV-011'] },
    { group: 'Amount', label: 'Tolerance',
      lcValue: (L.tolerance_plus || L.tolerance_minus)
        ? `+${L.tolerance_plus ?? 0}% / -${L.tolerance_minus ?? 0}%`
        : null,
      invValue: null,
      ruleIds: ['INV-011'],
      lcOnly: true },

    { group: 'Parties', label: 'Applicant / Buyer',
      lcValue: L.applicant_name ?? null,
      invValue: I.buyer_name ?? null,
      ruleIds: ['INV-004'] },
    { group: 'Parties', label: 'Beneficiary / Seller',
      lcValue: L.beneficiary_name ?? null,
      invValue: I.seller_name ?? null,
      ruleIds: ['INV-003'] },

    { group: 'Goods', label: 'Description',
      lcValue: L.field_45_a_raw ?? null,
      invValue: I.goods_description ?? null,
      ruleIds: ['INV-002'] },
    { group: 'Goods', label: 'Quantity',
      lcValue: null,
      invValue: I.quantity != null ? String(I.quantity) : null,
      ruleIds: ['INV-002'] },
    { group: 'Goods', label: 'Unit price',
      lcValue: null,
      invValue: I.unit_price != null ? String(I.unit_price) : null,
      ruleIds: ['INV-009'] },

    { group: 'Shipment', label: 'Port of loading',
      lcValue: L.port_of_loading ?? null,
      invValue: I.port_of_loading ?? null,
      ruleIds: ['INV-008'] },
    { group: 'Shipment', label: 'Port of discharge',
      lcValue: L.port_of_discharge ?? null,
      invValue: I.port_of_discharge ?? null,
      ruleIds: ['INV-008'] },
    { group: 'Shipment', label: 'Latest shipment',
      lcValue: L.latest_shipment_date ?? null,
      invValue: null,
      ruleIds: ['INV-010'],
      lcOnly: true },

    { group: 'Documents', label: 'LC reference',
      lcValue: L.lc_number ?? null,
      invValue: I.lc_reference ?? null,
      ruleIds: ['INV-005'] },
    { group: 'Documents', label: 'Signed',
      lcValue: 'required (per :46A:)',
      invValue: I.signed == null ? null : I.signed ? 'yes' : 'no',
      ruleIds: ['INV-007'] },
    { group: 'Documents', label: 'Trade terms',
      lcValue: null,
      invValue: I.trade_terms ?? null,
      ruleIds: ['INV-006'] },
    { group: 'Documents', label: 'Country of origin',
      lcValue: null,
      invValue: I.country_of_origin ?? null,
      ruleIds: ['INV-012'] },
  ];
}

function scoreRow(row: Row, ruleById: Map<string, CheckResult>): ScoredRow {
  for (const id of row.ruleIds) {
    const res = ruleById.get(id);
    if (!res) continue;
    const tone = toneFromStatus(res.status);
    if (tone) return { ...row, tone };
  }
  if (row.lcOnly) {
    return { ...row, tone: row.lcValue ? 'na' : 'missing' };
  }
  const lcN = norm(row.lcValue);
  const invN = norm(row.invValue);
  if (!lcN && !invN) return { ...row, tone: 'na' };
  if (!lcN || !invN) return { ...row, tone: 'missing' };
  return { ...row, tone: lcN === invN ? 'match' : 'mismatch' };
}

function toneFromStatus(status: CheckResult['status']): Tone | null {
  switch (status) {
    case 'PASS': return 'match';
    case 'DISCREPANT': return 'mismatch';
    case 'UNABLE_TO_VERIFY': return 'unable';
    case 'NOT_APPLICABLE': return 'na';
    default: return null;
  }
}

function norm(v: string | number | null | undefined): string {
  if (v === null || v === undefined) return '';
  return String(v).trim().toLowerCase();
}

function fmtAmount(v: string | number | null | undefined, ccy: string | null): string | null {
  if (v === null || v === undefined || v === '') return null;
  const n = typeof v === 'number' ? v : Number(v);
  if (Number.isNaN(n)) return String(v);
  const prefix = ccy ? `${ccy} ` : '';
  return prefix + n.toLocaleString('en-US', { minimumFractionDigits: 2 });
}
