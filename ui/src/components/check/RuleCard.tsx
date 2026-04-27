import type { CheckResult, CheckStatus, RuleSummary } from '../../types';
import { CitationPopover } from './CitationPopover';

interface Props {
  /** The completed check result. {@code null} means the rule hasn't run yet
   *  (e.g. its stage is still in flight) — card renders in pending state. */
  check: CheckResult | null;
  /** Catalog metadata for this rule. */
  rule: RuleSummary;
  failuresOnly: boolean;
}

const METHOD_LABEL: Record<string, string> = {
  PROGRAMMATIC: 'Deterministic',
  AGENT:        'LLM agent',
};

const CATEGORY_BADGE: Record<string, { label: string; cls: string }> = {
  PROGRAMMATIC: {
    label: 'PROG',
    cls:   'bg-teal-1/15 text-teal-2 border-teal-2/40',
  },
  AGENT: {
    label: 'AGENT',
    cls:   'bg-navy-1/10 text-navy-1 border-navy-1/40',
  },
};

function CategoryBadge({ kind }: { kind: string | null | undefined }) {
  const info = CATEGORY_BADGE[kind ?? 'PROGRAMMATIC'];
  if (!info) return null;
  return (
    <span
      className={[
        'shrink-0 inline-flex items-center font-mono text-[10px] uppercase tracking-[0.08em] font-bold',
        'px-1.5 py-0.5 rounded-sm border',
        info.cls,
      ].join(' ')}
      title={kind === 'AGENT' ? 'Agent rule (LLM call)' : 'Programmatic rule (deterministic SpEL)'}
    >
      {info.label}
    </span>
  );
}

// ─── Visual diet ─────────────────────────────────────────────────────────────
//
// The card is a NEUTRAL container — white background, muted text, generous
// whitespace. Status colour appears only where it earns attention:
//
//   • Status badge    (top-right of header)   — focal pill
//   • Border-left     (4-px coloured strip)   — quiet scroll-scan signal
//   • Verdict word    (in the Result zone)    — coloured prose
//
// Everything else (header background, data cells, authority footnote) stays
// neutral. This keeps the eye on the verdict, not on a competing wash of
// reds and greens.

export function RuleCard({ check, rule, failuresOnly }: Props) {
  if (check === null) {
    return <PendingRuleCard rule={rule} />;
  }
  if (failuresOnly && check.status === 'PASS') {
    return <CollapsedPassRow check={check} />;
  }

  const tone     = statusTone(check.status);
  const method   = METHOD_LABEL[check.check_type ?? rule.check_type ?? 'PROGRAMMATIC'] ?? '—';
  const phase    = (rule.business_phase ?? check.business_phase ?? '—').toLowerCase();
  const ucpRef   = check.ucp_ref ?? rule.ucp_ref ?? null;
  const isbpRef  = check.isbp_ref ?? rule.isbp_ref ?? null;

  return (
    <article
      id={`rule-${check.rule_id}`}
      className={[
        'border border-line border-l-[4px] rounded-sm bg-paper mb-4 scroll-mt-6',
        'overflow-hidden target:animate-flash',
        tone.borderLeft,
      ].join(' ')}
    >
      {/* Header — neutral white. Status badge is the only coloured element. */}
      <header className="flex items-center gap-3 px-4 py-2.5 border-b border-line bg-paper">
        <span className="font-mono text-xs font-bold text-navy-1 shrink-0 min-w-[72px]">
          {check.rule_id}
        </span>
        <CategoryBadge kind={check.check_type ?? rule.check_type} />
        <span className="font-sans text-[14px] font-medium text-navy-1 flex-1 min-w-0 truncate">
          {check.rule_name ?? rule.name ?? check.rule_id}
        </span>
        <span
          className={[
            'shrink-0 inline-flex items-center gap-1.5',
            'font-mono text-[11px] uppercase tracking-[0.09em] font-bold',
            'px-2.5 py-1 rounded-sm border',
            tone.badge,
          ].join(' ')}
        >
          {tone.glyph} {statusShort(check.status)}
        </span>
        {check.severity && check.status === 'FAIL' && (
          <span
            className={[
              'shrink-0 font-sans text-[11px] uppercase tracking-[0.07em] font-semibold',
              'px-2 py-0.5 rounded-sm border',
              check.severity === 'MAJOR'
                ? 'border-status-red/40 text-status-red'
                : 'border-line text-muted',
            ].join(' ')}
          >
            {check.severity}
          </span>
        )}
      </header>

      {/* Meta breadcrumb — phase / method / authority refs (muted). */}
      <div className="px-4 py-2 border-b border-line bg-paper flex flex-wrap items-center gap-x-2 gap-y-1 font-sans text-[11px] text-muted">
        <span className="uppercase tracking-[0.05em]">{phase}</span>
        <span aria-hidden className="text-line select-none">›</span>
        <span>{method}</span>
        {(ucpRef || isbpRef) && (
          <>
            <span aria-hidden className="text-line select-none">›</span>
            {ucpRef  && <CitationPopover reference={ucpRef}  excerpt={rule.ucp_excerpt} />}
            {isbpRef && <CitationPopover reference={isbpRef} excerpt={rule.isbp_excerpt} />}
          </>
        )}
      </div>

      {/* Data + result. The reference chips above carry the rule basis via
          their own popover, so no separate authority footnote here. */}
      <DataGrid check={check} tone={tone} />
    </article>
  );
}

