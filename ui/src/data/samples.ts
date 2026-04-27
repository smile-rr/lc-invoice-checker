/**
 * Pre-defined LC + invoice pairs for the upload page picker.
 *
 * Each pair tells one scenario story (kind = badge label + colour). For now
 * every pair uses the default {@code sample_lc_mt700.txt}; LLM-generated MT700
 * variants will land here as one-line {@code lcPath} edits when ready.
 *
 * Files live under {@code ui/public/samples/} and are kept in sync with
 * {@code test/invoice/} via {@code make ui-samples}.
 */
export type ScenarioKind =
  | 'baseline'           // standard text PDF — clean reference run
  | 'image-pdf';         // image / handwritten content — exercises vision LLM path

export type SamplePair = {
  id: string;
  title: string;          // scenario headline shown on the card
  note: string;           // one-line explanation of what this pair tests
  kind: ScenarioKind;
  lcPath: string;
  lcLabel: string;        // short human label for the LC chip
  invoicePath: string;
  invoiceLabel: string;   // short human label for the invoice chip
};

const DEFAULT_LC = '/samples/sample_lc_mt700.txt';
const DEFAULT_LC_LABEL = 'sample_lc_mt700.txt';

export const SAMPLES: SamplePair[] = [
  // ── Golden test pairs (test/golden/, mirrored to ui/public/samples/) ──
  // Curated demo cases with known verdict patterns. See test/golden/README.md
  // for the full expected outcome of each pair.
  {
    id: 'golden-01-widgets-pass',
    title: '01 · widgets · all-pass LC',
    note: 'MT700 hand-tuned to fully comply with the widget invoice — every applicable rule PASSes.',
    kind: 'baseline',
    lcPath: '/samples/invoice-01-widgets-singapore__pass.txt',
    lcLabel: 'invoice-01__pass.txt',
    invoicePath: '/samples/invoice-01-widgets-singapore.pdf',
    invoiceLabel: 'invoice-01-widgets.pdf',
  },
  {
    id: 'golden-01-widgets-fail',
    title: '01 · widgets · 3 deliberate FAILs',
    note: 'MT700 with currency / amount / beneficiary mismatched against the widget invoice.',
    kind: 'baseline',
    lcPath: '/samples/invoice-01-widgets-singapore__fail.txt',
    lcLabel: 'invoice-01__fail.txt',
    invoicePath: '/samples/invoice-01-widgets-singapore.pdf',
    invoiceLabel: 'invoice-01-widgets.pdf',
  },
  {
    id: 'inc-4-proforma',
    title: 'Inc-4 · proforma A',
    note: 'Proforma invoice with image-style header — exercises vision extractor.',
    kind: 'image-pdf',
    lcPath: DEFAULT_LC,
    lcLabel: DEFAULT_LC_LABEL,
    invoicePath: '/samples/inc-4-proforma.jpg.pdf',
    invoiceLabel: 'inc-4-proforma.jpg.pdf',
  },
  {
    id: 'inc-5-proforma-2',
    title: 'Inc-5 · proforma B',
    note: 'Second proforma layout variant.',
    kind: 'baseline',
    lcPath: DEFAULT_LC,
    lcLabel: DEFAULT_LC_LABEL,
    invoicePath: '/samples/inc-5-proforma-2.pdf',
    invoiceLabel: 'inc-5-proforma-2.pdf',
  },
  {
    id: 'inc-6-logo',
    title: 'Inc-6 · logo header',
    note: 'Invoice with logo header and rich layout.',
    kind: 'baseline',
    lcPath: DEFAULT_LC,
    lcLabel: DEFAULT_LC_LABEL,
    invoicePath: '/samples/inc-6-logo.pdf',
    invoiceLabel: 'inc-6-logo.pdf',
  },
  {
    id: 'inc-8-abc',
    title: 'Inc-8 · ABC layout',
    note: 'ABC-co invoice — three-column layout.',
    kind: 'baseline',
    lcPath: DEFAULT_LC,
    lcLabel: DEFAULT_LC_LABEL,
    invoicePath: '/samples/inc-8-abc.pdf',
    invoiceLabel: 'inc-8-abc.pdf',
  },
  {
    id: 'inv-1-global-e',
    title: 'Inv-1 · global-e',
    note: 'Standard global-e commercial invoice.',
    kind: 'baseline',
    lcPath: DEFAULT_LC,
    lcLabel: DEFAULT_LC_LABEL,
    invoicePath: '/samples/inv-1-global-e.pdf',
    invoiceLabel: 'inv-1-global-e.pdf',
  },
  {
    id: 'inv-2-art-finder',
    title: 'Inv-2 · art finder',
    note: 'Art-finder mid-density invoice with multiple line items.',
    kind: 'baseline',
    lcPath: DEFAULT_LC,
    lcLabel: DEFAULT_LC_LABEL,
    invoicePath: '/samples/inv-2-art-finder.pdf',
    invoiceLabel: 'inv-2-art-finder.pdf',
  },
  {
    id: 'inv-3-half-handwritten',
    title: 'Inv-3 · half-handwritten',
    note: 'Mixed printed + handwritten invoice — stress test for the vision LLM lane.',
    kind: 'image-pdf',
    lcPath: DEFAULT_LC,
    lcLabel: DEFAULT_LC_LABEL,
    invoicePath: '/samples/inv-3-half-handwritten.pdf',
    invoiceLabel: 'inv-3-half-handwritten.pdf',
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
