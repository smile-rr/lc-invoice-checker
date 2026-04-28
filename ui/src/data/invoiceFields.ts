/**
 * Canonical invoice field registry — mirrors the INVOICE FIELD INDEX in catalog.yml.
 * Used by the data preparation hook to drive the field-level grouping view.
 *
 * Source: docs/refer-doc/ucp600_isbp821_invoice_rules.md → Quick Cross-Reference Matrix
 */
import type { CheckStatus } from '../types';

export interface InvoiceFieldDef {
  /** F01 – F17 */
  id: string;
  /** Short display name used in chip labels */
  shortName: string;
  /** Full name matching the reference matrix */
  fieldName: string;
  /** UCP 600 / ISBP 821 anchors */
  refs: string;
  /** Rule IDs that cover this field (empty = not included) */
  coveringRules: string[];
  /**
   * How sub-rule verdicts aggregate into a field-level verdict.
   * Aggregation stops at the first FAIL; otherwise any DOUBTS → DOUBTS;
   * NOT_REQUIRED is excluded from aggregation (informational only).
   */
  aggregationType: 'strong' | 'weak';
  /** null = no rule for this field */
  type: 'PROGRAMMATIC' | 'AGENT' | 'AGENT+TOOL' | 'MIXED' | 'EMBEDDED' | 'NOT_INCLUDED' | null;
}

export const INVOICE_FIELDS: InvoiceFieldDef[] = [
  {
    id: 'F01',
    shortName: 'Issuer',
    fieldName: 'Issuer (Beneficiary as Seller)',
    refs: 'UCP 18(a)  ISBP C6',
    coveringRules: ['ISBP-C6'],
    aggregationType: 'strong',
    type: 'AGENT',
  },
  {
    id: 'F02',
    shortName: 'Buyer',
    fieldName: 'Buyer Name (Applicant)',
    refs: 'UCP 18(a)  ISBP C5',
    coveringRules: ['ISBP-C5'],
    aggregationType: 'strong',
    type: 'AGENT',
  },
  {
    id: 'F03',
    shortName: 'Signature',
    fieldName: 'Invoice Signature',
    refs: 'UCP 18(a)(d)  ISBP C2',
    coveringRules: ['ISBP-C2'],
    aggregationType: 'strong',
    type: 'AGENT',
  },
  {
    id: 'F04',
    shortName: 'LC Ref',
    fieldName: 'LC Number Reference',
    refs: 'UCP 18(a)  ISBP C1',
    coveringRules: ['ISBP-C1'],
    aggregationType: 'strong',
    type: 'AGENT',
  },
  {
    id: 'F05',
    shortName: 'Currency',
    fieldName: 'Currency',
    refs: 'UCP 18(b)  ISBP C8',
    coveringRules: ['UCP-18b-currency'],
    aggregationType: 'strong',
    type: 'PROGRAMMATIC',
  },
  {
    id: 'F06',
    shortName: 'Amount',
    fieldName: 'Invoice Amount ≤ LC Amount',
    refs: 'UCP 18(b)  UCP 30(a)(b)(c)  ISBP C9,C10',
    coveringRules: ['UCP-18b-amount', 'UCP-18b-math', 'UCP-30c', 'ISBP-C10'],
    aggregationType: 'weak',
    type: 'MIXED',
  },
  {
    id: 'F07',
    shortName: 'Unit Price',
    fieldName: 'Unit Price',
    refs: 'UCP 18(b)  ISBP C4',
    coveringRules: ['ISBP-C4'],
    aggregationType: 'strong',
    type: 'AGENT',
  },
  {
    id: 'F08',
    shortName: 'Goods',
    fieldName: 'Goods Description',
    refs: 'UCP 18(c)  ISBP C3',
    coveringRules: ['ISBP-C3'],
    aggregationType: 'strong',
    type: 'AGENT',
  },
  {
    id: 'F09',
    shortName: 'Incoterms',
    fieldName: 'Trade Terms (Incoterms)',
    refs: 'UCP 18(b)(c)  ISBP C8',
    coveringRules: ['ISBP-C8'],
    aggregationType: 'strong',
    type: 'AGENT',
  },
  {
    id: 'F10',
    shortName: 'Origin',
    fieldName: 'Country of Origin',
    refs: 'UCP 18(c)  ISBP C7',
    coveringRules: ['ISBP-C7'],
    aggregationType: 'strong',
    type: 'AGENT',
  },
  {
    id: 'F11',
    shortName: 'Inv. Date',
    fieldName: 'Invoice Date ≤ Presentation Date',
    refs: 'UCP 14(h)(i)  ISBP A19',
    coveringRules: ['UCP-14h', 'UCP-14i'],
    aggregationType: 'weak',
    type: 'PROGRAMMATIC',
  },
  {
    id: 'F12',
    shortName: 'Present.',
    fieldName: 'Presentation within 21 days',
    refs: 'UCP 14(c)  ISBP A1',
    coveringRules: [],
    aggregationType: 'strong',
    type: 'NOT_INCLUDED',
  },
  {
    id: 'F13',
    shortName: 'Cross-ref',
    fieldName: 'Non-contradiction with other docs',
    refs: 'UCP 14(d)  ISBP B1,D1,K1',
    coveringRules: [],
    aggregationType: 'strong',
    type: 'NOT_INCLUDED',
  },
  {
    id: 'F14',
    shortName: 'Address',
    fieldName: 'Address (country consistency)',
    refs: 'UCP 14(e)  ISBP C5,C6',
    coveringRules: ['UCP-14e'],
    aggregationType: 'strong',
    type: 'AGENT',
  },
  {
    id: 'F15',
    shortName: 'Format.',
    fieldName: 'Abbreviations / Minor Typos',
    refs: 'UCP 14(a)  ISBP A14,A15',
    coveringRules: [],
    aggregationType: 'strong',
    type: 'EMBEDDED',
  },
  {
    id: 'F16',
    shortName: 'Qty Tol.',
    fieldName: 'Quantity Tolerance (±5% / ±10%)',
    refs: 'UCP 30(a)(b)  ISBP C3,C4',
    coveringRules: ['UCP-30a', 'UCP-30b'],
    aggregationType: 'weak',
    type: 'AGENT',
  },
  {
    id: 'F17',
    shortName: 'Insurance',
    fieldName: 'Insurance (CIF invoices)',
    refs: 'UCP 28(f)  ISBP E1',
    coveringRules: [],
    aggregationType: 'strong',
    type: 'NOT_INCLUDED',
  },
];

/** Map<Fxx, InvoiceFieldDef> for O(1) lookup */
export const INVOICE_FIELD_BY_ID = new Map<string, InvoiceFieldDef>(
  INVOICE_FIELDS.map((f) => [f.id, f]),
);

/**
 * Aggregate sub-rule statuses into a field-level verdict.
 * - FAIL in any sub-rule → FAIL
 * - any DOUBTS (and no FAIL) → DOUBTS
 * - all PASS → PASS
 * - all NOT_REQUIRED → NOT_REQUIRED
 * - empty → null
 */
export function aggregateVerdict(
  statuses: CheckStatus[],
): CheckStatus | null {
  if (statuses.length === 0) return null;
  if (statuses.every((s) => s === 'NOT_REQUIRED')) return 'NOT_REQUIRED';
  if (statuses.includes('FAIL')) return 'FAIL';
  if (statuses.includes('DOUBTS')) return 'DOUBTS';
  if (statuses.every((s) => s === 'PASS')) return 'PASS';
  return null;
}
