import { useEffect, useMemo, useRef, useState } from 'react';
import type { BusinessPhase, CheckResult, CheckStatus, RuleSummary } from '../../types';
import type { SessionState } from '../../hooks/useCheckSession';
import { useRuleCatalog } from '../../hooks/useRuleCatalog';
import { useScrollspy } from '../../hooks/useScrollspy';
import { useInvoiceFieldView } from '../../hooks/useInvoiceFieldView';
import { ComplianceReferenceModal } from './ComplianceReferenceModal';
import { ComplianceSidebar } from './ComplianceSidebar';
import { PhaseSection } from './PhaseSection';
import { RuleCard } from './RuleCard';
import { RuleCatalogStrip } from './RuleCatalogStrip';
import { SourceDrawer, type DrawerTarget } from './SourceDrawer';
import { StatusFilterBar, type StatusFilter } from './StatusFilterBar';
import { FieldCard } from './FieldCard';

type ViewMode = 'rule' | 'field';

interface Props {
  state: SessionState;
}

const PHASE_ORDER: BusinessPhase[] = [
  'PARTIES',
  'MONEY',
  'GOODS',
  'LOGISTICS',
  'PROCEDURAL',
];

const SECTION_ID = (phase: BusinessPhase) => `phase-${phase}`;
const SECTION_IDS = PHASE_ORDER.map(SECTION_ID);

const ZERO_COUNTS: Record<CheckStatus, number> = {
  PASS: 0, FAIL: 0, DOUBTS: 0, NOT_REQUIRED: 0,
};

/**
 * Compliance review screen. Two views sharing the same sidebar:
 *   Rule view  — flat rules grouped by business phase (PARTIES / MONEY / GOODS …).
 *   Field view — rules grouped by invoice field (F01 Issuer / F02 Buyer …).
 *
 * Both views render every ENABLED catalog rule, regardless of whether a check
 * result has arrived yet. Catalog-disabled rules are invisible.
 *
 * Filter surfaces above the list:
 *   1. StatusFilterBar — counts double as filter buttons.
 *   2. RuleCatalogStrip / FieldStrip — chips per rule or per field.
 */
