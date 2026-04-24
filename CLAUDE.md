# LC Invoice Checker — Development Plan

## Project Overview

A Java Spring AI backend service that accepts an LC (SWIFT MT700 plain text) and a
commercial invoice (PDF), checks the invoice against UCP 600 / ISBP 821 rules, and
returns a structured JSON discrepancy report.

---

## Infra Reference

### V1 (Current Build Target)

```
lc-checker-api        Java Spring Boot        :8080
docling-svc           Python FastAPI          :8081
mineru-svc            Python FastAPI          :8082
deepseek / minimax    LLM API                 (external, OpenAI-compatible)
```

PDF is uploaded directly via curl multipart — no object storage in V1.
LLM calls go to DeepSeek (primary) or MiniMax (fallback) via OpenAI-compatible protocol.

### V2 (Reference Only — Do Not Implement)

```
lc-checker-api        Java Spring Boot        :8080
docling-svc           Python FastAPI          :8081
mineru-svc            Python FastAPI          :8082
minio                 Object Storage          :9000 / :9001

postgres              Session Persistence     :5432
redis                 Extraction Cache        :6379

otel-collector        Telemetry Pipeline      :4317 / :4318
langfuse              LLM Observability       :3000  (optional, bank approval required)
prometheus            Metrics                 :9090
grafana               Dashboards              :3001

vllm                  Local LLM               :8000  (requires GPU)
vision-llm            Vision LLM              :8003  (requires GPU)
```

---

## API Design

### Endpoints

```
POST /api/v1/files/upload
  multipart: file (application/pdf)
  → 200 { "fileKey": "uuid", "filename": "invoice.pdf" }
  → 400 { "error": "NOT_A_PDF", "message": "..." }

POST /api/v1/lc-check
  multipart: lc (text/plain), invoice (application/pdf)
  → 200 DiscrepancyReport
  → 400 { "error": "VALIDATION_FAILED", "stage": "...", "message": "..." }
  → 502 { "error": "EXTRACTOR_UNAVAILABLE", "message": "..." }

GET /api/v1/lc-check/{sessionId}/trace
  → 200 CheckSession (full intermediate state for visualization)
  → 404 { "error": "SESSION_NOT_FOUND" }
```

### curl Usage (V1 Demo)

```bash
# Single-step: upload + check together
curl -X POST http://localhost:8080/api/v1/lc-check \
  -F "lc=@./test/sample_mt700.txt;type=text/plain" \
  -F "invoice=@./test/sample_invoice.pdf;type=application/pdf"

# View intermediate trace
curl http://localhost:8080/api/v1/lc-check/{sessionId}/trace
```

---

## Project Structure

```
lc-invoice-checker/
├── docker-compose.yml
├── lc-checker-api/                         ← Java Spring Boot
│   ├── build.gradle
│   ├── src/main/java/com/lc/checker/
│   │   ├── LcCheckerApplication.java
│   │   ├── api/
│   │   │   ├── LcCheckController.java
│   │   │   └── FileUploadController.java
│   │   ├── model/
│   │   │   ├── LcDocument.java             ← MT700 parsed output
│   │   │   ├── InvoiceDocument.java        ← PDF extracted output
│   │   │   ├── CheckResult.java            ← Single rule result
│   │   │   ├── DiscrepancyReport.java      ← Final JSON output
│   │   │   └── CheckSession.java           ← Full trace state
│   │   ├── parser/
│   │   │   └── Mt700Parser.java            ← Pure regex, no LLM
│   │   ├── extractor/
│   │   │   ├── InvoiceExtractor.java       ← Interface
│   │   │   ├── DoclingExtractorClient.java ← HTTP call to :8081
│   │   │   ├── MineruExtractorClient.java  ← HTTP call to :8082
│   │   │   └── ExtractorRouter.java        ← Docling first, MiniRU fallback
│   │   ├── checker/
│   │   │   ├── RuleChecker.java            ← Interface
│   │   │   ├── AmountToleranceChecker.java
│   │   │   ├── CurrencyChecker.java
│   │   │   ├── BeneficiaryChecker.java
│   │   │   ├── ApplicantChecker.java
│   │   │   ├── GoodsDescriptionChecker.java
│   │   │   ├── TradeTermsChecker.java
│   │   │   ├── LcNumberReferenceChecker.java
│   │   │   ├── InvoiceDateChecker.java
│   │   │   ├── SignatureChecker.java
│   │   │   ├── QuantityToleranceChecker.java
│   │   │   ├── UnitPriceChecker.java
│   │   │   ├── AddressCountryChecker.java
│   │   │   ├── PresentationPeriodChecker.java
│   │   │   └── CountryOfOriginChecker.java
│   │   ├── engine/
│   │   │   └── ComplianceEngine.java       ← Iterates all RuleCheckers
│   │   ├── validation/
│   │   │   └── InputValidator.java         ← Pre-pipeline validation
│   │   └── store/
│   │       └── CheckSessionStore.java      ← ConcurrentHashMap, in-memory
│   └── src/main/resources/
│       ├── application.yml
│       └── prompts/
│           ├── invoice-extraction.st       ← Spring AI prompt template
│           └── goods-description-check.st  ← Semantic check prompt
├── extractors/
│   ├── docling/
│   │   ├── Dockerfile
│   │   ├── requirements.txt
│   │   └── main.py                         ← FastAPI + Docling
│   └── mineru/
│       ├── Dockerfile
│       ├── requirements.txt
│       └── main.py                         ← FastAPI + MiniRU
└── test/
    ├── sample_mt700.txt
    ├── sample_invoice.pdf
    └── check.sh
```

