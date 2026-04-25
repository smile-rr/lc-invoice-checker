import { EMPTY_SESSION_STATE } from '../hooks/useCheckSession';
import { PipelineFlow } from '../components/shell/PipelineFlow';
import { SessionStrip } from '../components/shell/SessionStrip';
import { UploadStep } from '../components/upload/UploadStep';

/**
 * Pre-session landing page. Renders the same shell as {@link SessionPage}
 * (SessionStrip → PipelineFlow → body) using a stub idle state, so navigating
 * between '/' and '/session/:id?step=upload' doesn't shift the page chrome.
 */
export function UploadPage() {
  return (
    <div className="flex flex-col">
      <SessionStrip state={EMPTY_SESSION_STATE} onOpenTrace={() => {}} />
      <PipelineFlow state={null} active="upload" onSelect={() => {}} />
      <UploadStep primaryRunCta={true} />
    </div>
  );
}
