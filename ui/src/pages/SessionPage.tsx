import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useCheckSession } from '../hooks/useCheckSession';
import { usePipelineConfig } from '../hooks/usePipelineConfig';
import { TraceModal } from '../components/results/TraceModal';
import { PipelineFlow } from '../components/shell/PipelineFlow';
import { SessionStrip } from '../components/shell/SessionStrip';
import { useStep } from '../components/shell/steps';
import { LcAuditTab } from '../components/tabs/LcAuditTab';
import { InvoiceAuditTab } from '../components/tabs/InvoiceAuditTab';
import { CompareTab } from '../components/tabs/CompareTab';
import { RuleChecksTab } from '../components/tabs/RuleChecksTab';
import { ReviewTab } from '../components/tabs/ReviewTab';
import { UploadStep } from '../components/upload/UploadStep';

/**
 * Full-width session workspace driven by URL `?step=…`. Layout layers:
 *   1. SessionStrip   — verdict + clickable count chips, always visible.
 *   2. PipelineFlow   — six-step flow; this is the primary navigation now.
 *   3. Active step    — full-width content per step.
 *
 * When `?step` is omitted, the active step auto-derives from session state
 * (latest available data) so the page lands on the most useful view.
 */
export function SessionPage() {
  const { id } = useParams<{ id: string }>();
  const sessionId = id ?? null;
  const state = useCheckSession(sessionId);
  const pipeline = usePipelineConfig();
  const [step, setStep] = useStep(state, pipeline.configuredStages);
  const [showTrace, setShowTrace] = useState(false);

  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }, [step]);

  if (!sessionId) {
    return <div className="p-6 text-status-red">Missing session id</div>;
  }

  return (
    <div className="flex flex-col">
      <SessionStrip state={state} onOpenTrace={() => setShowTrace(true)} />
      {/* Always show the current session's pipeline statuses, even when the
          operator is browsing Step 0 to set up a new run. The fresh "all
          pending" view only appears once they actually start a new session
          (which navigates to /session/<new-id> and remounts this component
          with empty state). Resetting earlier would erase the result they
          haven't yet committed to discarding. */}
      <PipelineFlow
        state={state}
        active={step}
        onSelect={setStep}
        configuredStages={pipeline.configuredStages}
      />

      {state.error && (
        <div className="mx-8 my-4 rounded-card bg-status-redSoft border border-status-red/30 p-4">
          <div className="font-semibold text-status-red">Pipeline error</div>
          <div className="text-sm mt-1">
            {state.error.stage && (
              <span className="font-mono">[{state.error.stage}] </span>
            )}
            {state.error.message}
          </div>
        </div>
      )}

      {state.haltedAfter && !state.error && (
        <div className="mx-8 my-4 rounded-card bg-amber-50 border border-amber-300/60 p-3">
          <div className="text-sm">
            <span className="font-semibold text-amber-800">Pipeline halted (debug)</span>
            <span className="text-amber-900/80 ml-2">
              ran through <span className="font-mono">{state.haltedAfter}</span>; downstream stages disabled by{' '}
              <span className="font-mono">.endHere()</span> in{' '}
              <span className="font-mono">pipeline/LcCheckPipeline.java</span>. Comment that line to enable the full pipeline.
            </span>
          </div>
        </div>
      )}

      <div className="flex-1">
        {step === 'upload' && <UploadStep primaryRunCta={true} />}
        {step === 'lc' && <LcAuditTab sessionId={sessionId} lc={state.lc} />}
        {step === 'invoice' && (
          <InvoiceAuditTab sessionId={sessionId} invoice={state.invoice} />
        )}
        {step === 'compare' && (
          <CompareTab lc={state.lc} invoice={state.invoice} checks={state.checks} />
        )}
        {step === 'rules' && (
          <RuleChecksTab
            checks={state.checks}
            inFlightRuleIds={state.inFlightRuleIds}
            activatedRuleIds={state.activatedRuleIds}
          />
        )}
        {step === 'review' && (
          <ReviewTab
            lc={state.lc}
            invoice={state.invoice}
            checks={state.checks}
            report={state.report}
          />
        )}
      </div>

      {showTrace && (
        <TraceModal sessionId={sessionId} onClose={() => setShowTrace(false)} />
      )}
    </div>
  );
}
