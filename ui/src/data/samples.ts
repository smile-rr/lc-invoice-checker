/**
 * Pre-defined LC + invoice pairs for the upload page picker.
 *
 * Each pair tells one scenario story (kind = badge label + colour); the user
 * sees both files clearly before clicking. Files live under {@code ui/public/samples}
 * and are kept in sync with {@code docs/refer-doc/} via {@code make ui-samples}.
 */
export type ScenarioKind =
  | 'baseline'           // standard LC, text PDF — clean reference run
  | 'high-value'         // large amount, multi-doc requirements
  | 'strict-tolerance'   // 0% tolerance / NOT EXCEEDING
  | 'fob-eur'            // FOB / EUR / partial OK — different incoterm + currency
  | 'expired'            // LC already expired — exercises date checks
  | 'image-pdf';         // image-based invoice — vision LLM path

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

export const SAMPLES: SamplePair[] = [
  {
    id: 'baseline-apple',
    title: 'Baseline · clean LC',
    note: 'Standard MT700 paired with a text-PDF invoice — fastest end-to-end run.',
    kind: 'baseline',
    lcPath: '/samples/sample_lc_mt700.txt',
    lcLabel: 'sample_lc_mt700.txt',
    invoicePath: '/samples/invoice-1-apple.pdf',
    invoiceLabel: 'invoice-1-apple.pdf',
  },
  {
    id: 'baseline-go-rails',
    title: 'Baseline · alt invoice',
    note: 'Same MT700 against a different text-PDF invoice — exercises layout variance.',
    kind: 'baseline',
    lcPath: '/samples/sample_lc_mt700.txt',
    lcLabel: 'sample_lc_mt700.txt',
    invoicePath: '/samples/invoice-2-go-rails.pdf',
    invoiceLabel: 'invoice-2-go-rails.pdf',
  },
  {
    id: 'high-value-machinery',
    title: 'High-value · multi-doc LC',
    note: 'USD 500K hydraulic-press LC with multiple required documents (CCPIT, mill cert).',
    kind: 'high-value',
    lcPath: '/samples/mt700-large-machinery.txt',
    lcLabel: 'mt700-large-machinery.txt',
    invoicePath: '/samples/invoice-1-apple.pdf',
    invoiceLabel: 'invoice-1-apple.pdf',
  },
  {
    id: 'strict-tolerance-textile',
    title: 'Strict tolerance · NOT EXCEEDING',
    note: 'USD 25K textile LC with 0/0 tolerance — any over-shipment becomes a discrepancy.',
    kind: 'strict-tolerance',
    lcPath: '/samples/mt700-tight-textile.txt',
    lcLabel: 'mt700-tight-textile.txt',
    invoicePath: '/samples/invoice-3-color-claude.pdf',
    invoiceLabel: 'invoice-3-color-claude.pdf',
  },
  {
    id: 'fob-eur-flexible',
    title: 'FOB · EUR · partial allowed',
    note: 'EUR 75K Rotterdam–Hamburg LC under FOB Incoterms; partial shipments permitted.',
    kind: 'fob-eur',
    lcPath: '/samples/mt700-fob-eur-flexible.txt',
    lcLabel: 'mt700-fob-eur-flexible.txt',
    invoicePath: '/samples/invoice-3-color-claude.pdf',
    invoiceLabel: 'invoice-3-color-claude.pdf',
  },
  {
    id: 'expired-strict',
    title: 'Expired LC · date checks',
    note: 'LC that expired 2024-01-15 — should trip presentation-period and expiry rules.',
    kind: 'expired',
    lcPath: '/samples/mt700-expired-strict.txt',
    lcLabel: 'mt700-expired-strict.txt',
    invoicePath: '/samples/invoice-1-apple.pdf',
    invoiceLabel: 'invoice-1-apple.pdf',
  },
  {
    id: 'image-vision',
    title: 'Image PDF · vision lane',
    note: 'Standard LC with an image-only invoice — forces the vision-LLM extractor path.',
    kind: 'image-pdf',
    lcPath: '/samples/sample_lc_mt700.txt',
    lcLabel: 'sample_lc_mt700.txt',
    invoicePath: '/samples/invoice-3-color-image.pdf',
    invoiceLabel: 'invoice-3-color-image.pdf',
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
