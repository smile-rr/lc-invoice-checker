import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useConfirm } from '../../hooks/useConfirm';
import { useQueueStatus } from '../../hooks/useQueueStatus';
import { HealthDot } from './HealthIndicator';
import { HistoryDropdown } from './HistoryDropdown';

export function TopNav() {
  const loc = useLocation();
  const nav = useNavigate();
  const onSession = loc.pathname.startsWith('/session/');
  const { confirm, Dialog } = useConfirm();
  // The chip is only useful when something is RUNNING — the hook keeps polling
  // while the chip is visible AND while the HistoryDropdown is open (separate
  // mount) and stops cleanly otherwise.
  const queueStatus = useQueueStatus(true);
  const runningId = queueStatus?.running?.[0] ?? null;
  const queuedDepth = queueStatus?.queued?.length ?? 0;

  // The brand link and the "New Check" button both navigate to "/". When the
  // operator is currently viewing a session result, that navigation visually
  // discards the view — which they may not have intended. Gate the navigation
  // behind the same confirm modal used by the upload form.
  async function leaveSession(e: React.MouseEvent<HTMLAnchorElement>) {
    if (!onSession) return; // nothing to confirm — already on /
    e.preventDefault();
    const ok = await confirm({
      title: 'Leave the current session?',
      message:
        'You are viewing a compliance check result. Returning to the upload page will hide it from view.\n\n' +
        'The session itself stays in history — you can reopen it from the recent-sessions list at any time.',
      confirmLabel: 'Leave session',
      cancelLabel: 'Stay here',
    });
    if (ok) nav('/');
  }

  return (
    <header className="bg-navy-1 text-white h-10 flex items-center px-6 gap-5 border-b border-navy-3 text-sm">
      <Link
        to="/"
        onClick={leaveSession}
        className="flex items-center gap-2 font-serif"
      >
        <span className="inline-block w-5 h-5 rounded-sm bg-teal-2 text-navy-1 grid place-items-center font-bold text-xs">
          &#x2B21;
        </span>
        <span className="leading-none">
          TradeVerify
          <span className="text-teal-2 text-[11px] font-mono ml-1.5">UCP 600 + ISBP 821</span>
        </span>
      </Link>
      <nav className="ml-auto flex items-center gap-2 text-xs">
        <HealthDot />
        {runningId && (
          <button
            onClick={() => nav(`/session/${runningId}`)}
            className="font-mono text-[11px] px-2 py-1 rounded bg-teal-1/15 text-teal-2 hover:bg-teal-1/30 inline-flex items-center gap-1.5"
            title={`Currently running: ${runningId}${queuedDepth > 0 ? ` · ${queuedDepth} queued` : ''}`}
          >
            <span className="inline-block w-1.5 h-1.5 rounded-full bg-teal-2 animate-pulse" />
            <span>{runningId.slice(0, 8)} · running</span>
            {queuedDepth > 0 && (
              <span className="text-[10px] text-teal-2/70">+{queuedDepth} queued</span>
            )}
          </button>
        )}
        <HistoryDropdown />
        <Link
          to="/"
          onClick={leaveSession}
          className={`px-2.5 py-1 rounded ${!onSession ? 'bg-teal-1' : 'hover:bg-navy-3'}`}
        >
          New Check
        </Link>
      </nav>
      {Dialog}
    </header>
  );
}
