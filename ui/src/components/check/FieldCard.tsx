/**
 * Field-level card for the invoice-field grouping view.
 *
 * One card per invoice field (F01–F17). Rules belonging to the same field
 * are MERGED into a single card:
 *   - Top-right badge: aggregated verdict (worst of all sub-rules)
 *   - Ref row: all UCP/ISBP refs merged and deduplicated → CitationPopover chips
 *   - Data grid: LC | Invoice columns, values grouped by output_field key (deduped)
 *   - Verdict: all sub-rule descriptions appended
 *
 * NOT_INCLUDED / EMBEDDED fields render their existing placeholder.
 */
import type { BusinessPhase, CheckStatus } from '../../types';
import type { FieldResult, SubRuleEntry } from '../../hooks/useInvoiceFieldView';
import { CitationPopover } from './CitationPopover';
import { UCP_ISBP_EXCERPTS } from '../../data/ucpIsbpExcerpts';
import type { DrawerTarget } from './SourceDrawer';

interface Props {
  fieldResult: FieldResult;
  failuresOnly: boolean;
  onViewSource?: (target: DrawerTarget) => void;
}

const METHOD_LABEL: Record<string, string> = {
  PROGRAMMATIC: 'Deterministic',
  AGENT:        'LLM agent',
};

const TYPE_LABEL: Record<string, string> = {
  PROGRAMMATIC: 'PROG',
  AGENT:        'AGENT',
  'AGENT+TOOL': 'AGENT+',
  MIXED:        'MIXED',
  EMBEDDED:     'EMBD',
  NOT_INCLUDED: 'N/I',
};

const STATUS_SHORT: Record<CheckStatus, string> = {
  PASS: 'Pass',
  FAIL: 'Fail',
  DOUBTS: 'Doubts',
  NOT_REQUIRED: 'Not req.',
};

function verdictTone(s: CheckStatus) {
  switch (s) {
    case 'PASS':        return { badge: 'bg-status-greenSoft text-status-green border-status-green/40', borderLeft: 'border-l-status-green', glyph: '✓', resultLabel: 'text-status-green' };
    case 'FAIL':        return { badge: 'bg-status-redSoft text-status-red border-status-red/40',     borderLeft: 'border-l-status-red',     glyph: '✗', resultLabel: 'text-status-red' };
    case 'DOUBTS':      return { badge: 'bg-status-goldSoft text-status-gold border-status-gold/40', borderLeft: 'border-l-gold',         glyph: '?', resultLabel: 'text-status-gold' };
    case 'NOT_REQUIRED': return { badge: 'bg-slate2 text-muted border-line',                          borderLeft: 'border-l-line',           glyph: '·', resultLabel: 'text-muted' };
  }
}

// ─── Ref excerpt normalization ─────────────────────────────────────────────────

/**
 * Try multiple key forms so that runtime ref strings from the backend match
 * entries in UCP_ISBP_EXCERPTS even with minor formatting differences.
 */
function getExcerpt(ref: string): string | null {
  if (!ref) return null;
  const trimmed = ref.trim();
  if (UCP_ISBP_EXCERPTS[trimmed]) return UCP_ISBP_EXCERPTS[trimmed];

  // Try with/without trailing period on "Art." and "Para."
  const normalise = (s: string) =>
    s.replace(/Art\.\s*/g, 'Art.').replace(/Para\.\s*/g, 'Para.');
  if (UCP_ISBP_EXCERPTS[normalise(trimmed)]) return UCP_ISBP_EXCERPTS[normalise(trimmed)];

  // Try abbreviated form: "UCP 600 Art. 18(c)" → "UCP 18(c)"
  const abbrev = trimmed.replace(/UCP 600 (Art\.)/g, 'UCP $1').replace(/ISBP 821 (Para\.)/g, 'ISBP $1');
  if (UCP_ISBP_EXCERPTS[abbrev]) return UCP_ISBP_EXCERPTS[abbrev];

  return null;
}

// ─── Deduplication helpers ─────────────────────────────────────────────────────

/** Collect all unique UCP + ISBP refs from sub-rules. */
function mergedRefs(subRules: SubRuleEntry[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const sr of subRules) {
    if (sr.ucpRef && !seen.has(sr.ucpRef)) { seen.add(sr.ucpRef); out.push(sr.ucpRef); }
    if (sr.isbpRef && !seen.has(sr.isbpRef)) { seen.add(sr.isbpRef); out.push(sr.isbpRef); }
  }
  return out;
}

// ─── Value merging — all sub-rules merged, split, deduplicated ────────────────────

/**
 * Merge all sub-rules' values into one block, split by line, dedupe.
 * Output: one merged string per column (LC / Invoice).
 */
