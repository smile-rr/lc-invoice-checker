# Plan: Reorganize catalog.yml — Invoice Field-First Structure

## Context

`catalog.yml` reorganized around invoice fields (F01–F17). The INVOICE FIELD INDEX in the YAML header mirrors the exact field sequence from `docs/refer-doc/ucp600_isbp821_invoice_rules.md → Quick Cross-Reference Matrix`. All 17 fields from the matrix are either implemented or explicitly noted as not included with a reason. No "V1.5" distinction — everything is V1.

## Changes overview

| What | Status |
|------|--------|
| `catalog.yml` header + index | ✅ Clean, matches reference matrix exactly |
| Java `Rule.java` | ✅ Added `invoiceField` field |
| TypeScript `RuleSummary` | ✅ Added `invoice_field: string \| null` |
| Plan file | ✅ This file |

## INVOICE FIELD INDEX

Sequence mirrors `ucp600_isbp821_invoice_rules.md → Quick Cross-Reference Matrix` exactly.

| ID | Field Name | UCP/ISBP Ref | Covering Rules | Type | Status |
|----|-----------|-------------|----------------|------|--------|
| F01 | Issuer (Beneficiary as Seller) | UCP 18(a) ISBP C6 | ISBP-C6 | AGENT | ✅ |
| F02 | Buyer Name (Applicant) | UCP 18(a) ISBP C5 | ISBP-C5 | AGENT | ✅ |
| F03 | Invoice Signature | UCP 18(a)(d) ISBP C2 | ISBP-C2 | AGENT | ✅ |
| F04 | LC Number Reference | UCP 18(a) ISBP C1 | ISBP-C1 | AGENT | ✅ |
| F05 | Currency | UCP 18(b) ISBP C8 | UCP-18b-currency | PROGRAMMATIC | ✅ |
| F06 | Invoice Amount ≤ LC Amount | UCP 18(b); UCP 30(a)(b)(c) ISBP C9,C10 | UCP-18b-amount, UCP-18b-math, UCP-30c, ISBP-C10 | MIXED | ✅ |
| F07 | Unit Price | UCP 18(b) ISBP C4 | ISBP-C4 | AGENT | ✅ |
| F08 | Goods Description | UCP 18(c) ISBP C3 | ISBP-C3 | AGENT | ✅ |
| F09 | Trade Terms (Incoterms) | UCP 18(b)(c) ISBP C8 | ISBP-C8 | AGENT | ✅ |
| F10 | Country of Origin | UCP 18(c) ISBP C7 | ISBP-C7 | AGENT | ✅ |
| F11 | Invoice Date ≤ Presentation Date | UCP 14(h)(i) ISBP A19 | UCP-14h, UCP-14i | PROGRAMMATIC | ✅ |
| F12 | Presentation within 21 days | UCP 14(c) ISBP A1 | — | — | ❌ not included — needs B/L shipment date |
| F13 | Non-contradiction with other docs | UCP 14(d) ISBP B1,D1,K1 | — | — | ❌ not included — needs cross-document input |
| F14 | Address (country consistency) | UCP 14(e) ISBP C5,C6 | UCP-14e | AGENT | ✅ |
| F15 | Abbreviations / Minor Typos | UCP 14(a) ISBP A14,A15 | ✅ embedded in all rules | — | ✅ |
| F16 | Quantity Tolerance (±5% / ±10%) | UCP 30(a)(b) ISBP C3,C4 | UCP-30a, UCP-30b | AGENT | ✅ |
| F17 | Insurance (CIF invoices) | UCP 28(f) ISBP E1 | — | — | ❌ not included — needs insurance document |

## Enabled rules (18 total, verified `total=22 enabled=18 disabled=4`)

| # | Field ID | Rule ID | Type | Tool |
|---|----------|---------|------|------|
| 1 | F01 | ISBP-C6 | AGENT | — |
| 2 | F02 | ISBP-C5 | AGENT | — |
| 3 | F03 | ISBP-C2 | AGENT | — |
| 4 | F04 | ISBP-C1 | AGENT | — |
| 5 | F05 | UCP-18b-currency | PROGRAMMATIC | — |
| 6 | F06 | UCP-18b-amount | AGENT+TOOL | check_within_tolerance |
| 7 | F06 | UCP-18b-math | AGENT+TOOL | verify_arithmetic |
| 8 | F06 | UCP-30c | PROGRAMMATIC | — |
| 9 | F06 | ISBP-C10 | AGENT | — |
| 10 | F07 | ISBP-C4 | AGENT | — |
| 11 | F08 | ISBP-C3 | AGENT | — |
| 12 | F09 | ISBP-C8 | AGENT | — |
| 13 | F10 | ISBP-C7 | AGENT | — |
| 14 | F11 | UCP-14h | PROGRAMMATIC | — |
| 15 | F11 | UCP-14i | PROGRAMMATIC | — |
| 16 | F14 | UCP-14e | AGENT | — |
| 17 | F16 | UCP-30a | AGENT | — |
| 18 | F16 | UCP-30b | AGENT | — |

Every enabled rule has `invoice_field: Fxx` tag.

## DISABLED rules (4 total)

| Rule ID | Field | Category | Reason |
|---------|-------|----------|--------|
| UCP-14c | F12 | CONDITIONAL_ON_DOC | needs B/L shipment date |
| ISBP-C9 | F06 | MULTI_INVOICE_DEFERRED | single-invoice mode |
| ISBP-A24 | — | NOT_APPLICABLE_V1 | original/copy distinction |
| BANK-001 | — | BANK_OPTIONAL | per-institution policy |

## NOT MODELED fields (not included)

| Field | Reason |
|-------|--------|
| F12 Presentation | needs B/L shipment date |
| F13 Non-contradiction | needs cross-document input (B/L, P/L, CoO) |
| F15 Formatting | embedded in all rules via system prompt (ISBP A14/A15) — DONE |
| F17 Insurance | needs insurance document input |

## Verification

1. **Boot**: `Loaded rule catalog: total=22 enabled=18 disabled=4`
2. **Index sequence**: matches reference matrix field order exactly
3. **UCP/ISBP references**: all match reference matrix
4. **UI build**: `npm run build` — clean, 0 TypeScript errors

## Data preparation layer (UI)

### `ui/src/data/invoiceFields.ts`
Canonical F01–F17 field definitions matching the catalog.yml index. Provides `INVOICE_FIELDS[]` array and `aggregateVerdict()` helper.

### `ui/src/hooks/useInvoiceFieldView.ts`
Transforms `RuleSummary[]` + `CheckResult[]` into `FieldResult[]` (one per field):
- Groups rules by `invoice_field` tag
- Aggregates sub-rule statuses: FAIL → FAIL, DOUBTS → DOUBTS, all PASS → PASS, NOT_REQUIRED excluded
- Returns `FieldResult[]` ordered by F01–F17 sequence

### `ui/src/components/check/FieldCard.tsx`
Field-level card component. Renders aggregated verdict + sub-rule rows. Handles NOT_INCLUDED (dashed placeholder) and EMBEDDED (F15 Formatting) fields.

### `ui/src/components/check/ComplianceCheckPanel.tsx`
Added view mode toggle (Phase / Field). Field view renders FieldCards via `useInvoiceFieldView`. Field strip chips colour-coded by field-level verdict (fail = red, doubt = gold, pass = green, pending = muted).
