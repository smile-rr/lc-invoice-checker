/**
 * Plain-English summaries of the UCP 600 articles and ISBP 821 paragraphs the
 * rule catalog references. Paraphrased to be safe under ICC copyright — these
 * are NOT verbatim text. The official ICC publication remains the source of
 * truth; bank users with ICC subscriptions can cross-reference there.
 *
 * Keyed by the exact citation string the backend emits in
 * {@code CheckResult.ucp_ref / isbp_ref}.
 */

export interface RegEntry {
  /** Heading shown in the evidence panel. */
  title: string;
  /** One-sentence summary visible without expanding. */
  snippet: string;
  /** Longer plain-English paraphrase shown when the user expands the entry. */
  full: string;
}

export const REGS: Record<string, RegEntry> = {
  'UCP 600 Art. 14(c)': {
    title: 'UCP 600 Article 14(c) — Period for Presentation',
    snippet:
      'Documents must be presented within 21 days of shipment and before the LC expiry, whichever comes first.',
    full: 'A presentation including one or more original transport documents must be made by or on behalf of the beneficiary not later than 21 calendar days after the date of shipment, but in any event not later than the expiry date of the credit.',
  },
  'UCP 600 Art. 14(i)': {
    title: 'UCP 600 Article 14(i) — Date of Documents',
    snippet:
      'A document may pre-date the credit but must not be dated after the date of presentation.',
    full: 'A document may be dated prior to the issuance date of the credit, but must not be dated later than its date of presentation.',
  },
  'UCP 600 Art. 18(a)': {
    title: 'UCP 600 Article 18(a) — Commercial Invoice Issuer & Currency',
    snippet:
      'A commercial invoice must appear to be issued by the beneficiary, made out to the applicant, in the same currency as the credit, and need not be signed.',
    full: 'A commercial invoice must appear to have been issued by the beneficiary (except as provided in article 38), made out in the name of the applicant (except as provided in sub-article 38(g)), made out in the same currency as the credit, and need not be signed.',
  },
  'UCP 600 Art. 18(b)': {
    title: 'UCP 600 Article 18(b) — Invoice Amount Tolerance',
    snippet:
      'A bank may accept an invoice issued for an amount in excess of the credit only if it has not honoured or negotiated above the credit amount.',
    full: 'A nominated bank acting on its nomination, a confirming bank, or the issuing bank may accept a commercial invoice issued for an amount in excess of the amount permitted by the credit, and its decision will be binding upon all parties, provided the bank in question has not honoured or negotiated for an amount in excess of that permitted by the credit.',
  },
  'UCP 600 Art. 18(c)': {
    title: 'UCP 600 Article 18(c) — Goods Description',
    snippet:
      'The description of the goods, services or performance in a commercial invoice must correspond with that in the credit.',
    full: 'The description of the goods, services or performance in a commercial invoice must correspond with that appearing in the credit. No mirror image is required, but the description on the invoice must not contradict the credit description.',
  },
  'UCP 600 Art. 30(b)': {
    title: 'UCP 600 Article 30(b) — Tolerance on Quantity & Amount',
    snippet:
      'Where goods are not described by units of weight or count, a 5% tolerance more or less is permitted, provided the credit amount is not exceeded.',
    full: 'A tolerance not to exceed 5% more or 5% less than the quantity of the goods is allowed, provided the credit does not state the quantity in terms of a stipulated number of packing units or individual items, and the total amount of the drawings does not exceed the amount of the credit.',
  },
  'ISBP 821 C1': {
    title: 'ISBP 821 paragraph C1 — Issuer of an Invoice',
    snippet:
      'An invoice must appear issued by the beneficiary; if the credit requires the LC number to appear, the invoice must show it.',
    full: 'An invoice is to appear to have been issued by the beneficiary or, in the case of a transferable credit, by the second beneficiary. When the credit requires the credit number to be quoted on the invoice, that number must appear on the invoice as required.',
  },
  'ISBP 821 C3': {
    title: 'ISBP 821 paragraph C3 — Description of Goods',
    snippet:
      'The invoice description must correspond with the credit. No mirror image is required; details may be spread across the invoice.',
    full: 'The description of the goods, services or performance shown on the invoice is to correspond with that in the credit. There is no requirement for a mirror image. For example, details of the goods may be stated in a number of areas within the invoice, which read together represent a description corresponding to that in the credit.',
  },
  'ISBP 821 C5': {
    title: 'ISBP 821 paragraph C5 — Addresses & Trade Term Source',
    snippet:
      'Beneficiary and applicant addresses on the invoice need not match the credit but must be in the same country. Trade term sources must be stated when relevant.',
    full: 'When a trade term is part of the goods description in the credit, or stated in connection with the amount, the trade term and any reference to its source (e.g. Incoterms 2020) is to be indicated on the invoice. Beneficiary and applicant addresses shown on the invoice need not be the same as those stated in the credit but must be within the same country.',
  },
  'ISBP 821 C6': {
    title: 'ISBP 821 paragraph C6 — Applicant',
    snippet:
      'When the credit requires the invoice in the applicant’s name, the name on the invoice must correspond to the credit.',
    full: 'When a credit calls for an invoice to be issued in the name of the applicant, the name of the applicant on the invoice must correspond with that stated in the credit.',
  },
  'ISBP 821 C13': {
    title: 'ISBP 821 paragraph C13 — Quantity, Weight & Unit Price',
    snippet:
      'Only the credit-stated quantity (or the article 30 tolerance) may be invoiced. Unit prices must not contradict the credit.',
    full: 'When a credit specifies a quantity of goods, only the quantity stated in the credit (or one acceptable in tolerance under UCP 600 article 30) may be invoiced. Where unit prices are stated in the credit, the unit prices on the invoice must not be inconsistent with the credit.',
  },
  'ISBP 821 C14': {
    title: 'ISBP 821 paragraph C14 — Trade Terms',
    snippet:
      'The invoice must indicate the same trade term as the credit (CIF, FOB, etc.) including the named place.',
    full: 'When a credit indicates a trade term as part of the goods description, the invoice must indicate the same trade term and the trade term named place. Charges or costs to be included in the trade term must not appear as separate line items on the invoice unless the credit so allows.',
  },
  'ISBP 821 C16': {
    title: 'ISBP 821 paragraph C16 — Country of Origin',
    snippet:
      'Country of origin shown on the invoice must correspond with any certificate of origin presented under the credit.',
    full: 'When a credit calls for an indication of the country of origin on the invoice, that indication must correspond with the country shown in any certificate of origin presented under the same credit.',
  },
};

/**
 * Look up a citation. Returns {@code null} if the reference isn't in our
 * catalog (e.g. a new rule cites an article we haven't authored yet).
 */
export function lookupReg(ref: string | null | undefined): RegEntry | null {
  if (!ref) return null;
  return REGS[ref] ?? null;
}
