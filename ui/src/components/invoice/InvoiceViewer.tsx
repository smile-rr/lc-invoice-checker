import { useEffect, useState } from 'react';
import { Document, Page, pdfjs } from 'react-pdf';
import { invoiceUrl } from '../../api/client';

pdfjs.GlobalWorkerOptions.workerSrc = `https://unpkg.com/pdfjs-dist@${pdfjs.version}/build/pdf.worker.min.mjs`;

interface Props {
  /** Either a sessionId (resolves to backend /invoice URL),
   *  a direct URL, or a {@code File}/{@code Blob} for a locally-loaded PDF. */
  sessionId?: string;
  src?: string;
  file?: File | Blob;
  /** Optional text-content highlight (used by the InvoiceAuditTab hover sync). */
  highlight?: string | null;
  maxHeightClass?: string;
}

const BASE_WIDTH = 560;
const ZOOM_MIN = 0.5;
const ZOOM_MAX = 2.5;
const ZOOM_STEP = 0.25;

/**
 * pdf.js render with a slim toolbar — zoom in/out, current %, reset. The
 * container is overflow-auto on both axes so when zoom pushes the page wider
 * or taller than the viewport the user gets natural scroll-as-pan. Toolbar is
 * {@code sticky top-0} inside the scroll container so it follows the user
 * down a tall document.
 */
export function InvoiceViewer({ sessionId, src, file, highlight, maxHeightClass = 'max-h-[78vh]' }: Props) {
  const [numPages, setNumPages] = useState(0);
  const [zoom, setZoom] = useState(1.0);
  const [err, setErr] = useState<string | null>(null);

  // pdfjs handles File/Blob via { data: ArrayBuffer } — fetching a blob URL
  // string fails ("Unexpected server response (0)"). For local previews pass
  // the File directly; backend URLs and arbitrary src strings stay as URLs.
  const docFile: File | Blob | string | null =
    file ?? src ?? (sessionId ? invoiceUrl(sessionId) : null);

  useEffect(() => {
    if (!highlight) {
      document.querySelectorAll('.pdf-hit').forEach((el) => el.classList.remove('pdf-hit'));
      return;
    }
    const t = window.setTimeout(() => applyHighlight(highlight), 150);
    return () => window.clearTimeout(t);
  }, [highlight]);

  const dec = () =>
    setZoom((z) => Math.max(ZOOM_MIN, +(z - ZOOM_STEP).toFixed(2)));
  const inc = () =>
    setZoom((z) => Math.min(ZOOM_MAX, +(z + ZOOM_STEP).toFixed(2)));
  const reset = () => setZoom(1.0);

  if (err) {
    return <div className="p-4 text-sm text-status-red">PDF load failed: {err}</div>;
  }
  if (!docFile) {
    return <div className="p-4 text-sm text-muted italic">No PDF source provided.</div>;
  }

  return (
    <div className={`bg-paper rounded-card border border-line overflow-auto ${maxHeightClass}`}>
      {/* Toolbar — sticky inside the scroll container */}
      <div className="sticky top-0 z-10 bg-slate2/95 backdrop-blur border-b border-line px-3 py-1.5 flex items-center gap-1 text-xs">
        <ToolBtn onClick={dec} disabled={zoom <= ZOOM_MIN} label="Zoom out">−</ToolBtn>
        <span className="font-mono w-12 text-center select-none">
          {Math.round(zoom * 100)}%
        </span>
        <ToolBtn onClick={inc} disabled={zoom >= ZOOM_MAX} label="Zoom in">+</ToolBtn>
        <span className="w-px h-4 bg-line mx-1.5" />
        <ToolBtn onClick={reset} disabled={zoom === 1.0} label="Reset zoom">
          <span className="font-mono text-[11px]">100%</span>
        </ToolBtn>
        <div className="ml-auto font-mono text-[10px] text-muted">
          {numPages > 0 ? `${numPages} page${numPages === 1 ? '' : 's'}` : ''}
        </div>
      </div>

      <Document
        file={docFile}
        onLoadSuccess={(d) => setNumPages(d.numPages)}
        onLoadError={(e) => setErr(e.message)}
        loading={<div className="p-6 text-sm text-muted">Loading PDF…</div>}
      >
        <div className="py-4 flex flex-col items-center gap-4">
          {Array.from({ length: numPages }, (_, i) => (
            <Page
              key={i}
              pageNumber={i + 1}
              width={BASE_WIDTH * zoom}
              renderTextLayer
              renderAnnotationLayer={false}
            />
          ))}
        </div>
      </Document>
    </div>
  );
}

function ToolBtn({
  children,
  onClick,
  disabled,
  label,
}: {
  children: React.ReactNode;
  onClick: () => void;
  disabled?: boolean;
  label: string;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={label}
      aria-label={label}
      className="px-2 py-0.5 rounded font-mono text-sm text-navy-1 hover:bg-paper hover:border-line border border-transparent disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
    >
      {children}
    </button>
  );
}

function applyHighlight(query: string) {
  document.querySelectorAll('.pdf-hit').forEach((el) => el.classList.remove('pdf-hit'));
  const q = query.trim();
  if (!q) return;
  if (/^\d{1,4}$/.test(q)) return;
  const needle = q.toLowerCase();
  const spans = document.querySelectorAll<HTMLSpanElement>('.react-pdf__Page__textContent > span');
  let hit: HTMLSpanElement | null = null;
  for (const s of spans) {
    if (s.textContent?.toLowerCase().includes(needle)) {
      s.classList.add('pdf-hit');
      if (!hit) hit = s;
    }
  }
  if (hit) hit.scrollIntoView({ behavior: 'smooth', block: 'center' });
}