function mergeFieldValues(
  subRules: SubRuleEntry[],
  which: 'lc' | 'invoice',
): string {
  const allLines: string[] = [];
  const seen = new Set<string>();
  for (const sr of subRules) {
    const raw = which === 'lc' ? sr.lcValue : sr.presentedValue;
    if (!raw) continue;
    for (const line of raw.split('\n')) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      if (!seen.has(trimmed)) {
        seen.add(trimmed);
        allLines.push(trimmed);
      }
    }
  }
  return allLines.join('\n');
}

// ─── Main component ────────────────────────────────────────────────────────────

export function FieldCard({ fieldResult, failuresOnly, onViewSource }: Props) {
  const { fieldDef, verdict, subRules, pendingRuleIds } = fieldResult;
  const tone = verdict ? verdictTone(verdict) : null;
  const hasPending = pendingRuleIds.length > 0;

  // ── NOT_INCLUDED ────────────────────────────────────────────────────────────
  if (fieldDef.type === 'NOT_INCLUDED') {
    return (
      <article
        id={`field-${fieldDef.id}`}
        className="border border-dashed border-line/60 border-l-[4px] border-l-muted/40 rounded-sm bg-paper mb-4"
      >
        <header className="flex items-center gap-3 px-4 py-2.5 border-b border-dashed border-line/60">
          <span className="font-mono text-[10px] font-bold text-muted/70 shrink-0">{fieldDef.id}</span>
          <span className="font-sans text-[13px] font-medium text-muted/70">{fieldDef.fieldName}</span>
          <div className="flex-1" />
          <span className="font-mono text-[10px] text-muted/50 uppercase tracking-widest">not included</span>
        </header>
        <div className="px-4 py-2.5 text-xs text-muted/60">
          {fieldDef.id === 'F12' && 'Requires Bill of Lading shipment date to activate.'}
          {fieldDef.id === 'F13' && 'Requires cross-document input (B/L, packing list, CoO) to activate.'}
          {fieldDef.id === 'F17' && 'Requires insurance document to activate.'}
        </div>
      </article>
    );
  }

  // ── EMBEDDED ─────────────────────────────────────────────────────────────────
  if (fieldDef.type === 'EMBEDDED') {
    return (
      <article
        id={`field-${fieldDef.id}`}
        className="border border-dashed border-line/60 border-l-[4px] border-l-teal-2/40 rounded-sm bg-paper mb-4"
      >
        <header className="flex items-center gap-3 px-4 py-2.5 border-b border-dashed border-line/60">
          <span className="font-mono text-[10px] font-bold text-muted/70 shrink-0">{fieldDef.id}</span>
          <span className="font-sans text-[13px] font-medium text-muted/70">{fieldDef.fieldName}</span>
          <div className="flex-1" />
          <span className="font-mono text-[10px] uppercase tracking-widest px-1.5 py-0.5 rounded border border-teal-2/30 bg-teal-2/10 text-teal-2">
            embedded
          </span>
        </header>
        <div className="px-4 py-2.5 text-xs text-muted/60">
          Applied by all rules via system prompt (ISBP A14/A15 — abbreviations and minor typos are tolerated).
        </div>
      </article>
    );
  }

  // ── Active field ─────────────────────────────────────────────────────────────
  const visibleSubRules = failuresOnly
    ? subRules.filter((s) => s.status === 'FAIL')
    : subRules;

  const refs = mergedRefs(subRules);
  const lcText = mergeFieldValues(subRules, 'lc');
  const invoiceText = mergeFieldValues(subRules, 'invoice');

  const first = subRules[0];
  // Phase from the first covering rule (now sourced from catalog, not a string guess).
  const phase = fieldResult.businessPhase;
  const method = first ? METHOD_LABEL[first.checkType ?? 'AGENT'] ?? '—' : null;

  const typeTag = fieldDef.type ? TYPE_LABEL[fieldDef.type] ?? fieldDef.type : '—';

  return (
    <article
      id={`field-${fieldDef.id}`}
      className={[
        'border border-line rounded-sm bg-paper mb-4 overflow-hidden',
        tone ? `${tone.borderLeft} border-l-[4px]` : 'border-l-[4px] border-l-line',
      ].join(' ')}
    >
      {/* ── Field header ── */}
      <header className="flex items-center gap-3 px-4 py-2.5 border-b border-line bg-paper flex-wrap">
        <span className="font-mono text-[11px] font-bold text-navy-1 shrink-0">{fieldDef.id}</span>
        <span className="font-sans text-[13px] font-medium text-navy-1 flex-1 min-w-0 truncate">
          {fieldDef.fieldName}
        </span>
        <span
          className="shrink-0 font-mono text-[10px] text-muted/70"
          title={fieldDef.refs}
        >
          {typeTag}
        </span>
      </header>

      {/* ── Phase / method + ref chips ── */}
      <div className="px-4 py-2 border-b border-line bg-paper flex flex-wrap items-center gap-x-2 gap-y-1">
        {phase && (
          <>
            <span className="font-sans text-[11px] uppercase tracking-[0.05em] text-muted">{phaseLabel(phase)}</span>
            <span aria-hidden className="text-line select-none">›</span>
          </>
        )}
        {method && (
          <span className="font-sans text-[11px] text-muted">{method}</span>
        )}
        {refs.length > 0 && (
          <>
            <span aria-hidden className="text-line select-none">›</span>
            {refs.map((ref) => (
              <CitationPopover
                key={ref}
                reference={ref}
                excerpt={getExcerpt(ref)}
              />
            ))}
          </>
        )}
      </div>

      {/* ── Data grid ── */}
      {lcText || invoiceText ? (
        <div className="border-b border-line">
          {/* Column headers */}
          <div className="grid grid-cols-2 bg-slate2/50 border-b border-line">
            <div className="px-4 py-1.5">
              <span className="font-sans text-[11px] uppercase tracking-[0.10em] font-semibold text-muted">LC</span>
            </div>
            <div className="px-4 py-1.5 border-l border-line">
              <span className="font-sans text-[11px] uppercase tracking-[0.10em] font-semibold text-muted">Invoice</span>
            </div>
          </div>
          {/* Data cells */}
          <MergedDataRows lcText={lcText} invoiceText={invoiceText} />
        </div>
      ) : null}

      {/* ── Verdict ── */}
      {visibleSubRules.length > 0 && (
        <div className="px-4 py-3 bg-paper">
          {visibleSubRules.map((sr, i) => {
            const srTone = verdictTone(sr.status);
            return (
              <div key={sr.ruleId} className={i > 0 ? 'mt-3 pt-3 border-t border-line/50' : ''}>
                <div className="flex items-baseline gap-2 mb-1 flex-wrap">
                  <span className={['shrink-0 font-mono text-[10px] uppercase tracking-[0.08em] font-bold px-1.5 py-0.5 rounded-sm border', srTone.badge].join(' ')}>
                    {STATUS_SHORT[sr.status]}
                  </span>
                  <span className="font-mono text-[11px] font-semibold text-navy-1">{sr.ruleId}</span>
                  {sr.ruleName && (
                    <span className="font-sans text-[11px] text-muted">{sr.ruleName}</span>
                  )}
                </div>
                {sr.description && (
                  <div className="font-sans text-sm text-navy-1 leading-relaxed">
                    {sr.description}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* ── Pending rules ── */}
      {hasPending && (
        <div className="px-4 py-3 border-t border-line/50 bg-slate2/20">
          <div className="flex items-center gap-2">
            <span className="w-1.5 h-1.5 rounded-full bg-muted/60 animate-pulse shrink-0" />
            <span className="font-mono text-[11px] text-muted">
              Waiting for: {pendingRuleIds.join(' · ')}
            </span>
          </div>
        </div>
      )}

      {/* ── Footer — View source (same position as RuleCard's CardFooter) ── */}
      {onViewSource && first && (
        <div className="px-4 py-2.5 bg-slate2/40 border-t border-line flex items-start gap-3">
          <div className="flex-1" />
          <button
            type="button"
            onClick={() => onViewSource({
              ruleId: first.ruleId,
              ruleName: first.ruleName ?? first.ruleId,
              lcEvidence: first.lcValue,
              invoiceEvidence: first.presentedValue,
            })}
            className="shrink-0 font-mono text-[11px] uppercase tracking-[0.08em] px-2.5 py-1 rounded-sm border border-line bg-paper text-navy-1 hover:border-teal-1/60 hover:text-teal-1 transition-colors"
            title="Open original LC + invoice with this rule's evidence highlighted"
          >
            View source
          </button>
        </div>
      )}
    </article>
  );
}

// ─── Merged data rows ─────────────────────────────────────────────────────────

function MergedDataRows({
  lcText,
  invoiceText,
}: {
  lcText: string;
  invoiceText: string;
}) {
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 bg-paper">
      {/* LC column */}
      <div className="px-4 py-3">
        {lcText
          ? lcText.split('\n').map((line, i) => (
              <div key={i} className="font-mono text-xs text-navy-1 leading-relaxed whitespace-pre-wrap break-words">{line}</div>
            ))
          : <span className="text-muted font-mono text-sm">—</span>
        }
      </div>

      {/* Invoice column */}
      <div className="px-4 py-3 border-t border-line md:border-t-0 md:border-l border-line">
        {invoiceText
          ? invoiceText.split('\n').map((line, i) => (
              <div key={i} className="font-mono text-xs text-navy-1 leading-relaxed whitespace-pre-wrap break-words">{line}</div>
            ))
          : <span className="text-muted font-mono text-sm">—</span>
        }
      </div>
    </div>
  );
}

// ─── Phase label ────────────────────────────────────────────────────────────────

const PHASE_LABEL: Record<BusinessPhase, string> = {
  PARTIES:    'Parties',
  MONEY:      'Money',
  GOODS:      'Goods',
  LOGISTICS:  'Logistics',
  PROCEDURAL: 'Procedural',
};

function phaseLabel(phase: string): string {
  return PHASE_LABEL[phase as BusinessPhase] ?? phase;
}
