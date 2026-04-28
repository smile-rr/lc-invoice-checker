import { useEffect, useRef } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useCheckSession } from '../hooks/useCheckSession';
import { usePipelineConfig } from '../hooks/usePipelineConfig';
import { ApiProgressBar } from '../components/shell/ApiProgressBar';
import { PipelineFlow } from '../components/shell/PipelineFlow';
import { SessionStrip } from '../components/shell/SessionStrip';
import { useStep } from '../components/shell/steps';
import { LcAuditTab } from '../components/tabs/LcAuditTab';
import { InvoiceAuditTab } from '../components/tabs/InvoiceAuditTab';
import { ComplianceCheckPanel } from '../components/check/ComplianceCheckPanel';
import { ReviewTab } from '../components/tabs/ReviewTab';
import { QueueWaitCard } from '../components/queue/QueueWaitCard';
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
  const nav = useNavigate();
  const state = useCheckSession(sessionId);
  const pipeline = usePipelineConfig();
  const [step, setStep] = useStep(state, pipeline.configuredStages);
  const queued = !!state.queueContext;

  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }, [step]);

  // Auto-advance from the Invoice step to Compliance Check once invoice
  // extraction completes. The user is most often on `invoice` while
  // extraction is in flight; the moment it finishes, the natural next view
  // is the rule run that's about to start. We only fire once per transition
  // (the ref guards against re-firing if the user manually navigates back).
  const advancedRef = useRef(false);
  useEffect(() => {
    const invoiceDone = state.stages.invoice_extract.status === 'done';
    if (invoiceDone && step === 'invoice' && !advancedRef.current) {
      advancedRef.current = true;
      setStep('check');
    }
    if (!invoiceDone) advancedRef.current = false;
  }, [state.stages.invoice_extract.status, step, setStep]);

  if (!sessionId) {
    return <div className="p-6 text-status-red">Missing session id</div>;
  }

  // App-shell layout: SessionStrip + PipelineFlow are fixed at top; the active
  // step body fills the remaining viewport and provides its OWN scroll context.
  // Compliance Check needs this so the sidebar stays anchored while the rule
  // list scrolls inside the panel — not the document. Other steps wrap their
  // body in an internal scroller so behaviour is uniform.
  return (
    <div className="flex flex-col h-full overflow-hidden">
      <SessionStrip state={state} />
      <PipelineFlow
        state={state}
        active={step}
        onSelect={setStep}
        configuredStages={pipeline.configuredStages}
      />
      <ApiProgressBar />

      {state.error && (
        <div className="mx-8 my-4 rounded-card bg-status-redSoft border border-status-red/30 p-4 shrink-0">
          <div className="font-semibold text-status-red">Pipeline error</div>
          <div className="text-sm mt-1">
            {state.error.stage && (
              <span className="font-mono">[{state.error.stage}] </span>
            )}
            {state.error.message}
          </div>
        </div>
      )}

      <div className="flex-1 min-h-0 overflow-hidden">
        {step === 'upload' && (
          <Scrollable>
            {queued && state.queueContext && (
              <div className="max-w-[1600px] mx-auto px-8 pt-5">
                <QueueWaitCard
                  sessionId={sessionId}
                  context={state.queueContext}
                  onCancelled={() => nav('/')}
                />
              </div>
            )}
            <UploadStep primaryRunCta={true} />
          </Scrollable>
        )}
        {step === 'lc' && <Scrollable><LcAuditTab sessionId={sessionId} lc={state.lc} /></Scrollable>}
        {step === 'invoice' && (
          <Scrollable><InvoiceAuditTab sessionId={sessionId} invoice={state.invoice} /></Scrollable>
        )}
        {step === 'check' && <ComplianceCheckPanel state={state} />}
        {step === 'review' && (
          <Scrollable>
            <ReviewTab
              sessionId={sessionId}
              lc={state.lc}
              invoice={state.invoice}
              checks={state.checks}
              report={state.report}
            />
          </Scrollable>
        )}
      </div>
    </div>
  );
}

/** Wraps a step body in its own y-scroll so the page chrome stays fixed. */
function Scrollable({ children }: { children: React.ReactNode }) {
  return <div className="h-full overflow-y-auto">{children}</div>;
}
