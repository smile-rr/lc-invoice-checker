/**
 * Canonical UCP 600 / ISBP 821 reference excerpts.
 *
 * Sources:
 *   - catalog.yml: ucp_ref → ucp_excerpt, isbp_ref → isbp_excerpt
 *   - ucp600_isbp821_invoice_rules.md: full article text for embedded/not-included refs
 *
 * Keys match the `refs` field in invoiceFields.ts (abbreviated form).
 * Full form (from catalog.yml) included as aliases.
 */

/** Maps abbreviated ref ("UCP 18(a)") and full ref ("UCP 600 Art. 18(a)") to excerpt. */
export const UCP_ISBP_EXCERPTS: Record<string, string> = {
  // ── UCP 600 ────────────────────────────────────────────────────────────────
  'UCP 600 Art. 18(a)': 'Invoice must appear to be issued by the beneficiary; must be made out in the name of the applicant; need not be signed.',
  'UCP 18(a)': 'Invoice must appear to be issued by the beneficiary; must be made out in the name of the applicant; need not be signed.',

  'UCP 600 Art. 18(b)': 'Invoice must be made out in the same currency as the LC; invoice amount must not exceed the LC amount. The keywords "about" or "approximately" allow a 10% tolerance; otherwise 5% more-or-less applies only to quantity if not unit-priced.',
  'UCP 18(b)': 'Invoice must be made out in the same currency as the LC; invoice amount must not exceed the LC amount. The keywords "about" or "approximately" allow a 10% tolerance; otherwise 5% more-or-less applies only to quantity if not unit-priced.',

  'UCP 600 Art. 18(c)': 'Description of goods, services, or performance on the invoice must correspond with that appearing in the LC.',
  'UCP 18(c)': 'Description of goods, services, or performance on the invoice must correspond with that appearing in the LC.',

  'UCP 600 Art. 14(a)': 'A nominated bank, confirming bank, and issuing bank must each determine if documents constitute a complying presentation. Invoice must comply on its face with the LC terms; strict compliance standard applies.',
  'UCP 14(a)': 'A nominated bank, confirming bank, and issuing bank must each determine if documents constitute a complying presentation. Invoice must comply on its face with the LC terms; strict compliance standard applies.',

  'UCP 600 Art. 14(c)': 'Documents must be presented within 21 calendar days after shipment date but not later than the LC expiry date.',
  'UCP 14(c)': 'Documents must be presented within 21 calendar days after shipment date but not later than the LC expiry date.',

  'UCP 600 Art. 14(d)': 'Non-Contradiction Rule: documents must not contradict each other.',
  'UCP 14(d)': 'Non-Contradiction Rule: documents must not contradict each other.',

  'UCP 600 Art. 14(e)': 'Addresses of the beneficiary and applicant appearing in any stipulated document need not be the same as in the credit, provided they are within the same country as in the credit.',
  'UCP 14(e)': 'Addresses of the beneficiary and applicant appearing in any stipulated document need not be the same as in the credit, provided they are within the same country as in the credit.',

  'UCP 600 Art. 14(h)': 'A document may be dated prior to the issuance date of the credit but not later than its date of presentation.',
  'UCP 14(h)': 'A document may be dated prior to the issuance date of the credit but not later than its date of presentation.',

  'UCP 600 Art. 14(i)': 'Invoice must not be dated later than the date of presentation; document date must precede or equal presentation date.',
  'UCP 14(i)': 'Invoice must not be dated later than the date of presentation; document date must precede or equal presentation date.',

  'UCP 600 Art. 30(a)': 'The words "about" or "approximately" used in connection with the amount of the credit or the quantity or the unit price stated in the credit are to be construed as allowing a tolerance not to exceed 10% more or 10% less.',
  'UCP 30(a)': 'The words "about" or "approximately" used in connection with the amount of the credit or the quantity or the unit price stated in the credit are to be construed as allowing a tolerance not to exceed 10% more or 10% less.',
  'UCP 600 Art. 30(b)': 'A tolerance not exceeding 5% more or 5% less in the quantity of the goods is allowed, provided the credit does not stipulate the quantity in terms of a stipulated number of packing units or individual items and the total amount of the drawings does not exceed the amount of the credit.',
  'UCP 30(b)': 'A tolerance not exceeding 5% more or 5% less in the quantity of the goods is allowed, provided the credit does not stipulate the quantity in terms of a stipulated number of packing units or individual items and the total amount of the drawings does not exceed the amount of the credit.',
  'UCP 600 Art. 30(c)': 'A drawing under a credit may be for less than the amount of the credit, even if partial drawings or shipments are not permitted.',
  'UCP 30(c)': 'A drawing under a credit may be for less than the amount of the credit, even if partial drawings or shipments are not permitted.',

  'UCP 600 Art. 28(f)': 'Insurance coverage must be at least 110% of CIF/CIP value. If invoice is CIF, insurance amount must be >= 110% of invoice value.',
  'UCP 28(f)': 'Insurance coverage must be at least 110% of CIF/CIP value. If invoice is CIF, insurance amount must be >= 110% of invoice value.',

  // ── ISBP 821 ──────────────────────────────────────────────────────────────
  'ISBP 821 Para. C1': 'If the LC requires the invoice to bear the LC number, failure to state it is a discrepancy; if not required, the invoice may optionally state the LC number.',
  'ISBP C1': 'If the LC requires the invoice to bear the LC number, failure to state it is a discrepancy; if not required, the invoice may optionally state the LC number.',

  'ISBP 821 Para. C2': 'Invoice need not be signed unless the LC explicitly requires a signature; if required, any form of authentication (stamp, electronic signature) is acceptable.',
  'ISBP C2': 'Invoice need not be signed unless the LC explicitly requires a signature; if required, any form of authentication (stamp, electronic signature) is acceptable.',

  'ISBP 821 Para. C3': 'The description of goods on the invoice must correspond with the LC description; it may be more specific but must NOT be more general; the description need not be identical word-for-word but must clearly correspond.',
  'ISBP C3': 'The description of goods on the invoice must correspond with the LC description; it may be more specific but must NOT be more general; the description need not be identical word-for-word but must clearly correspond.',

  'ISBP 821 Para. C4': 'If the LC stipulates a unit price, the invoice must reflect that unit price; a higher total arising from a different unit price is discrepant even if the grand total falls within tolerance.',
  'ISBP C4': 'If the LC stipulates a unit price, the invoice must reflect that unit price; a higher total arising from a different unit price is discrepant even if the grand total falls within tolerance.',

  'ISBP 821 Para. C5': "The applicant's name and address on the invoice must correspond with those in the LC (Field 50); minor formatting differences in address are acceptable if they do not create ambiguity.",
  'ISBP C5': "The applicant's name and address on the invoice must correspond with those in the LC (Field 50); minor formatting differences in address are acceptable if they do not create ambiguity.",

  'ISBP 821 Para. C6': "The beneficiary's name on the invoice must correspond with the LC (Field 59); address need not be identical but must be in the same country.",
  'ISBP C6': "The beneficiary's name on the invoice must correspond with the LC (Field 59); address need not be identical but must be in the same country.",

  'ISBP 821 Para. C7': 'If the LC requires country of origin to be stated on the invoice, absence is a discrepancy.',
  'ISBP C7': 'If the LC requires country of origin to be stated on the invoice, absence is a discrepancy.',

  'ISBP 821 Para. C8': 'If the LC states a trade term (CIF, FOB, CFR, etc.), the invoice must reflect the same term and destination.',
  'ISBP C8': 'If the LC states a trade term (CIF, FOB, CFR, etc.), the invoice must reflect the same term and destination.',

  'ISBP 821 Para. C9': 'If multiple invoices are presented, their combined total must not exceed the LC amount; each invoice must individually comply.',
  'ISBP C9': 'If multiple invoices are presented, their combined total must not exceed the LC amount; each invoice must individually comply.',

  'ISBP 821 Para. C10': 'Freight, insurance, or other charges on invoice must be consistent with the trade term; charges should not inflate total above LC amount.',
  'ISBP C10': 'Freight, insurance, or other charges on invoice must be consistent with the trade term; charges should not inflate total above LC amount.',

  'ISBP 821 Para. A1': 'Defines "international standard banking practice"; examiner should consider whether documents comply based on their data content. Do not apply overly strict typographic standards; minor typos that do not create ambiguity are not discrepancies.',
  'ISBP A1': 'Defines "international standard banking practice"; examiner should consider whether documents comply based on their data content. Do not apply overly strict typographic standards; minor typos that do not create ambiguity are not discrepancies.',

  'ISBP 821 Para. A14': 'Commonly understood abbreviations are acceptable (e.g., "Co." for "Company", "Ltd" for "Limited", "St" for "Street"). Invoice using "Co." instead of "Company" is NOT a discrepancy.',
  'ISBP A14': 'Commonly understood abbreviations are acceptable (e.g., "Co." for "Company", "Ltd" for "Limited", "St" for "Street"). Invoice using "Co." instead of "Company" is NOT a discrepancy.',

  'ISBP 821 Para. A15': 'A misspelling or typing error that does not affect the meaning of a word or the document\'s compliance is not a discrepancy. "Indusrial" instead of "Industrial" — not discrepant if context is clear.',
  'ISBP A15': 'A misspelling or typing error that does not affect the meaning of a word or the document\'s compliance is not a discrepancy. "Indusrial" instead of "Industrial" — not discrepant if context is clear.',

  'ISBP 821 Para. A19': 'Date formats are acceptable in any order (DD MM YYYY, MM DD YYYY, etc.) as long as the date is unambiguous; non-calendar dates must be converted.',
  'ISBP A19': 'Date formats are acceptable in any order (DD MM YYYY, MM DD YYYY, etc.) as long as the date is unambiguous; non-calendar dates must be converted.',

  'ISBP 821 Para. B1': 'Documents must be consistent with each other; the same entity may appear with slightly different names if it is clear they refer to the same party. "ABC Trading Pte Ltd" vs "ABC Trading" — acceptable if context confirms same entity.',
  'ISBP B1': 'Documents must be consistent with each other; the same entity may appear with slightly different names if it is clear they refer to the same party. "ABC Trading Pte Ltd" vs "ABC Trading" — acceptable if context confirms same entity.',

  'ISBP 821 Para. D1': 'Ports, dates, vessel names, and goods description on transport documents must not contradict the invoice. Invoice: "Port Klang to Singapore"; B/L: "Penang to Singapore" = contradiction.',
  'ISBP D1': 'Ports, dates, vessel names, and goods description on transport documents must not contradict the invoice. Invoice: "Port Klang to Singapore"; B/L: "Penang to Singapore" = contradiction.',

  'ISBP 821 Para. E1': 'Insurance policy/certificate amount must cover at least 110% of CIF invoice value; currency must match LC. Invoice CIF USD 100,000; insurance must be >= USD 110,000.',
  'ISBP E1': 'Insurance policy/certificate amount must cover at least 110% of CIF invoice value; currency must match LC. Invoice CIF USD 100,000; insurance must be >= USD 110,000.',

  'ISBP 821 Para. K1': 'Country of origin on certificate must not contradict invoice; issuer must be as stipulated in LC. CoO states "Made in China" but invoice states "Made in Germany" = contradiction.',
  'ISBP K1': 'Country of origin on certificate must not contradict invoice; issuer must be as stipulated in LC. CoO states "Made in China" but invoice states "Made in Germany" = contradiction.',
};
