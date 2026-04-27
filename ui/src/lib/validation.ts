// Pre-submit validation for LC text + invoice PDF. Mirrors the backend
// InputValidator + per-tag mandatory check so the user sees the error inline
// before a network round-trip is wasted. Mandatory tag list comes from
// /api/v1/lc-meta/tags so the UI doesn't drift when YAML changes.
//
// Patterns and per-tag length limits are NOT replicated here — the server
// holds the canonical regex; we only do shape checks the UI can do alone.

const PDF_MAGIC = [0x25, 0x50, 0x44, 0x46]; // "%PDF"
const TAG_SNIFF = /:\d{2}[A-Z]?:/;
export const MAX_LC_BYTES = 1 * 1024 * 1024;        // 1 MB
export const MAX_INVOICE_BYTES = 20 * 1024 * 1024;  // 20 MB — matches backend default

export interface ValidationOpts {
  /** Tags the server requires. If absent the UI falls back to the canonical
   *  six (20, 31D, 32B, 45A, 50, 59) so an offline / errored /lc-meta call
   *  still gives sensible feedback. */
  mandatoryTags?: string[];
}

const FALLBACK_MANDATORY = ['20', '31D', '32B', '45A', '50', '59'];

export async function validateLcFile(file: File, opts: ValidationOpts = {}): Promise<string[]> {
  const errors: string[] = [];
  const name = file.name.toLowerCase();
  if (!name.endsWith('.txt')) {
    errors.push(`LC file must be .txt (got "${file.name}")`);
  }
  if (file.size === 0) {
    errors.push('LC file is empty');
  }
  if (file.size > MAX_LC_BYTES) {
    errors.push(`LC file too large (${formatBytes(file.size)} > ${formatBytes(MAX_LC_BYTES)})`);
  }
  // Reading the file once is cheap (LCs are <100 KB typically). Skip if
  // the basic shape checks already failed.
  if (errors.length === 0) {
    const text = await file.text();
    if (!TAG_SNIFF.test(text)) {
      errors.push('LC text contains no MT700 tag (expected pattern :NN[L]: somewhere in the input)');
    } else {
      const required = opts.mandatoryTags && opts.mandatoryTags.length > 0
        ? opts.mandatoryTags
        : FALLBACK_MANDATORY;
      for (const tag of required) {
        if (!text.includes(`:${tag}:`)) {
          errors.push(`Mandatory MT700 field :${tag}: missing from LC input`);
        }
      }
    }
  }
  return errors;
}

export async function validateInvoiceFile(file: File): Promise<string[]> {
  const errors: string[] = [];
  const name = file.name.toLowerCase();
  if (!name.endsWith('.pdf')) {
    errors.push(`Invoice must be .pdf (got "${file.name}")`);
  }
  if (file.size === 0) {
    errors.push('Invoice file is empty');
  }
  if (file.size > MAX_INVOICE_BYTES) {
    errors.push(`Invoice file too large (${formatBytes(file.size)} > ${formatBytes(MAX_INVOICE_BYTES)})`);
  }
  if (errors.length === 0) {
    const head = await file.slice(0, 4).arrayBuffer();
    const bytes = new Uint8Array(head);
    if (bytes.length < 4
        || bytes[0] !== PDF_MAGIC[0]
        || bytes[1] !== PDF_MAGIC[1]
        || bytes[2] !== PDF_MAGIC[2]
        || bytes[3] !== PDF_MAGIC[3]) {
      errors.push('Uploaded file is not a real PDF (header bytes "%PDF" not found)');
    }
  }
  return errors;
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}