// ---------------------------------------------------------------------------
// Pending card — same neutral frame, dashed border + "Waiting…" badge
// ---------------------------------------------------------------------------

function PendingRuleCard({ rule }: { rule: RuleSummary }) {
  const phase = (rule.business_phase ?? '—').toLowerCase();
  const method = METHOD_LABEL[rule.check_type ?? 'PROGRAMMATIC'] ?? '—';
  const ucpRef = rule.ucp_ref ?? null;
  const isbpRef = rule.isbp_ref ?? null;
  return (
    <article
      id={`rule-${rule.id}`}
      className="border border-dashed border-line border-l-[4px] border-l-line/60 rounded-sm bg-paper mb-4"
    >
      <header className="flex items-center gap-3 px-4 py-2.5 border-b border-line bg-paper">
        <span className="font-mono text-xs font-bold text-navy-1 shrink-0 min-w-[72px]">
          {rule.id}
        </span>
        <CategoryBadge kind={rule.check_type} />
        <span className="font-sans text-[14px] font-medium text-navy-1 flex-1 min-w-0 truncate">
          {rule.name}
        </span>
        <span className="shrink-0 inline-flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.09em] font-bold px-2.5 py-1 rounded-sm bg-paper text-muted border border-line">
          <span className="w-1.5 h-1.5 rounded-full bg-muted/60 animate-pulse" />
          Waiting…
        </span>
      </header>
      <div className="px-4 py-2 border-b border-line bg-paper flex flex-wrap items-center gap-x-2 gap-y-1 font-sans text-[11px] text-muted">
        <span className="uppercase tracking-[0.05em]">{phase}</span>
        <span aria-hidden className="text-line select-none">›</span>
        <span>{method}</span>
        {(ucpRef || isbpRef) && (
          <>
            <span aria-hidden className="text-line select-none">›</span>
            {ucpRef  && <CitationPopover reference={ucpRef}  excerpt={rule.ucp_excerpt} />}
            {isbpRef && <CitationPopover reference={isbpRef} excerpt={rule.isbp_excerpt} />}
          </>
        )}
      </div>
      <div className="px-4 py-3 bg-paper text-sm text-muted italic">
        Pending verdict…
      </div>
    </article>
  );
}

// ---------------------------------------------------------------------------
// Data grid + result row
// ---------------------------------------------------------------------------