export function ComplianceCheckPanel({ state }: Props) {
  const { rules: catalogRules } = useRuleCatalog();
  const [viewMode, setViewMode] = useState<ViewMode>('field');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>(null);
  const [ruleId, setRuleId] = useState<string | null>(null);
  const [fieldId, setFieldId] = useState<string | null>(null);
  const [drawerTarget, setDrawerTarget] = useState<DrawerTarget>(null);
  const [showReference, setShowReference] = useState(false);
  const isWide = useMediaQueryWide();

  // Map rule_id → completed result.
  const resultByRuleId = useMemo(() => {
    const m = new Map<string, CheckResult>();
    for (const c of state.checks) m.set(c.rule_id, c);
    return m;
  }, [state.checks]);

  // ─── Rule view counts ────────────────────────────────────────────────────────

  const counts = useMemo(() => {
    const out: Record<CheckStatus, number> = { ...ZERO_COUNTS };
    for (const r of catalogRules) {
      const result = resultByRuleId.get(r.id);
      if (!result) continue;
      out[result.status] += 1;
    }
    return out;
  }, [catalogRules, resultByRuleId]);

  const totalCompleted = useMemo(() => {
    let n = 0;
    for (const r of catalogRules) {
      if (resultByRuleId.has(r.id)) n++;
    }
    return n;
  }, [catalogRules, resultByRuleId]);

  const visibleRules = useMemo(() => {
    let rules = catalogRules;
    if (statusFilter !== null) {
      rules = rules.filter((r) => {
        const result = resultByRuleId.get(r.id);
        return result?.status === statusFilter;
      });
    }
    if (ruleId !== null) {
      rules = rules.filter((r) => r.id === ruleId);
    }
    return rules;
  }, [catalogRules, statusFilter, ruleId, resultByRuleId]);

  const visibleByPhase = useMemo(() => groupRulesByPhase(visibleRules), [visibleRules]);
  const enabledByPhase = useMemo(() => groupRulesByPhase(catalogRules), [catalogRules]);

  // ─── Field view ────────────────────────────────────────────────────────────

  const fieldResults = useInvoiceFieldView(catalogRules, state.checks);

  // Field view counts: PASS/FAIL/DOUBTS per field
  const fieldCounts = useMemo(() => {
    const out = new Map<string, Record<CheckStatus, number>>();
    for (const fr of fieldResults) {
      out.set(fr.fieldId, {
        PASS: fr.passedCount,
        FAIL: fr.failedCount,
        DOUBTS: fr.doubtsCount,
        NOT_REQUIRED: fr.notRequiredCount,
      });
    }
    return out;
  }, [fieldResults]);

  // Overall field-view status counts
  const fieldViewCounts = useMemo(() => {
    const out: Record<CheckStatus, number> = { ...ZERO_COUNTS };
    for (const fr of fieldResults) {
      if (fr.verdict) out[fr.verdict] += 1;
    }
    return out;
  }, [fieldResults]);

  const fieldTotalCompleted = useMemo(
    () => fieldResults.filter((fr) => fr.subRules.length > 0 || fr.pendingRuleIds.length > 0).length,
    [fieldResults],
  );

  /** Only fields with at least one covering rule — excludes F12/F13/F15/F17. */
  const totalFieldsWithRules = useMemo(
    () => fieldResults.filter((fr) => fr.fieldDef.coveringRules.length > 0).length,
    [fieldResults],
  );

  const visibleFieldResults = useMemo(() => {
    let results = fieldResults;
    if (statusFilter !== null) {
      results = results.filter((fr) => fr.verdict === statusFilter);
    }
    if (fieldId !== null) {
      results = results.filter((fr) => fr.fieldId === fieldId);
    }
    return results;
  }, [fieldResults, statusFilter, fieldId]);

  // ─── Scrollspy ─────────────────────────────────────────────────────────────

  const mainRef = useRef<HTMLDivElement | null>(null);
  const activeId = useScrollspy(SECTION_IDS, { root: mainRef.current });
  const activePhase: BusinessPhase | null = activeId
    ? (activeId.replace('phase-', '') as BusinessPhase)
    : null;

  // ─── Interaction handlers ─────────────────────────────────────────────────

  const handleRuleClick = (clicked: string) => {
    setRuleId(ruleId === clicked ? null : clicked);
    requestAnimationFrame(() => {
      const el = document.getElementById(`rule-${clicked}`);
      if (el && mainRef.current?.contains(el)) {
        scrollToInContainer(`rule-${clicked}`, mainRef.current);
      }
    });
  };

  const handleFieldClick = (clicked: string) => {
    setFieldId(fieldId === clicked ? null : clicked);
    requestAnimationFrame(() => {
      const el = document.getElementById(`field-${clicked}`);
      if (el && mainRef.current?.contains(el)) {
        scrollToInContainer(`field-${clicked}`, mainRef.current);
      }
    });
  };

  const hasFilter = viewMode === 'rule'
    ? (statusFilter !== null || ruleId !== null)
    : (statusFilter !== null || fieldId !== null);

  const handleClearAll = () => {
    setStatusFilter(null);
    setRuleId(null);
    setFieldId(null);
  };

  const onViewSource = (target: DrawerTarget) => setDrawerTarget(target);

  return (
    <div className="h-full bg-paper">
      <div className={isWide ? 'flex h-full' : 'flex flex-col h-full'}>
        {/* Sidebar — visible in both views */}
        {isWide && (
          <ComplianceSidebar
            checks={state.checks}
            activePhase={activePhase}
            onJumpPhase={(phase) => scrollToInContainer(SECTION_ID(phase), mainRef.current)}
            onReference={() => setShowReference(true)}
          />
        )}

        <div className="flex-1 min-w-0 min-h-0 flex flex-col overflow-hidden">
          {/* Sticky top bar: view toggle + filter controls */}
          <div className="flex flex-col border-b border-line bg-paper shrink-0">

            {/* Row 1: view toggle — distinct from filters */}
            <div className="flex items-center gap-3 px-4 py-1.5">
              {/* "View" label */}
              <span className="font-mono text-[10px] uppercase tracking-widest text-muted/70 font-semibold shrink-0">
                View
              </span>

              {/* Segmented control: Field | Rule */}
              <div className="shrink-0 flex rounded-full border border-navy-1 overflow-hidden">
                <button
                  type="button"
                  onClick={() => setViewMode('field')}
                  className={[
                    'px-3 py-1 font-mono text-[11px] uppercase tracking-widest font-semibold transition-colors',
                    viewMode === 'field'
                      ? 'bg-navy-1 text-white'
                      : 'bg-paper text-muted hover:bg-slate2 hover:text-navy-1',
                  ].join(' ')}
                >
                  Field
                </button>
                <button
                  type="button"
                  onClick={() => setViewMode('rule')}
                  className={[
                    'px-3 py-1 font-mono text-[11px] uppercase tracking-widest font-semibold transition-colors',
                    viewMode === 'rule'
                      ? 'bg-navy-1 text-white'
                      : 'bg-paper text-muted hover:bg-slate2 hover:text-navy-1',
                  ].join(' ')}
                >
                  Rule
                </button>
              </div>

              {/* Filter bar — inline after toggle */}
              <StatusFilterBar
                counts={viewMode === 'rule' ? counts : fieldViewCounts}
                totalEnabled={viewMode === 'rule' ? catalogRules.length : totalFieldsWithRules}
                totalCompleted={viewMode === 'rule' ? totalCompleted : fieldTotalCompleted}
                status={statusFilter}
                ruleId={viewMode === 'rule' ? ruleId : fieldId}
                viewMode={viewMode}
                onStatusChange={setStatusFilter}
                onClearAll={handleClearAll}
              />
            </div>
          </div>

          {/* Rule strip (rule view) or Field strip (field view) */}
          {viewMode === 'rule' ? (
            <RuleCatalogStrip
              rules={catalogRules}
              resultByRuleId={resultByRuleId}
              focusedRuleId={ruleId}
              onRuleClick={handleRuleClick}
            />
          ) : (
            <FieldStrip
              fieldResults={fieldResults}
              fieldCounts={fieldCounts}
              focusedFieldId={fieldId}
              onFieldClick={handleFieldClick}
            />
          )}

          <div ref={mainRef} className="flex-1 min-h-0 overflow-y-auto bg-slate2">
            <main>
              <div className="px-6 py-5 max-w-[1280px] mx-auto">
                {viewMode === 'rule' ? (
                  <PhaseView
                    visibleByPhase={visibleByPhase}
                    enabledByPhase={enabledByPhase}
                    resultByRuleId={resultByRuleId}
                    hasFilter={hasFilter}
                    onViewSource={onViewSource}
                  />
                ) : (
                  <FieldView
                    visibleFieldResults={visibleFieldResults}
                    onViewSource={onViewSource}
                  />
                )}
              </div>
            </main>
          </div>
        </div>
      </div>

      <SourceDrawer
        target={drawerTarget}
        sessionId={state.sessionId}
        lc={state.lc}
        invoice={state.invoice}
        onClose={() => setDrawerTarget(null)}
      />

      {showReference && (
        <ComplianceReferenceModal onClose={() => setShowReference(false)} />
      )}
    </div>
  );
}