---

## Module 1: MT700 Parser

**Rule: Pure regex + state machine. Zero LLM involvement.**

MT700 fields follow `:TAG:value` format. Parse into a strongly-typed `LcDocument`.

### Key Fields to Parse

```
:20:   LC Reference Number         → String lcReference
:31D:  Expiry Date & Place         → LocalDate expiryDate, String expiryPlace
:32B:  Currency + Amount           → String currency, BigDecimal amount
:39A:  Tolerance                   → int tolerancePlus, int toleranceMinus  (e.g. "5/5")
:39B:  Maximum Credit Amount       → String maxCreditClause  (e.g. "NOT EXCEEDING")
:41A:  Available With / By         → String availableWith
:44A:  Place of Taking in Charge   → String placeOfReceipt
:44E:  Port of Loading             → String portOfLoading
:44F:  Port of Discharge           → String portOfDischarge
:45A:  Description of Goods        → String goodsDescription  (free text, multiline)
:46A:  Documents Required          → String documentsRequired (free text, multiline)
:47A:  Additional Conditions       → String additionalConditions
:48:   Period for Presentation     → int presentationDays
:50:   Applicant                   → String applicantName, String applicantAddress
:59:   Beneficiary                 → String beneficiaryName, String beneficiaryAddress
```

### LcDocument Model

```java
public record LcDocument(
    String lcReference,
    String currency,
    BigDecimal amount,
    int tolerancePlus,          // default 0 if :39A: absent
    int toleranceMinus,
    LocalDate expiryDate,
    String expiryPlace,
    String portOfLoading,
    String portOfDischarge,
    String applicantName,
    String applicantAddress,
    String beneficiaryName,
    String beneficiaryAddress,
    String goodsDescription,    // :45A: raw text
    String documentsRequired,   // :46A: raw text
    String additionalConditions,
    int presentationDays,       // :48: default 21 per UCP 600 Art. 14(c)
    Map<String, String> rawFields  // all parsed raw fields for trace
) {}
```

### Parsing Logic

```java
// Mt700Parser.java
public LcDocument parse(String mt700Text) {
    // 1. Split by field tag pattern :\d{2}[A-Z]?:
    // 2. Build Map<String, String> rawFields
    // 3. Map each tag to LcDocument fields
    // 4. Handle multiline values (continuation lines)
    // 5. Parse :32B: → split first 3 chars as currency, rest as amount
    // 6. Parse :39A: → split by "/" into plus/minus tolerance
    // 7. Parse :31D: → first 6 chars YYMMDD as date, rest as place
    // 8. Throw LcParseException with field name if mandatory field missing
}
```

### Mandatory Field Validation

Throw `LcParseException` if any of these are absent:
`:20:` `:31D:` `:32B:` `:45A:` `:50:` `:59:`

---

## Module 2: Invoice PDF Extractor

### Extractor Interface (Java)

