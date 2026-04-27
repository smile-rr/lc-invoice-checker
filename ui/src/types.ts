// Wire contracts — must stay 1:1 with the Java records. Jackson serialises
// snake_case, so every field here is snake_case to match the wire format
// without a transformer in between.

/**
 * Anchor stages emitted by the backend pipeline. The frontend stepper aligns
 * to these four; backend may emit a `session` status as a fifth marker for
 * the run start.
 */
export type StageName =
  | 'session'
  | 'lc_parse'
  | 'invoice_extract'
  | 'programmatic'
  | 'agent';

export type BusinessPhase =
  | 'PARTIES'
  | 'MONEY'
  | 'GOODS'
  | 'LOGISTICS'
  | 'PROCEDURAL'
  | 'HOLISTIC';

// ─── Unified envelope (single SSE / trace shape) ──────────────────────────

export interface BaseEvent {
  ts: string;
  seq: number;
}

export interface StatusEvent extends BaseEvent {
  type: 'status';
  stage: StageName;
  /**
   * `started` — first transition into the stage.
   * `running` — intra-stage progress update (e.g. one of N parallel sources
   *             completed; one of N rules just started).
   * `completed` — terminal transition; payload may carry stage output.
   */
  state: 'started' | 'running' | 'completed';
  message: string;
  /** On `completed`, optionally the structured stage output —
   *  LcDocument for `lc_parse`, InvoiceDocument for `invoice_extract`,
   *  a counts summary for `programmatic` / `agent`. */
  data?: unknown;
}

export interface RuleEvent extends BaseEvent {
  type: 'rule';
  data: CheckResult;
}

export interface ErrorEvent extends BaseEvent {
  type: 'error';
  stage?: string;
  message: string;
}

export interface CompleteEvent extends BaseEvent {
  type: 'complete';
  data: DiscrepancyReport;
}

export type Event = StatusEvent | RuleEvent | ErrorEvent | CompleteEvent;

export type FieldType =
  | 'STRING'
  | 'AMOUNT'
  | 'DATE'
  | 'INTEGER'
  | 'ENUM'
  | 'MULTILINE_TEXT'
  | 'DOCUMENT_LIST'
  | 'CURRENCY_CODE'
  | 'TABLE';

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
  parsed_rows: ParsedRow[];
}

/**
 * Outcome of a single rule check — four buckets:
 *   PASS         — rule applied and the invoice complies
 *   FAIL         — rule applied and a discrepancy was found
 *   DOUBTS       — rule applied but the agent isn't confident enough to call it
 *   NOT_REQUIRED — rule does not apply to this presentation; reason cites UCP/ISBP
 */
export type CheckStatus = 'PASS' | 'FAIL' | 'DOUBTS' | 'NOT_REQUIRED';

export type CheckTypeEnum = 'PROGRAMMATIC' | 'AGENT';

export type Severity = 'MAJOR' | 'MINOR';

export interface CheckResult {
  rule_id: string;
  rule_name: string | null;
  check_type: CheckTypeEnum | null;
  /** Business phase this rule belongs to (PARTIES / MONEY / GOODS / etc.).
   *  Backend serialises the enum constant verbatim — uppercase. */
  business_phase: BusinessPhase | null;
  status: CheckStatus;
  severity: Severity | null;
  field: string | null;
  lc_value: string | null;
  presented_value: string | null;
  description: string | null;
  ucp_ref: string | null;
  isbp_ref: string | null;
  /** Tier-3 only — the deterministic computation the agent committed to.
   *  Null for Tier-1 (programmatic) and Tier-2 (semantic LLM, no tools).
   *  Example: "max_allowed = 50000 × 1.05 = 52500; invoice 55000 > 52500 → ABOVE". */
  equation_used?: string | null;
}

export interface Summary {
  total_checks: number;
  passed: number;
  failed: number;
  doubts: number;
  not_required: number;
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
  /** Spec-shape Discrepancy summaries (no rule_id) — sourced from `failed`. */
  discrepancies: Discrepancy[];
  /** Typed CheckResults grouped by the 4-bucket taxonomy. */
  passed: CheckResult[];
  failed: CheckResult[];
  doubts: CheckResult[];
  not_required: CheckResult[];
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

/**
 * One forensic record per executed rule. Carries the inputs the strategy bound,
 * the deterministic expression evaluation (Type A / AB pre-gate), and the LLM
 * call envelope (Type B / AB confirm). The UI uses {@link llm_trace} to render
 * the audit pane on LLM rule cards.
 */
export interface LlmTrace {
  purpose: string;
  model: string;
  prompt_rendered: string;
  raw_response: string;
  parsed_response?: unknown;
  latency_ms: number;
  error?: string | null;
  tokens_in?: number;
  tokens_out?: number;
}

export interface CheckTrace {
  rule_id: string;
  check_type: CheckTypeEnum | null;
  status: CheckStatus;
  input_snapshot: Record<string, unknown>;
  expression_trace?: unknown;
  llm_trace?: LlmTrace | null;
  duration_ms: number;
  error?: string | null;
}

/**
 * One row of the rule catalog as returned by {@code GET /api/v1/rules}.
 * Read-only metadata used by the UI to enrich {@link CheckResult} rows
 * without inflating the per-rule SSE wire payload.
 */
export interface RuleSummary {
  id: string;
  name: string;
  description: string | null;
  business_phase: BusinessPhase | null;
  check_type: CheckTypeEnum | null;
  ucp_ref: string | null;
  isbp_ref: string | null;
  ucp_excerpt: string | null;
  isbp_excerpt: string | null;
  rule_reference_label: string | null;
  output_field: string | null;
  severity_on_fail: Severity | null;
  enabled: boolean;
  disabled_reason: string | null;
}

/**
 * Queue context for a still-QUEUED session. Sent on the trace response and
 * pushed via the SSE `status` event with stage="queue", state="waiting".
 */
export interface QueueContext {
  position: number;
  depth: number;
  running_session_id: string | null;
}

/** Trace API reply — same envelope shape as live SSE, ordered by {@link Event.seq}. */
export interface TraceResponse {
  session_id: string;
  events: Event[];
  queue_context?: QueueContext | null;
}

export interface StartResponse {
  session_id: string;
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  queue_position: number;
  invoice_filename: string | null;
  invoice_bytes: number;
  lc_length: number;
}

export interface QueueStatus {
  concurrency: number;
  running: string[];
  queued: Array<{ session_id: string; position: number }>;
}

export interface TagMeta {
  tag: string;
  mandatory: boolean;
  max_length: number | null;
  min_length: number | null;
}

export interface ExtractAttempt {
  source: string;
  /** Persisted status from the backend `/extracts` endpoint:
   *    SUCCESS / FAILED — terminal states from one extractor run. */
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | string;
  is_selected: boolean;
  document: InvoiceDocument | null;
  duration_ms: number;
  started_at: string | null;
  error: string | null;
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
  // Original-upload metadata, used as a display-name fallback when
  // lc_reference is null (e.g. sessions that failed before LC parse finished).
  lc_filename: string | null;
  invoice_filename: string | null;
}