// ─── Rule view ─────────────────────────────────────────────────────────────────

function PhaseView({
  visibleByPhase,
  enabledByPhase,
  resultByRuleId,
  hasFilter,
  onViewSource,
}: {
  visibleByPhase: Map<BusinessPhase, RuleSummary[]>;
  enabledByPhase: Map<BusinessPhase, RuleSummary[]>;
  resultByRuleId: Map<string, CheckResult>;
  hasFilter: boolean;
  onViewSource: (t: DrawerTarget) => void;
}) {
  return (
    <>
      {PHASE_ORDER.map((phase) => {
        const phaseRules = visibleByPhase.get(phase) ?? [];
        const enabledCount = (enabledByPhase.get(phase) ?? []).length;
        const phaseChecks: CheckResult[] = phaseRules
          .map((r) => resultByRuleId.get(r.id))
          .filter((c): c is CheckResult => Boolean(c));
        if (enabledCount === 0) return null;
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
                onViewSource={onViewSource}
              />
            ))}
          </PhaseSection>
        );
      })}
    </>
  );
}

// ─── Field view ─────────────────────────────────────────────────────────────

function FieldView({
  visibleFieldResults,
  onViewSource,
}: {
  visibleFieldResults: import('../../hooks/useInvoiceFieldView').FieldResult[];
  onViewSource: (t: DrawerTarget) => void;
}) {
  const fieldResultsWithRules = visibleFieldResults.filter(
    (fr) => fr.subRules.length > 0 || fr.pendingRuleIds.length > 0,
  );

  if (fieldResultsWithRules.length === 0) {
    return (
      <div className="font-sans text-sm text-muted italic px-1 py-4">
        No fields match the current filter.
      </div>
    );
  }

  return (
    <>
      {fieldResultsWithRules.map((fr) => (
        <FieldCard
          key={fr.fieldId}
          fieldResult={fr}
          failuresOnly={false}
          onViewSource={onViewSource}
        />
      ))}
    </>
  );
}

