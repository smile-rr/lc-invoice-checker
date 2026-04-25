# LC Parser — Requirements & Feature Plan
> Scope: MT700 field parsing only. Invoice extraction and Rule Engine are out of scope for this phase.
> Target: Java / Spring Boot, Prowide Core as parsing foundation.

---

## Table of Contents
1. [Background & Goals](#1-background--goals)
2. [System Boundaries](#2-system-boundaries)
3. [Architecture Overview](#3-architecture-overview)
4. [Module Breakdown](#4-module-breakdown)
   - M1: Raw Message Pre-processor
   - M2: Prowide-based Tag Extractor
   - M3: Field Pool Registry
   - M4: LC Tag Mapping Config
   - M5: Field Mapper
   - M6: Sub-field Parsers
   - M7: Parsed LC Model
   - M8: Validation
5. [Field Coverage](#5-field-coverage)
6. [Config File Specs](#6-config-file-specs)
7. [Data Model Specs](#7-data-model-specs)
8. [Error Handling](#8-error-handling)
9. [Test Plan](#9-test-plan)
10. [Out of Scope](#10-out-of-scope)
11. [Acceptance Criteria](#11-acceptance-criteria)
12. [Suggested File Structure](#12-suggested-file-structure)

---

## 1. Background & Goals

### Context
A Trade Finance platform needs to programmatically parse SWIFT MT700 messages (Letters of Credit)
into structured domain objects. The parsed output will later be consumed by:
- An Invoice Rule Checker (Phase 2)
- A Document Compliance Engine (Phase 3)

### Goals for This Phase
1. Parse any valid MT700 plain text into a strongly-typed `LCDocument` Java object
2. All field definitions driven by external YAML config — zero hardcoded field values in business logic
3. Clear separation between official SWIFT tags and bank-custom tag extensions
4. Field keys are canonical and reusable — the same key names will later be used in Invoice extraction
5. Every parsed field traceable back to its source tag and config definition
6. Graceful handling of optional/missing tags with correct defaults per UCP 600

### Non-goals for This Phase
- Invoice PDF extraction
- Rule checking / discrepancy detection
- MT701 multi-message merging (deferred, see Out of Scope)

---

## 2. System Boundaries

```
┌─────────────────────────────────────────────────────────────────┐
│                     LC PARSER (This Phase)                      │
│                                                                 │
│  Input:  MT700 raw plain text (String / InputStream)            │
│                                                                 │
│  ┌──────────┐   ┌──────────┐   ┌─────────┐   ┌─────────────┐  │
│  │  Pre-    │→  │ Prowide  │→  │ Field   │→  │  LCDocument │  │
│  │processor │   │Extractor │   │ Mapper  │   │  (output)   │  │
│  └──────────┘   └──────────┘   └─────────┘   └─────────────┘  │
│                                    ↑                            │
│                              YAML Configs                       │
│                        (field-pool + tag-mapping)               │
│                                                                 │
│  Output: LCDocument (Java POJO) + ParseResult (warnings/errors) │
└─────────────────────────────────────────────────────────────────┘

External dependency:  Prowide Core (pw-swift-core, Apache 2.0)
Config files:         field-pool.yaml, lc-tag-mapping.yaml
```

---

## 3. Architecture Overview

```
lc-parser/
│
├── config/                      ← YAML-driven field definitions
│   ├── field-pool.yaml          ← canonical field keys (single source of truth)
│   └── lc-tag-mapping.yaml      ← MT700 tag → canonical field key
│
├── domain/                      ← output model
│   ├── LCDocument.java
│   ├── DocumentRequirement.java
│   └── ParseResult.java
│
├── registry/                    ← loads and indexes config at startup
│   ├── FieldPoolRegistry.java
│   └── TagMappingRegistry.java
│
├── parser/                      ← parsing pipeline
│   ├── LCParser.java            ← main entry point
│   ├── RawMessagePreProcessor.java
│   ├── TagExtractor.java        ← Prowide wrapper
│   ├── FieldMapper.java         ← tag → LCDocument
│   └── subfield/                ← per-format parsers
│       ├── SubFieldParser.java  (interface)
│       ├── AmountWithCurrencyParser.java
│       ├── DateYYMMDDParser.java
│       ├── DatePlusTextParser.java
│       ├── SlashSeparatedIntParser.java
│       ├── MultilineFullParser.java
│       ├── MultilineFirstLineParser.java
│       ├── EnumNormalizedParser.java
│       ├── IntBeforeSlashParser.java
│       └── DocumentListParser.java
│
└── validation/
    ├── LCStructureValidator.java  ← mandatory tag presence, tag order
    └── FieldFormatValidator.java  ← format symbol rules
```

---

## 4. Module Breakdown

---

### M1 — Raw Message Pre-processor

**Responsibility:** Sanitise and normalise raw MT700 text before parsing.

**Features:**
- F1.1 Strip BOM / leading whitespace
- F1.2 Normalise line endings (`\r\n` → `\n`)
- F1.3 Validate SWIFT X-charset: reject messages containing illegal characters
  - Legal: `0-9 A-Z a-z / - ? : ( ) . , ' + SPACE LF`
  - On violation: add `ParseWarning` with position, do not abort
- F1.4 Detect block boundaries `{1:…}` `{2:…}` `{3:…}` `{4:…-}` `{5:…}`
- F1.5 Reject if Block 4 is absent
- F1.6 Detect message type from Block 2 — reject if not `700`
- F1.7 Extract Block 3 user reference `{108:…}` if present
- F1.8 Detect `:27:` value — if not `1/1`, emit `ParseWarning.MT701_CONTINUATION_DETECTED`
  (do not abort; multi-message merging is out of scope)

**Input:** `String rawMessage`
**Output:** `PreProcessedMessage { block4Text, senderBic, receiverBic, userRef, warnings }`

---

### M2 — Prowide-based Tag Extractor

**Responsibility:** Delegate raw Block 4 text to Prowide Core; extract all tag-value pairs.

**Features:**
- F2.1 Parse Block 4 via `MT700.parse(rawMessage)`
- F2.2 Iterate all tags present in the parsed message
- F2.3 Build `Map<String, List<String>> rawTagMap` (tag → list of values)
  - `List` because duplicate tag detection requires all occurrences
- F2.4 Detect duplicate tags — emit `ParseError.DUPLICATE_TAG` for any tag appearing more than once
- F2.5 Detect unknown tags (not in `lc-tag-mapping.yaml`) — emit `ParseWarning.UNKNOWN_TAG`
  - Store unknown tags in `LCDocument.unknownTags` for transparency
- F2.6 Validate tag sequence order against the expected MT700 sequence
  - Violations: emit `ParseWarning.TAG_SEQUENCE_VIOLATION` (warn, do not abort)

**Expected tag order for validation:**
```
27 → 40A → 20 → 23 → 31C → 40E → 31D → 51A/52A → 50 → 59
→ 32B → 39A/39B/39C → 41A/41D → 42C → 42A/42D/42M/42P
→ 43P → 43T → 44A/44E → 44F → 44B → 44C/44D
→ 45A → 46A → 47A → 71B → 48 → 49 → 53A/53D → 78 → 57A/57D → 72Z
```

**Input:** `PreProcessedMessage`
**Output:** `ExtractedTags { rawTagMap, tagSequence, warnings, errors }`

---

### M3 — Field Pool Registry

**Responsibility:** Load `field-pool.yaml` at application startup; provide field metadata lookup.

**Features:**
- F3.1 Load all field definitions from `field-pool.yaml` into memory on startup
- F3.2 Index by `key` for O(1) lookup
- F3.3 Provide `FieldDefinition getByKey(String key)` — throws if key not found
- F3.4 Provide `List<FieldDefinition> getBySourceTag(String tag)` — all fields mapped from a tag
- F3.5 Validate at startup: no duplicate keys, all referenced enum values non-empty
- F3.6 Expose full field list for documentation/introspection

**FieldDefinition model:**
```java
public record FieldDefinition(
    String key,            // canonical key, e.g. "credit_amount"
    String nameEn,
    String nameZh,
    FieldType type,        // STRING, AMOUNT, DATE, INTEGER, ENUM, MULTILINE_TEXT, DOCUMENT_LIST, CURRENCY_CODE
    String descriptionZh,
    List<String> sourceTags,
    String invoiceSection, // nullable — for future Invoice mapper
    boolean ruleRelevant,
    List<String> enumValues // nullable
)
```

---

### M4 — LC Tag Mapping Config

**Responsibility:** Load `lc-tag-mapping.yaml`; provide tag → field mapping lookup.

**Features:**
- F4.1 Load all tag mappings on startup
- F4.2 Separate official tags vs bank_custom_tags into distinct registries
- F4.3 For each tag mapping, store:
  - target field key(s) — single or list
  - parser type (enum: `SIMPLE_STRING`, `AMOUNT_WITH_CURRENCY`, `DATE_YYMMDD`, etc.)
  - mandatory flag
  - default value (if any)
- F4.4 `TagMapping getByTag(String tag)` — returns null if tag is unconfigured (triggers unknown tag warning)
- F4.5 Validate at startup: all referenced field keys exist in FieldPoolRegistry

**TagMapping model:**
```java
public record TagMapping(
    String tag,
    List<String> fieldKeys,   // one tag can map to multiple field keys (e.g. 32B → currency + amount)
    ParserType parserType,
    boolean mandatory,
    Map<String, Object> defaults,
    TagCategory category      // OFFICIAL / BANK_CUSTOM
)
```

---

### M5 — Field Mapper

**Responsibility:** For each tag in `ExtractedTags`, apply the configured parser and write
the result to the `LCDocument` builder.

**Features:**
- F5.1 For each tag in `rawTagMap`, look up `TagMapping`
- F5.2 Dispatch to the appropriate `SubFieldParser` based on `parserType`
- F5.3 Write parsed value(s) to `LCDocument` via field key
- F5.4 For mandatory tags that are absent: record `ParseError.MISSING_MANDATORY_TAG`
- F5.5 For optional tags that are absent: apply configured default value (if any)
- F5.6 Capture parse exceptions per-field without aborting the whole parse
  — add to `ParseResult.fieldErrors`
- F5.7 Store original raw tag value alongside parsed value for traceability
- F5.8 Handle multi-key mappings (e.g. tag `32B` populates both `credit_currency` and `credit_amount`)

---

### M6 — Sub-field Parsers

Each parser implements:
```java
public interface SubFieldParser<T> {
    T parse(String rawValue, TagMapping mapping) throws FieldParseException;
}
```

| Parser Class | Tag Examples | Format | Notes |
|---|---|---|---|
| `SimpleStringParser` | `:20:` `:27:` | `16x` | trim only |
| `AmountWithCurrencyParser` | `:32B:` | `3!a15d` | currency=first 3 chars; amount=rest, replace `,` with `.` |
| `DateYYMMDDParser` | `:31C:` `:44C:` | `6!n` | parse as `LocalDate`, century prefix: `24xx` → `20xx`, `99xx` → `19xx` |
| `DatePlusTextParser` | `:31D:` | `6!n29x` | component1=date, component2=place (trimmed) |
| `SlashSeparatedIntParser` | `:39A:` | `2n/2n` | split on `/`, parse each as int |
| `IntBeforeSlashParser` | `:48:` | `3n[/35x]` | take numeric part before `/` |
| `MultilineFirstLineParser` | `:50:` `:59:` | `4*35x` | first non-empty line = company name; remaining lines = address joined |
| `MultilineFullParser` | `:45A:` `:47A:` `:78:` | `100*65x` | strip leading `+`, return as List<String> |
| `EnumNormalizedParser` | `:43P:` `:43T:` `:49:` | `11x / 7!x` | uppercase, map to enum constant |
| `DocumentListParser` | `:46A:` | `100*65x` | see F6-DocumentListParser below |
| `BicParser` | `:51A:` `:42D:` | `4!a2!a2!c[3!c]` | extract BIC, strip optional account prefix |

**F6-DocumentListParser detail:**

Split by `+` delimiter. For each line group, attempt pattern matching to extract:

```
DocumentRequirement {
    DocType type,        // COMMERCIAL_INVOICE, BILL_OF_LADING, CERT_OF_ORIGIN,
                         // PACKING_LIST, INSURANCE_CERT, INSPECTION_CERT, OTHER
    Integer originals,   // parsed from "IN TRIPLICATE" / "3 ORIGINALS" / "ONE ORIGINAL"
    Integer copies,      // parsed from "IN DUPLICATE" / "2 COPIES" / "ONE COPY"
    boolean signed,      // "SIGNED" keyword present
    boolean fullSet,     // "FULL SET" keyword present
    boolean onBoard,     // "ON BOARD" keyword present
    String  consignee,   // "MADE OUT TO ORDER OF ..."
    String  freightCondition,  // "FREIGHT PREPAID" / "FREIGHT COLLECT"
    String  notifyParty, // "NOTIFY APPLICANT" / free text
    String  issuingBody, // "ISSUED BY SINGAPORE CHAMBER OF COMMERCE"
    String  rawText      // always keep original text
}
```

Word-to-number mapping required:
```
ONE → 1, TWO → 2, THREE → 3, TRIPLICATE → 3, DUPLICATE → 2, QUADRUPLICATE → 4
```

Unmatched lines → `DocType.OTHER`, `rawText` preserved.

---

### M7 — Parsed LC Model

```java
public class LCDocument {

    // ── Traceability ─────────────────────────────────────────────
    private String rawMessage;                    // original input, stored as-is
    private Map<String, String> rawTagValues;     // tag → raw string before parsing

    // ── Block Headers ────────────────────────────────────────────
    private String senderBic;
    private String receiverBic;
    private String userReference;                 // Block 3 :108:

    // ── Sequence A: General Information ──────────────────────────
    private String  sequenceOfTotal;              // :27:
    private String  creditType;                   // :40A: IRREVOCABLE etc.
    private String  lcNumber;                     // :20:
    private String  preAdviceReference;           // :23: optional
    private LocalDate issueDate;                  // :31C:
    private String  applicableRules;              // :40E:
    private LocalDate expiryDate;                 // :31D: component1
    private String  expiryPlace;                  // :31D: component2
    private String  applicantBankBic;             // :51A: optional
    private String  applicantName;                // :50: line1
    private String  applicantAddress;             // :50: lines 2-4
    private String  beneficiaryName;              // :59: line1
    private String  beneficiaryAddress;           // :59: lines 2-4
    private String  creditCurrency;               // :32B: component1
    private BigDecimal creditAmount;              // :32B: component2
    private Integer tolerancePlus;                // :39A: component1
    private Integer toleranceMinus;               // :39A: component2
    private String  maxCreditAmountFlag;          // :39B: "NOT EXCEEDING"
    private String  additionalAmountsCovered;     // :39C:

    // ── Sequence B: Availability ─────────────────────────────────
    private String  availableWith;               // :41A: or :41D: — bank name/BIC
    private String  availableBy;                 // BY PAYMENT / NEGOTIATION etc.
    private String  draftsAt;                    // :42C:
    private String  draweeBic;                   // :42A: or :42D:
    private String  mixedPaymentDetails;          // :42M:
    private String  deferredPaymentDetails;       // :42P:

    // ── Sequence C: Shipment ──────────────────────────────────────
    private String  partialShipments;            // :43P:
    private String  transhipment;                // :43T:
    private String  placeOfReceipt;              // :44A:
    private String  portOfLoading;               // :44E:
    private String  portOfDischarge;             // :44F:
    private String  placeOfDelivery;             // :44B:
    private LocalDate latestShipmentDate;        // :44C:
    private String  shipmentPeriod;              // :44D:

    // ── Sequence D: Documents ─────────────────────────────────────
    private List<String> goodsDescription;       // :45A: lines
    private String       incoterms;              // extracted from :45A: (CIF/FOB/CFR etc.)
    private List<DocumentRequirement> documentsRequired;  // :46A: parsed
    private List<String> additionalConditions;   // :47A: lines

    // ── Sequence E: Charges & Presentation ───────────────────────
    private List<String> charges;                // :71B:
    private Integer presentationDays;            // :48: (default: 21)
    private String  confirmationInstructions;    // :49:
    private String  reimbursingBankBic;          // :53A: or :53D:
    private List<String> bankInstructions;       // :78:
    private String  adviseThroughBank;           // :57A: or :57D:
    private String  senderToReceiverInfo;        // :72Z:

    // ── Extensions ───────────────────────────────────────────────
    private Map<String, String> unknownTags;     // tags not in config, stored raw
    private Map<String, String> bankCustomFields; // parsed bank_custom_tags

    // ── Computed / Derived Fields ─────────────────────────────────
    // These are computed after parsing, not read from tags directly
    private BigDecimal maxAllowableAmount;       // creditAmount × (1 + tolerancePlus/100)
    private BigDecimal minAllowableAmount;       // creditAmount × (1 - toleranceMinus/100)
    private LocalDate  presentationDeadline;     // latestShipmentDate + presentationDays
}
```

```java
public class ParseResult {
    private LCDocument document;          // null if fatal error
    private boolean success;
    private List<ParseError> errors;      // fatal — parsing stopped or field skipped
    private List<ParseWarning> warnings;  // non-fatal — parsing continued
    private Map<String, String> rawTagValues; // for debugging

    public boolean hasErrors() { ... }
    public boolean isFullyCompliant() { ... } // no errors AND no warnings
}

public record ParseError(
    String code,      // e.g. MISSING_MANDATORY_TAG, DUPLICATE_TAG, INVALID_FORMAT
    String tag,       // which tag caused the error
    String fieldKey,  // which canonical field
    String message
) {}

public record ParseWarning(
    String code,      // e.g. UNKNOWN_TAG, TAG_SEQUENCE_VIOLATION, CHARSET_VIOLATION
    String tag,
    String message
) {}
```

---

### M8 — Validation

Two validators run after field mapping:

**LCStructureValidator** — cross-field business rules:
```
V1: expiryDate must be present (UCP 600 Art.6)
V2: latestShipmentDate <= expiryDate (UCP 600 Art.29)
V3: latestShipmentDate + presentationDays <= expiryDate (UCP 600 Art.14c)
V4: creditAmount > 0
V5: if 42C present → either 42A or 42D must also be present
V6: 39A and 39B must not both be present
V7: 44C and 44D must not both be present
V8: if availableBy contains ACCEPTANCE or NEGOTIATION → draftsAt (42C) must be present
```

Each violation → `ParseWarning` (not error — the document is still returned but flagged).

**FieldFormatValidator** — spot-checks key format constraints:
```
- lcNumber: no leading/trailing slash, no double slash, max 16 chars
- creditCurrency: must be 3 uppercase letters (ISO 4217 pattern)
- issueDate: must not be in the future
- presentationDays: 1–999
```

---

## 5. Field Coverage

The following MT700 tags must be handled in Phase 1:

| Tag | Mandatory | Parser Type | Field Key(s) |
|-----|-----------|-------------|--------------|
| `:27:` | M | SIMPLE_STRING | `sequence_of_total` |
| `:40A:` | M | ENUM_NORMALIZED | `credit_type` |
| `:20:` | M | SIMPLE_STRING | `lc_number` |
| `:23:` | O | SIMPLE_STRING | `pre_advice_reference` |
| `:31C:` | M | DATE_YYMMDD | `issue_date` |
| `:40E:` | M | SIMPLE_STRING | `applicable_rules` |
| `:31D:` | M | DATE_PLUS_TEXT | `expiry_date`, `expiry_place` |
| `:51A:` | O | BIC | `applicant_bank_bic` |
| `:50:` | M | MULTILINE_FIRST_LINE | `applicant_name`, `applicant_address` |
| `:59:` | M | MULTILINE_FIRST_LINE | `beneficiary_name`, `beneficiary_address` |
| `:32B:` | M | AMOUNT_WITH_CURRENCY | `credit_currency`, `credit_amount` |
| `:39A:` | O | SLASH_SEPARATED_INT | `tolerance_plus`, `tolerance_minus` |
| `:39B:` | O | SIMPLE_STRING | `max_credit_amount_flag` |
| `:39C:` | O | MULTILINE_FULL | `additional_amounts_covered` |
| `:41A:` | C | BIC | `available_with`, `available_by` |
| `:41D:` | C | MULTILINE_AVAILABLE_BY | `available_with`, `available_by` |
| `:42C:` | C | SIMPLE_STRING | `drafts_at` |
| `:42A:` | C | BIC | `drawee_bic` |
| `:42D:` | C | SIMPLE_STRING | `drawee_bic` |
| `:42M:` | C | MULTILINE_FULL | `mixed_payment_details` |
| `:42P:` | C | MULTILINE_FULL | `deferred_payment_details` |
| `:43P:` | O | ENUM_NORMALIZED | `partial_shipments` |
| `:43T:` | O | ENUM_NORMALIZED | `transhipment` |
| `:44A:` | O | SIMPLE_STRING | `place_of_receipt` |
| `:44E:` | O | SIMPLE_STRING | `port_of_loading` |
| `:44F:` | O | SIMPLE_STRING | `port_of_discharge` |
| `:44B:` | O | SIMPLE_STRING | `place_of_delivery` |
| `:44C:` | C | DATE_YYMMDD | `latest_shipment_date` |
| `:44D:` | C | MULTILINE_FULL | `shipment_period` |
| `:45A:` | O | MULTILINE_FULL + INCOTERMS_EXTRACT | `goods_description`, `incoterms` |
| `:46A:` | O | DOCUMENT_LIST | `documents_required` |
| `:47A:` | O | MULTILINE_FULL | `additional_conditions` |
| `:71B:` | O | MULTILINE_FULL | `charges` |
| `:48:` | O | INT_BEFORE_SLASH | `presentation_days` (default: 21) |
| `:49:` | M | ENUM_NORMALIZED | `confirmation_instructions` |
| `:53A:` | O | BIC | `reimbursing_bank_bic` |
| `:53D:` | O | SIMPLE_STRING | `reimbursing_bank_bic` |
| `:78:` | O | MULTILINE_FULL | `bank_instructions` |
| `:57A:` | O | BIC | `advise_through_bank` |
| `:57D:` | O | SIMPLE_STRING | `advise_through_bank` |
| `:72Z:` | O | FREE_TEXT_WITH_SUBKEYS | `bank_custom_fields` (Map) |

---

## 6. Config File Specs

### `field-pool.yaml` — required top-level keys per entry
```yaml
- key: string              # REQUIRED. snake_case, globally unique
  name_en: string          # REQUIRED.
  name_zh: string          # REQUIRED.
  type: FieldType          # REQUIRED. STRING|AMOUNT|DATE|INTEGER|ENUM|
                           #           MULTILINE_TEXT|DOCUMENT_LIST|CURRENCY_CODE
  description_zh: string   # REQUIRED.
  source_tags: [string]    # list of MT700 tags that produce this field; [] for invoice-only fields
  invoice_section: string  # nullable; hint for future invoice mapper
  rule_relevant: boolean   # REQUIRED.
  enum_values: [string]    # only when type=ENUM
```

### `lc-tag-mapping.yaml` — required structure
```yaml
mt700:
  official_tags:
    "<tag>":
      field_key: string | [string]   # one or many canonical keys
      parser: ParserType             # enum value
      mandatory: boolean
      default:                       # optional, per-key defaults
        <field_key>: <value>

  bank_custom_tags:
    "<tag>":
      type: FREE_TEXT_WITH_SUBKEYS | CONDITION_KEYWORD_EXTRACT
      sub_mappings:
        "<subkey>":
          field_key: string
          description_zh: string
          extract_pattern: string    # regex, for CONDITION_KEYWORD_EXTRACT type
```

---

## 7. Data Model Specs

### Enum: `FieldType`
```
STRING, AMOUNT, DATE, INTEGER, ENUM,
MULTILINE_TEXT, DOCUMENT_LIST, CURRENCY_CODE
```

### Enum: `ParserType`
```
SIMPLE_STRING, AMOUNT_WITH_CURRENCY, DATE_YYMMDD, DATE_PLUS_TEXT,
SLASH_SEPARATED_INT, INT_BEFORE_SLASH, MULTILINE_FIRST_LINE,
MULTILINE_FULL, MULTILINE_AVAILABLE_BY, ENUM_NORMALIZED,
BIC, DOCUMENT_LIST, FREE_TEXT_WITH_SUBKEYS
```

### Enum: `DocType`
```
COMMERCIAL_INVOICE, BILL_OF_LADING, AIRWAY_BILL,
CERT_OF_ORIGIN, PACKING_LIST, INSURANCE_CERT,
INSPECTION_CERT, WEIGHT_LIST, PHYTOSANITARY_CERT, OTHER
```

### Enum: `TagCategory`
```
OFFICIAL, BANK_CUSTOM
```

---

## 8. Error Handling

| Scenario | Behaviour | Output |
|---|---|---|
| Block 4 missing | Abort, return null document | `ParseError.MISSING_BLOCK4` |
| Not an MT700 message | Abort | `ParseError.WRONG_MESSAGE_TYPE` |
| Mandatory tag missing | Continue parsing rest | `ParseError.MISSING_MANDATORY_TAG` |
| Tag value fails format validation | Skip field, keep raw value | `ParseError.INVALID_FORMAT` |
| Duplicate tag | Keep first, warn | `ParseError.DUPLICATE_TAG` |
| Unknown tag | Store in `unknownTags`, warn | `ParseWarning.UNKNOWN_TAG` |
| Tag out of sequence | Continue, warn | `ParseWarning.TAG_SEQUENCE_VIOLATION` |
| Illegal SWIFT charset character | Continue, warn with position | `ParseWarning.CHARSET_VIOLATION` |
| `:27:` indicates MT701 continuation | Continue, warn | `ParseWarning.MT701_CONTINUATION_DETECTED` |
| Cross-field validation failure | Warn, document still returned | `ParseWarning.CROSS_FIELD_VALIDATION_*` |
| Config YAML load failure | Fail fast at startup | `IllegalStateException` at boot |

---

## 9. Test Plan

### Unit Tests

**T1 — PreProcessor**
- [ ] `T1.1` Valid message → clean output, no warnings
- [ ] `T1.2` CRLF line endings → normalised
- [ ] `T1.3` Illegal charset character `@` → warning with position
- [ ] `T1.4` Missing Block 4 → `MISSING_BLOCK4` error
- [ ] `T1.5` Block 2 type `710` (not `700`) → `WRONG_MESSAGE_TYPE` error
- [ ] `T1.6` `:27:2/2` → `MT701_CONTINUATION_DETECTED` warning

**T2 — SubField Parsers**
- [ ] `T2.1` `AmountWithCurrencyParser("USD50000,")` → currency=USD, amount=50000.00
- [ ] `T2.2` `DateYYMMDDParser("241115")` → `2024-11-15`
- [ ] `T2.3` `DatePlusTextParser("241215SINGAPORE")` → date=`2024-12-15`, place=`SINGAPORE`
- [ ] `T2.4` `SlashSeparatedIntParser("10/10")` → [10, 10]
- [ ] `T2.5` `IntBeforeSlashParser("21")` → 21; `IntBeforeSlashParser("21/CONDITION")` → 21
- [ ] `T2.6` `MultilineFirstLineParser` on `:50:` block → correct name + address split
- [ ] `T2.7` `EnumNormalizedParser("NOT ALLOWED")` → `NOT_ALLOWED`
- [ ] `T2.8` `DocumentListParser` on `:46A:` sample → 5 structured DocumentRequirement objects

**T3 — DocumentListParser detail**
- [ ] `T3.1` `SIGNED COMMERCIAL INVOICE IN TRIPLICATE` → type=COMMERCIAL_INVOICE, signed=true, originals=3
- [ ] `T3.2` `FULL SET 3/3 ORIGINAL CLEAN ON BOARD BILLS OF LADING MADE OUT TO ORDER OF HSBC HONG KONG MARKED FREIGHT PREPAID AND NOTIFY APPLICANT` → fullSet=true, originals=3, onBoard=true, consignee="HSBC HONG KONG", freightCondition=PREPAID
- [ ] `T3.3` `PACKING LIST IN DUPLICATE` → type=PACKING_LIST, copies=2
- [ ] `T3.4` Unrecognised document text → type=OTHER, rawText preserved

**T4 — Full parse on sample_lc_mt700.txt**
- [ ] `T4.1` `lcNumber` == `LC2024-000123`
- [ ] `T4.2` `creditCurrency` == `USD`, `creditAmount` == `50000.00`
- [ ] `T4.3` `tolerancePlus` == `10`, `toleranceMinus` == `10`
- [ ] `T4.4` `expiryDate` == `2024-12-15`, `expiryPlace` == `SINGAPORE`
- [ ] `T4.5` `latestShipmentDate` == `2024-11-15`
- [ ] `T4.6` `presentationDays` == `21`
- [ ] `T4.7` `applicantName` == `SINO IMPORTS CO LTD`
- [ ] `T4.8` `beneficiaryName` == `WIDGET EXPORTS PTE LTD`
- [ ] `T4.9` `partialShipments` == `NOT_ALLOWED`
- [ ] `T4.10` `incoterms` == `CIF`
- [ ] `T4.11` `documentsRequired` has 5 entries, types match expected
- [ ] `T4.12` `maxAllowableAmount` == `55000.00` (50000 × 1.10)
- [ ] `T4.13` `presentationDeadline` == `2024-12-06` (2024-11-15 + 21 days)
- [ ] `T4.14` No `ParseError` entries in `ParseResult`
- [ ] `T4.15` `rawTagValues` contains all 29 tags from the sample

**T5 — Validation**
- [ ] `T5.1` latestShipmentDate > expiryDate → `ParseWarning.CROSS_FIELD_V2`
- [ ] `T5.2` 39A and 39B both present → `ParseWarning.CROSS_FIELD_V6`
- [ ] `T5.3` Available by ACCEPTANCE without 42C → `ParseWarning.CROSS_FIELD_V8`

**T6 — Config integrity**
- [ ] `T6.1` All `source_tags` in field-pool.yaml referenced in lc-tag-mapping.yaml
- [ ] `T6.2` All `field_key` values in lc-tag-mapping.yaml exist in field-pool.yaml
- [ ] `T6.3` No duplicate keys in field-pool.yaml

---

## 10. Out of Scope

The following are explicitly deferred to later phases:

| Item | Reason |
|------|--------|
| MT701 multi-message merge | Requires stateful session; Phase 1 warns and continues |
| `:46A:` condition → dynamic rule extraction | Phase 2 (Rule Engine) |
| `:47A:` condition → dynamic rule extraction | Phase 2 (Rule Engine) |
| Invoice PDF extraction | Phase 2 |
| Invoice vs LC rule checking | Phase 2 |
| MT707 amendment parsing | Phase 3 |
| MT710 / MT720 other message types | Phase 3 |
| ISO 20022 tsmt equivalent | Future |
| REST API endpoint | Phase 2 wrapper |
| Prowide Integrator full NVR validation | Optional commercial add-on |

---

## 11. Acceptance Criteria

The LC Parser phase is complete when:

- [ ] `AC1` All 39 tags in Field Coverage table are handled
- [ ] `AC2` `field-pool.yaml` and `lc-tag-mapping.yaml` are the sole source of field definitions — no field names, tag strings, or business descriptions hardcoded in Java
- [ ] `AC3` `LCParser.parse(sampleLcMt700Text)` returns a `ParseResult` with `success=true`, zero errors, and all T4 assertions passing
- [ ] `AC4` All unit tests in T1–T6 pass
- [ ] `AC5` `LCDocument` includes `rawTagValues` map for every tag present in the input
- [ ] `AC6` An unknown tag in the input produces a `ParseWarning`, not an exception
- [ ] `AC7` A missing mandatory tag produces a `ParseError` entry but does NOT throw an exception — partial document is still returned
- [ ] `AC8` Config YAML load failure throws `IllegalStateException` at application startup with a clear message identifying which file and which entry failed
- [ ] `AC9` `LCDocument.maxAllowableAmount` and `minAllowableAmount` are correctly computed from `creditAmount` + `tolerancePlus/Minus`
- [ ] `AC10` `DocumentRequirement` list from `:46A:` contains at minimum: type, originals/copies, rawText

---

## 12. Suggested File Structure

```
lc-parser/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/bank/lcparser/
│   │   │   ├── LcParserApplication.java
│   │   │   ├── config/
│   │   │   │   └── ParserConfig.java              ← Spring @Configuration, loads YAML beans
│   │   │   ├── domain/
│   │   │   │   ├── LCDocument.java
│   │   │   │   ├── DocumentRequirement.java
│   │   │   │   ├── ParseResult.java
│   │   │   │   ├── ParseError.java
│   │   │   │   └── ParseWarning.java
│   │   │   ├── registry/
│   │   │   │   ├── FieldDefinition.java
│   │   │   │   ├── FieldPoolRegistry.java
│   │   │   │   ├── TagMapping.java
│   │   │   │   └── TagMappingRegistry.java
│   │   │   ├── parser/
│   │   │   │   ├── LCParser.java                  ← main entry: parse(String) → ParseResult
│   │   │   │   ├── RawMessagePreProcessor.java
│   │   │   │   ├── TagExtractor.java
│   │   │   │   ├── FieldMapper.java
│   │   │   │   └── subfield/
│   │   │   │       ├── SubFieldParser.java
│   │   │   │       ├── SimpleStringParser.java
│   │   │   │       ├── AmountWithCurrencyParser.java
│   │   │   │       ├── DateYYMMDDParser.java
│   │   │   │       ├── DatePlusTextParser.java
│   │   │   │       ├── SlashSeparatedIntParser.java
│   │   │   │       ├── IntBeforeSlashParser.java
│   │   │   │       ├── MultilineFirstLineParser.java
│   │   │   │       ├── MultilineFullParser.java
│   │   │   │       ├── MultilineAvailableByParser.java
│   │   │   │       ├── EnumNormalizedParser.java
│   │   │   │       ├── BicParser.java
│   │   │   │       ├── DocumentListParser.java
│   │   │   │       └── FreeTextWithSubkeysParser.java
│   │   │   └── validation/
│   │   │       ├── LCStructureValidator.java
│   │   │       └── FieldFormatValidator.java
│   │   └── resources/
│   │       ├── application.yaml
│   │       ├── field-pool.yaml
│   │       └── lc-tag-mapping.yaml
│   └── test/
│       ├── java/com/bank/lcparser/
│       │   ├── parser/
│       │   │   ├── LCParserIntegrationTest.java   ← T4: full parse on sample
│       │   │   ├── RawMessagePreProcessorTest.java ← T1
│       │   │   └── subfield/
│       │   │       ├── AmountWithCurrencyParserTest.java
│       │   │       ├── DateYYMMDDParserTest.java
│       │   │       ├── DocumentListParserTest.java ← T3
│       │   │       └── ...
│       │   ├── registry/
│       │   │   └── ConfigIntegrityTest.java        ← T6
│       │   └── validation/
│       │       └── LCStructureValidatorTest.java   ← T5
│       └── resources/
│           └── fixtures/
│               └── sample_lc_mt700.txt
├── REQUIREMENTS.md                                 ← this file
└── README.md
```

---

## Dependencies (pom.xml)

```xml
<!-- SWIFT MT parser -->
<dependency>
    <groupId>com.prowidesoftware</groupId>
    <artifactId>pw-swift-core</artifactId>
    <version>SRU2024-10.1.5</version>
</dependency>

<!-- YAML config loading -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
</dependency>

<!-- Lombok (reduce boilerplate) -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>

<!-- Testing -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

---

*Document version: 1.0 | Phase: LC Parser only | Next phase: Invoice Extractor + Rule Engine*
