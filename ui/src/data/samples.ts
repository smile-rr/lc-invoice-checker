export type SamplePair = {
  id: string;
  label: string;
  note: string;
  lcPath: string;
  invoicePath: string;
};

/**
 * Pre-defined LC + invoice pairs copied into {@code ui/public/samples} by
 * {@code make ui-samples}. One LC text, multiple invoices to exercise the
 * different extractor paths (text PDF, image-based, etc.).
 */
export const SAMPLES: SamplePair[] = [
  {
    id: 'apple',
    label: 'Apple invoice',
    note: 'text PDF',
    lcPath: '/samples/sample_lc_mt700.txt',
    invoicePath: '/samples/invoice-1-apple.pdf',
  },
  {
    id: 'go-rails',
    label: 'Go Rails invoice',
    note: 'text PDF',
    lcPath: '/samples/sample_lc_mt700.txt',
    invoicePath: '/samples/invoice-2-go-rails.pdf',
  },
  {
    id: 'claude-color',
    label: 'Claude color invoice',
    note: 'text PDF',
    lcPath: '/samples/sample_lc_mt700.txt',
    invoicePath: '/samples/invoice-3-color-claude.pdf',
  },
  {
    id: 'claude-image',
    label: 'Claude image invoice',
    note: 'image-based · vision LLM',
    lcPath: '/samples/sample_lc_mt700.txt',
    invoicePath: '/samples/invoice-3-color-image.pdf',
  },
];

/**
 * Fetches both files for a sample pair and returns them as {@code File} objects
 * ready to multipart-upload. Honors an optional {@code AbortSignal} so callers
 * can cancel a slow load when the user picks something else.
 */
export async function fetchSamplePair(
  s: SamplePair,
  signal?: AbortSignal,
): Promise<{ lc: File; invoice: File }> {
  const [lcRes, invRes] = await Promise.all([
    fetch(s.lcPath, { signal }),
    fetch(s.invoicePath, { signal }),
  ]);
  if (!lcRes.ok || !invRes.ok) throw new Error('sample assets missing');
  const lcBlob = await lcRes.blob();
  const invBlob = await invRes.blob();
  return {
    lc: new File([lcBlob], s.lcPath.split('/').pop() ?? 'lc.txt', {
      type: 'text/plain',
    }),
    invoice: new File([invBlob], s.invoicePath.split('/').pop() ?? 'invoice.pdf', {
      type: 'application/pdf',
    }),
  };
}
