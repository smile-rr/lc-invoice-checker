import { useState } from 'react';
import type { CheckResult, CheckStatus, RuleSummary } from '../../types';

interface Props {
  check: CheckResult;
  rule: RuleSummary | undefined;
  failuresOnly: boolean;
  onAuthorityClick?: (ref: string) => void;
}

const METHOD_LABEL: Record<string, string> = {
  PROGRAMMATIC: 'Deterministic',
  AGENT:        'LLM agent',
};

// ─── Surface layering (Apple HIG "Materials" / IBM Carbon "Layer" pattern) ───
//
// Each card has 4 visual zones from top to bottom:
//   1. Header   — status-tinted, full opacity → instant triage colour signal
//   2. Meta     — white (paper), subtle separator
//   3. Data     — slate header row + white data cells (table pattern)
//   4. Result   — strongly tinted, thick status border → the verdict
//   5. Authority — slate, recedes (reference material, not primary)
//
// Key rule: every zone uses FULL opacity backgrounds. Semi-transparent tints
// blend into white and become invisible — that was the prior readability problem.

const HEADER_BG: Record<CheckStatus, string> = {
  DISCREPANT:            'bg-status-redSoft',       // clearly red-tinted
  PASS:                  'bg-status-greenSoft/50',   // clearly green-tinted
  UNABLE_TO_VERIFY:      'bg-status-goldSoft/60',
  REQUIRES_HUMAN_REVIEW: 'bg-status-goldSoft/60',
  HUMAN_REVIEW:          'bg-status-goldSoft/60',
  NOT_APPLICABLE:        'bg-slate2',
};

export function RuleCard({ check, rule, failuresOnly, onAuthorityClick }: Props) {
  if (failuresOnly && check.status === 'PASS') {
    return <CollapsedPassRow check={check} />;
  }

  const tone     = statusTone(check.status);
  const method   = METHOD_LABEL[check.check_type ?? rule?.check_type ?? 'A'] ?? '—';
  const phase    = (rule?.business_phase ?? check.business_phase ?? '—').toLowerCase();
  const ucpRef   = check.ucp_ref ?? rule?.ucp_ref ?? null;
  const isbpRef  = check.isbp_ref ?? rule?.isbp_ref ?? null;
  const headerBg = HEADER_BG[check.status] ?? 'bg-slate2';

  return (
    <article
      id={`rule-${check.rule_id}`}
      className={[
        'border border-line border-l-[4px] rounded-sm shadow-sm mb-5 scroll-mt-6',
        'overflow-hidden',          // keeps rounded corners on child backgrounds
        'target:animate-flash',
        tone.borderLeft,
      ].join(' ')}
    >
      {/* ── Zone 1: Header ── status-tinted for instant triage ─────────────── */}
      <header className={`flex items-center gap-3 px-4 py-2.5 border-b border-line ${headerBg}`}>
        {/* Rule ID — monospace (it IS a code) */}
        <span className="font-mono text-xs font-bold text-navy-1 shrink-0 min-w-[72px]">
          {check.rule_id}
        </span>
        {/* Rule name — sans, slightly larger, flex fills remaining width */}
        <span className="font-sans text-[14px] font-medium text-navy-1 flex-1 min-w-0 truncate">
          {check.rule_name ?? rule?.name ?? check.rule_id}
        </span>
        {/* Status badge */}
        <span
          className={[
            'shrink-0 inline-flex items-center gap-1.5',
            'font-mono text-[11px] uppercase tracking-[0.09em] font-bold',
            'px-2.5 py-1 rounded-sm',
            tone.badge,
          ].join(' ')}
        >
          {tone.glyph} {statusShort(check.status)}
        </span>
        {check.severity && (
          <span
            className={[
              'shrink-0 font-sans text-[11px] uppercase tracking-[0.07em] font-semibold',
              'px-2 py-0.5 rounded-sm border',
              check.severity === 'MAJOR'
                ? 'border-status-red/50 text-status-red bg-status-redSoft/50'
                : 'border-line text-muted',
            ].join(' ')}
          >
            {check.severity}
          </span>
        )}
      </header>

      {/* ── Zone 2: Meta breadcrumb ─────────────────────────────────────────── */}
      {/* font-sans, solid muted (no opacity reduction) so it's actually readable */}
      <div className="px-4 py-2 border-b border-line bg-paper flex flex-wrap items-center gap-x-2 gap-y-1 font-sans text-[11px] text-muted">
        <span className="uppercase tracking-[0.05em]">{phase}</span>
        <span aria-hidden className="text-line select-none">›</span>
        <span>{method}</span>
        {(ucpRef || isbpRef) && (
          <>
            <span aria-hidden className="text-line select-none">›</span>
            {ucpRef  && <CitationChip ref_={ucpRef}  onClick={onAuthorityClick} />}
            {isbpRef && <CitationChip ref_={isbpRef} onClick={onAuthorityClick} />}
          </>
        )}
      </div>

      {/* ── Zone 3: Data grid — table pattern ───────────────────────────────── */}
      {/*
          Column header row uses bg-slate2 (off-white) to look like <thead>.
          Data cells are white.
          This is the same "layer" pattern used in Apple's tables and IBM Carbon.
      */}
      <DataGrid check={check} tone={tone} />

      {/* ── Zone 5: Authority footnote — recedes with slate bg ──────────────── */}
      <AuthorityFootnote rule={rule} ucpRef={ucpRef} isbpRef={isbpRef} />

      {/* AGENT prompt/response audit pane is provided by /trace replay; the
          live card no longer carries the per-rule trace inline. */}
    </article>
  );
}

