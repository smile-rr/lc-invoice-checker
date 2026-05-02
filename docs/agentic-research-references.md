# 代理式 LC 管道 — 研究参考

代理式重设计的背景阅读和证据基础。编译于 2026-04-30。

---

## 1. 行业演变：基于规则 → LLM 判断 → 自主代理（LC / 贸易金融）

这些证实了行业的三阶段演变，并阐明了为什么规则目录架构是上限受限的。

1. LLM 如何改变贸易金融中的 SBLC  
   https://cleareye.ai/how-llms-are-transforming-sblc-in-trade-finance/  
   从规则引擎到 LLM 增强再到代理式 LC 审查的具体银行侧迁移故事

2. 贸易金融中的 AI 驱动转型：自动化 LC 文件审查的路线图（ScienceDirect）  
   https://www.sciencedirect.com/science/article/pii/S2666954425000250  
   学术路线图；定义了 LC 审查的 AI 成熟度阶段

3. 高盛让 AI 代理处理会计和合规工作（PYMNTS）  
   https://www.pymnts.com/artificial-intelligence-2/2026/goldman-sachs-lets-ai-agents-do-accounting-and-compliance-work/  
   一级银行部署 Anthropic Claude 代理进行合规工作

4. 金融服务中的代理式 AI：2026 年完整指南  
   https://www.nimbleappgenie.com/blogs/agentic-ai-in-financial-services/  
   53% 的金融服务机构在生产中运行代理；2025 年支出 500 亿美元

5. 为什么监管机构喜欢代理式 AI（SymphonyAI）  
   https://www.symphonyai.com/resources/blog/financial-services/why-regulators-love-agentic-ai/  
   监管机构立场 — 可解释性 + 治理启用代理式采用

6. 代理式 AI 在 AML 合规中的角色指南（Napier AI）  
   https://www.napier.ai/knowledgehub/agentic-ai-aml-compliance  
   AML 特定的代理设计模式，可转移到 LC 合规

---

## 2. 计划-执行 vs ReAct（代理架构选择）

为合规场景选择 **计划-执行 + 审查者** 的证据基础。

7. 超越准确性：评估企业代理式 AI 系统的多维度框架（arXiv 2511.14136）  
   https://arxiv.org/html/2511.14136v1  
   2026 年基准测试跨越 6 个企业领域 × 300 个任务。合规：计划-执行 72% 准确率；领域调优 PAS=0.93 vs ReAct-GPT4 0.87

8. ReWOO vs. ReAct：选择正确的代理架构（Nutrient.io）  
   https://www.nutrient.io/blog/rewoo-vs-react-choosing-right-agent-architecture/  
   根据任务形状选择架构的决策树

9. AI 代理规划：ReAct vs 计划和执行以提高可靠性  
   https://byaiteam.com/blog/2025/12/09/ai-agent-planning-react-vs-plan-and-execute-for-reliability/  
   计划-执行在高风险流程中的可靠性优势

10. 计划-执行提示：先分解，然后行动（SurePrompts）  
    https://sureprompts.com/blog/plan-and-execute-prompting  
    规划器阶段的提示工程模式

11. 代理架构：ReAct、自问、计划-执行（apxml）  
    https://apxml.com/courses/langchain-production-llm/chapter-2-sophisticated-agents-tools/agent-architectures  
    并排比较；成本 vs 准确率权衡

12. ReAct vs 计划-执行 vs ReWOO vs Reflexion（theaiengineer）  
    https://theaiengineer.substack.com/p/the-4-single-agent-patterns  
    单代理模式的调查

13. ReAct vs 计划-执行：实际比较（DEV.to）  
    https://dev.to/jamesli/react-vs-plan-and-execute-a-practical-comparison-of-llm-agent-patterns-4gh9  
    代码级比较

---

## 3. 文档理解与视觉-LLM 提取

为什么固定模式 OCR-然后提取输给原生多模态 LLM — 直接解决痛点 #1 & #2（发票多样性 / 不可预测内容）。

14. 多模态视觉 vs 基于文本的解析：发票处理的 LLM 策略基准测试（arXiv 2509.04469）  
    https://arxiv.org/html/2509.04469v1  
    原生图像处理始终优于结构化中间转换

15. 2026 年文档数据提取：LLM vs OCR（Vellum）  
    https://www.vellum.ai/blog/document-data-extraction-llms-vs-ocrs  
    工程迁移指南；成本和复杂性比较

16. 使用 LLM 的文档智能：从非结构化数据中提取结构（Virtido）  
    https://virtido.com/blog/document-intelligence-llm-extraction-guide  
    多模态 LLM 提取模式

