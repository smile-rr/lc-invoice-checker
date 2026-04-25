import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import type { CheckResult, CheckStatus } from '../../types';
import { lookupReg, type RegEntry } from '../../data/regs';

interface Props {
  checks: CheckResult[];
  inFlightRuleIds: Set<string>;
  activatedRuleIds?: string[];
}

type FilterKey = 'all' | 'fail' | 'pass' | 'unable' | 'na';

/**
 * Master-detail rule browser.
 *
 * Evidence panel was rebuilt: values now lead (you see the numbers first),
 * a single-sentence "at a glance" follows, and the UCP/ISBP citations render
 * with their plain-English snippet inline (click to expand for the longer
 * paraphrase). The verbose LLM description is preserved but demoted — it's
 * now optional reading, not the headline.
 */
export function RuleChecksTab({ checks, inFlightRuleIds, activatedRuleIds }: Props) {
  const { id: sessionId } = useParams<{ id: string }>();
  const nav = useNavigate();
  const [params] = useSearchParams();

  // URL-driven: ?rule=… for selection, ?filter=… as a deep-link hint.
  const focused = params.get('rule');
  const setFocused = (id: string | null) => {
    if (!sessionId) return;
    const p = new URLSearchParams(params);
    if (id) p.set('rule', id);
    else p.delete('rule');
    p.set('step', 'rules');
    nav(`/session/${sessionId}?${p.toString()}`);
  };

  const initialFilter = (params.get('filter') as FilterKey | null) ?? 'all';
  const [filter, setFilter] = useState<FilterKey>(
    ['all', 'fail', 'pass', 'unable', 'na'].includes(initialFilter) ? initialFilter : 'all',
  );
  const [query, setQuery] = useState('');

  const listRef = useRef<HTMLDivElement>(null);

  const counts = useMemo(() => countByStatus(checks), [checks]);
  const filtered = useMemo(
    () => checks.filter((c) => matchesFilter(c, filter) && matchesQuery(c, query)),
    [checks, filter, query],
  );

  const selected =
    focused ? checks.find((c) => c.rule_id === focused) ?? null : null;

  useEffect(() => {
    if (!focused || !listRef.current) return;
    const el = listRef.current.querySelector(`[data-rule-id="${focused}"]`);
    el?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [focused]);

  const running = inFlightRuleIds.size > 0;
  const totalExpected = activatedRuleIds?.length ?? checks.length + inFlightRuleIds.size;

  return (
    <div className="px-6 py-5">
      {/* Filter strip */}
      <div className="flex items-center gap-2 mb-3 flex-wrap">
        <FilterChip k="all"    label="All"    n={counts.all}    active={filter === 'all'}    onClick={setFilter} />
        <FilterChip k="fail"   label="Failed" n={counts.fail}   active={filter === 'fail'}   onClick={setFilter} tone="red" />
        <FilterChip k="pass"   label="Passed" n={counts.pass}   active={filter === 'pass'}   onClick={setFilter} tone="green" />
        <FilterChip k="unable" label="Unable" n={counts.unable} active={filter === 'unable'} onClick={setFilter} tone="gold" />
        <FilterChip k="na"     label="N/A"    n={counts.na}     active={filter === 'na'}     onClick={setFilter} tone="muted" />
        <div className="flex-1" />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search rule id, field, description…"
          className="text-sm px-3 py-1.5 rounded-btn border border-line bg-paper w-64 focus:border-teal-2 focus:outline-none"
        />
        {running && (
          <span className="text-xs text-muted font-mono flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-status-gold animate-blink" />
            {checks.length} / {totalExpected} · {inFlightRuleIds.size} running
          </span>
        )}
      </div>

      <div className="grid grid-cols-[minmax(360px,1fr)_minmax(0,2fr)] gap-5 min-h-[560px]">
        {/* LIST */}
        <div
          ref={listRef}
          className="bg-paper rounded-card border border-line divide-y divide-line overflow-auto max-h-[78vh]"
        >
          {filtered.length === 0 && !running && (
            <div className="p-6 text-center text-sm text-muted">
              No rules match the current filter.
            </div>
          )}
          {filtered.map((c) => (
            <RuleRow
              key={c.rule_id}
              check={c}
              selected={c.rule_id === focused}
              onSelect={() => setFocused(c.rule_id)}
            />
          ))}
          {running &&
            Array.from(inFlightRuleIds).map((id) => (
              <div
                key={id}
                className="px-4 py-2.5 flex items-center gap-3 text-sm text-muted font-mono animate-fadein"
              >
                <span className="w-2 h-2 rounded-full bg-status-gold animate-blink" />
                <span>{id}</span>
                <span className="text-xs italic">running…</span>
              </div>
            ))}
        </div>

        {/* EVIDENCE */}
        <div className="bg-paper rounded-card border border-line p-6 min-h-full">
          {selected ? (
            <Evidence check={selected} />
          ) : (
            <EmptyEvidence hasChecks={checks.length > 0} />
          )}
        </div>
      </div>
    </div>
  );
}

// ─── list ───────────────────────────────────────────────────────────────────

function RuleRow({
  check,
  selected,
  onSelect,
}: {
  check: CheckResult;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      data-rule-id={check.rule_id}
      onClick={onSelect}
      className={[
        'w-full text-left px-4 py-2.5 flex items-center gap-3 hover:bg-slate2 animate-fadein',
        selected ? 'bg-teal-1/5 border-l-4 border-l-teal-1 -ml-[4px] pl-[calc(1rem-4px)]' : '',
      ].join(' ')}
    >
      <StatusIcon status={check.status} />
      <span className="font-mono text-[11px] text-muted w-20 shrink-0">{check.rule_id}</span>
      <span className="text-sm flex-1 truncate">{check.rule_name ?? check.description}</span>
      {check.severity && (
        <span className="font-mono text-[9px] uppercase text-muted">{check.severity}</span>
      )}
    </button>
  );
}

function StatusIcon({ status }: { status: CheckStatus }) {
  const map: Record<CheckStatus, { sym: string; cls: string }> = {
    PASS:                   { sym: '✓', cls: 'text-status-green' },
    DISCREPANT:             { sym: '✗', cls: 'text-status-red' },
    UNABLE_TO_VERIFY:       { sym: '?', cls: 'text-status-gold' },
    NOT_APPLICABLE:         { sym: '·', cls: 'text-muted' },
    HUMAN_REVIEW:           { sym: '!', cls: 'text-status-gold' },
    REQUIRES_HUMAN_REVIEW:  { sym: '!', cls: 'text-status-gold' },
  };
  const { sym, cls } = map[status] ?? { sym: '·', cls: 'text-muted' };
  return <span className={`font-mono font-bold w-5 text-center ${cls}`}>{sym}</span>;
}

// ─── evidence ───────────────────────────────────────────────────────────────

function Evidence({ check }: { check: CheckResult }) {
  const refs = collectRefs(check);

  return (
    <div className="space-y-5">
      {/* Header — slim. Field name (technical) is appended to the title as a
           muted mono badge when present, so we don't repeat it as a separate
           footer stanza below. */}
      <header className="border-b border-line pb-3">
        <div className="flex items-center gap-3 mb-1">
          <StatusBadge status={check.status} />
          {check.severity && (
            <span className="font-mono text-[10px] uppercase tracking-widest text-muted">
              {check.severity}
            </span>
          )}
          <span className="font-mono text-xs text-muted">{check.rule_id}</span>
          {check.check_type && (
            <span className="ml-auto font-mono text-[10px] text-muted">
              {check.check_type === 'A' && 'Deterministic'}
              {check.check_type === 'B' && 'Semantic (LLM)'}
              {check.check_type === 'AB' && 'Deterministic + LLM'}
              {check.check_type === 'SPI' && 'SPI'}
            </span>
          )}
        </div>
        <h2 className="font-serif text-xl text-navy-1 leading-tight">
          {check.rule_name ?? 'Unnamed rule'}
          {check.field && (
            <span className="ml-2 font-mono text-[11px] font-normal text-muted bg-slate2 px-1.5 py-0.5 rounded align-middle">
              {check.field}
            </span>
          )}
        </h2>
      </header>

      {/* Values — top of the evidence stack */}
      <div className="grid grid-cols-2 gap-4">
        <ValueBlock prefix="LC"  value={check.lc_value}        source="from MT700" />
        <ValueBlock prefix="INV" value={check.presented_value} source="from invoice" />
      </div>

      {/* At a glance — single sentence */}
      {check.description && (
        <div className="rounded-card bg-slate2 px-4 py-3">
          <div className="text-[10px] uppercase tracking-[0.2em] text-muted mb-1">
            At a glance
          </div>
          <p className="text-sm leading-relaxed text-navy-1">{check.description}</p>
        </div>
      )}

      {/* Regulations */}
      {refs.length > 0 && (
        <div>
          <div className="text-[10px] uppercase tracking-[0.2em] text-muted mb-2">
            Governing references
          </div>
          <div className="space-y-1.5">
            {refs.map((r) => (
              <RegRow key={r.cite} cite={r.cite} entry={r.entry} />
            ))}
          </div>
        </div>
      )}

    </div>
  );
}

function ValueBlock({
  prefix,
  value,
  source,
}: {
  prefix: string;
  value: string | null;
  source: string;
}) {
  return (
    <div className="rounded-card bg-paper border border-line px-4 py-3">
      <div className="flex items-baseline justify-between">
        <span className="font-mono text-[10px] tracking-[0.2em] text-teal-1 font-semibold">
          {prefix}
        </span>
        <span className="font-mono text-[10px] text-muted/70">{source}</span>
      </div>
      <div className="font-mono text-base text-navy-1 mt-1.5 leading-snug break-words">
        {value === null || value === '' ? (
          <em className="text-muted italic text-sm">none</em>
        ) : (
          value
        )}
      </div>
    </div>
  );
}

function RegRow({ cite, entry }: { cite: string; entry: RegEntry | null }) {
  const [open, setOpen] = useState(false);
  if (!entry) {
    // Citation we don't have authored content for — render just the citation.
    return (
      <div className="rounded border border-line px-3 py-2 font-mono text-xs text-muted">
        {cite}
        <span className="ml-2 italic text-muted/60">(reference text not available)</span>
      </div>
    );
  }
  return (
    <div className="rounded border border-line bg-paper">
      <button
        onClick={() => setOpen(!open)}
        className="w-full text-left px-3 py-2 flex items-start gap-2 hover:bg-slate2"
      >
        <span className="font-mono text-muted shrink-0">{open ? '▾' : '▸'}</span>
        <div className="min-w-0">
          <div className="text-sm text-navy-1">
            <span className="font-mono text-[11px] text-teal-1 mr-2">{cite}</span>
            {entry.title.replace(`${cite} — `, '').replace(`${cite}`, '')}
          </div>
          {!open && (
            <div className="text-xs text-muted mt-0.5 leading-relaxed">{entry.snippet}</div>
          )}
        </div>
      </button>
      {open && (
        <div className="px-3 pb-3 pt-0 ml-6 -mt-1">
          <p className="text-sm text-navy-1 leading-relaxed">{entry.full}</p>
        </div>
      )}
    </div>
  );
}

function collectRefs(check: CheckResult): Array<{ cite: string; entry: RegEntry | null }> {
  const out: Array<{ cite: string; entry: RegEntry | null }> = [];
  if (check.ucp_ref) {
    splitCitations(check.ucp_ref).forEach((c) => out.push({ cite: c, entry: lookupReg(c) }));
  }
  if (check.isbp_ref) {
    splitCitations(check.isbp_ref).forEach((c) => out.push({ cite: c, entry: lookupReg(c) }));
  }
  return out;
}

/**
 * Backend may emit either a single citation ("UCP 600 Art. 18(b)") or a
 * compound one ("UCP 600 Art. 18(b) / Art. 30(b)"). Split them so each gets
 * its own row with its own snippet.
 */
function splitCitations(s: string): string[] {
  return s
    .split(/\s*[\/,;]\s*/)
    .map((p) => p.trim())
    .filter(Boolean);
}

// ─── empty / status / filter helpers ───────────────────────────────────────

function StatusBadge({ status }: { status: CheckStatus }) {
  const map: Record<CheckStatus, string> = {
    PASS:                   'bg-status-greenSoft text-status-green',
    DISCREPANT:             'bg-status-redSoft text-status-red',
    UNABLE_TO_VERIFY:       'bg-status-goldSoft text-status-gold',
    NOT_APPLICABLE:         'bg-slate2 text-muted',
    HUMAN_REVIEW:           'bg-status-goldSoft text-status-gold',
    REQUIRES_HUMAN_REVIEW:  'bg-status-goldSoft text-status-gold',
  };
  return (
    <span className={`px-2 py-1 rounded font-mono text-[11px] font-bold ${map[status] ?? ''}`}>
      {status}
    </span>
  );
}

function EmptyEvidence({ hasChecks }: { hasChecks: boolean }) {
  return (
    <div className="h-full grid place-items-center text-center">
      <div>
        <div className="font-serif text-xl text-navy-1 mb-1">
          {hasChecks ? 'Select a rule' : 'Waiting for checks…'}
        </div>
        <div className="text-sm text-muted max-w-sm">
          {hasChecks
            ? 'Pick a rule on the left to see the LC value, the presented value, and the UCP / ISBP reference text that governs it.'
            : 'Rules will appear here as Stage 3 runs.'}
        </div>
      </div>
    </div>
  );
}

function FilterChip({
  k,
  label,
  n,
  active,
  onClick,
  tone,
}: {
  k: FilterKey;
  label: string;
  n: number;
  active: boolean;
  onClick: (k: FilterKey) => void;
  tone?: 'red' | 'green' | 'gold' | 'muted';
}) {
  const tc: Record<string, string> = {
    red: 'text-status-red',
    green: 'text-status-green',
    gold: 'text-status-gold',
    muted: 'text-muted',
  };
  return (
    <button
      onClick={() => onClick(k)}
      className={[
        'px-3 py-1.5 rounded-btn text-xs inline-flex items-center gap-2 border transition',
        active
          ? 'bg-navy-1 text-white border-navy-1'
          : 'bg-paper text-navy-1 border-line hover:border-teal-2',
      ].join(' ')}
    >
      <span>{label}</span>
      <span
        className={[
          'font-mono font-bold',
          active ? 'text-white' : tone ? tc[tone] : 'text-muted',
        ].join(' ')}
      >
        {n}
      </span>
    </button>
  );
}

function countByStatus(checks: CheckResult[]) {
  const c = { all: checks.length, fail: 0, pass: 0, unable: 0, na: 0 };
  for (const r of checks) {
    if (r.status === 'PASS') c.pass++;
    else if (r.status === 'DISCREPANT') c.fail++;
    else if (r.status === 'NOT_APPLICABLE') c.na++;
    else c.unable++;
  }
  return c;
}

function matchesFilter(c: CheckResult, k: FilterKey): boolean {
  switch (k) {
    case 'all':    return true;
    case 'fail':   return c.status === 'DISCREPANT';
    case 'pass':   return c.status === 'PASS';
    case 'na':     return c.status === 'NOT_APPLICABLE';
    case 'unable': return c.status === 'UNABLE_TO_VERIFY' ||
                          c.status === 'HUMAN_REVIEW' ||
                          c.status === 'REQUIRES_HUMAN_REVIEW';
  }
}

function matchesQuery(c: CheckResult, q: string): boolean {
  if (!q) return true;
  const needle = q.toLowerCase();
  return (
    c.rule_id.toLowerCase().includes(needle) ||
    (c.rule_name ?? '').toLowerCase().includes(needle) ||
    (c.description ?? '').toLowerCase().includes(needle) ||
    (c.field ?? '').toLowerCase().includes(needle)
  );
}