// ---------------------------------------------------------------------------
// Citation chip
// ---------------------------------------------------------------------------

function CitationChip({ ref_, onClick }: { ref_: string; onClick?: (ref: string) => void }) {
  const cls = 'font-mono text-[11px] text-teal-1 hover:text-teal-2 hover:underline underline-offset-2';
  if (!onClick) return <span className={`font-mono text-[11px] text-teal-1`}>{ref_}</span>;
  return (
    <button type="button" onClick={() => onClick(ref_)} className={cls} title={`Filter to ${ref_}`}>
      {ref_}
    </button>
  );
}

// ---------------------------------------------------------------------------
// Data grid — table-header pattern + result row
// ---------------------------------------------------------------------------

function DataGrid({ check, tone }: { check: CheckResult; tone: ReturnType<typeof statusTone> }) {
  return (
    <div className="border-b border-line">

      {/* Column header row — slate bg = visual table-header signal */}
      <div className="grid grid-cols-2 bg-slate2 border-b border-line">
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

      {/* Data cells — both white, clear divider between them */}
      <div className="grid grid-cols-1 md:grid-cols-2 bg-paper">
        <div className="px-4 py-3">
          <Value value={check.lc_value} />
        </div>
        <div className="px-4 py-3 border-t border-line md:border-t-0 md:border-l">
          <Value value={check.presented_value} />
        </div>
      </div>

      {/* ── Zone 4: Result — the verdict, most important zone ─────────────── */}
      {/*
          Thick top border in status colour + full-opacity tinted background.
          "Most important" means strongest visual weight — you should see this
          section first when scanning a card.
      */}
      <div className={`col-span-2 px-4 py-3.5 ${tone.resultBorder} ${tone.resultBg}`}>
        <div className="flex items-center gap-2 mb-2">
          <span className={`font-sans text-[11px] uppercase tracking-[0.10em] font-bold ${tone.resultLabel}`}>
            Result
          </span>
          <span className={`font-mono text-[11px] font-bold ${tone.resultLabel}`}>
            {tone.glyph} {statusShort(toneStatus(tone))}
          </span>
        </div>
        {/* Description — the verdict prose. 14px sans, navy, readable. */}
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
// Authority footnote — clearly separated by full-opacity slate background
// ---------------------------------------------------------------------------

function AuthorityFootnote({
  rule, ucpRef, isbpRef,
}: {
  rule: RuleSummary | undefined;
  ucpRef: string | null;
  isbpRef: string | null;
}) {
  const [open, setOpen] = useState(false);
  const ucpEx    = rule?.ucp_excerpt ?? null;
  const isbpEx   = rule?.isbp_excerpt ?? null;
  if (!ucpRef && !isbpRef) return null;
  const hasExcerpt = !!(ucpEx || isbpEx);

  return (
    // Full-opacity slate2 — clearly visually separated from the white data cells.
    // No border-t here: the DataGrid wrapper already provides border-b, adding
    // border-t would create a visually thicker double-line.
    <div className="px-4 py-2.5 bg-slate2">
      <button
        type="button"
        onClick={() => hasExcerpt && setOpen((v) => !v)}
        disabled={!hasExcerpt}
        className={[
          'flex items-center gap-2 font-sans text-[11px]',
          hasExcerpt ? 'text-muted hover:text-navy-1 cursor-pointer' : 'text-muted',
        ].join(' ')}
      >
        {hasExcerpt && <span aria-hidden className="text-xs">{open ? '▾' : '▸'}</span>}
        <span className="font-semibold uppercase tracking-[0.08em]">Authority</span>
        {ucpRef  && <span className="font-mono text-navy-1">{ucpRef}</span>}
        {ucpRef && isbpRef && <span aria-hidden className="text-muted">·</span>}
        {isbpRef && <span className="font-mono text-navy-1">{isbpRef}</span>}
      </button>
      {open && hasExcerpt && (
        <dl className="mt-3 grid gap-2 animate-fadein">
          {ucpEx  && <ExcerptRow ref_={ucpRef!}  text={ucpEx}  />}
          {isbpEx && <ExcerptRow ref_={isbpRef!} text={isbpEx} />}
        </dl>
      )}
    </div>
  );
}

function ExcerptRow({ ref_, text }: { ref_: string; text: string }) {
  return (
    <div className="grid grid-cols-[140px_1fr] gap-3">
      <dt className="font-mono text-[11px] text-teal-1 pt-0.5 font-semibold">{ref_}</dt>
      <dd className="font-sans text-xs text-navy-1 leading-snug">{text}</dd>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Collapsed pass row
// ---------------------------------------------------------------------------

function CollapsedPassRow({ check }: { check: CheckResult }) {
  return (
    <a
      href={`#rule-${check.rule_id}`}
      id={`rule-${check.rule_id}`}
      className="flex items-center gap-3 px-4 py-2 border-l-4 border-status-green/60 mb-1.5 bg-status-greenSoft/40 hover:bg-status-greenSoft/70 transition-colors no-underline rounded-sm"
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
    case 'PASS':                  return 'Pass';
    case 'DISCREPANT':            return 'Discrepant';
    case 'UNABLE_TO_VERIFY':      return 'Unable';
    case 'NOT_APPLICABLE':        return 'N/A';
    case 'HUMAN_REVIEW':
    case 'REQUIRES_HUMAN_REVIEW': return 'Review';
  }
}

function statusTone(s: CheckStatus): {
  badge:        string;
  borderLeft:   string;
  glyph:        string;
  resultBg:     string;
  resultBorder: string;   // thick status-coloured top border for the result zone
  resultLabel:  string;
} {
  switch (s) {
    case 'PASS':
      return {
        badge:        'bg-status-greenSoft text-status-green border border-status-green/40',
        borderLeft:   'border-l-status-green',
        glyph:        '✓',
        resultBg:     'bg-status-greenSoft/70',
        resultBorder: 'border-t-2 border-status-green/40',
        resultLabel:  'text-status-green',
      };
    case 'DISCREPANT':
      return {
        badge:        'bg-status-redSoft text-status-red border border-status-red/40',
        borderLeft:   'border-l-status-red',
        glyph:        '✗',
        // Full-opacity redSoft — clearly visible, no guessing
        resultBg:     'bg-status-redSoft',
        resultBorder: 'border-t-2 border-status-red/50',
        resultLabel:  'text-status-red',
      };
    case 'UNABLE_TO_VERIFY':
      return {
        badge:        'bg-status-goldSoft text-status-gold border border-status-gold/40',
        borderLeft:   'border-l-status-gold',
        glyph:        '?',
        resultBg:     'bg-status-goldSoft',
        resultBorder: 'border-t-2 border-status-gold/40',
        resultLabel:  'text-status-gold',
      };
    case 'NOT_APPLICABLE':
      return {
        badge:        'bg-slate2 text-muted border border-line',
        borderLeft:   'border-l-line',
        glyph:        '·',
        resultBg:     'bg-slate2',
        resultBorder: 'border-t border-line',
        resultLabel:  'text-muted',
      };
    case 'HUMAN_REVIEW':
    case 'REQUIRES_HUMAN_REVIEW':
      return {
        badge:        'bg-status-goldSoft text-status-gold border border-status-gold/40',
        borderLeft:   'border-l-status-gold',
        glyph:        '⚠',
        resultBg:     'bg-status-goldSoft',
        resultBorder: 'border-t-2 border-status-gold/40',
        resultLabel:  'text-status-gold',
      };
  }
}

function toneStatus(_tone: ReturnType<typeof statusTone>): CheckStatus {
  switch (_tone.glyph) {
    case '✓': return 'PASS';
    case '✗': return 'DISCREPANT';
    case '?': return 'UNABLE_TO_VERIFY';
    case '⚠': return 'REQUIRES_HUMAN_REVIEW';
    default:  return 'NOT_APPLICABLE';
  }
}
