import { useLocation } from 'react-router-dom';
import { EMPTY_SESSION_STATE } from '../hooks/useCheckSession';
import { PipelineFlow } from '../components/shell/PipelineFlow';
import { SessionStrip } from '../components/shell/SessionStrip';
import { UploadStep } from '../components/upload/UploadStep';

/**
 * Pre-session landing page. Renders the same shell as {@link SessionPage}
 * (SessionStrip → PipelineFlow → body) using a stub idle state, so navigating
 * between '/' and '/session/:id?step=upload' doesn't shift the page chrome.
 *
 * {@code UploadStep} is keyed on {@code location.key} so clicking "New Check"
 * (in TopNav) — even when already on '/' — produces a fresh component instance
 * with cleared form state. Without this, react-router's same-URL navigation
 * does NOT remount and any previously-loaded files / sample selection lingers.
 */
export function UploadPage() {
  const loc = useLocation();
  return (
    <div className="flex flex-col h-full overflow-hidden">
      <SessionStrip state={EMPTY_SESSION_STATE} onOpenTrace={() => {}} />
      <PipelineFlow state={null} active="upload" onSelect={() => {}} />
      <div className="flex-1 min-h-0 overflow-y-auto">
        <UploadStep key={loc.key} primaryRunCta={true} />
      </div>
    </div>
  );
}
