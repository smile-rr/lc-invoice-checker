/**
 * Pre-defined LC + invoice pairs for the upload page picker.
 *
 * The manifest is owned by the API at {@code GET /api/v1/samples} and the
 * backing files are streamed from {@code /api/v1/samples/{id}/lc} and
 * {@code /api/v1/samples/{id}/invoice}. The UI no longer ships sample
 * binaries — that lets the same UI bundle work locally, behind nginx, and
 * over a Cloudflare tunnel without touching `/public/samples/`.
 */
import { apiJson, apiFetch } from '../lib/apiClient';

export type ScenarioKind =
  | 'baseline'           // standard text PDF — clean reference run
  | 'image-pdf';         // image / handwritten content — exercises vision LLM path

export type LcVariant = 'pass' | 'fail';

export type SamplePair = {
  /** Stable UI key — composed of {sampleId}-{variant}, unique per card. */
  cardId: string;
  /** Backing sample id from the API manifest (without variant suffix). */
  sampleId: string;
  variant: LcVariant;
  title: string;          // scenario headline shown on the card
  note: string;           // one-line explanation of what this pair tests
  kind: ScenarioKind;
  lcUrl: string;          // GET URL for the MT700 (variant-aware)
  invoiceUrl: string;     // GET URL for the invoice PDF
  lcLabel: string;        // short human label for the LC chip (filename)
  invoiceLabel: string;   // short human label for the invoice chip (filename)
};

interface ApiSampleSummary {
  id: string;
  title: string;
  note: string;
  kind: string;
  invoice_filename: string;
  lc_pass_filename: string;
  lc_fail_filename: string | null;
}

function normaliseKind(k: string): ScenarioKind {
  return k === 'image-pdf' ? 'image-pdf' : 'baseline';
}

function makeCard(s: ApiSampleSummary, variant: LcVariant): SamplePair {
  const lcFilename = variant === 'fail' ? s.lc_fail_filename! : s.lc_pass_filename;
  const variantLabel = variant === 'fail' ? 'FAIL' : 'PASS';
  return {
    cardId: `${s.id}-${variant}`,
    sampleId: s.id,
    variant,
    title: `${s.title} · ${variantLabel}`,
    note: variant === 'fail'
      ? `${s.note} · LC introduces 3 deliberate discrepancies for FAIL verification.`
      : s.note,
    kind: normaliseKind(s.kind),
    lcUrl: `/api/v1/samples/${s.id}/lc?variant=${variant}`,
    invoiceUrl: `/api/v1/samples/${s.id}/invoice`,
    lcLabel: lcFilename,
    invoiceLabel: s.invoice_filename,
  };
}

/**
 * Fetch the sample manifest from the API and expand each entry into one card
 * per LC variant (pass + optional fail). Returns cards in declaration order.
 */
export async function loadSamples(): Promise<SamplePair[]> {
  const list = await apiJson<ApiSampleSummary[]>('/api/v1/samples');
  const out: SamplePair[] = [];
  for (const s of list) {
    out.push(makeCard(s, 'pass'));
    if (s.lc_fail_filename) out.push(makeCard(s, 'fail'));
  }
  return out;
}

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
    apiFetch(s.lcUrl, { signal }),
    apiFetch(s.invoiceUrl, { signal }),
  ]);
  if (!lcRes.ok || !invRes.ok) throw new Error('sample assets missing');
  const lcBlob = await lcRes.blob();
  const invBlob = await invRes.blob();
  return {
    lc: new File([lcBlob], s.lcLabel, { type: 'text/plain' }),
    invoice: new File([invBlob], s.invoiceLabel, { type: 'application/pdf' }),
  };
}