```java
public interface InvoiceExtractor {
    InvoiceDocument extract(byte[] pdfBytes) throws ExtractionException;
    String extractorName();
}
```

### ExtractorRouter

```java
// Primary: Docling. Fallback: MiniRU.
// If confidence < 0.80 or exception → try MiniRU
// If both fail → throw ExtractionException (API returns 502)
```

### Extractor Sidecar Contract

Both `docling-svc` and `mineru-svc` expose identical HTTP contract:

**Request:**
```
POST /extract
Content-Type: multipart/form-data
file: <pdf bytes>
```

**Response:**
```json
{
  "extractor": "docling",
  "confidence": 0.92,
  "raw_markdown": "...",
  "fields": {
    "invoice_number": "INV-2024-001",
    "invoice_date": "2024-03-15",
    "seller_name": "ABC Export Co Ltd",
    "seller_address": "123 Trade St, Shanghai",
    "buyer_name": "XYZ Import Corp",
    "buyer_address": "456 Commerce Ave, Singapore",
    "goods_description": "1000 units Industrial Widgets Model IW-2024",
    "quantity": "1000",
    "unit_price": "USD 50.00",
    "total_amount": "55000.00",
    "currency": "USD",
    "lc_reference": "LC2024-000123",
    "trade_terms": "CIF SINGAPORE",
    "port_of_loading": "SHANGHAI",
    "port_of_discharge": "SINGAPORE",
    "signed": true
  }
}
```

### InvoiceDocument Model (Java)

```java
public record InvoiceDocument(
    String invoiceNumber,
    LocalDate invoiceDate,
    String sellerName,
    String sellerAddress,
    String buyerName,
    String buyerAddress,
    String goodsDescription,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal totalAmount,
    String currency,
    String lcReference,
    String tradeTerms,
    String portOfLoading,
    String portOfDischarge,
    boolean signed,
    String extractorUsed,
    double extractorConfidence,
    String rawMarkdown             // preserved for trace
) {}
```

### Docling Sidecar (Python)

```python
# extractors/docling/main.py
from fastapi import FastAPI, UploadFile, HTTPException
from docling.document_converter import DocumentConverter

app = FastAPI()
converter = DocumentConverter()

@app.post("/extract")
async def extract(file: UploadFile):
    if file.content_type != "application/pdf":
        raise HTTPException(400, "PDF only")
    
    content = await file.read()
    # Save temp file → Docling convert → export markdown
    # Call LLM API to parse markdown into structured fields
    # Return unified JSON contract
    ...

@app.get("/health")
def health():
    return {"status": "ok", "extractor": "docling"}
```

### MiniRU Sidecar (Python)

```python
# extractors/mineru/main.py
# Same FastAPI structure, uses magic-pdf pipeline instead
# Returns same JSON contract as Docling sidecar
```

---

## Module 3: Compliance Engine

### RuleChecker Interface

```java
public interface RuleChecker {
    String getRuleId();           // e.g. "UCP_ART_18B"
    String getRuleReference();    // e.g. "UCP 600 Art. 18(b)"
    CheckType getCheckType();     // DETERMINISTIC or SEMANTIC
    CheckResult check(LcDocument lc, InvoiceDocument invoice);
}

public enum CheckType { DETERMINISTIC, SEMANTIC }
```

### CheckResult Model

```java
public record CheckResult(
    String ruleId,
    String ruleReference,
    CheckType checkType,
    CheckStatus status,          // PASS, FAIL, WARN, SKIPPED
    String field,                // e.g. "invoice_amount"
    String lcValue,
    String presentedValue,
    String description,
    Map<String, Object> inputSnapshot,   // for trace
    String llmResponseSummary,           // null if DETERMINISTIC
    long durationMs
) {}

public enum CheckStatus { PASS, FAIL, WARN, SKIPPED }
```

### ComplianceEngine

```java
@Component
public class ComplianceEngine {
    
    private final List<RuleChecker> checkers;  // Spring auto-injects all beans
    
    public List<CheckResult> runAll(LcDocument lc, InvoiceDocument invoice) {
        return checkers.stream()
            .map(checker -> runSafe(checker, lc, invoice))
            .collect(toList());
    }
    
    private CheckResult runSafe(RuleChecker checker, LcDocument lc, InvoiceDocument invoice) {
        try {
            long start = System.currentTimeMillis();
            CheckResult result = checker.check(lc, invoice);
            // wrap with duration
        } catch (Exception e) {
            // return CheckResult with status=WARN, reason=checker error
        }
    }
}
```

