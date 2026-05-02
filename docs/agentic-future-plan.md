# Agentic LC Pipeline — Parked Future Plan (Reference)

> **状态**：STAGED / PARKED — 此方案在 2026-05-01 评审中被决定不立即实施。当前主线改为「在现有 v1 规则版基础上渐进升级」。本文档保留全部研究、决策与设计细节，供未来重启该方案时直接复用。
>
> **关联文档**：
> - 研究引用清单：[`agentic-research-references.md`](./agentic-research-references.md)
> - 当前活跃 plan：`/Users/pc-rn/.claude/plans/current-implementation-is-soft-penguin.md`（渐进升级 v1，新版本）
>
> **重启触发条件**（建议）：
> 1. v1 渐进升级遇到三类固有天花板（自由文本条款解析、多文档 cross-check、UCP/ISBP 知识更新效率）
> 2. 业务侧明确需要替换式重构而非渐进改良
> 3. 团队具备 cloud long-context 模型（DeepSeek 64K / Qwen-Plus 128K）的稳定预算

---

## 一、整体定位（决策已锁定）

**目标**：在新独立服务中构建 production-ready 的 agentic LC 检查系统，不依赖手写规则字典，从 UCP 600 / ISBP 821 / 银行合规文档自主推导检查计划。

**不是**：规则版的对照实验、原型 demo、规则版的增强补丁。

**最终态**：agent 版稳定后，规则版（`lc-checker-svc`）从生产下线、模块从 repo 删除。

**架构选型依据**：2026 年合规场景基准（arXiv 2511.14136）显示 Plan-and-Execute 准确率 72%、PAS=0.93，显著优于 ReAct（0.87）。

---

## 二、Pipeline 链路（agent 服务内部）

```
[1] LcParseStage              MT700 → LcDocument (确定性 SWIFT tag 解析，保留)
[2] DocumentIngestStage  ★    多文档 PDF → Document[] (raw markdown + 元数据)
[3] KnowledgeRetrievalStage ★ LC 关键字段 → RAG 查 UCP/ISBP/合规库 → KnowledgeContext
[4] PlannerStage         ★    LC + Documents + Knowledge → CheckPlan (LLM 1 次)
                              (可选门: PLAN_REVIEW_GATE=true 时挂起等审批)
[5] ExecutorStage        ★    For each PlanItem: 1 次带工具的 LLM 调用 → AgenticFinding
[6] ReviewerStage        ★    Plan + Findings → ReviewedFindings (引用准确性/漏检/冲突)
[7] ReportAssemblyStage  ★    AgenticReport (新结构，多文档证据 + 引用条款全文)

(★ = 全新或大幅改造)
```

### 三角色 agent 职责

| Agent | 调用次数 | 职责 |
|---|---|---|
| **Planner** | 1 / session | 读 LC + 文档摘要 + 检索的 UCP/ISBP，产出 CheckPlan（agent 自决检查项） |
| **Executor** | 1 / PlanItem | 对单个 plan item 调用工具验证，产出单条 AgenticFinding |
| **Reviewer** | 1 / session | 二次校验引用准确性、漏检、冲突 |

---

## 三、服务隔离策略

### 物理隔离
```
仓库:
  lc-cheker-solution/
  ├── lc-checker-svc/          ← 现有规则版（main 零变更）
  ├── lc-check-agent-svc/      ← 新模块（feature/agentic 分支开发）
  └── settings.gradle.kts      ← include("lc-check-agent-svc")

工作流:
  ~/ws/ai-lab/lc-cheker-solution/           main 分支 worktree
  ~/ws/ai-lab/lc-cheker-solution-agentic/   feature/agentic 分支 worktree
  (git worktree add 共享 .git)
```

### 资源共享 / 隔离

| 资源 | 策略 |
|---|---|
| Postgres | 共享实例，独立 schema：`lc_check.*`（旧）/ `lc_agent.*`（新） |
| MinIO | 共享 bucket，SHA-256 内容寻址天然去重 |
| Job queue | 各自独立表 |
| Langfuse | 共享实例，`service.name` 区分 trace |
| Vision-LLM endpoint | 共享配置 |
| API gateway (Traefik) | 路径分流：`/api/v1/lc-check/*` 旧、`/api/v2/agent/*` 新 |
| 前端 | 同一 React 应用，`/agent/*` 新路由 |

---

## 四、API 规划

### Path 命名空间
- 旧：`/api/v1/lc-check/*`（保留不动）
- 新：`/api/v2/agent/*`（全新）

### 新端点清单
```
POST   /api/v2/agent/files/upload                  → fileKey
POST   /api/v2/agent/check/start                   多文档输入
GET    /api/v2/agent/check/{id}/stream             SSE (细粒度事件)
GET    /api/v2/agent/check/{id}/plan
POST   /api/v2/agent/check/{id}/plan/approve       审批门
POST   /api/v2/agent/check/{id}/plan/reject
GET    /api/v2/agent/check/{id}/report             AgenticReport
GET    /api/v2/agent/check/{id}/findings/{fid}     单条 finding 详情
GET    /api/v2/agent/check/{id}/documents/{did}    单文档 raw markdown
GET    /api/v2/agent/knowledge/search?q=...        UCP/ISBP 搜索（可选）
```

