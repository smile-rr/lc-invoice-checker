/**
 * Data-preparation layer for the field-level grouping view.
 *
 * Takes the rule catalog and live check results and transforms them into
 * one `FieldResult` per invoice field (F01–F17). Both this view and the
 * raw rule-level view feed the same `RuleCard` component — the card key
 * switches from `ruleId` (rule view) to `invoice_field` (field view).
 *
 * Aggregation: NOT_REQUIRED sub-rules are excluded from verdict calculation
 * (informational only). A single FAIL in any sub-rule collapses the field
 * to FAIL. Otherwise any DOUBTS collapses to DOUBTS. All PASS → PASS.
 */
import { useMemo } from 'react';
import type { CheckResult, RuleSummary } from '../types';
import {
  INVOICE_FIELDS,
  aggregateVerdict,
  type InvoiceFieldDef,
} from '../data/invoiceFields';

export interface SubRuleEntry {
  ruleId: string;
  ruleName: string | null;
  status: CheckResult['status'];
  lcValue: string | null;
  presentedValue: string | null;
  description: string | null;
  checkType: CheckResult['check_type'];
  severity: CheckResult['severity'];
  ucpRef: string | null;
  isbpRef: string | null;
  equationUsed: string | null | undefined;
  /** Catalog output_field — used as deduplication key for merged LC/Invoice values. */
  outputField: string | null;
}

export interface FieldResult {
  fieldId: string;
  fieldDef: InvoiceFieldDef;
  /** Aggregated verdict across all sub-rules (excludes NOT_REQUIRED from calc). */
  verdict: CheckResult['status'] | null;
  /** Rules that ran and returned something. */
  subRules: SubRuleEntry[];
  /** Rules that are in the catalog but have not yet produced a result. */
  pendingRuleIds: string[];
  passedCount: number;
  failedCount: number;
  doubtsCount: number;
  notRequiredCount: number;
}

/** resultByRuleId cache — caller passes this in to avoid recomputing. */
export function useInvoiceFieldView(
  catalogRules: RuleSummary[],
  checks: CheckResult[],
): FieldResult[] {
  return useMemo(() => prepareFieldView(catalogRules, checks), [catalogRules, checks]);
}

/** Pure transformation — exported for testing. */
export function prepareFieldView(
  catalogRules: RuleSummary[],
  checks: CheckResult[],
): FieldResult[] {
  const resultByRuleId = new Map<string, CheckResult>();
  for (const c of checks) resultByRuleId.set(c.rule_id, c);

  const rulesByField = new Map<string, RuleSummary[]>();
  for (const r of catalogRules) {
    if (!r.invoice_field) continue;
    const arr = rulesByField.get(r.invoice_field);
    if (arr) arr.push(r);
    else rulesByField.set(r.invoice_field, [r]);
  }

  return INVOICE_FIELDS.map((fieldDef): FieldResult => {
    const fieldRules = rulesByField.get(fieldDef.id) ?? [];

    const subRules: SubRuleEntry[] = [];
    const pendingRuleIds: string[] = [];

    let passed = 0, failed = 0, doubts = 0, notRequired = 0;

    for (const rule of fieldRules) {
      const result = resultByRuleId.get(rule.id);
      if (!result) {
        // Catalog has the rule but no result yet — pending.
        pendingRuleIds.push(rule.id);
        continue;
      }
      const { status } = result;
      if (status === 'PASS') passed++;
      else if (status === 'FAIL') failed++;
      else if (status === 'DOUBTS') doubts++;
      else if (status === 'NOT_REQUIRED') notRequired++;

      subRules.push({
        ruleId: result.rule_id,
        ruleName: result.rule_name ?? rule.name,
        status: result.status,
        lcValue: result.lc_value,
        presentedValue: result.presented_value,
        description: result.description,
        checkType: result.check_type,
        severity: result.severity,
        ucpRef: result.ucp_ref ?? rule.ucp_ref ?? null,
        isbpRef: result.isbp_ref ?? rule.isbp_ref ?? null,
        equationUsed: result.equation_used,
        outputField: rule.output_field ?? null,
      });
    }

    const verdict = aggregateVerdict(
      subRules.map((s) => s.status),
    );

    return {
      fieldId: fieldDef.id,
      fieldDef,
      verdict,
      subRules,
      pendingRuleIds,
      passedCount: passed,
      failedCount: failed,
      doubtsCount: doubts,
      notRequiredCount: notRequired,
    };
  });
}
