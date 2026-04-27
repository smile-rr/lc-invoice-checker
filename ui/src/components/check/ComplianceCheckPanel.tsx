import { useEffect, useMemo, useRef, useState } from 'react';
import type { BusinessPhase, CheckResult, CheckStatus, CheckTypeEnum, RuleSummary } from '../../types';
import type { SessionState } from '../../hooks/useCheckSession';
import { useRuleCatalog } from '../../hooks/useRuleCatalog';
import { useScrollspy } from '../../hooks/useScrollspy';
import { ComplianceSidebar } from './ComplianceSidebar';
import { PhaseSection } from './PhaseSection';
import { RuleCard } from './RuleCard';
import { RuleCatalogStrip } from './RuleCatalogStrip';
import { SourceDrawer, type DrawerTarget } from './SourceDrawer';
import { StatusFilterBar, type StatusFilter, type TypeFilter } from './StatusFilterBar';

interface Props {
  state: SessionState;
}

const PHASE_ORDER: BusinessPhase[] = [
  'PARTIES',
  'MONEY',
  'GOODS',
  'LOGISTICS',
  'PROCEDURAL',
  'HOLISTIC',
];

const SECTION_ID = (phase: BusinessPhase) => `phase-${phase}`;
const SECTION_IDS = PHASE_ORDER.map(SECTION_ID);

const ZERO_COUNTS: Record<CheckStatus, number> = {
  PASS: 0, FAIL: 0, DOUBTS: 0, NOT_REQUIRED: 0,
};

const ZERO_TYPE_COUNTS: Record<CheckTypeEnum, number> = {
  PROGRAMMATIC: 0, AGENT: 0,
};

/**
 * Compliance review screen. The panel renders every ENABLED catalog rule in
 * its phase section, regardless of whether a check result has arrived yet —
 * this gives the operator a complete picture (running rules show "Waiting…",
 * completed rules show their verdict). Catalog-disabled rules are invisible.
 *
 * <p>Two filter surfaces above the phase list:
 *   1. {@code StatusFilterBar} — counts double as filter buttons, narrow by
 *      verdict (Pass / Fail / Doubts / Not required).
 *   2. {@code RuleCatalogStrip} — one chip per enabled rule, click to focus
 *      a single rule.
 */