17. 2026 年 AI 发票数据提取指南（Unstract）  
    https://unstract.com/blog/ai-invoice-processing-and-data-extraction/  
    实际发票提取方法

18. 2026 年文档筛选的最佳开源 LLM（SiliconFlow）  
    https://www.siliconflow.com/articles/en/best-open-source-LLM-for-Document-screening  
    开源模型选择指南

19. 以 LLM 为中心的发票信息提取管道（HAL）  
    https://hal.science/hal-04772570v1/document  
    学术发票提取管道

20. 自动化发票数据提取：使用 LLM 和 OCR（arXiv 2511.05547）  
    https://arxiv.org/abs/2511.05547  
    混合 LLM + OCR 方法基准测试

21. LlamaIndex AI 代理用于文档 OCR + 工作流  
    https://www.llamaindex.ai/  
    代理式文档工作流框架

---

## 4. 合规代理设计模式（多角色 / HITL）

我们规划器 / 执行器 / 审查器拆分的基础。

22. 合规检查的 AI 代理：使用多代理智能自动化监管保证（Lyzr.ai）  
    https://www.lyzr.ai/blog/ai-agents-for-compliance-checks/  
    规划 / 差距 / 证据 / 批准四角色分解

23. 合规的 AI 代理：用例、益处、挑战（AI21）  
    https://www.ai21.com/knowledge/ai-agents-for-compliance/  
    人类在回路中的最佳实践

24. 代理式 AI 合规：治理 AI 代理的技术指南（Aisera）  
    https://aisera.com/blog/agentic-ai-compliance/  
    治理模式

25. 代理式 AI 治理和合规（Okta）  
    https://www.okta.com/identity-101/agentic-ai-governance-and-compliance/  
    代理的身份/访问模式

26. 合规的代理式 AI | 自动化监管合规解决方案  
    https://www.aiagentscompliance.com/  
    供应商中立框架

27. 合规的 AI 代理 — 自主 AI 副驾驶（Regulativ.ai）  
    https://www.regulativ.ai/ai-agents  
    自主副驾驶模式

28. CISO 和安全领导者的代理式 AI 合规指南（Tredence）  
    https://www.tredence.com/blog/agentic-ai-compliance  
    安全和风险视角

29. AI 代理能否创造监管合规风险？（ICAEW）  
    https://www.icaew.com/insights/viewpoints-on-the-news/2026/apr-2026/can-ai-agents-create-regulatory-compliance-risks  
    风险评估视角

---

## 5. 2026 年银行业 / 金融中更广泛的代理式 AI 背景

30. 2026 年金融顶级 AI 代理开发公司（Intellectyx）  
    https://www.intellectyx.com/top-ai-agent-development-companies-for-financial-services-in-2026/

31. 金融合规和 AML 监控的 AI 代理：2026 年指南（CallSphere）  
    https://callsphere.ai/blog/agentic-ai-financial-compliance-aml-monitoring

32. 金融和银行业中的 AI 代理：2026 年 12 个经过验证的用例（Kore.ai）  
    https://www.kore.ai/blog/ai-agents-in-finance-banking-12-proven-use-cases-2026

33. 代理式银行业：AI 系统和代币化合规如何重组银行（Medium / Jung-Hua Liu）  
    https://medium.com/@gwrx2005/agentic-banking-how-ai-systems-and-tokenized-compliance-are-restructuring-investment-and-30f61e33b293

34. 金融中的 AI 代理：2026 年银行和金融科技的 15 个用例（Dextralabs）  
    https://dextralabs.com/blog/top-15-ai-agent-use-cases-in-finance/

35. 2026 年 KYC/AML 的 AI 合规代理：炒作 vs 现实（KYC Chain）  
    https://kyc-chain.com/ai-compliance-agents-kyc-aml/

36. 2026 年 AI 评估指标：由对话专家测试（MasterOfCode）  
    https://masterofcode.com/blog/ai-agent-evaluation

37. 2026 年精彩 AI 代理论文（GitHub / VoltAgent）  
    https://github.com/VoltAgent/awesome-ai-agent-papers

---

## 这些参考如何映射到我们的计划

- 计划-执行 + 审查者架构: #7, #8, #9, #11
- UCP 600 / ISBP 821 的 RAG（知识摄取）: #1, #2, #22
- 替换固定模式 InvoiceDocument 提取: #14, #15, #16
- 三角色代理拆分（规划器 / 执行器 / 审查者）: #22, #23, #24
- 可选的人在回路中的计划批准门: #23, #5
- 生产就绪（不是研究玩具）框架: #3, #4, #30, #34
- 代理式决策的监管机构可接受性: #5, #25, #29
