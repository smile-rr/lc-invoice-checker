import { useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { ApiError, getLcRaw, invoiceUrl, startCheck } from '../../api/client';
import { fetchSamplePair, loadSamples, type SamplePair, type ScenarioKind } from '../../data/samples';
import { useConfirm } from '../../hooks/useConfirm';
import { useMandatoryTags } from '../../hooks/useMandatoryTags';
import { validateInvoiceFile, validateLcFile } from '../../lib/validation';
import { SamplePreview } from './SamplePreview';

interface Props {
  /** When true, the run button is the primary CTA. Used pre-session. */
  primaryRunCta?: boolean;
}

export function UploadStep({ primaryRunCta = true }: Props) {
  const nav = useNavigate();
  const loc = useLocation();
  const { id: sessionIdFromRoute } = useParams<{ id: string }>();
  const insideSession = loc.pathname.startsWith('/session/');
  const [lc, setLc] = useState<File | null>(null);
  const [invoice, setInvoice] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [validationErrors, setValidationErrors] = useState<string[]>([]);
  // Samples are pulled from the API at mount-time. Empty array shows nothing
  // until the first response lands; on failure the error band surfaces it.
  const [samples, setSamples] = useState<SamplePair[]>([]);
  const { mandatory } = useMandatoryTags();
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

  // Load the sample manifest from the API once on mount. Soft-fails — if the
  // API is unreachable the picker grid simply stays empty and the operator
  // can still drag/drop their own files.
  useEffect(() => {
    let cancelled = false;
    loadSamples()
      .then((list) => { if (!cancelled) setSamples(list); })
      .catch((e) => { if (!cancelled) setErr(`Could not load samples: ${(e as Error).message}`); });
    return () => { cancelled = true; };
  }, []);

  // When the user navigates back to Upload INSIDE an existing session, load
  // that session's pair from the backend so they land directly on the side-
  // by-side preview (the same shape they had pre-run). Replace / Run still
  // work — Run starts a NEW session keyed off whatever's currently on screen.
  useEffect(() => {
    if (!insideSession || !sessionIdFromRoute) return;
    if (lc || invoice) return;             // already populated this mount
    let cancelled = false;
    (async () => {
      try {
        const [lcText, invRes] = await Promise.all([
          getLcRaw(sessionIdFromRoute),
          fetch(invoiceUrl(sessionIdFromRoute)),
        ]);
        if (cancelled) return;
        if (!invRes.ok) throw new Error(`invoice fetch failed: ${invRes.status}`);
        const invBlob = await invRes.blob();
        if (cancelled) return;
        setLc(new File([new Blob([lcText])], 'lc.txt', { type: 'text/plain' }));
        setInvoice(new File([invBlob], 'invoice.pdf', { type: 'application/pdf' }));
      } catch (e) {
        if (cancelled) return;
        // Soft-fail — leave the empty slots so the user can pick anew.
        setErr(`Could not load this session's pair: ${(e as Error).message}`);
      }
    })();
    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionIdFromRoute, insideSession]);

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

  // Re-run client-side validation whenever the operator swaps either file.
  // Errors are accumulated so users see all problems at once instead of fixing
  // one and bumping into the next on the next attempt.
  useEffect(() => {
    let cancelled = false;
    if (!lc && !invoice) {
      setValidationErrors([]);
      return;
    }
    (async () => {
      const errs: string[] = [];
      if (lc) errs.push(...(await validateLcFile(lc, { mandatoryTags: mandatory })));
      if (invoice) errs.push(...(await validateInvoiceFile(invoice)));
      if (!cancelled) setValidationErrors(errs);
    })();
    return () => { cancelled = true; };
  }, [lc, invoice, mandatory]);

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
    if (validationErrors.length > 0) {
      setErr('Resolve the validation issues above before submitting.');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      const r = await startCheck(lc, invoice);
      // Don't pin a `?step=` here — that would block downstream auto-advance
      // (e.g. lc → invoice once invoice_extract starts streaming). The empty-
      // upload-flash race is handled in `derivedStep` (see steps.ts), which
      // falls back to the first content step when a session exists but no
      // SSE event has arrived yet.
      nav(`/session/${r.session_id}`);
    } catch (e) {
      // Backend validation errors arrive as ApiError with status=400 and
      // a structured body — surface the server's `message` directly so the
      // user sees the same shape as a client-side validation failure.
      if (e instanceof ApiError && e.body && typeof e.body === 'object') {
        const body = e.body as { message?: string; field?: string };
        setErr([body.field, body.message].filter(Boolean).join(' — ') || e.message);
      } else {
        setErr((e as Error).message);
      }
      setBusy(false);
    }
  }

  // When both files are loaded, switch to the side-by-side preview shell.
  // The empty / single-file states keep the original slot UI so dragging one
  // file at a time still works.
  if (lc && invoice) {
    return (
      <>
        <SamplePreview
          lc={lc}
          invoice={invoice}
          busy={busy}
          onLcChange={setLcGuarded}
          onInvoiceChange={setInvoiceGuarded}
          onClear={() => {
            // Inside a session, "Reset" must leave the session URL too —
            // otherwise SessionPage's tabs keep replaying the previous
            // session's trace into the new file selection. Navigating to '/'
            // unmounts SessionPage cleanly and resets all derived state.
            if (insideSession) {
              nav('/');
              return;
            }
            setLc(null);
            setInvoice(null);
            setErr(null);
          }}
          onRun={run}
          runDisabled={validationErrors.length > 0}
        />
        {(err || validationErrors.length > 0) && (
          <div className="max-w-[1600px] mx-auto px-8 pb-4 space-y-2">
            {validationErrors.length > 0 && (
              <div className="p-3 rounded-btn bg-status-redSoft text-status-red text-sm">
                <div className="font-mono text-[10px] uppercase tracking-widest mb-1">
                  Pre-submit validation
                </div>
                <ul className="list-disc list-inside space-y-0.5">
                  {validationErrors.map((m) => <li key={m}>{m}</li>)}
                </ul>
              </div>
            )}
            {err && (
              <div className="p-3 rounded-btn bg-status-redSoft text-status-red text-sm">{err}</div>
            )}
          </div>
        )}
        {Dialog}
      </>
    );
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

      {/* Run CTA — disabled until both files are loaded; once both are loaded
           the page re-renders into SamplePreview above and this branch unmounts. */}
      <div className="flex items-center gap-3">
        <button
          disabled={!lc || !invoice || busy || validationErrors.length > 0}
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

      {/* Pre-defined samples — clicking one loads both files and flips the page
           into the side-by-side preview. */}
      <div className="border-t border-line pt-5">
        <div className="flex items-baseline justify-between mb-3">
          <span className="text-[10px] uppercase tracking-[0.2em] text-muted">
            Pre-defined samples
          </span>
          <span className="font-mono text-[10px] text-muted">
            {samples.length} scenarios
          </span>
        </div>
        <div className="grid grid-cols-2 gap-2.5">
          {samples.map((s) => (
            <SampleCard key={s.cardId} sample={s} onClick={() => loadSample(s)} />
          ))}
        </div>
      </div>

      {validationErrors.length > 0 && (
        <div className="p-3 rounded-btn bg-status-redSoft text-status-red text-sm">
          <div className="font-mono text-[10px] uppercase tracking-widest mb-1">
            Pre-submit validation
          </div>
          <ul className="list-disc list-inside space-y-0.5">
            {validationErrors.map((m) => <li key={m}>{m}</li>)}
          </ul>
        </div>
      )}
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

  // Blob URL for the native browser preview (PDF → built-in viewer, .txt → plain text).
  // Created lazily per file and revoked when the file changes or the slot unmounts.
  // Note: revoking invalidates any preview tab the user opened from a previous file —
  // acceptable, since picking a new file is an intentional context switch.
  const previewUrl = useMemo(() => (file ? URL.createObjectURL(file) : null), [file]);
  useEffect(() => {
    return () => {
      if (previewUrl) URL.revokeObjectURL(previewUrl);
    };
  }, [previewUrl]);

  function pick(fs: FileList | null) {
    if (!fs || fs.length === 0) return;
    void onFile(fs[0]);
  }

  function openPreview() {
    if (!previewUrl) return;
    // noopener so the new tab can't reach back into our window object.
    window.open(previewUrl, '_blank', 'noopener,noreferrer');
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

      {/* Header row. Filled state puts a comfortably-sized "✕ Remove" pill at top-right
           so the operator can clear without hunting for a tiny icon embedded in the body. */}
      <div className="flex items-baseline justify-between mb-2">
        <span className="font-mono text-[10px] uppercase tracking-[0.22em] text-muted">
          {slotLabel}
        </span>
        {filled && (
          <button
            onClick={onClear}
            className="font-mono text-[10px] uppercase tracking-widest text-muted hover:text-status-red hover:bg-status-redSoft/40 px-2 py-0.5 rounded inline-flex items-center gap-1 transition-colors"
            title="Remove this file"
          >
            <span className="text-[11px] leading-none">✕</span>
            <span>remove</span>
          </button>
        )}
      </div>

      {filled ? (
        <div className="flex items-start gap-3 flex-1 min-h-0">
          <span className="text-2xl leading-none">{icon}</span>
          <div className="min-w-0 flex-1">
            {/* Filename is a button → opens a native browser preview in a new tab.
                 Underline + ↗ glyph signal external-tab behavior. */}
            <button
              onClick={openPreview}
              className="text-base font-semibold text-navy-1 truncate hover:text-teal-1 hover:underline text-left max-w-full inline-flex items-baseline gap-1.5"
              title="Open preview in a new tab"
            >
              <span className="truncate">{file!.name}</span>
              <span className="text-xs text-muted shrink-0" aria-hidden>
                ↗
              </span>
            </button>
            <div className="text-xs font-mono mt-1 inline-flex items-center gap-1.5">
              <span className="text-teal-1 font-semibold">✓ loaded</span>
              <span className="text-muted/60">·</span>
              <span className="text-muted">{(file!.size / 1024).toFixed(1)} KB</span>
            </div>
            <div className="font-mono text-[10px] text-muted/70 mt-1">{futureHint}</div>
          </div>
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

// ─── Sample card ────────────────────────────────────────────────────────────
//
// One outer card per scenario. Header row carries the title + a coloured
// scenario badge; body row stacks two file chips side-by-side that mirror
// the LC / Invoice slots above so the operator can see at a glance which
// two files a click will load.

function SampleCard({ sample, onClick }: { sample: SamplePair; onClick: () => void }) {
  const badge = SCENARIO_BADGE[sample.kind];
  return (
    <button
      onClick={onClick}
      className="text-left rounded-btn border border-line bg-paper hover:border-teal-2 hover:shadow-sm transition-colors px-3 py-3 group"
    >
      {/* Header: title + scenario badge */}
      <div className="flex items-baseline justify-between gap-2 mb-1">
        <span className="text-sm font-semibold text-navy-1 truncate">{sample.title}</span>
        <span
          className={[
            'shrink-0 font-mono text-[9px] uppercase tracking-wider px-1.5 py-0.5 rounded',
            badge.cls,
          ].join(' ')}
          title={badge.tooltip}
        >
          {badge.label}
        </span>
      </div>
      <div className="text-xs text-muted mb-2.5 leading-snug">{sample.note}</div>

      {/* Two file chips — side by side, mirrors the upload slot layout */}
      <div className="grid grid-cols-2 gap-1.5">
        <FileChip icon="📄" role="LC" filename={sample.lcLabel} />
        <FileChip icon="🧾" role="Invoice" filename={sample.invoiceLabel} />
      </div>
    </button>
  );
}

function FileChip({ icon, role, filename }: { icon: string; role: string; filename: string }) {
  return (
    <div className="flex items-center gap-2 px-2 py-1.5 rounded border border-line bg-slate2/40 group-hover:border-teal-2/40 transition-colors min-w-0">
      <span className="text-base leading-none shrink-0" aria-hidden>
        {icon}
      </span>
      <div className="min-w-0 flex-1">
        <div className="font-mono text-[8px] uppercase tracking-widest text-muted leading-none mb-0.5">
          {role}
        </div>
        <div className="font-mono text-[10px] text-navy-1 truncate" title={filename}>
          {filename}
        </div>
      </div>
    </div>
  );
}

const SCENARIO_BADGE: Record<ScenarioKind, { label: string; cls: string; tooltip: string }> = {
  baseline: {
    label: 'baseline',
    cls: 'bg-teal-1/10 text-teal-1',
    tooltip: 'Standard happy-path run — text-PDF invoice',
  },
  'image-pdf': {
    label: 'image · vision',
    cls: 'bg-status-goldSoft text-status-gold',
    tooltip: 'Image-only invoice — forces the vision-LLM extraction lane',
  },
};