### SSE 事件粒度

```typescript
type AgentEvent =
  | { type: "stage.start", stage: "knowledge_retrieval" | "planner" | "executor" | "reviewer" }
  | { type: "stage.end",   stage: ..., durationMs: number }
  | { type: "knowledge.retrieved", chunks: [{ source, article, paragraph, snippet }] }
  | { type: "planner.plan_generated", plan: { items: PlanItem[] } }
  | { type: "plan.awaiting_approval" }
  | { type: "executor.item.start", itemId, what, ucpRef }
  | { type: "executor.tool_call",  itemId, tool, args, result, latencyMs }
  | { type: "executor.item.finished", itemId, finding: AgenticFinding }
  | { type: "reviewer.note", category, text, refersTo: itemId? }
  | { type: "done", reportId }
  | { type: "error", stage, message }
```

### Report 结构

```typescript
interface AgenticReport {
  sessionId: string
  compliant: boolean
  inputs: {
    lc: { mt700Raw, parsedFields },
    documents: [{ id, type, pages, extractor, confidence }]
  }
  plan: {
    items: PlanItem[],
    plannerModel: string,
    plannerLatencyMs: number,
    approvedBy?: string
  }
  knowledgeChunksUsed: [{ source, article, snippet, retrievedFor: itemId }]
  findings: Array<{
    id, planItemId,
    verdict: "PASS" | "FAIL" | "DOUBTS" | "NOT_REQUIRED",
    severity?: "MAJOR" | "MINOR",
    ucpRef: { article, paragraph, clauseText },
    isbpRef?: { section, paragraph, clauseText },
    evidence: Array<{ docId, docType, field, value, sourceLocation }>,
    toolCallTrace: Array<{ tool, args, result, latencyMs, ts }>,
    reasoning: string,
    reviewerNote?: string,
  }>
  reviewerSummary: {
    citationAccuracy: "OK" | "ISSUES_FOUND",
    coverage: "COMPLETE" | "GAPS_FOUND",
    additionalFindings: AgenticFinding[]
  }
}
```

---

## 五、UI 规划

### 路由
```
现有:           /lc-check                    (规则版，零变更)
新增:           /agent/check                 (agentic 上传 + 进度)
                /agent/check/{id}            (实时进度 + 流式)
                /agent/check/{id}/plan       (plan 审批)
                /agent/check/{id}/report     (最终报告)
                /agent/check/{id}/docs/{did} (文档检视)
                /agent/sessions              (新历史列表)
                /agent/knowledge             (UCP/ISBP 浏览，可选)
```

### 新增组件
- `<PlanTimelineView>` — 横向时间线展示 plan 生成 + 执行
- `<FindingCard>` — finding 详情卡片，含 ucp citation hover、tool trace expand
- `<ToolCallTrace>` — tool 调用链路可视化
- `<UcpCitation>` — UCP 引用，hover 显示 clause 全文
- `<DocumentInspector>` — markdown 预览 + 元数据
- `<DocumentTypeSelector>` — type 下拉
- `<MultiFileUploader>` — 多文件上传

### 共享 design system 不重做
- 颜色 / 字体 / spacing token、Button / Card / Modal / Drawer / Toast、SSE hook、表格 / 分页

---

## 六、数据模型

### 多文档输入
```
PresentationBundle {
  lcDocument: LcDocument                  // 已有，结构化 tag 解析
  documents: List<Document>               // 多文档集
}

Document {
  id: UUID
  type: DocumentType                      // INVOICE / BILL_OF_LADING /
                                          //   PACKING_LIST / CERT_ORIGIN /
                                          //   INSURANCE / INSPECTION / OTHER
  sourceFile: String                      // MinIO key
  rawMarkdown: String                     // vision-LLM 提取的 markdown
  pages: int
  extractionMeta: { extractor, confidence, latencyMs }
  envelope: Map<String, Object>           // 可选 hint 字段，agent 不依赖
}
```

### LC parser 与 field-pool 决策
- MT700 结构化 tag (`:20:` `:32B:` 等)：保留 deterministic parser
- MT700 自由文本 (`:47A:` `:46A:` `:45A:` `:78:`)：不预解析，由 Planner 语义拆解
- Invoice / 其他文档：不预定义 schema，vision-LLM 仅产出 raw markdown
- `lc-tag-mapping.yaml`：保留（SWIFT tag → canonical name 常量映射）
- `field-pool.yaml` LC 部分：保留
- `field-pool.yaml` invoice 部分：删除
- `InvoiceDocument` record：改造为 `DocumentEnvelope`（去 18 typed 字段）

---

## 七、四模块做法 + 「只是 demo」质疑回应

