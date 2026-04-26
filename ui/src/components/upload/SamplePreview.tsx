import { useEffect, useRef, useState } from 'react';
import { InvoiceViewer } from '../invoice/InvoiceViewer';

interface Props {
  lc: File;
  invoice: File;
  busy: boolean;
  onLcChange: (f: File) => void | Promise<void>;
  onInvoiceChange: (f: File) => void | Promise<void>;
  onClear: () => void;
  onRun: () => void;
  /** Optional run-button label override — useful for re-run / replay flows. */
  runLabel?: string;
}

const LC_ACCEPT = '.txt,text/plain';
const PDF_ACCEPT = 'application/pdf,.pdf';

/**
 * Side-by-side preview of a loaded LC + invoice pair. Used after the user
 * picks a sample (or drops both files), and also when navigating back to
 * "Upload" inside an existing session — there the files are pre-loaded from
 * the backend so the operator sees what was already submitted.
 *
 * <p>Header carries the primary CTA (Run) so it's always visible without
 * scrolling. Each pane has its own "Replace" control with strict MIME
 * validation: only .txt for the LC, only .pdf for the invoice.
 */
export function SamplePreview({
  lc,
  invoice,
  busy,
  onLcChange,
  onInvoiceChange,
  onClear,
  onRun,
  runLabel,
}: Props) {
  return (
    // Fill the available height of the page body so the side-by-side panes
    // stretch to the bottom instead of stopping at a fixed 560px and leaving
    // a big empty band below. The parent wrapper (UploadPage / SessionPage's
    // Scrollable) provides the height; we just claim it.
    <div className="max-w-[1600px] mx-auto px-8 py-5 h-full flex flex-col gap-4 min-h-0">
      <div className="flex items-center gap-3 border-b border-line pb-3 shrink-0">
        <div className="flex-1 min-w-0">
          <h2 className="font-serif text-lg text-navy-1 leading-tight">Pair preview</h2>
          <span className="font-mono text-[11px] text-muted">
            Inspect both inputs · replace either if needed · then run.
          </span>
        </div>
        <button
          onClick={onClear}
          disabled={busy}
          className="text-xs text-muted hover:text-navy-1 px-3 py-2 disabled:opacity-40"
          title="Clear both files and pick again"
        >
          Replace pair
        </button>
        <button
          disabled={busy}
          onClick={onRun}
          className="px-5 py-2.5 rounded-btn font-medium bg-navy-1 text-white hover:bg-navy-2 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          {busy ? 'Starting…' : (runLabel ?? '▶  Run Compliance Check')}
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-5 flex-1 min-h-0">
        <LcPane file={lc} onReplace={onLcChange} />
        <InvoicePane file={invoice} onReplace={onInvoiceChange} />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// LC pane — MT700 text, monospace, scrolls inside the pane
// ---------------------------------------------------------------------------

function LcPane({ file, onReplace }: { file: File; onReplace: (f: File) => void | Promise<void> }) {
  const [text, setText] = useState<string>('');
  const [err, setErr] = useState<string | null>(null);
  const [edited, setEdited] = useState<boolean>(false);
  const inputRef = useRef<HTMLInputElement>(null);

  // Load file content into editable buffer whenever the file identity changes
  // (replace-file or new sample). The `edited` flag tracks whether the user
  // typed since load — used to gate the "Reset" affordance.
  useEffect(() => {
    let cancelled = false;
    file.text().then((s) => {
      if (cancelled) return;
      setText(s);
      setEdited(false);
    });
    return () => {
      cancelled = true;
    };
  }, [file]);

  function pick(fs: FileList | null) {
    setErr(null);
    if (!fs || fs.length === 0) return;
    const f = fs[0];
    if (!isTxt(f)) {
      setErr(`MT700 must be .txt — got ${f.name}`);
      return;
    }
    void onReplace(f);
  }

  // Live-sync edits: every keystroke materialises a new File so Run uses the
  // latest text without requiring an explicit "save". Filename is preserved
  // so it stays meaningful in subsequent UI surfaces.
  function onTextChange(next: string) {
    setText(next);
    setEdited(true);
    const replacement = new File([next], file.name, { type: 'text/plain' });
    void onReplace(replacement);
  }

  return (
    <section className="rounded-card border border-line bg-paper flex flex-col h-full min-h-0 overflow-hidden">
      <PaneHead
        slotLabel="LETTER OF CREDIT · MT700"
        filename={file.name}
        sizeBytes={new Blob([text]).size || file.size}
        onReplaceClick={() => inputRef.current?.click()}
        replaceHint=".txt only"
        edited={edited}
      />
      <input
        ref={inputRef}
        type="file"
        accept={LC_ACCEPT}
        className="hidden"
        onChange={(e) => pick(e.target.files)}
      />
      {err && (
        <div className="px-4 py-2 bg-status-redSoft text-status-red text-xs border-b border-status-red/30">
          {err}
        </div>
      )}
      <textarea
        value={text}
        onChange={(e) => onTextChange(e.target.value)}
        spellCheck={false}
        className="flex-1 min-h-0 overflow-auto px-4 py-3 font-mono text-[11px] leading-relaxed text-navy-1 whitespace-pre bg-paper resize-none focus:outline-none focus:ring-1 focus:ring-teal-2/40"
        aria-label="MT700 text — editable"
      />
    </section>
  );
}

// ---------------------------------------------------------------------------
// Invoice pane — PDF rendered via the same InvoiceViewer component used in
// the Invoice tab, sourced from a local Blob URL so it works pre-session.
// ---------------------------------------------------------------------------

function InvoicePane({ file, onReplace }: { file: File; onReplace: (f: File) => void | Promise<void> }) {
  const [err, setErr] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  function pick(fs: FileList | null) {
    setErr(null);
    if (!fs || fs.length === 0) return;
    const f = fs[0];
    if (!isPdf(f)) {
      setErr(`Invoice must be PDF — got ${f.name}`);
      return;
    }
    void onReplace(f);
  }

  return (
    <section className="rounded-card border border-line bg-paper flex flex-col h-full min-h-0 overflow-hidden">
      <PaneHead
        slotLabel="COMMERCIAL INVOICE · PDF"
        filename={file.name}
        sizeBytes={file.size}
        onReplaceClick={() => inputRef.current?.click()}
        replaceHint=".pdf only"
      />
      <input
        ref={inputRef}
        type="file"
        accept={PDF_ACCEPT}
        className="hidden"
        onChange={(e) => pick(e.target.files)}
      />
      {err && (
        <div className="px-4 py-2 bg-status-redSoft text-status-red text-xs border-b border-status-red/30">
          {err}
        </div>
      )}
      <div className="flex-1 min-h-0 overflow-hidden p-2">
        <InvoiceViewer file={file} maxHeightClass="max-h-full" />
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Shared pane head — filename + size + Replace control
// ---------------------------------------------------------------------------

function PaneHead({
  slotLabel,
  filename,
  sizeBytes,
  onReplaceClick,
  replaceHint,
  edited,
}: {
  slotLabel: string;
  filename: string;
  sizeBytes: number;
  onReplaceClick: () => void;
  replaceHint: string;
  /** True if the operator has typed in this pane since the file was loaded. */
  edited?: boolean;
}) {
  return (
    <header className="px-4 py-2.5 border-b border-line bg-slate2/60 flex items-center gap-3">
      <div className="min-w-0 flex-1">
        <div className="font-mono text-[10px] uppercase tracking-[0.18em] text-muted flex items-center gap-2">
          <span>{slotLabel}</span>
          {edited && (
            <span className="font-sans normal-case tracking-normal text-[10px] text-status-gold border border-status-gold/40 bg-status-goldSoft/50 px-1.5 py-0 rounded-sm">
              edited
            </span>
          )}
        </div>
        <div className="text-sm font-semibold text-navy-1 truncate" title={filename}>
          {filename}
        </div>
      </div>
      <span className="font-mono text-[10px] text-muted whitespace-nowrap">
        {(sizeBytes / 1024).toFixed(1)} KB
      </span>
      <button
        onClick={onReplaceClick}
        title={`Replace this file (${replaceHint})`}
        className="font-mono text-[10px] uppercase tracking-[0.12em] px-2 py-1 border border-line text-navy-1 hover:border-teal-2 hover:text-teal-2 transition-colors"
      >
        Replace
      </button>
    </header>
  );
}

function isTxt(f: File): boolean {
  return f.type === 'text/plain' || /\.txt$/i.test(f.name);
}

function isPdf(f: File): boolean {
  return f.type === 'application/pdf' || /\.pdf$/i.test(f.name);
}
