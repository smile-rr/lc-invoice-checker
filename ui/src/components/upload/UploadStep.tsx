import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { startCheck } from '../../api/client';
import { fetchSamplePair, SAMPLES, type SamplePair } from '../../data/samples';
import { useConfirm } from '../../hooks/useConfirm';

interface Props {
  /** When true, the run button is the primary CTA. Used pre-session. */
  primaryRunCta?: boolean;
}

export function UploadStep({ primaryRunCta = true }: Props) {
  const nav = useNavigate();
  const loc = useLocation();
  const insideSession = loc.pathname.startsWith('/session/');
  const [lc, setLc] = useState<File | null>(null);
  const [invoice, setInvoice] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  // One-shot guard: only ask for confirmation the first time the operator
  // mutates inputs while viewing an existing session. Once they OK the
  // change, subsequent picks (a different sample, swapping a file) don't
  // re-prompt — they're already in "configuring a new check" mode.
  const [confirmedNewCheck, setConfirmedNewCheck] = useState(false);
  const { confirm, Dialog } = useConfirm();

  // AbortController for in-flight sample fetches. Picking another sample (or
  // unmounting) cancels any earlier load so a slow request can't overwrite a
  // newer selection.
  const abortRef = useRef<AbortController | null>(null);
  useEffect(() => () => abortRef.current?.abort(), []);

  async function ensureConfirmed(): Promise<boolean> {
    if (!insideSession || confirmedNewCheck) return true;
    const ok = await confirm({
      title: 'Configure a new compliance check?',
      message:
        'You are about to replace the inputs of the session you are currently viewing.\n\n' +
        'The current session result will remain available — you can reopen it from history at any time.',
      confirmLabel: 'Continue with new inputs',
      cancelLabel: 'Keep current session',
    });
    if (ok) setConfirmedNewCheck(true);
    return ok;
  }

  async function setLcGuarded(f: File) {
    if (!(await ensureConfirmed())) return;
    setLc(f);
  }

  async function setInvoiceGuarded(f: File) {
    if (!(await ensureConfirmed())) return;
    setInvoice(f);
  }

  async function loadSample(s: SamplePair) {
    if (!(await ensureConfirmed())) return;
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setErr(null);
    try {
      const { lc: lcFile, invoice: invFile } = await fetchSamplePair(s, ctrl.signal);
      if (ctrl.signal.aborted) return;
      setLc(lcFile);
      setInvoice(invFile);
    } catch (e) {
      if ((e as Error).name === 'AbortError') return;
      setErr(`Sample load failed: ${(e as Error).message}`);
    }
  }

  async function run() {
    if (!lc || !invoice) return;
    setBusy(true);
    setErr(null);
    try {
      const r = await startCheck(lc, invoice);
      nav(`/session/${r.session_id}`);
    } catch (e) {
      setErr((e as Error).message);
      setBusy(false);
    }
  }

  return (
    <div className="max-w-5xl mx-auto px-8 py-8 space-y-6">
      {/* Source slots — fixed-height, single layout regardless of empty/filled */}
      <div className="grid grid-cols-2 gap-5">
        <SourceSlot
          file={lc}
          accept=".txt,text/plain"
          icon="📄"
          slotLabel="LETTER OF CREDIT"
          slotHint="MT700 plain text"
          futureHint="future: load from S3 / SWIFT gateway"
          onFile={setLcGuarded}
          onClear={() => setLc(null)}
        />
        <SourceSlot
          file={invoice}
          accept="application/pdf,.pdf"
          icon="🧾"
          slotLabel="COMMERCIAL INVOICE"
          slotHint="PDF · up to 20 MB"
          futureHint="future: load by document ID"
          onFile={setInvoiceGuarded}
          onClear={() => setInvoice(null)}
        />
      </div>

      {/* Run CTA */}
      <div className="flex items-center gap-3">
        <button
          disabled={!lc || !invoice || busy}
          onClick={run}
          className={[
            'px-5 py-2.5 rounded-btn font-medium',
            primaryRunCta
              ? 'bg-navy-1 text-white hover:bg-navy-2'
              : 'border border-navy-1 text-navy-1 hover:bg-slate2',
            'disabled:opacity-40 disabled:cursor-not-allowed',
          ].join(' ')}
        >
          {busy ? 'Starting…' : '▶  Run Compliance Check'}
        </button>
        {(lc || invoice) && (
          <button
            onClick={() => {
              setLc(null);
              setInvoice(null);
              setErr(null);
            }}
            className="text-sm text-muted hover:text-navy-1 px-2 py-1"
          >
            Clear
          </button>
        )}
      </div>

      {/* Pre-defined samples */}
      <div className="border-t border-line pt-5">
        <div className="text-[10px] uppercase tracking-[0.2em] text-muted mb-3">
          Pre-defined samples
        </div>
        <div className="grid grid-cols-2 gap-2">
          {SAMPLES.map((s) => (
            <button
              key={s.id}
              onClick={() => loadSample(s)}
              className="text-left rounded-btn border border-line bg-paper hover:border-teal-2 px-3 py-2.5"
            >
              <div className="text-sm text-navy-1">
                MT700 + <span className="font-semibold">{s.label}</span>
              </div>
              <div className="text-xs text-muted">{s.note}</div>
              <div className="mt-1 font-mono text-[10px] text-muted truncate">
                {s.invoicePath.split('/').pop()}
              </div>
            </button>
          ))}
        </div>
      </div>

      {err && (
        <div className="p-3 rounded-btn bg-status-redSoft text-status-red text-sm">{err}</div>
      )}
      {Dialog}
    </div>
  );
}