### 1. Plan（规划层）
**做法**：Planner agent 单次 LLM 调用产出 `CheckPlan = List<PlanItem>`，结构化 JSON schema 受约束、写库可审、可选人工审批门。

**回应**：非 free-form CoT；非 ReAct 边想边做；持久化 + 可重放 + 可审；temperature=0 + Langfuse 监控。

**胜规则版**：47A 自由文本条款规则版不解析；新行业 LC 无需新规则代码。

### 2. Execute（执行层）
**做法**：每个 PlanItem 单独一次 LLM 调用，带受限工具集（取值类、确定性验证类、知识类、输出类）。每条 finding 必含 `ucp_ref` + `evidence` + 完整 `tool_call_trace`。

**回应**：算术/容差/比对走 Java 工具零幻觉；条款引用从 RAG 取真实文本；单步 token 可控；Langfuse 全程可观测。

**胜规则版**：跨文档检查（Art. 14(d)）规则版难写；Invoice 字段不可预测时按需读 raw markdown。

### 3. Review（审查层）
**做法**：Reviewer agent 一次 LLM 调用，三类校验（citation 准确性、漏检、冲突），可新增 follow-up findings。

**回应**：解决「单 agent 自圆其说」根本风险；非简单 rerun；可关闭做基准对比。

**胜规则版**：规则版无此层，结果即终态无 fail-safe。

### 4. RAG（知识层）
**做法**：UCP 600 + ISBP 821 + 银行合规分块入 pgvector，~500 tokens / chunk 按 article/paragraph 切。Stage 3 KnowledgeRetrieval 注入 Planner；Executor `lookup_ucp_clause` 同 RAG。Embedding 复用 OpenAI 兼容 endpoint。

**回应**：知识来自真文本杜绝幻觉；索引一次复用稳定；银行内部策略可独立入库；可灰度（top_k、reranker）。

**胜规则版**：规则版知识只在 18 个 prompt 里二次蒸馏，新条款手写新 prompt；RAG 版只更新 markdown 重 ingest。

---

## 八、为何不是 demo（系统层论据）

| 维度 | Demo 通病 | 我们的设计 |
|---|---|---|
| 输出稳定性 | 每次不一样 | temperature=0 + 结构化 JSON schema + 审批门 |
| 可解释性 | 黑盒 | plan + tool_call_trace + UCP 原文引用全部持久化 |
| 可审计 | 无 | Langfuse 完整 trace + DB 快照 + plan 审批记录 |
| 错误兜底 | 无 | Reviewer 二次校验 + 工具确定性计算 + 人工 final review |
| 知识更新 | 改 prompt | 更新 markdown + reingest |
| 多文档 | 单文档 demo | 多 Document + cross-document 工具 |
| 部署形态 | notebook | 独立 Spring Boot 服务 + 独立 schema + 独立队列 + Traefik 路由 + 灰度切流 |
| 退出策略 | 无 | 独立模块，不稳定可一键回 v1 |

---

## 九、相比规则版的核心优势场景

1. **47A 自由文本条款**：规则版不解析，agent 自动拆
2. **多文档 cross-check**：规则版要为每组合写 SpEL，agent 通用工具
3. **Invoice 字段超 schema**：规则版丢失，agent 读 raw markdown
4. **新行业 / 新币种 LC**：规则版加规则，agent 即时适应
5. **银行内部合规策略**：规则版加 prompt，agent 加 markdown reingest

---

## 十、坦诚的弱项

1. **Token 成本**：单 session ~5-15 次 LLM 调用，单次更长
2. **首次部署延迟**：UCP/ISBP 首次 ingest 几分钟（一次性）
3. **上下文限制**：本地 8B 装不下 Planner 上下文，Planner / Reviewer 必须走 cloud
4. **Plan 漂移**：稳定性需基准 10× 跑同输入验证
5. **UCP/ISBP 版权**：需提供合法版本或银行已购版本

---

## 十一、迁移与退场（重启时参考）

```
T0:     上线 v2 (lc-check-agent-svc)，前端加 /agent 路由；v1 完全工作
T0+N 周: 默认前端首页改成 /agent；/lc-check 仍可访问做对照
T0+N+M: 真实生产流量切 v2；/lc-check 加 deprecation banner
T0+ 稳定: 删除 lc-checker-svc 模块 + /lc-check 路由 + v1 API
```

---

## 十二、Phase 实施分期（重启时直接套用）

```
Phase 1: 基础设施（pgvector + Knowledge ingest + 新工具集）
Phase 2: Agents（Planner + Executor + Reviewer）
Phase 3: 集成（新 stage + 新 LcCheckPipeline）
Phase 4: 知识（UCP 600 / ISBP 821 / bank-compliance 文档放入 resources/knowledge/）
Phase 5: 审批门（可选 plan review gate REST 接口）
Phase 6: UI（Agentic Plan tab 等新屏幕）
Phase 7: 测试（4 个 agent 单测 + 1 个 E2E 集成测）
```