export function ComplianceCheckPanel({ state }: Props) {
  const { rules: catalogRules } = useRuleCatalog();
  const [statusFilter, setStatusFilter] = useState<StatusFilter>(null);
  const [typeFilter, setTypeFilter] = useState<TypeFilter>(null);
  const [ruleId, setRuleId] = useState<string | null>(null);
  const [drawerTarget, setDrawerTarget] = useState<DrawerTarget>(null);
  const isWide = useMediaQueryWide();

  // Map rule_id → completed result. Used for both per-rule rendering and
  // the catalog strip's chip colouring.
  const resultByRuleId = useMemo(() => {
    const m = new Map<string, CheckResult>();
    for (const c of state.checks) m.set(c.rule_id, c);
    return m;
  }, [state.checks]);

  // Status-filter counts — only over enabled rules.
  const counts = useMemo(() => {
    const out: Record<CheckStatus, number> = { ...ZERO_COUNTS };
    for (const r of catalogRules) {
      const result = resultByRuleId.get(r.id);
      if (!result) continue;  // pending rules don't count toward any bucket
      out[result.status] += 1;
    }
    return out;
  }, [catalogRules, resultByRuleId]);

  // Type counts — over the enabled rule set itself (not result-dependent),
  // so the chips are useful even before any rule has completed.
  const typeCounts = useMemo(() => {
    const out: Record<CheckTypeEnum, number> = { ...ZERO_TYPE_COUNTS };
    for (const r of catalogRules) {
      if (r.check_type === 'PROGRAMMATIC') out.PROGRAMMATIC += 1;
      else if (r.check_type === 'AGENT') out.AGENT += 1;
    }
    return out;
  }, [catalogRules]);

  // Per-type completed — number of rules of each type that already have a
  // result. Drives the "4/8 PROG · 2/9 AGENT · 6/17 total" progress strip.
  const typeCompleted = useMemo(() => {
    const out: Record<CheckTypeEnum, number> = { ...ZERO_TYPE_COUNTS };
    for (const r of catalogRules) {
      if (!resultByRuleId.has(r.id)) continue;
      if (r.check_type === 'PROGRAMMATIC') out.PROGRAMMATIC += 1;
      else if (r.check_type === 'AGENT') out.AGENT += 1;
    }
    return out;
  }, [catalogRules, resultByRuleId]);

  const totalCompleted = typeCompleted.PROGRAMMATIC + typeCompleted.AGENT;

  // Filter the rule list once for both the strip and the per-phase rendering.
  // Status / type / rule-id filters AND together. Pending rules survive the
  // status filter only when no status filter is active.
  const visibleRules = useMemo(() => {
    let rules = catalogRules;
    if (statusFilter !== null) {
      rules = rules.filter((r) => {
        const result = resultByRuleId.get(r.id);
        return result?.status === statusFilter;
      });
    }
    if (typeFilter !== null) {
      rules = rules.filter((r) => r.check_type === typeFilter);
    }
    if (ruleId !== null) {
      rules = rules.filter((r) => r.id === ruleId);
    }
    return rules;
  }, [catalogRules, statusFilter, typeFilter, ruleId, resultByRuleId]);

  const visibleByPhase = useMemo(() => groupRulesByPhase(visibleRules), [visibleRules]);
  const enabledByPhase = useMemo(() => groupRulesByPhase(catalogRules), [catalogRules]);

  const mainRef = useRef<HTMLDivElement | null>(null);
  const activeId = useScrollspy(SECTION_IDS, { root: mainRef.current });
  const activePhase: BusinessPhase | null = activeId
    ? (activeId.replace('phase-', '') as BusinessPhase)
    : null;

  const handleRuleClick = (clicked: string) => {
    setRuleId(ruleId === clicked ? null : clicked);
    requestAnimationFrame(() => {
      const el = document.getElementById(`rule-${clicked}`);
      if (el && mainRef.current?.contains(el)) {
        scrollToInContainer(`rule-${clicked}`, mainRef.current);
      }
    });
  };

  const hasFilter = statusFilter !== null || typeFilter !== null || ruleId !== null;

  return (
    <div className="h-full bg-paper">
      <div className={isWide ? 'flex h-full' : 'flex flex-col h-full'}>
        {isWide && (
          <ComplianceSidebar
            checks={state.checks}
            activeId={activePhase}
            onJumpPhase={(phase) => scrollToInContainer(SECTION_ID(phase), mainRef.current)}
          />
        )}

        <div className="flex-1 min-w-0 min-h-0 flex flex-col overflow-hidden">
          <StatusFilterBar
            counts={counts}
            typeCounts={typeCounts}
            typeCompleted={typeCompleted}
            totalEnabled={catalogRules.length}
            totalCompleted={totalCompleted}
            status={statusFilter}
            type={typeFilter}
            ruleId={ruleId}
            onStatusChange={setStatusFilter}
            onTypeChange={setTypeFilter}
            onClearAll={() => {
              setStatusFilter(null);
              setTypeFilter(null);
              setRuleId(null);
            }}
          />
          <RuleCatalogStrip
            rules={catalogRules}
            resultByRuleId={resultByRuleId}
            focusedRuleId={ruleId}
            onRuleClick={handleRuleClick}
          />

          <div ref={mainRef} className="flex-1 min-h-0 overflow-y-auto bg-slate2">
            <main>
              <div className="px-6 py-5 max-w-[1280px] mx-auto">
                {PHASE_ORDER.map((phase) => {
                  const phaseRules = visibleByPhase.get(phase) ?? [];
                  const enabledCount = (enabledByPhase.get(phase) ?? []).length;
                  if (enabledCount === 0) return null;
                  const phaseChecks: CheckResult[] = phaseRules
                    .map((r) => resultByRuleId.get(r.id))
                    .filter((c): c is CheckResult => Boolean(c));
                  return (
                    <PhaseSection
                      key={phase}
                      id={SECTION_ID(phase)}
                      phase={phase}
                      totalRulesInPhase={enabledCount}
                      visibleRuleCount={phaseRules.length}
                      hasFilter={hasFilter}
                      checks={phaseChecks}
                    >
                      {phaseRules.map((rule) => (
                        <RuleCard
                          key={rule.id}
                          rule={rule}
                          check={resultByRuleId.get(rule.id) ?? null}
                          failuresOnly={false}
                          onViewSource={setDrawerTarget}
                        />
                      ))}
                    </PhaseSection>
                  );
                })}
              </div>
            </main>
          </div>
        </div>
      </div>

      {/* Source-viewer drawer — opens over the compliance screen when a
          reviewer clicks "View source" on a rule card. Closing returns the
          reviewer to their exact scroll position; no state loss. */}
      <SourceDrawer
        target={drawerTarget}
        sessionId={state.sessionId}
        lc={state.lc}
        invoice={state.invoice}
        onClose={() => setDrawerTarget(null)}
      />
    </div>
  );
}

function groupRulesByPhase(rules: RuleSummary[]): Map<BusinessPhase, RuleSummary[]> {
  const out = new Map<BusinessPhase, RuleSummary[]>();
  for (const r of rules) {
    const p = (r.business_phase ?? 'PROCEDURAL') as BusinessPhase;
    const arr = out.get(p);
    if (arr) arr.push(r);
    else out.set(p, [r]);
  }
  return out;
}

function scrollToInContainer(elementId: string, container: HTMLElement | null) {
  const el = document.getElementById(elementId);
  if (!el) return;
  if (container && container.contains(el)) {
    const containerTop = container.getBoundingClientRect().top;
    const elTop = el.getBoundingClientRect().top;
    const targetScroll = container.scrollTop + (elTop - containerTop) - 4;
    container.scrollTo({ top: targetScroll, behavior: 'smooth' });
  } else {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}

function useMediaQueryWide(): boolean {
  const [wide, setWide] = useState(() =>
    typeof window === 'undefined' ? true : window.matchMedia('(min-width: 1024px)').matches,
  );
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const mq = window.matchMedia('(min-width: 1024px)');
    const handler = (e: MediaQueryListEvent) => setWide(e.matches);
    mq.addEventListener('change', handler);
    return () => mq.removeEventListener('change', handler);
  }, []);
  return wide;
}