/**
 * One source slot. Single fixed-size container that just swaps inner content
 * based on whether a file is loaded — no layout shift, no morphing borders,
 * no transitions. The outer card is always the same height and same spacing;
 * the only visual delta is the border colour and a small "loaded" badge.
 *
 * Drag-and-drop on the whole card always replaces the file (whether empty or
 * already filled). Click on the empty state opens the native file picker.
 */
function SourceSlot({
  file,
  accept,
  icon,
  slotLabel,
  slotHint,
  futureHint,
  onFile,
  onClear,
}: {
  file: File | null;
  accept: string;
  icon: string;
  slotLabel: string;
  slotHint: string;
  futureHint: string;
  onFile: (f: File) => void | Promise<void>;
  onClear: () => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const filled = !!file;

  function pick(fs: FileList | null) {
    if (!fs || fs.length === 0) return;
    void onFile(fs[0]);
  }

  return (
    <div
      className={[
        'relative rounded-card border-2 bg-paper px-5 py-4 h-[140px] flex flex-col',
        filled ? 'border-teal-1' : 'border-line border-dashed',
      ].join(' ')}
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => {
        e.preventDefault();
        pick(e.dataTransfer.files);
      }}
    >
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        className="hidden"
        onChange={(e) => pick(e.target.files)}
      />

      <div className="flex items-baseline justify-between mb-2">
        <span className="font-mono text-[10px] uppercase tracking-[0.22em] text-muted">
          {slotLabel}
        </span>
        {filled && (
          <span className="font-mono text-[9px] uppercase tracking-widest text-teal-1 font-bold">
            ✓ loaded
          </span>
        )}
      </div>

      {filled ? (
        <div className="flex items-start gap-3 flex-1 min-h-0">
          <span className="text-2xl leading-none">{icon}</span>
          <div className="min-w-0 flex-1">
            <div className="text-base font-semibold text-navy-1 truncate">{file!.name}</div>
            <div className="text-xs text-muted font-mono mt-1">
              file · {(file!.size / 1024).toFixed(1)} KB
            </div>
            <div className="font-mono text-[10px] text-muted/70 mt-1">{futureHint}</div>
          </div>
          <button
            onClick={onClear}
            className="text-xs text-muted hover:text-status-red px-1.5 py-0.5"
            title="Remove this file"
          >
            ✕
          </button>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => inputRef.current?.click()}
          className="flex items-start gap-3 flex-1 min-h-0 text-left w-full"
        >
          <span className="text-2xl leading-none opacity-60">{icon}</span>
          <div className="min-w-0 flex-1">
            <div className="text-base text-navy-1">Click to choose · or drop here</div>
            <div className="text-xs text-muted mt-1">{slotHint}</div>
            <div className="font-mono text-[10px] text-muted/70 mt-1">{futureHint}</div>
          </div>
        </button>
      )}
    </div>
  );
}