---

## Module 4: Rule Implementations

### Priority 1 — Must Pass (matches sample output in requirements)

#### AmountToleranceChecker → UCP 600 Art. 18(b)

```
Logic (DETERMINISTIC):
  lcAmount = LcDocument.amount
  tolerance = LcDocument.tolerancePlus / toleranceMinus  (from :39A:)
  If :39A: absent AND :39B: = "NOT EXCEEDING" → tolerance = 0
  If :39A: absent AND no :39B: → apply default UCP 10% rule only if no unit price

  maxAllowed = lcAmount * (1 + tolerancePlus/100)
  minAllowed = lcAmount * (1 - toleranceMinus/100)

  FAIL if invoice.totalAmount > maxAllowed
  FAIL if invoice.totalAmount < minAllowed
```

#### GoodsDescriptionChecker → UCP 600 Art. 18(c) + ISBP 821 C3

```
Logic (SEMANTIC — LLM required):
  Prompt: Given LC goods description and invoice goods description,
          determine if invoice description is consistent with LC.
          Invoice may use broader/shorter description but must not contradict.
          Check quantity match specifically.
          Return: { "compliant": bool, "reason": string, "quantity_match": bool }

  FAIL if not compliant
  FAIL if quantity_match = false (separate discrepancy entry)
```

#### LcNumberReferenceChecker → ISBP 821 C1

```
Logic (DETERMINISTIC):
  Check if :46A: documentsRequired contains instruction to quote LC number.
  If yes: invoice.lcReference must match LcDocument.lcReference exactly.
  If :46A: silent on this: still check invoice.lcReference if present.

  FAIL if required but invoice.lcReference is null or does not match.
```

### Priority 2 — Core Rules

#### CurrencyChecker → UCP 600 Art. 18(a)

```
Logic (DETERMINISTIC):
  FAIL if invoice.currency != lc.currency (case-insensitive)
```

#### BeneficiaryChecker → UCP 600 Art. 18(a) + ISBP 821 C5

```
Logic (SEMANTIC):
  LLM compare lc.beneficiaryName vs invoice.sellerName
  Minor variations acceptable (punctuation, abbreviation)
  FAIL if clearly different entity
```

#### ApplicantChecker → ISBP 821 C6

```
Logic (SEMANTIC):
  LLM compare lc.applicantName vs invoice.buyerName
  Same tolerance as beneficiary check
```

#### TradeTermsChecker → ISBP 821 C14

```
Logic (DETERMINISTIC + SEMANTIC):
  Extract trade terms from lc.goodsDescription (e.g. CIF SINGAPORE)
  Check invoice.tradeTerms contains equivalent
  FAIL if trade terms absent from invoice when specified in LC
```

#### InvoiceDateChecker → UCP 600 Art. 14(i)

```
Logic (DETERMINISTIC):
  FAIL if invoice.invoiceDate > lc.expiryDate
  FAIL if invoice.invoiceDate is in the future
```

#### PresentationPeriodChecker → UCP 600 Art. 14(c)

```
Logic (DETERMINISTIC):
  Default presentation period = 21 days if :48: absent
  invoice.invoiceDate + presentationDays must be <= lc.expiryDate
  WARN if cutting close (within 3 days)
```

### Priority 3 — Additional Rules

#### SignatureChecker → UCP 600 Art. 18(a) + ISBP 821 C3

```
Logic (DETERMINISTIC):
  FAIL if invoice.signed == false
```

#### QuantityToleranceChecker → UCP 600 Art. 30(b)

```
Logic (DETERMINISTIC):
  If :39A: tolerance present → same % applies to quantity
  If goods described by units (each/pcs) → 0% tolerance on quantity
  FAIL if quantity deviation exceeds tolerance
```

#### UnitPriceChecker → ISBP 821 C13

```
Logic (DETERMINISTIC):
  If LC specifies unit price in :45A:
  invoice.unitPrice must match exactly
  FAIL if mismatch
```