function DataGrid({ check, tone }: { check: CheckResult; tone: ReturnType<typeof statusTone> }) {
  return (
    <div className="border-b border-line">
      {/* Column header row */}
      <div className="grid grid-cols-2 bg-slate2/50 border-b border-line">
        <div className="px-4 py-1.5">
          <span className="font-sans text-[11px] uppercase tracking-[0.10em] font-semibold text-muted">
            LC
          </span>
        </div>
        <div className="px-4 py-1.5 border-l border-line">
          <span className="font-sans text-[11px] uppercase tracking-[0.10em] font-semibold text-muted">
            Invoice
          </span>
        </div>
      </div>

      {/* Data cells — neutral, no status tint */}
      <div className="grid grid-cols-1 md:grid-cols-2 bg-paper">
        <div className="px-4 py-3">
          <Value value={check.lc_value} />
        </div>
        <div className="px-4 py-3 border-t border-line md:border-t-0 md:border-l">
          <Value value={check.presented_value} />
        </div>
      </div>

      {/* Result row — neutral background; ONLY the verdict word is coloured. */}
      <div className="col-span-2 px-4 py-3 bg-paper border-t border-line">
        <div className="flex items-baseline gap-2 mb-1.5">
          <span className="font-sans text-[11px] uppercase tracking-[0.10em] font-semibold text-muted">
            Verdict
          </span>
          <span className={`font-mono text-[11px] font-bold ${tone.resultLabel}`}>
            {tone.glyph} {statusShort(check.status)}
          </span>
        </div>
        <div className="font-sans text-sm text-navy-1 leading-relaxed">
          {check.description
            ?? <span className="text-muted italic">No verdict text provided.</span>}
        </div>
      </div>
    </div>
  );
}

function Value({ value }: { value: string | null }) {
  if (!value) {
    return <span className="text-muted font-mono text-sm">—</span>;
  }
  return (
    <div className="font-mono text-sm text-navy-1 whitespace-pre-wrap break-words leading-relaxed">
      {value}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Collapsed pass row (used when failuresOnly is on)
// ---------------------------------------------------------------------------

function CollapsedPassRow({ check }: { check: CheckResult }) {
  return (
    <a
      href={`#rule-${check.rule_id}`}
      id={`rule-${check.rule_id}`}
      className="flex items-center gap-3 px-4 py-2 border-l-4 border-status-green/60 mb-1.5 bg-paper hover:bg-slate2 transition-colors no-underline rounded-sm"
    >
      <span className="font-mono text-[11px] uppercase font-bold text-status-green shrink-0">
        ✓ Pass
      </span>
      <span className="font-mono text-xs text-muted shrink-0">{check.rule_id}</span>
      <span className="font-sans text-xs text-navy-1 truncate">{check.rule_name}</span>
    </a>
  );
}

// ---------------------------------------------------------------------------
// Status helpers
// ---------------------------------------------------------------------------

function statusShort(s: CheckStatus): string {
  switch (s) {
    case 'PASS':         return 'Pass';
    case 'FAIL':         return 'Fail';
    case 'DOUBTS':       return 'Doubts';
    case 'NOT_REQUIRED': return 'Not required';
  }
}

function statusTone(s: CheckStatus): {
  badge:       string;
  borderLeft:  string;
  glyph:       string;
  resultLabel: string;
} {
  switch (s) {
    case 'PASS':
      return {
        badge:       'bg-status-greenSoft text-status-green border-status-green/40',
        borderLeft:  'border-l-status-green',
        glyph:       '✓',
        resultLabel: 'text-status-green',
      };
    case 'FAIL':
      return {
        badge:       'bg-status-redSoft text-status-red border-status-red/40',
        borderLeft:  'border-l-status-red',
        glyph:       '✗',
        resultLabel: 'text-status-red',
      };
    case 'DOUBTS':
      return {
        badge:       'bg-status-goldSoft text-status-gold border-status-gold/40',
        borderLeft:  'border-l-status-gold',
        glyph:       '?',
        resultLabel: 'text-status-gold',
      };
    case 'NOT_REQUIRED':
      return {
        badge:       'bg-slate2 text-muted border-line',
        borderLeft:  'border-l-line',
        glyph:       '·',
        resultLabel: 'text-muted',
      };
  }
}
