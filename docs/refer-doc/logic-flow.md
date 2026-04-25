```text
╔══════════════════════════════════════════════════════════════════════════════════╗
║                    LC DOCUMENT CHECKING SYSTEM — FULL LOGIC                    ║
║                    Source of Truth: UCP 600 + ISBP 821                         ║
╚══════════════════════════════════════════════════════════════════════════════════╝


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 STAGE 0 ║ INPUT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  ┌─────────────────────┐        ┌─────────────────────┐
  │   MT700 SWIFT Text  │        │  Invoice (PDF/Scan)  │
  │                     │        │                      │
  │ :20:  LC2024-000123 │        │  OCR → raw text      │
  │ :32B: USD50000.00   │        │  NLP → field extract │
  │ :45A: 1000 UNITS... │        │                      │
  │ :46A: SIGNED INV... │        └──────────┬───────────┘
  │ :47A: ALL DOCS IN.. │                   │
  └──────────┬──────────┘                   │
             │                              │
             ▼                              ▼


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 STAGE 1 ║ PARSING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  ┌────────────────────────────────────────────────────────────┐
  │ MT700 PARSER                                               │
  │                                                            │
  │  Part A：结构化 tag（直接 split，零 LLM）                   │
  │  ┌──────────────────────────────────────────────────────┐  │
  │  │ :20:  → lc_number                                    │  │
  │  │ :31C: → issue_date                                   │  │
  │  │ :31D: → expiry_date + expiry_place                   │  │
  │  │ :32B: → currency + amount                            │  │
  │  │ :39A: → tolerance (e.g. 5/5 → ±5%)                  │  │
  │  │ :39B: → max_amount_flag                              │  │
  │  │ :43P: → partial_shipment (ALLOWED/NOT ALLOWED)       │  │
  │  │ :43T: → transhipment    (ALLOWED/NOT ALLOWED)        │  │
  │  │ :44C: → latest_shipment_date                         │  │
  │  │ :44D: → shipment_period                              │  │
  │  │ :44E: → port_of_loading                              │  │
  │  │ :44F: → port_of_discharge                            │  │
  │  │ :44A: → place_of_receipt                             │  │
  │  │ :44B: → place_of_delivery                            │  │
  │  │ :48:  → presentation_period (days)                   │  │
  │  │ :40E: → applicable_rules (UCP LATEST VERSION)        │  │
  │  │ :50:  → applicant (name + address)                   │  │
  │  │ :59:  → beneficiary (name + address)                 │  │
  │  └──────────────────────────────────────────────────────┘  │
  │                                                            │
  │  Part B：自由文本 tag（NLP/LLM 解析）                       │
  │  ┌──────────────────────────────────────────────────────┐  │
  │  │                                                      │  │
  │  │  :45A: ──► LLM 提取结构化属性 + 保留原文              │  │
  │  │             {goods_name, quantity, unit,              │  │
  │  │              unit_price, incoterms, origin,           │  │
  │  │              packing, weight, ...}                    │  │
  │  │             + field_45A_raw (保留完整原文)             │  │
  │  │                                                      │  │
  │  │  :46A: ──► LLM 解析单据清单                           │  │
  │  │             [{doc_type: "COMMERCIAL INVOICE",         │  │
  │  │               copies: 3,                             │  │
  │  │               signed: true,                          │  │
  │  │               special_requirements: [...]}]          │  │
  │  │                                                      │  │
  │  │  :47A: ──► 两步解析（重要：只结构化，不做 check）       │  │
  │  │    Step 1: LLM 拆成独立 condition list                │  │
  │  │             [{id: "47A-1",                           │  │
  │  │               type: REQUIREMENT/RESTRICTION/         │  │
  │  │                     RELAXATION,                      │  │
  │  │               target_doc: INVOICE/BL/ALL,            │  │
  │  │               text: "INVOICE MUST STATE...",         │  │
  │  │               checkable_field: "...",                │  │
  │  │               expected_value: "..."}]                │  │
  │  │    Step 2: 输出 conditions_47A list                  │  │
  │  │            供后续 Stage 3 逐条 check 使用             │  │
  │  └──────────────────────────────────────────────────────┘  │
  └────────────────────────────────────────────────────────────┘

  ┌────────────────────────────────────────────────────────────┐
  │ INVOICE PARSER (OCR + NLP)                                 │
  │                                                            │
  │  issuer_name, issuer_address                               │
  │  applicant_name, applicant_address                         │
  │  invoice_number, invoice_date, issuing_place               │
  │  lc_reference (if present)                                 │
  │  currency, total_amount, unit_price                        │
  │  goods_description_raw + goods_description_parsed          │
  │  quantity, unit                                            │
  │  trade_terms (incoterms)                                   │
  │  port_loading, port_discharge                              │
  │  country_of_origin (if present)                            │
  │  net_weight, gross_weight (if present)                     │
  │  marks_numbers (if present)                                │
  │  packing (if present)                                      │
  │  signature (present/absent)                                │
  │  corrections (present/absent + authenticated?)             │
  │  copies_count                                              │
  │  invoice_full_raw_text (保留完整原文)                       │
  └────────────────────────────────────────────────────────────┘

             │                              │
             └──────────────┬───────────────┘
                            │
                    LCContext + InvoiceObject
                            │
                            ▼


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 STAGE 2 ║ RULE ACTIVATION（规则激活）
         ║ 以 Rule Catalog 为驱动，不是以 LC 为驱动
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  遍历 Rule Catalog 35条，每条判断激活状态：

  ┌─────────────────────────────────────────────────────────┐
  │                                                         │
  │  trigger = UNCONDITIONAL?                               │
  │  ──────────────────────────                             │
  │  YES → 直接激活                                          │
  │        （UCP 底线，不管 LC 写没写）                       │
  │        例：INV-001 beneficiary name                     │
  │            INV-010 currency code                        │
  │            INV-015 goods description                    │
  │                                                         │
  │  trigger = LC_STIPULATED?                               │
  │  ──────────────────────────                             │
  │  检查 LC 对应字段是否存在该要求：                          │
  │                                                         │
  │  INV-007 LC reference：                                 │
  │    46A 含 "LC NUMBER" / "CREDIT NUMBER"？               │
  │    YES → 激活     NO → NOT_APPLICABLE                   │
  │                                                         │
  │  INV-008 Signature：                                    │
  │    46A 含 "SIGNED" 修饰 invoice？                       │
  │    YES → 激活     NO → NOT_APPLICABLE                   │
  │                                                         │
  │  INV-019 Country of Origin：                            │
  │    47A conditions 含 origin 要求？                       │
  │    YES → 激活     NO → NOT_APPLICABLE                   │
  │                                                         │
  │  INV-023/024 Port of Loading/Discharge：                │
  │    44E / 44F 字段存在？                                  │
  │    YES → 激活（CROSS_FIELD）                             │
  │    NO  → UNABLE_TO_VERIFY                               │
  │                                                         │
  │  trigger = 47A_DYNAMIC?                                 │
  │  ──────────────────────────                             │
  │  每条 conditions_47A 中 target=INVOICE 的条目            │
  │  → 生成对应的动态 check node                             │
  │  → 追加到激活规则列表                                     │
  │                                                         │
  └─────────────────────────────────────────────────────────┘

  输出：ActiveRuleList（每条含激活状态 + 参数）
                            │
                            ▼


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 STAGE 3 ║ CHECK EXECUTION（Layer 1 + Layer 2）
         ║ 对每条激活规则执行前置检查 + 实际检查
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  ┌─────────────────────────────────────────────────────────┐
  │ 前置检查（每条规则执行前）                                  │
  │                                                         │
  │  Q1：LC 侧字段存在？（CROSS_FIELD 类规则需要）             │
  │      NO  → 输出 UNABLE_TO_VERIFY，跳过此规则             │
  │      YES → 继续                                         │
  │                                                         │
  │  Q2：Invoice 侧字段存在？                                 │
  │      NO + missing_invoice_action = DISCREPANT           │
  │          → 直接输出 DISCREPANT MAJOR，不需要执行 check   │
  │      NO + missing_invoice_action = NOT_APPLICABLE       │
  │          → 输出 NOT_APPLICABLE，跳过                     │
  │      YES → 继续执行实际 check                            │
  └─────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────┐
  │ TYPE A — 纯代码（无 LLM）                                 │
  │                                                         │
  │  INV-010 currency：        lc.currency == inv.currency  │
  │  INV-011 amount limit：    inv.total <= lc.amount       │
  │                            × (1 + tolerance)            │
  │  INV-012 tolerance：       parse 39A → 计算上下限        │
  │  INV-014 arithmetic：      qty × unit_price == total    │
  │  INV-025 partial ship：    43P=NOT ALLOWED              │
  │                            + inv.qty < lc.qty → DISC    │
  │  INV-026 invoice date：    inv.date <= presentation_date│
  │  INV-027 21-day rule：     presentation - bl_date <= 48 │
  │  INV-028 expiry：          presentation <= 31D          │
  │  INV-029 latest shipment： bl_date <= 44C               │
  │  INV-005 invoice number：  not null, not empty          │
  │  INV-009 issuing place：   not null, not empty          │
  │  INV-030 copies count：    parse 46A count == actual    │
  │  INV-032 applicable rules：40E contains UCP reference   │
  │  INV-035 date format：     all dates unambiguous        │
  └─────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────┐
  │ TYPE B — LLM（语义判断）                                  │
  │                                                         │
  │  Prompt 结构（每条规则）：                                 │
  │  ┌───────────────────────────────────────────────────┐  │
  │  │ [Rule]    规则描述 + UCP/ISBP 条文引用             │  │
  │  │ [LC]      相关 LC 字段原文                         │  │
  │  │ [LC Parsed] 结构化提取结果（作为语义锚点）           │  │
  │  │ [Invoice] 相关 Invoice 字段原文                    │  │
  │  │ [Already Verified] Type A 已确认项，勿重复检查      │  │
  │  │ [Task]    只检查语义对应性，返回 JSON               │  │
  │  └───────────────────────────────────────────────────┘  │
  │                                                         │
  │  INV-001 beneficiary name：                             │
  │    LC Field 59 vs invoice issuer                        │
  │    缩写/大小写容差 per ISBP A14                          │
  │                                                         │
  │  INV-002 applicant name：                               │
  │    LC Field 50 vs invoice addressee                     │
  │                                                         │
  │  INV-003/004 address country：                          │
  │    LC 59/50 国家 vs invoice 地址国家                     │
  │    细节可不同，国家必须一致 per Art 14(e)                 │
  │                                                         │
  │  INV-015 goods description：                            │
  │    45A 原文 + 45A 提取结构                               │
  │    vs invoice goods_description_raw                     │
  │    "correspond, may be more detailed, must not conflict"│
  │                                                         │
  │  INV-008 signature：                                    │
  │    invoice 有无签字/印章/认证痕迹                         │
  │                                                         │
  │  INV-019 country of origin：                            │
  │    47A 要求的国家 vs invoice 所注明的国家                  │
  │                                                         │
  │  INV-031 47A conditions（逐条）：                        │
  │    每条 condition 独立一个 LLM call                      │
  │    上下文小且精准，不混在一起                              │
  │    例：                                                 │
  │    condition_1: "ALL DOCUMENTS IN ENGLISH"              │
  │      → invoice 语言是英文吗？                            │
  │    condition_2: "INVOICE MUST STATE CONTRACT NO."       │
  │      → invoice 有没有 contract number 字段？             │
  │                                                         │
  │  INV-033 no intra-doc conflict：                        │
  │    invoice 内部各字段是否互相一致                          │
  │    （header 货物描述 vs line items vs 总额）               │
  │                                                         │
  │  INV-034 corrections authenticated：                    │
  │    有无涂改？涂改是否有认证？                               │
  └─────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────┐
  │ TYPE A+B — 混合（代码提取值，LLM 验意义）                  │
  │                                                         │
  │  INV-013 unit price：                                   │
  │    A: 数值提取 + 精确比较                                 │
  │    若数值不符 → 直接 DISCREPANT，不再问 LLM               │
  │    若数值符合 → B: LLM 确认货币单位和计量单位语义等价        │
  │                                                         │
  │  INV-016 quantity：                                     │
  │    A: 数值提取 + Art 30 tolerance 计算                   │
  │    B: LLM 确认 unit 语义等价（PCS ≈ PIECES ≈ PC）        │
  │                                                         │
  │  INV-017 unit of measure：                              │
  │    A: 提取 unit string                                  │
  │    B: LLM 判断语义等价 or 实质不同（KG vs SETS）          │
  │                                                         │
  │  INV-018 Incoterms：                                    │
  │    A: 提取 Incoterm 缩写 (CIF/FOB/EXW...)               │
  │    若缩写不符 → 直接 DISCREPANT MAJOR                    │
  │    B: LLM 确认 named place 一致                          │
  │                                                         │
  │  INV-023/024 ports：                                    │
  │    A: 提取港口名称字符串                                   │
  │    B: LLM 确认等价（CNSHA ≈ Shanghai，SGSIN ≈ Singapore）│
  └─────────────────────────────────────────────────────────┘

  每条 check 输出：
  {
    rule_id, check_type,
    status: PASS | DISCREPANT | UNABLE_TO_VERIFY | NOT_APPLICABLE,
    severity: MAJOR | MINOR | null,
    lc_value, invoice_value,
    ucp_ref, isbp_ref,
    description
  }
                            │
                    Layer 1+2 Results[]
                            │
                            ▼


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 STAGE 4 ║ HOLISTIC SWEEP（Layer 3）
         ║ UCP/ISBP 驱动兜底，非 LC 驱动
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  目的：捕捉 Catalog 35条覆盖不到的边缘规则和 edge case

  ┌─────────────────────────────────────────────────────────┐
  │ Pass 1：UCP/ISBP 底线扫描（不看 LC）                      │
  │                                                         │
  │  输入：Invoice 全文 + Layer 1/2 已检查项清单              │
  │                                                         │
  │  Prompt：                                               │
  │  "你是一个熟背 UCP 600 + ISBP 821 的审单员               │
  │   不看 LC，只看这张 invoice 本身                          │
  │   以下是 Layer 1/2 已经检查过的项目（不要重复）            │
  │   请找出这张 invoice 违反 UCP/ISBP 强制要求的地方         │
  │   重点关注：                                             │
  │   - Art 17：原本单据认定（ORIGINAL 字样、手签等）          │
  │   - ISBP A14：大小写/缩写容差边界                         │
  │   - ISBP A17：更改认证规则                               │
  │   - ISBP A18：日期格式歧义                               │
  │   - Art 14(d)：单据日期早于 LC 是否有其他问题              │
  │   - 任何其他 UCP/ISBP 强制要求"                          │
  │                                                         │
  │  输出：potential_issues_pass1[]                          │
  │        （只进人工复核队列，不直接出 DISCREPANT）            │
  └─────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────┐
  │ Pass 2：LC 特定要求全局扫描                               │
  │                                                         │
  │  输入：Invoice 全文 + LC 全文 + Layer 1/2 已检查项清单    │
  │                                                         │
  │  Prompt：                                               │
  │  "对照这张 LC 的全部条款                                  │
  │   以下是 Layer 1/2 已经检查过的项目（不要重复）            │
  │   请找出 invoice 未满足 LC 特定要求的地方                  │
  │   重点关注：                                             │
  │   - 46A 中是否有遗漏检查的单据要求                        │
  │   - 47A 中是否有 target 不是 INVOICE                     │
  │     但实际影响到整体 complying presentation 的条件        │
  │   - LC 隐含但未明说的要求                                 │
  │     （如 Art 14(a) 的 surface compliance 原则）"          │
  │                                                         │
  │  输出：potential_issues_pass2[]                          │
  │        （只进人工复核队列，不直接出 DISCREPANT）            │
  └─────────────────────────────────────────────────────────┘

  注：Layer 3 的所有输出
      不输出 DISCREPANT
      全部标记为 REQUIRES_HUMAN_REVIEW
      原因：LLM 自由扫描无确定性保证，误判风险高
                            │
                    Layer 3 Results[]
                            │
                            ▼


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 STAGE 5 ║ OUTPUT ASSEMBLY（结果汇总）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  ┌─────────────────────────────────────────────────────────┐
  │                                                         │
  │  CONFIRMED DISCREPANCIES（Layer 1+2 输出）               │
  │  ──────────────────────────────────────────             │
  │  每条包含：                                              │
  │  rule_id | severity | ucp_ref | isbp_ref               │
  │  lc_value | invoice_value | description                 │
  │  check_type (A/B/AB) | source (CATALOG/47A_DYNAMIC)     │
  │                                                         │
  │  UNABLE TO VERIFY（Layer 1+2 输出）                      │
  │  ──────────────────────────────────                     │
  │  LC 缺字段导致无法比对的规则                               │
  │  → 进人工复核队列                                         │
  │                                                         │
  │  HUMAN REVIEW QUEUE（Layer 3 输出）                      │
  │  ──────────────────────────────────                     │
  │  potential_issues_pass1（UCP/ISBP edge case）           │
  │  potential_issues_pass2（LC 全局扫描发现）               │
  │  + 所有 UNABLE_TO_VERIFY 条目                            │
  │  + 高金额交易（超过阈值）强制进队列                         │
  │                                                         │
  │  PASSED（Layer 1+2 输出）                                │
  │  ──────────────────────                                 │
  │  有 rule_id + 条文引用支撑的通过项                         │
  │                                                         │
  │  NOT APPLICABLE                                         │
  │  ──────────────                                         │
  │  LC 未激活的条件规则                                      │
  │                                                         │
  └─────────────────────────────────────────────────────────┘

  overall_status：
    有任何 CONFIRMED DISCREPANCY → DISCREPANT
    全部 PASS（HUMAN REVIEW 不影响）→ COMPLYING PENDING REVIEW


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 DECISION BOUNDARY（系统边界）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  系统可自动决定                系统不可自动决定
  ────────────────             ────────────────────────────
  Type A DISCREPANT            Layer 3 potential issues
  Type B MAJOR DISCREPANT      UNABLE_TO_VERIFY 条目
  NOT_APPLICABLE               47A 中 target≠INVOICE 的条件
  PASS（有条文支撑）             Art 29 假日顺延计算
                               discrepancy waiver 决定
                               最终付款放行


━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 DESIGN PRINCIPLES SUMMARY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  1. SOT（Source of Truth）
     UCP/ISBP Rule Catalog 是唯一真相
     MT700 和 Invoice 都是被检查对象，不是检查标准

  2. 检查方向
     Rule Catalog → 激活规则 → 去两个 source 找对应字段
     不是 source 里有什么 → 再去对照规则

  3. 两层分离
     UCP/ISBP 底线（Unconditional）+ LC 特定要求（LC_Stipulated）
     先底线后特定，两层都要过

  4. 三轨协作
     Type A（确定性）→ Type B（语义）→ Layer 3（兜底）
     Type B 的 prompt 告知 A 已验证项，避免重复
     Layer 3 输出只进人工队列，不直接判差异

  5. Missing Field 的三种结果
     DISCREPANT：UCP 要求的字段 invoice 里没有
     UNABLE_TO_VERIFY：需要 LC 字段做对比但 LC 没有
     NOT_APPLICABLE：条件规则，LC 未激活

  6. 人工始终在闭环里
     系统负责：可解释、可审计的 DISCREPANT 输出
     人工负责：edge case 裁定、waiver 决定、最终放行
     ```