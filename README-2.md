# LC Invoice Checker — Solution Document

---

## Major Considerations

#### **Output Accuracy**

Accuracy is the most important consideration. Any missed issue means immediate financial loss — LC funds go to the beneficiary and cannot be recovered.

Hence each stage of **LLM agent output must be right.** Human review remains the final gate as LLM didn't reach that stage yet.

#### **Workload Decoupling**

Banks have 5 days to check LC and invoice. But document submission can happen anytime, in any volume. Also the LLM inference engine have capacity and limits processing paralel request.

So **decouple submission from processing is needed.** This lets the system scale freely without concurrency failures.

#### **Observation Capability**

The vision and language models are black boxes to us. We need to see what goes in and what comes out. That visibility is how we close the accuracy gap. Frameworks like SpringAI, LangChain are convenient — but they hide the details.

We still need to **inspect the raw LLM API request and response** for judging and tuning LLM Performance.



## Workflow

#### 1. Task Submission

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

#### 2. LC Check Pipeline

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

#### 3. Progress and Status Inquiry (SSE)

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

## Key Step as Configuration

LC mt700 text parsing:

Invoice Fields Extract

UCP600, ISBP821 Rule Check

## Solution Architecture

## Performance and Optimization

1. Overall performance
2. Vision Model for Invoice Extration
3. Text Model for Rule Check
4. Cost
