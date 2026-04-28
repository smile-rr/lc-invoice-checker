/**
 * Static UCP 600 / ISBP 821 compliance coverage matrix.
 *
 * Sourced from:
 *   - lc-checker-svc/src/main/resources/rules/catalog.yml
 *   - docs/refer-doc/ucp600_isbp821_invoice_rules.md
 *
 * Updated whenever the rule catalog changes. Not fetched from the API —
 * this is pure informational content for the Compliance Reference modal.
 */

export type RuleType = 'PROGRAMMATIC' | 'AGENT' | 'AGENT+TOOL';
export type RuleStatus = 'enabled' | 'disabled' | 'not_modeled';
export type BusinessPhase = 'PARTIES' | 'MONEY' | 'GOODS' | 'LOGISTICS' | 'PROCEDURAL';
export type DisabledReason =
  | 'CONDITIONAL_ON_DOC'
  | 'MULTI_INVOICE_DEFERRED'
  | 'NOT_APPLICABLE_V1'
  | 'BANK_OPTIONAL'
  | 'COVERED_ELSEWHERE'
  | 'CROSS_CUTTING'
  | 'OUT_OF_SCOPE'
  | 'META';

export interface UcpIsbpEntry {
  article: string;           // e.g. "UCP 600 Art. 14(c)" or "ISBP 821 Para. C3"
  title: string;
  status: RuleStatus;
  ruleId?: string;          // catalog rule id when applicable
  type?: RuleType;
  phase?: BusinessPhase;
  disabledReason?: DisabledReason;
  disabledDetail?: string;  // human-readable unblock condition
  coveredBy?: string;       // when status=not_modeled + COVERED_ELSEWHERE
  note?: string;            // extra context
}

