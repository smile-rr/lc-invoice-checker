# LC Invoice Checker — Solution Document



> **System URL** : https://lccheck.moments-plus.com/.    Passcode shared in mail seperately

## Table of Contents

- [Major Considerations](#major-considerations)

- [Workflow](#workflow)

- [Deployment Architecture](#deployment-architecture)

- [Project Structure](#project-structure)

- [Config-Driven Design](#config-driven-design)

- [Performance and Optimization](#performance-and-optimization)

- [Screenshots & Live Demo](#screenshots--live-demo)

  


## Major Considerations

### **Output Accuracy**

Accuracy is the most important consideration. Any missed discrepancy means immediate financial loss — LC funds go to the beneficiary and cannot be recovered.

Hence each stage of **LLM agent output must be right.** Human review remains the final gate as LLM didn't reach that standard yet.

### **Compliance and Regulatory**

Banks are a highly regulated industry. Data has country boundaries. AI model selection and training datasets are also regulated.

So according to business scope and covered markets, model selection, deployment datacenter and training dataset **must consider the regulation law**s **in each market** as baseline. And follow the bank's own compliance rules as the higher standard.

### **Workload Decoupling**

Banks have 5 days to check LC and invoice. But document submission can happen anytime, in any volume. Also the LLM inference engine has capacity limits processing parallel requests

So **decouple submission from processing is needed.** This lets the system scale freely without concurrency failures.

### **Observation Capability**

The vision and language models are black boxes to us. We need to see what goes in and what comes out. That visibility is how we close the accuracy gap. Frameworks like SpringAI, LangChain are convenient - but they abstract away the details.

That's why we need to **inspect the raw LLM API request and response** for judging and tuning LLM Performance.



## Workflow

### 1. Task Submission

**API:** `POST /api/v1/lc-check/start` — submit LC text + invoice PDF, get sessionId immediately

```mermaid
sequenceDiagram
    participant C as Client
    participant API as lc-check-svc
    participant S3 as S3 (MinIO)
    participant DB as PostgreSQL

    C->>API: submit
    API->>API: validate input
    API->>S3: store LC + invoice files
    S3-->>API: keys
    API->>DB: INSERT session (QUEUED)
    DB-->>API: sessionId
    API-->>C: 200 { sessionId, status:"QUEUED" }
```

### 2. LC Check Pipeline

**API:** async scheduler — polls queue, runs compliance checks, saves report

```mermaid
sequenceDiagram
    participant SCH as Scheduler
    participant DB as PostgreSQL
    participant S3 as S3 (MinIO)
    participant OL as Vision LLM
    participant CE as ComplianceEngine
    participant LLM as Text LLM

    SCH->>SCH: poll/schedule
    SCH->>DB: poll QUEUED sessions
    SCH->>DB: mark PROCESSING
    SCH->>S3: fetch LC + invoice files
    S3-->>SCH: files

    SCH->>SCH: parse LC text
    SCH->>OL: extract invoice fields

    SCH->>CE: run compliance rules

    CE->>CE: Tier 1 — PROGRAMMATIC (SpEL, no LLM)

    CE->>LLM: Tier 2 — AGENT rules (1 call per rule)
    LLM-->>CE: { compliant, reason }

    CE->>LLM: Tier 3 — AGENT + TOOL
    LLM-->>CE: result

    CE-->>SCH: List<CheckResult>
    SCH->>DB: save report + DONE
```

### 3. Progress Inquiry (SSE)

**API:** `GET /api/v1/lc-check/{sessionId}/stream` — live SSE stream of status and result

```mermaid
sequenceDiagram
    participant C as Client
    participant API as lc-check-svc
    participant DB as PostgreSQL

    C->>API: connect
    API->>API: read Cache (miss → PostgreSQL)
    API-->>C: SSE: QUEUED
    API->>API: read Cache (miss → PostgreSQL)
    API-->>C: SSE: status update
    API-->>C: SSE: DONE + report
```



## Deployment Architecture

![Deployment Architecture](docs/diagrams/deploy-architecture.png)



## Project Structure

```text
lc-cheker-solution/
├── extractors/                     # Python microservices (docling + mineru PDF extractors)
├── infra/                          # docker-compose, postgres schema, scripts
├── lc-checker-svc/									# SpringBoot microservice
│   └── src/main/java/com/lc/checker/
│       ├── api/                    # Controllers + error handling
│       ├── domain/                 # Domain models (LC doc, invoice, rules, results, sessions)
│       ├── infra/                  # Config, persistence, queue, storage, observability
│       ├── pipeline/               # LC Check Pipeline (stages, flow, context)
│       ├── stage/                  # Stage implementations (parse, extract, check, assemble)
│       └── tools/                  # LLM agent @Tool definitions
│   └── src/main/resources/
│       ├── fields/                 # LC, Invoice Fields registries (field-pool, lc-tag-mapping)
│       ├── prompts/                # LLM prompt templates (extract + check)
│       └── rules/                  # LC Check Rule catalog YAML
├── ui/                             # React frontend
└── README.md
```



## Config-Driven Design

### **LC MT700 Parsing** 

We use the 2 files as gold source fields for validation and parse fields from MT700 txt for LC fields. any new fields definition can add there in the config.

- [`field-pool.yaml`](lc-checker-svc/src/main/resources/fields/field-pool.yaml): for definition  of LC fields and description.
- [`lc-tag-mapping.yaml`](lc-checker-svc/src/main/resources/fields/lc-tag-mapping.yaml): for mapping between MT700 tag to field-pool defination.

### **Invoice Extraction** 

We extract all invoice fields from fields-pool definition, inject all required fields and description into the vision extract prompt.

- [`field-pool.yaml`](lc-checker-svc/src/main/resources/fields/field-pool.yaml) : along with lc tag fields , invoice field share the same key in field-pool.yml as golden source.

- [`invoice-extract-vision.st`](lc-checker-svc/src/main/resources/prompts/extract/invoice-extract-vision.st): prompt template for vission llm extract invoice fields.  optimize needed for more accuracy extraction besides the model fine tuning part.  optimize this one first before model fine tuning.

### **Compliance Rules**

Rules are defined in [`catalog.yml`](lc-checker-svc/src/main/resources/rules/catalog.yml), driven by UCP 600 and ISBP 821. See [`ucp600_isbp821_invoice_rules.md`](docs/refer-doc/ucp600_isbp821_invoice_rules.md) — **Quick Cross-Reference Matrix** for full rule reference.

- [`catalog.yml`](lc-checker-svc/src/main/resources/rules/catalog.yml): defines rules at field level. Grouped by business phase: Parties, Money, Goods, Logistics, Procedural
  - **PROGRAMMATIC**: 4 rules — straightforward logic via SpEL, no LLM
  - **AGENT**: 12 rules — prompt-driven checks via [`prompts/check/`](lc-checker-svc/src/main/resources/prompts/check/) templates
  - **AGENT+TOOLS**: 2 rules — LLM calls Java tool for computation. [`prompts/check/`](lc-checker-svc/src/main/resources/prompts/check/) templates



## Performance and Optimization

### Overall performance

- Overall: 2 mins

- LC parse: < 1 seconds

- Invoice extract:
  - 30~40s: local qwen3-vl:4b mac book pro m1    
  - 10s: qwen-plus (Aliyun) as reference benchmark
- LC rule check: 3~4s by qwen3-vl:4b on  my local.    better performance via Text Model and GPU based server.

### Vision Model for Invoice Extraction

* Bigger size model like qwen3-vl: 7b or larger will result a better accuracy

* Prompt optimization further can improve the extract quality
* LoRA, QLoRA fine tuning can improve the quality. 
* Cross reference. Cross reference by different OCR source can ensure 

### Text Model for Rule Check

* Bigger Size model and GPU hosted server can result better performance

* Specific Rule Prompt template optimization will result accurate rule result and reason.

  pre-condition is OCR Extraction fields accuracy. 

* Fine tuning of the text model driven by UCP, ISBP rules and data

### Cost Estimation based on LLM Providers Price

**Pipeline Total Tokens:**  ~40,000 input / ~3,500 output   

Local deployment may be required for compliance, but total infrastructure cost can exceed cloud-hosted pricing. 

Market LLM pricing serves as a useful benchmark.

| Model             | Total Cost | $/1M input · output |
| ----------------- | ---------- | ------------------- |
| GPT-5.5           | $0.305     | $5 · $30            |
| Claude Opus 4.7   | $0.288     | $5 · $25            |
| Claude Sonnet 4.6 | $0.173     | $3 · $15            |
| Gemini 3.1 Pro    | $0.122     | $2 · $12            |
| GPT-4.1           | $0.108     | $2 · $8             |
| Gemini 2.5 Pro    | $0.085     | $1.25 · $10         |
| Claude Haiku 4.5  | $0.058     | $1 · $5             |
| Qwen3-Max         | $0.045     | $0.78 · $3.90       |
| Qwen3.5 Plus      | $0.024     | $0.40 · $2.40       |
| Gemini 2.5 Flash  | $0.021     | $0.30 · $2.50       |



---

## Screenshots & Live Demo

**Live Demo:** [https://lccheck.moments-plus.com](https://lccheck.moments-plus.com)

### 00 — Landing Page

![Landing Page](docs/screenshots/00.landing-page.jpg)

### 01 — LC MT700 Parser

![LC MT700 Parser](docs/screenshots/01.landing-page-preview.jpg)

### 02 — LC Parser Result

![LC Parser Result](docs/screenshots/02.lc-parser.jpg)

### 03 — Invoice PDF Extraction

![Invoice Extraction](docs/screenshots/03.invoice-extract.jpg)

### 04 — Compliance Check

Compliance engine runs all rules against extracted LC and invoice fields.

- **Passed:** all rules satisfied

![Compliance Check — Passed](docs/screenshots/04-1.compliance-check-passed.jpg)

- **Failed:** one or more discrepancies detected

![Compliance Check — Failed](docs/screenshots/04-2.compliance-check-failed.jpg)

### 05 — Final Review & Report

Human reviewer makes the final decision based on the compliance report.

- **Passed:** compliant with discrepancies resolved

![Final Review — Passed](docs/screenshots/05-1.final-review-passed.jpg)

- **Failed:** non-compliant; discrepancy report issued

![Final Review — Failed](docs/screenshots/05-2.final-review-failed.jpg)

### Observation — Langfuse Tracing

Monitor raw LLM requests and responses via Langfuse to debug and tune model performance across all pipeline stages.

![Langfuse Tracing](docs/screenshots/langfuse-tracing.jpg) 

