import { useEffect, useMemo, useRef, useState } from 'react';
import type { BusinessPhase, CheckResult, CheckStatus } from '../../types';
import type { SessionState } from '../../hooks/useCheckSession';
import { useRuleCatalog } from '../../hooks/useRuleCatalog';
import { useScrollspy } from '../../hooks/useScrollspy';
import { ComplianceSidebar } from './ComplianceSidebar';
import { PhaseSection } from './PhaseSection';
import { RuleCard } from './RuleCard';

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

interface Filters {
  failuresOnly: boolean;
  status: CheckStatus | null;
  authority: string | null;
  ruleId: string | null;
}

const EMPTY_FILTERS: Filters = {
  failuresOnly: false,
  status: null,
  authority: null,
  ruleId: null,
};

/**
 * Compliance review screen. Sidebar (left) navigates phases; main column
 * shows phase sections with rule cards. Rules append to their phase section
 * as `rule` events arrive. Filters AND together — clicking a chip in the
 * sidebar or filter bar narrows the visible set.
 */
export function ComplianceCheckPanel({ state }: Props) {
  const { rules: catalogRules, byId: catalogById } = useRuleCatalog();
  const [filters] = useState<Filters>(EMPTY_FILTERS);
  const isWide = useMediaQueryWide();

  const visibleChecks = useMemo(
    () => state.checks.filter((c) => matchesFilters(c, filters)),
    [state.checks, filters],
  );
  const checksByPhase = useMemo(() => groupChecksByPhase(visibleChecks), [visibleChecks]);
  const totalsByPhase = useMemo(() => countCatalogPerPhase(catalogRules), [catalogRules]);

  const mainRef = useRef<HTMLDivElement | null>(null);
  const activeId = useScrollspy(SECTION_IDS, { root: mainRef.current });
  const activePhase: BusinessPhase | null = activeId
    ? (activeId.replace('phase-', '') as BusinessPhase)
    : null;

  const handleAuthorityClick = (_ref: string) => {
    // No-op for now — filter UI removed; authority chips remain in card text only.
  };

  const hasFilter = filters.status !== null || filters.authority !== null || filters.ruleId !== null;

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
          {!isWide && (
            <MobileQuickNav
              phases={PHASE_ORDER}
              activeId={activePhase}
              onJump={(phase) => scrollToInContainer(SECTION_ID(phase), mainRef.current)}
            />
          )}

          <div ref={mainRef} className="flex-1 min-h-0 overflow-y-auto bg-slate2">
            <main>
              <div className="px-6 py-5 max-w-[1280px] mx-auto">
                {PHASE_ORDER.map((phase) => {
                  const phaseChecks = checksByPhase.get(phase) ?? [];
                  return (
                    <PhaseSection
                      key={phase}
                      id={SECTION_ID(phase)}
                      phase={phase}
                      totalRulesInPhase={totalsByPhase.get(phase) ?? 0}
                      visibleRuleCount={phaseChecks.length}
                      hasFilter={hasFilter}
                      checks={phaseChecks}
                    >
                      {phaseChecks.map((check) => (
                        <RuleCard
                          key={check.rule_id}
                          check={check}
                          rule={catalogById.get(check.rule_id)}
                          failuresOnly={filters.failuresOnly}
                          onAuthorityClick={handleAuthorityClick}
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
    </div>
  );
}

function MobileQuickNav({
  phases,
  activeId,
  onJump,
}: {
  phases: BusinessPhase[];
  activeId: BusinessPhase | null;
  onJump: (phase: BusinessPhase) => void;
}) {
  return (
    <div className="px-4 py-2 border-b border-line bg-slate2 flex items-center gap-2 overflow-x-auto">
      <span className="font-mono text-[10px] uppercase tracking-[0.18em] text-muted shrink-0">
        Phases
      </span>
      {phases.map((phase) => (
        <button
          key={phase}
          type="button"
          onClick={() => onJump(phase)}
          className={[
            'font-mono text-[10px] uppercase tracking-[0.12em] px-2 py-1 border shrink-0',
            activeId === phase
              ? 'border-teal-1 text-teal-1 bg-paper'
              : 'border-line text-muted hover:text-navy-1 hover:border-navy-1/40',
          ].join(' ')}
        >
          {phase}
        </button>
      ))}
    </div>
  );
}

function matchesFilters(check: CheckResult, filters: Filters): boolean {
  if (filters.ruleId !== null && check.rule_id !== filters.ruleId) return false;
  if (filters.status !== null && check.status !== filters.status) return false;
  if (filters.authority !== null) {
    if (check.ucp_ref !== filters.authority && check.isbp_ref !== filters.authority) return false;
  }
  return true;
}

function groupChecksByPhase(checks: CheckResult[]): Map<BusinessPhase, CheckResult[]> {
  const out = new Map<BusinessPhase, CheckResult[]>();
  for (const c of checks) {
    const phase = (c.business_phase ?? 'PROCEDURAL') as BusinessPhase;
    const arr = out.get(phase);
    if (arr) arr.push(c);
    else out.set(phase, [c]);
  }
  return out;
}

function countCatalogPerPhase(
  catalog: Array<{ business_phase: string | null; enabled: boolean }>,
): Map<BusinessPhase, number> {
  const out = new Map<BusinessPhase, number>();
  for (const r of catalog) {
    if (!r.enabled || !r.business_phase) continue;
    out.set(r.business_phase as BusinessPhase, (out.get(r.business_phase as BusinessPhase) ?? 0) + 1);
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