export const COMPLIANCE_MATRIX: UcpIsbpEntry[] = [

  // ── UCP 600 — Framework / Meta (NOT a check rule) ──────────────────────
  { article: 'UCP 600 Art. 2',     title: 'Definitions',                               status: 'not_modeled', disabledReason: 'META', note: 'Framework — defines "complying presentation", "honour", etc. No check rule.' },
  { article: 'UCP 600 Art. 3',     title: 'Interpretations',                           status: 'not_modeled', disabledReason: 'META', note: 'Framework — governs how credit terms are interpreted. No check rule.' },
  { article: 'UCP 600 Art. 4',     title: 'Credits vs Contracts',                      status: 'not_modeled', disabledReason: 'META', note: 'Framework — banks deal in documents, not goods/contracts. No check rule.' },
  { article: 'UCP 600 Art. 5',     title: 'Documents vs Goods/Services',               status: 'not_modeled', disabledReason: 'META', note: 'Framework — banks examine documents on face value. No check rule.' },

  // ── UCP 600 Art. 14 — Examination ─────────────────────────────────────────
  { article: 'UCP 600 Art. 14(a)', title: 'Standard for Examination',                status: 'not_modeled', disabledReason: 'COVERED_ELSEWHERE', coveredBy: 'All rules enforce face-value compliance', note: 'Implicitly enforced by every Art. 18 rule.' },
  { article: 'UCP 600 Art. 14(b)', title: 'Examination Period (5 banking days)',     status: 'not_modeled', disabledReason: 'OUT_OF_SCOPE', note: 'Bank internal SLA — not an invoice content check.' },
  { article: 'UCP 600 Art. 14(c)', title: '21-Day Presentation Window',               status: 'enabled', ruleId: 'UCP-14c', type: 'PROGRAMMATIC', phase: 'PROCEDURAL', note: 'Active as invoice-proxy: invoice_date + 21 days ≤ expiry_date. Exact version (needs B/L date) = UCP-14c-exact (DISABLED).' },
  { article: 'UCP 600 Art. 14(d)', title: 'Non-Contradiction Rule',                  status: 'disabled', ruleId: 'UCP-14d', type: 'AGENT', phase: 'PROCEDURAL', disabledReason: 'CONDITIONAL_ON_DOC', disabledDetail: 'Needs at least one additional stipulated document (B/L, packing list, CoO).' },
  { article: 'UCP 600 Art. 14(e)', title: 'Address Country Consistency',              status: 'enabled', ruleId: 'UCP-14e', type: 'AGENT', phase: 'PARTIES' },
  { article: 'UCP 600 Art. 14(f)', title: 'Non-Required Documents',               status: 'not_modeled', disabledReason: 'META', note: 'Framework — banks ignore documents not stipulated in the LC.' },
  { article: 'UCP 600 Art. 14(h)', title: 'Document Dating',                        status: 'enabled', ruleId: 'UCP-14h', type: 'PROGRAMMATIC', phase: 'PROCEDURAL' },
  { article: 'UCP 600 Art. 14(i)', title: 'Invoice Date ≤ Presentation Date',      status: 'enabled', ruleId: 'UCP-14i', type: 'PROGRAMMATIC', phase: 'PROCEDURAL' },
  { article: 'UCP 600 Art. 14(j)', title: 'Transport Document Port Consistency',   status: 'disabled', ruleId: 'UCP-14j', type: 'AGENT', phase: 'LOGISTICS', disabledReason: 'CONDITIONAL_ON_DOC', disabledDetail: 'Requires bill of lading or other transport document.' },

  // ── UCP 600 Art. 16 — Refusal ───────────────────────────────────────────
  { article: 'UCP 600 Art. 16',    title: 'Discrepancy Notice (All-or-Nothing)',     status: 'not_modeled', disabledReason: 'OUT_OF_SCOPE', note: 'Report-aggregation logic, not an invoice check rule. All discrepancies must be listed in a single refusal notice.' },

  // ── UCP 600 Art. 18 — Invoice ───────────────────────────────────────────
  { article: 'UCP 600 Art. 18(a)', title: 'Invoice Issuer & Applicant Name',       status: 'not_modeled', disabledReason: 'COVERED_ELSEWHERE', coveredBy: 'ISBP-C5 (Applicant) + ISBP-C6 (Beneficiary)' },
  { article: 'UCP 600 Art. 18(b)', title: 'Currency & Amount',                      status: 'enabled', note: 'Covered by 4 rules: UCP-18b-currency (PROG), UCP-18b-amount (TOOL), UCP-18b-math (TOOL), UCP-30a (AGENT)' },
  { article: 'UCP 600 Art. 18(c)', title: 'Goods Description',                      status: 'enabled', ruleId: 'ISBP-C3', type: 'AGENT', phase: 'GOODS' },
  { article: 'UCP 600 Art. 18(d)', title: 'Invoice Signature Not Required',        status: 'enabled', ruleId: 'ISBP-C2', type: 'AGENT', phase: 'PROCEDURAL' },

  // ── UCP 600 Art. 28 — Insurance ────────────────────────────────────────
  { article: 'UCP 600 Art. 28(f)', title: 'Insurance Coverage ≥ 110% CIF/CIP',    status: 'disabled', ruleId: 'UCP-28f', type: 'AGENT', phase: 'GOODS', disabledReason: 'CONDITIONAL_ON_DOC', disabledDetail: 'Requires insurance certificate/policy. Insured amount comes from the insurance document.' },

  // ── UCP 600 Art. 30 — Quantity & Drawing Tolerances ──────────────────────
  { article: 'UCP 600 Art. 30(a)', title: 'Quantity Tolerance — "about" ±10%',    status: 'enabled', ruleId: 'UCP-30a', type: 'AGENT', phase: 'MONEY' },
  { article: 'UCP 600 Art. 30(b)', title: 'Quantity Tolerance — ±5% Bulk Goods',   status: 'enabled', ruleId: 'UCP-30b', type: 'AGENT', phase: 'MONEY' },
  { article: 'UCP 600 Art. 30(c)', title: 'Underdrawing Permitted',               status: 'not_modeled', disabledReason: 'COVERED_ELSEWHERE', coveredBy: 'UCP-18b-amount (lower bound). Rule removed — no-op always-pass.' },

  // ── ISBP 821 — General (A-series) ────────────────────────────────────────
  { article: 'ISBP 821 Para. A1–A9', title: 'General Principles (ISBP)',           status: 'not_modeled', disabledReason: 'CROSS_CUTTING', note: 'Embedded in all AGENT system prompts. Minor typos / formatting do not constitute discrepancies.' },
  { article: 'ISBP 821 Para. A14',   title: 'Abbreviations Acceptable',            status: 'not_modeled', disabledReason: 'CROSS_CUTTING', note: 'Embedded in AGENT prompts. "Co.", "Ltd", "St" etc. are not discrepancies.' },
  { article: 'ISBP 821 Para. A15',   title: 'Spelling Errors Without Ambiguity',   status: 'not_modeled', disabledReason: 'CROSS_CUTTING', note: 'Embedded in AGENT prompts. Non-meaning-altering typos are not discrepancies.' },
  { article: 'ISBP 821 Para. A19',   title: 'Date Format Flexibility',              status: 'enabled', ruleId: 'UCP-14i', type: 'PROGRAMMATIC', phase: 'PROCEDURAL', note: 'Implicitly handled by date parsing in UCP-14i.' },
  { article: 'ISBP 821 Para. A24',   title: 'Originals vs Copies Acceptable',    status: 'disabled', ruleId: 'ISBP-A24', type: 'AGENT', phase: 'PROCEDURAL', disabledReason: 'NOT_APPLICABLE_V1', disabledDetail: 'V1 input model does not surface the original/copy distinction from extracted invoices.' },

  // ── ISBP 821 — B-series (General Doc Requirements) ───────────────────────
  { article: 'ISBP 821 Para. B1–B5', title: 'Document Consistency / Party Name Variations', status: 'not_modeled', disabledReason: 'CROSS_CUTTING', note: 'Embedded in AGENT prompts. Same entity may appear with slightly different names.' },

  // ── ISBP 821 — C-series (Invoice Content) ────────────────────────────────
  { article: 'ISBP 821 Para. C1',  title: 'LC Number Reference on Invoice',           status: 'enabled', ruleId: 'ISBP-C1',  type: 'AGENT', phase: 'PROCEDURAL' },
  { article: 'ISBP 821 Para. C2',  title: 'Invoice Signature (When LC Requires)',    status: 'enabled', ruleId: 'ISBP-C2',  type: 'AGENT', phase: 'PROCEDURAL' },
  { article: 'ISBP 821 Para. C3',  title: 'Goods Description Correspondence',         status: 'enabled', ruleId: 'ISBP-C3',  type: 'AGENT', phase: 'GOODS' },
  { article: 'ISBP 821 Para. C4',  title: 'Unit Price Matches LC Stipulation',       status: 'enabled', ruleId: 'ISBP-C4',  type: 'AGENT', phase: 'MONEY' },
  { article: 'ISBP 821 Para. C5',  title: 'Applicant Name & Address Correspondence',  status: 'enabled', ruleId: 'ISBP-C5',  type: 'AGENT', phase: 'PARTIES' },
  { article: 'ISBP 821 Para. C6',  title: 'Beneficiary Name & Address Correspondence',status: 'enabled', ruleId: 'ISBP-C6',  type: 'AGENT', phase: 'PARTIES' },
  { article: 'ISBP 821 Para. C7',  title: 'Country of Origin (When LC Requires)',     status: 'enabled', ruleId: 'ISBP-C7',  type: 'AGENT', phase: 'GOODS' },
  { article: 'ISBP 821 Para. C8',  title: 'Trade Terms (Incoterms) Consistency',   status: 'enabled', ruleId: 'ISBP-C8',  type: 'AGENT', phase: 'LOGISTICS' },
  { article: 'ISBP 821 Para. C9',  title: 'Multiple Invoices ≤ LC Amount',           status: 'disabled', ruleId: 'ISBP-C9',  type: 'AGENT', phase: 'MONEY', disabledReason: 'MULTI_INVOICE_DEFERRED', disabledDetail: 'V1 single-invoice mode. Activates when the API supports multi-invoice presentation.' },
  { article: 'ISBP 821 Para. C10', title: 'Charges & Deductions Consistency',        status: 'enabled', ruleId: 'ISBP-C10', type: 'AGENT', phase: 'MONEY', note: 'LLM evaluates charges from invoice text. No dedicated charges_amount field in V1 extractor.' },

  // ── ISBP 821 — D-series (Transport Documents) ─────────────────────────────
  { article: 'ISBP 821 Para. D1–D10', title: 'Transport Document vs Invoice Consistency', status: 'disabled', ruleId: 'ISBP-D1', type: 'AGENT', phase: 'LOGISTICS', disabledReason: 'CONDITIONAL_ON_DOC', disabledDetail: 'Requires bill of lading or other transport document.' },

  // ── ISBP 821 — E-series (Insurance Documents) ─────────────────────────────
  { article: 'ISBP 821 Para. E1–E10', title: 'Insurance Document vs Invoice Consistency', status: 'disabled', ruleId: 'ISBP-E1', type: 'AGENT', phase: 'GOODS', disabledReason: 'CONDITIONAL_ON_DOC', disabledDetail: 'Requires insurance certificate/policy. Insured amount comes from insurance document.' },

  // ── ISBP 821 — K-series (Certificates of Origin) ──────────────────────────
  { article: 'ISBP 821 Para. K1–K6', title: 'Certificate of Origin vs Invoice Consistency', status: 'disabled', ruleId: 'ISBP-K1', type: 'AGENT', phase: 'PROCEDURAL', disabledReason: 'CONDITIONAL_ON_DOC', disabledDetail: 'Requires certificate of origin. Activates when V1.5 ingests CoO documents.' },

  // ── Bank Policy ───────────────────────────────────────────────────────────
  { article: 'BANK-001', title: 'Invoice Language Requirement (English)',         status: 'disabled', ruleId: 'BANK-001', type: 'AGENT', phase: 'PROCEDURAL', disabledReason: 'BANK_OPTIONAL', disabledDetail: 'Per-deployment bank policy. Enable in catalog per institution.' },
];

export const PHASE_LABELS: Record<BusinessPhase, string> = {
  PARTIES:    'Parties',
  MONEY:      'Money',
  GOODS:      'Goods',
  LOGISTICS:  'Logistics',
  PROCEDURAL: 'Procedural',
};

export const STATUS_LABELS: Record<string, string> = {
  enabled:    'Enabled',
  disabled:   'Pending',
  not_modeled: 'Out of Scope',
};

export const TYPE_LABELS: Record<string, string> = {
  PROGRAMMATIC: 'Programmatic',
  AGENT:        'Agent',
  'AGENT+TOOL': 'Agent + Tool',
};
