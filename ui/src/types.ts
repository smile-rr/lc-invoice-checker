// Wire contracts — must stay 1:1 with the Java records. Jackson serialises
// snake_case, so every field here is snake_case to match the wire format
// without a transformer in between.

export type StageName =
  | 'lc_parse'
  | 'invoice_extract'
  | 'rule_activation'
  | 'rule_check'
  | 'report_assembly';

export type CheckEventType =
  | 'session.started'
  | 'stage.started'
  | 'stage.completed'
  | 'check.started'
  | 'check.completed'
  | 'extract.source.started'
  | 'extract.source.completed'
  | 'report.complete'
  | 'error';

export interface CheckEvent<P = unknown> {
  type: CheckEventType;
  stage: StageName | null;
  payload: P;
  timestamp: string;
}

export type FieldType =
  | 'STRING'
  | 'AMOUNT'
  | 'DATE'
  | 'INTEGER'
  | 'ENUM'
  | 'MULTILINE_TEXT'
  | 'DOCUMENT_LIST'
  | 'CURRENCY_CODE';

/** One row of /api/v1/fields. The single source of truth for field metadata. */
export interface FieldDefinition {
  key: string;
  name_en: string | null;
  name_zh: string | null;
  type: FieldType;
  description_zh: string | null;
  applies_to: Array<'LC' | 'INVOICE'>;
  source_tags: string[];
  invoice_aliases: string[];
  enum_values: string[];
  rule_relevant: boolean;
  default_value: unknown;
  group: string | null;
}

/** Generic map view of any document — the contract any UI panel should consume. */
export interface FieldEnvelope {
  doc_type: 'LC' | 'INVOICE';
  fields: Record<string, unknown>;
  extras: Record<string, unknown>;
  raw_source: Record<string, string>;
  warnings: Array<{ code: string; source: string | null; message: string }>;
}

export interface DocumentRequirement {
  type: string;
  originals: number | null;
  copies: number | null;
  signed: boolean;
  full_set: boolean;
  on_board: boolean;
  consignee: string | null;
  freight_condition: string | null;
  notify_party: string | null;
  issuing_body: string | null;
  raw_text: string;
}

/** A single display-ready row of a parsed document, computed by the backend. */
export interface ParsedRow {
  tag: string;
  group: string;
  label: string;
  display_value: string;
  sublines: Array<{ label: string; value: string }>;
  meta: Record<string, unknown>;
  sort_key: string;
}

export interface LcDocument {
  lc_number: string | null;
  issue_date: string | null;
  expiry_date: string | null;
  expiry_place: string | null;
  currency: string | null;
  amount: string | number | null;
  tolerance_plus: number;
  tolerance_minus: number;
  max_amount_flag: string | null;
  partial_shipment: string | null;
  transhipment: string | null;
  place_of_receipt: string | null;
  place_of_delivery: string | null;
  latest_shipment_date: string | null;
  shipment_period: string | null;
  port_of_loading: string | null;
  port_of_discharge: string | null;
  presentation_days: number;
  applicable_rules: string | null;
  applicant_name: string | null;
  applicant_address: string | null;
  beneficiary_name: string | null;
  beneficiary_address: string | null;
  field_45_a_raw: string | null;
  field_46_a_raw: string | null;
  field_47_a_raw: string | null;
  raw_fields: Record<string, string>;
  /** Generic registry-keyed view. Prefer this in new UI. */
  envelope: FieldEnvelope;
  documents_required: DocumentRequirement[];
  /** Display-ready rows for the parsed pane — backend already grouped, formatted, ordered. */
  parsed_rows: ParsedRow[];
}

export interface InvoiceDocument {
  invoice_number: string | null;
  invoice_date: string | null;
  seller_name: string | null;
  seller_address: string | null;
  buyer_name: string | null;
  buyer_address: string | null;
  goods_description: string | null;
  quantity: string | number | null;
  unit: string | null;
  unit_price: string | number | null;
  total_amount: string | number | null;
  currency: string | null;
  lc_reference: string | null;
  trade_terms: string | null;
  port_of_loading: string | null;
  port_of_discharge: string | null;
  country_of_origin: string | null;
  signed: boolean | null;
  extractor_used: string;
  extractor_confidence: number;
  image_based: boolean;
  pages: number;
  extraction_ms: number;
  raw_markdown: string | null;
  raw_text: string | null;
  envelope: FieldEnvelope;
}

export type CheckStatus =
  | 'PASS'
  | 'DISCREPANT'
  | 'UNABLE_TO_VERIFY'
  | 'NOT_APPLICABLE'
  | 'HUMAN_REVIEW'
  | 'REQUIRES_HUMAN_REVIEW';

export type CheckTypeEnum = 'A' | 'B' | 'AB' | 'SPI';

export type Severity = 'MAJOR' | 'MINOR';

export interface CheckResult {
  rule_id: string;
  rule_name: string | null;
  check_type: CheckTypeEnum | null;
  status: CheckStatus;
  severity: Severity | null;
  field: string | null;
  lc_value: string | null;
  presented_value: string | null;
  description: string | null;
  ucp_ref: string | null;
  isbp_ref: string | null;
}

export interface Summary {
  total_checks: number;
  passed: number;
  discrepant: number;
  unable_to_verify: number;
  not_applicable: number;
}

export interface Discrepancy {
  field: string | null;
  lc_value: string | null;
  presented_value: string | null;
  rule_reference: string | null;
  description: string | null;
}

export interface DiscrepancyReport {
  session_id: string;
  compliant: boolean;
  /** Summary objects (no rule_id) — kept for the sync /lc-check API contract. */
  discrepancies: Discrepancy[];
  /** Typed CheckResults for DISCREPANT rules — UI source of truth. */
  discrepant: CheckResult[];
  unable_to_verify: CheckResult[];
  passed: CheckResult[];
  not_applicable: CheckResult[];
  summary: Summary;
}

export interface StageTrace<O = unknown> {
  stage_name: string;
  status: string;
  started_at: string;
  duration_ms: number;
  output: O | null;
  llm_calls: unknown[];
  error: string | null;
}

export interface CheckSession {
  session_id: string;
  started_at: string;
  completed_at: string | null;
  lc_parsing: StageTrace<LcDocument> | null;
  invoice_extraction: StageTrace<InvoiceDocument> | null;
  activation: unknown;
  checks: unknown[];
  final_report: DiscrepancyReport | null;
  error: string | null;
}

export interface StartResponse {
  session_id: string;
  invoice_filename: string | null;
  invoice_bytes: number;
  lc_length: number;
}

export interface ExtractAttempt {
  source: string;
  /** RUNNING is a UI-only state synthesized from extract.source.started events
   *  that haven't yet seen their matching extract.source.completed. */
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | string;
  is_selected: boolean;
  document: InvoiceDocument | null;
  duration_ms: number;
  started_at: string | null;
  error: string | null;
  /** Optional confidence (0..1) — only set when the source completed successfully. */
  confidence?: number;
}

export interface SessionSummary {
  session_id: string;
  lc_reference: string | null;
  beneficiary: string | null;
  applicant: string | null;
  status: string;
  compliant: boolean | null;
  created_at: string;
  completed_at: string | null;
  rules_run: number;
  discrepancies: number;
}