#### AddressCountryChecker → ISBP 821 C5

```
Logic (SEMANTIC):
  Country in invoice seller/buyer address must not contradict LC
  FAIL if country differs
```

#### CountryOfOriginChecker → ISBP 821 C16 (if :46A: requires it)

```
Logic (DETERMINISTIC):
  If :46A: mentions "certificate of origin" or "country of origin"
  Check invoice references country of origin if required
  SKIPPED if LC does not require
```

---

## Module 5: Output Models

### DiscrepancyReport (Final API Response)

```java
public record DiscrepancyReport(
    String sessionId,
    boolean compliant,
    List<Discrepancy> discrepancies,
    Summary summary
) {}

public record Discrepancy(
    String field,
    String lcValue,
    String presentedValue,
    String ruleReference,
    String description
) {}

public record Summary(
    int totalChecks,
    int passed,
    int failed,
    int warnings,
    int skipped
) {}
```

### Sample Output

```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "compliant": false,
  "discrepancies": [
    {
      "field": "invoice_amount",
      "lc_value": "USD 50,000.00",
      "presented_value": "USD 55,000.00",
      "rule_reference": "UCP 600 Art. 18(b)",
      "description": "Invoice amount USD 55,000.00 exceeds LC amount USD 50,000.00 beyond 5% tolerance band (max USD 52,500.00)."
    },
    {
      "field": "goods_description",
      "lc_value": "SUPPLY OF 1000 UNITS OF INDUSTRIAL WIDGETS MODEL NO. IW-2024, CIF SINGAPORE",
      "presented_value": "500 UNITS OF INDUSTRIAL WIDGETS MODEL IW-2024",
      "rule_reference": "UCP 600 Art. 18(c) / ISBP 821 C3",
      "description": "Invoice quantity 500 does not match LC required quantity 1000."
    },
    {
      "field": "lc_number_reference",
      "lc_value": "LC2024-000123",
      "presented_value": null,
      "rule_reference": "ISBP 821 C1",
      "description": "Invoice does not reference LC number as required by field 46A."
    }
  ],
  "summary": {
    "totalChecks": 14,
    "passed": 11,
    "failed": 3,
    "warnings": 0,
    "skipped": 0
  }
}
```

### CheckSession (Trace Response)

```java
public record CheckSession(
    String sessionId,
    Instant timestamp,
    StageResult lcParsing,
    StageResult invoiceExtraction,
    List<CheckResult> checksRun,
    DiscrepancyReport finalReport
) {}

public record StageResult(
    String status,          // SUCCESS, FAILED
    long durationMs,
    Object output,          // LcDocument or InvoiceDocument
    String error            // null if SUCCESS
) {}
```

---

## Module 6: Input Validation

Validate before entering pipeline. Return 400 immediately on failure.

```java
// InputValidator.java
public void validate(String lcText, byte[] pdfBytes) {
    // 1. lcText not null/blank
    // 2. MT700 structure: must contain at least one :\d{2}[A-Z]?: pattern
    // 3. Mandatory fields present: :20: :32B: :31D: :45A: :50: :59:
    // 4. pdfBytes magic bytes check: 0x25 0x50 0x44 0x46 (%PDF)
    // 5. PDF size check: reject if > 20MB
}
```

### Error Response Format

```json
{
  "error": "VALIDATION_FAILED",
  "stage": "lc_parsing",
  "message": "Mandatory field :32B: (Currency/Amount) missing from MT700 input",
  "field": ":32B:"
}
```

---

## LLM Configuration

### application.yml

```yaml
spring:
  ai:
    openai:
      api-key: ${LLM_API_KEY}
      base-url: ${LLM_BASE_URL:https://api.deepseek.com}
      chat:
        options:
          model: ${LLM_MODEL:deepseek-chat}
          temperature: 0.0      # deterministic output for compliance checks

llm:
  primary:
    base-url: https://api.deepseek.com
    model: deepseek-chat
  fallback:
    base-url: https://api.minimaxi.chat/v1
    model: MiniMax-Text-01

extractor:
  docling-url: http://docling-svc:8081
  mineru-url: http://mineru-svc:8082
  confidence-threshold: 0.80
  timeout-seconds: 30
```

### Switching Provider