// ─── Field strip ─────────────────────────────────────────────────────────────

function FieldStrip({
  fieldResults,
  fieldCounts,
  focusedFieldId,
  onFieldClick,
}: {
  fieldResults: import('../../hooks/useInvoiceFieldView').FieldResult[];
  fieldCounts: Map<string, Record<CheckStatus, number>>;
  focusedFieldId: string | null;
  onFieldClick: (id: string) => void;
}) {
  return (
    <div className="flex items-center gap-1 px-4 py-1.5 border-b border-line bg-paper overflow-x-auto shrink-0">
      <span className="shrink-0 font-mono text-[10px] uppercase tracking-widest text-muted mr-1">
        Fields
      </span>
      {fieldResults.map((fr) => {
        const counts = fieldCounts.get(fr.fieldId);
        const hasFail = (counts?.FAIL ?? 0) > 0;
        const hasDoubt = (counts?.DOUBTS ?? 0) > 0;
        const isFocused = focusedFieldId === fr.fieldId;
        return (
          <button
            key={fr.fieldId}
            type="button"
            onClick={() => onFieldClick(fr.fieldId)}
            title={`${fr.fieldDef.fieldName} — ${fr.subRules.length} rules`}
            className={[
              'shrink-0 inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-mono font-semibold border transition-colors',
              isFocused
                ? 'bg-navy-1 text-white border-navy-1'
                : hasFail
                  ? 'bg-status-redSoft text-status-red border-status-red/30 hover:border-status-red'
                  : hasDoubt
                    ? 'bg-status-goldSoft text-status-gold border-status-gold/30 hover:border-status-gold'
                    : fr.verdict === 'PASS' && fr.subRules.length > 0
                      ? 'bg-status-greenSoft text-status-green border-status-green/30 hover:border-status-green'
                      : 'bg-slate2 text-muted border-line hover:border-navy-1 hover:text-navy-1',
            ].join(' ')}
          >
            {fr.fieldDef.shortName}
            {counts && (counts.FAIL > 0 || counts.DOUBTS > 0) && (
              <span>
                {counts.FAIL > 0 ? ` ${counts.FAIL}✗` : ` ${counts.DOUBTS}?`}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

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