Only `LLM_BASE_URL` and `LLM_MODEL` env vars need to change.
Spring AI `ChatClient` abstraction handles the rest.
Both DeepSeek and MiniMax support OpenAI-compatible `/v1/chat/completions`.

---

## Spring AI Prompt Templates

### invoice-extraction.st

```
You are a trade finance document parser.
Extract the following fields from this invoice text and return ONLY valid JSON.
No explanation, no markdown, no preamble.

Invoice text:
{invoiceMarkdown}

Return JSON with these exact keys:
invoice_number, invoice_date (YYYY-MM-DD), seller_name, seller_address,
buyer_name, buyer_address, goods_description, quantity, unit_price,
total_amount, currency, lc_reference, trade_terms,
port_of_loading, port_of_discharge, signed (true/false)

If a field is not found, use null. Never guess.
```

### goods-description-check.st

```
You are a UCP 600 compliance checker for trade finance.

LC goods description (from field 45A):
{lcGoodsDescription}

Invoice goods description:
{invoiceGoodsDescription}

Per UCP 600 Art. 18(c) and ISBP 821 para C3:
- Invoice description must not conflict with LC description
- Invoice may use broader terms but must not contradict
- Quantity must match unless tolerance applies

Return ONLY valid JSON:
{
  "compliant": true/false,
  "quantity_match": true/false,
  "reason": "brief explanation"
}
```

---

## Error Handling Summary

| Scenario | HTTP | Error Code |
|---|---|---|
| Not a PDF | 400 | INVALID_FILE_TYPE |
| MT700 mandatory field missing | 400 | LC_PARSE_ERROR |
| PDF unreadable / corrupted | 400 | PDF_UNREADABLE |
| Both extractors unavailable | 502 | EXTRACTOR_UNAVAILABLE |
| LLM API timeout | 200* | rule status = WARN + reason |
| Session not found for trace | 404 | SESSION_NOT_FOUND |

*LLM timeout on a single semantic check should not fail the entire request.
Mark that check as WARN with reason "LLM_TIMEOUT", continue other checks.

---

## Implementation Order

```
Step 1   Mt700Parser               + unit tests with sample MT700
Step 2   InputValidator            + unit tests for all error cases
Step 3   LcDocument model          fully typed, all fields
Step 4   Docling sidecar           FastAPI /extract endpoint
Step 5   MiniRU sidecar            FastAPI /extract endpoint (same contract)
Step 6   InvoiceExtractor + Router HTTP client + fallback logic
Step 7   InvoiceDocument model     fully typed
Step 8   RuleChecker interface     + CheckResult model
Step 9   AmountToleranceChecker    DETERMINISTIC — highest priority
Step 10  LcNumberReferenceChecker  DETERMINISTIC — highest priority
Step 11  GoodsDescriptionChecker   SEMANTIC — LLM prompt
Step 12  Remaining 11 checkers     in priority order
Step 13  ComplianceEngine          wires all checkers
Step 14  CheckSession + Store      in-memory ConcurrentHashMap
Step 15  LcCheckController         POST /lc-check
Step 16  Trace endpoint            GET /{sessionId}/trace
Step 17  DiscrepancyReport output  matches required JSON format exactly
Step 18  Integration test          full curl flow with sample files
```

---

## Test Files Required

Prepare before coding:

```
test/
  sample_mt700.txt         Complete MT700 with :20: :32B: :39A: :45A: :46A: :50: :59:
  sample_invoice.pdf       Matching invoice with intentional discrepancies:
                             - Amount exceeds tolerance
                             - Quantity mismatch
                             - LC number not referenced
  check.sh                 curl script for end-to-end demo
```

Search "sample LC MT700 swift plain text" and "sample beneficiary invoice pdf LC"
for realistic test data.

---

## Constraints Checklist

```
✓ MT700 parsed programmatically via regex — no hardcoded field values
✓ Invoice extracted from PDF bytes — no pre-parsed JSON input accepted
✓ Every discrepancy references UCP 600 article or ISBP 821 paragraph
✓ Graceful error handling at every stage with structured error response
✓ Modular RuleChecker interface — new rules added without touching engine
✓ /trace endpoint exposes all intermediate results per stage
✓ Java + Spring AI only
✓ No UI required — REST API + curl
✓ docker-compose up starts all services
```
