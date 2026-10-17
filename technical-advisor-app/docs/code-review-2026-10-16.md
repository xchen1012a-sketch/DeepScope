# 代码规范 / 质量 / 健壮性审查报告（2026-10-16）

- **审查范围**：`technical-advisor-app`（backend + frontend）。上游框架模块（`deepscope-core`、`deepscope-harness`、`deepscope-extensions`、`deepscope-examples`）不在本次范围。
- **判据（SSOT）**：`.ai-spec/change-levels.md`、`.ai-spec/collaboration.md`、`.ai-spec/backend/{java.md,gates.md}`、`.ai-spec/frontend/{spec.md,gates.md}`、`AGENTS.md` 导航；通用工程标准（文件 ~350–400 行、函数 ≤40 行、禁调试残留、类型收敛）。
- **方法**：静态阅读 + 自动化度量（文件规模、路由/权限分布、吞异常、死代码、内联样式、跨 feature 依赖、测试断言质量）。**未运行** `mvn`/`npm`/浏览器（并发会话正在开发，避免写产物与冲突）；因此"能跑通"类结论不代表已执行，见文末。
- **过程说明**：原计划 6 个分域只读审查子任务，**全部因基础设施故障失败**（无收尾输出），改为人工分域审查 + 自动化检查。工作区有多个并发会话的未提交改动，本报告评审的是**当前工作区快照**。

## 一、总体结论

| 维度 | 评级 | 一句话 |
| --- | --- | --- |
| 规范性 | **中上** | 列表/弹窗/表单已统一到共享组件且我做了结构核验；扣分主要在**文件规模普遍超标**、**后端请求契约弱类型**、**文案硬编码**、**feature 之间依赖无规则** |
| 质量 | **中** | 无调试残留、无跳过测试、i18n 有自动对齐测试；但**超大文件/多职责类**、**91 处无参弱断言**、**选择器分页改造留下死代码** |
| 健壮性 | **中上** | 认证覆盖面清晰、新控制器逐端点做了归属校验、迁移幂等；风险在**同一权限语义有 3 份实现**、**28 处吞异常**、**门禁对环境敏感且全局耦合** |

**无静态可证的 BLOCKING 在线缺陷**；唯一曾经的 BLOCKING（产品身份/行为文档不进提示词）已修复，待重启验证。

## 二、IMPORTANT（按影响排序）

### I1 文件规模普遍超标（单一职责被打破）
标准建议 ~350–400 行；实测：

- 后端 **42 个**源文件 >400 行：`tool/AdvisorToolController.java` **1322**、`session/SessionAttachments.java` 1076、`tool/ToolCatalog.java` 984、`session/TimelineStore.java` 931。
- 前端 **15 个**非测试文件 >400 行：`features/chat/useAdvisorConversation.ts` **1269**、`features/skill/SkillsPage.tsx` 1219、`features/chat/components/ComposerDock.tsx` 944。
- 典型症状：一个 Controller 内同时包含路由、视图装配（`toolView`/`serverView`）、业务编排、测试台；`SkillsPage.tsx` 同时是页面、对话框、类型与 API 中心。

### I2 后端请求契约弱类型
**42 处** `@RequestBody Map<String, Object>`（例：`skill/AdvisorSkillController.java:259,390,422,483,573`、`access/AdvisorAccessController.java:96`）。
后果：无类型 DTO、无 Bean Validation、字段缺失只能靠 `text(body, key)` 手工取值；前端 `spec.md` 要求类型收敛，后端应对齐。

### I3 业务文案硬编码在后端代码
`session/ReportPresentationMiddleware.java:31` 起是一整段写死的中文提示词约定。前端有 i18n 双语对齐测试，后端没有任何文案治理机制，改文案必须改代码 + 重新发布。

### I4 feature 之间依赖无规则，边界已渗漏
- `features/chat/*` → `features/account/{useAccount,newConversation,AccountShared}`（ChatPage、ConversationPanel）
- `features/agent/components/SkillSelectionSection.tsx`、`CreationSettingsSection.tsx` → `features/skill/SkillsPage`（**把页面模块当类型/API 中心**）
- `features/knowledge/KnowledgeSelection.tsx` → `features/agent/agentEditGuard`
- `features/knowledge/components/DocumentManagerTab.tsx` → `features/skill/skillDrop`

`spec.md` 只约束了 `components/ → features`（实测 **0 违规 ✔**），feature↔feature 无规则，实际已交织，改动一处易连带。

### I5 测试弱断言
**91 处** `toHaveBeenCalled()`（无参）：只验证"被调用"，不校验参数，重构或传错参数不会失败。正面：`it.skip`/`describe.skip` **0 处**、`expect(true)` **0 处**。

### I6 吞异常 28 处，需逐个定性
抽样多为可辩护（`SkillPackage.java:284` 宽松修复、`AgentCreationService.java:174` 复制来源缺失回退）；但存在**空 catch 且无注释**的情形，例如 `agent/AgentCreationService.java:659`、`agent/AgentCreationController.java:303`。静默失败会掩盖真实故障，建议：要么加注释说明为何可忽略，要么记日志并给出降级路径。

### I7 同一权限语义存在 3 份实现
1. `access/WorkspaceAuthorization.java`：按路径分支 + 逐路由权限点；
2. 各 Controller 内的 `requireAgent` / `owner(...)` / `KnowledgeCatalog.canView|canEdit`；
3. `access/AdvisorAccessController.java` 自己的 `forbidden(request)`。

且 `/api/advisor/sessions/**` 落在拦截器 `else { return true; }`（**无模块权限判定**），安全性完全依赖控制器内 owner 检查。任何一处漏改都会形成越权口子；建议收敛为单一来源，或至少补"路由 → 所需权限"的覆盖测试。

## 三、NIT

1. **死代码 / 无用导出**（选择器改服务端分页后的遗留）：`features/tool/groupTools.ts` 的 `groupExternalTools`/`countVisibleTools`（生产 0 调用）、`api/agent.ts` 的 `getSubagentTemplate`、非分页 `listSkills`/`listTools`；`agent/components/SubagentsSection.tsx:21` 未使用的 `Badge`（HEAD 即存在）。
2. **门禁脆弱**：`knowledge/PgChunkIndexTest` 缺 `ADVISOR_TEST_PGVECTOR_URL` 时**直接 fail**（应 `assumeTrue`/skip），使"后端全量绿"依赖环境；`spotless:check` 全模块耦合，任一人格式违规会阻断所有人验证（本周实际发生两次，均非本任务文件）。
3. **内联样式** 7 处（`ComposerToolbar.tsx:268`、`ComposerDock.tsx:456,756`、`ProcessDrawer.tsx:131`、`WorkspaceSection.tsx:270,291`、`PdfPageDialog.tsx:94`）——已逐条核对：**全部是运行时计算值**（百分比宽度、按层级缩进、PDF 坐标），属规范允许的例外，不判违规。
4. **客户端分页残留？** 已核对 `ProcessText.tsx:48`、`ToolTestPanel.tsx:183` 的 `slice(...)`：是**长文本分页显示**，不是列表分页，不违规。
5. **工程流程**：多个会话在同一工作区、无提交并行开发，实际造成 i18n 词典被整文件重排（已回滚）、`SettingsPage` 测试在他人中间态失败、`ModelManagementTest` 编译失败导致全模块无法验证。建议 worktree 隔离或强制小步提交。

## 四、规范条款符合度（对照 `.ai-spec`）

| 条款 | 结论 |
| --- | --- |
| 前端 · 标准表格（复用 `DataTable`/`Pagination`，禁自建表头/空态/分页） | 符合；能力选择器已迁移并补结构核验 |
| 前端 · 弹窗结构（`DialogHeader`/`DialogBody`/`DialogFooter`，无上下分隔线） | 符合；15 个文件标签配对、**0 处残留手写 footer** |
| 前端 · 文案零硬编码 + zh/en 键对齐 | 符合（有自动对齐测试）；本次新增词条双语齐备 |
| 前端 · 分层（`components/` 不得依赖 feature） | 符合（0 违规）；但 feature↔feature 无规则（见 I4） |
| 前端 · 样式用 Tailwind + `cn()`，禁内联 style | 基本符合；7 处均为动态计算值（例外） |
| 前端 · 类型收敛 | 显式 `any` 仅 6 处，可接受 |
| 后端 · 文件规模/单一职责 | **不符合**（见 I1） |
| 后端 · 请求契约类型化 | **不符合**（见 I2） |
| 后端 · 权限判定单一来源 | **部分不符合**（见 I7） |
| 后端 · 门禁可执行性（`gates.md`） | **部分不符合**：外部依赖测试 fail 而非 skip；格式检查全局耦合 |
| 变更分级流程（中级须先出计划、契约先行） | 本次会话内的改动符合；仓库缺机器化约束（靠人自觉） |

## 五、检查过且判定正常（正面证据）

- 0 处 `printStackTrace` / `System.out` / `TODO`（后端主源码）；前端 0 `console.log`。
- 认证过滤器覆盖 `/api/advisor/**`、`/api/auth/{me,logout}`，以及新加的 `/api/me`（加时验证过"无 token → 401"）。
- 新增控制器（`PdfDocumentController`、`AccountSelfServiceController`）逐端点做了归属校验（`canView/canEdit`、`account(request)`）。
- 数据库迁移全部 `IF NOT EXISTS` 幂等；业务数据与向量同库的策略有文档依据。

## 六、修复优先级（Top 10）

1. 重启后端并验证"你是谁 → 我是 DeepScope"（已修待验）。
2. 拆 `AdvisorToolController`(1322) / `SessionAttachments`(1076) / `ToolCatalog`(984) / `TimelineStore`(931)；前端先拆 `useAdvisorConversation`(1269) 与 `SkillsPage`(1219)。
3. 后端请求改类型化 DTO + Bean Validation（42 处），保留兼容。
4. 收敛权限判定到单一来源，并加"路由 → 权限"覆盖测试（含 `/api/advisor/sessions/**`）。
5. 清死代码：`groupTools` 未用导出、`getSubagentTemplate`、非分页 `listSkills`/`listTools`、未用 `Badge`。
6. `PgChunkIndexTest` 改 `assumeTrue`/skip；把 `spotless:check` 拆成"只查改动文件"的提交前钩子，避免全局耦合。
7. 治理后端提示词/文案：集中到一处常量或配置，必要时支持中英。
8. 明确 feature 依赖规则（允许方向 + 共享模块），抽出 `skills/types` 之类的中立模块。
9. 逐步把 91 处无参断言升级为带参断言（按模块滚动）。
10. 并行开发改 worktree 或小步提交，保证"全量绿"可复制。

## 七、无法静态验证 / 未覆盖

- 运行时行为：重启后身份是否生效、真实数据下分页与选择器、权限矩阵端到端（本次只看静态代码）。
- E2E 未执行（按用户要求不启动浏览器）；`frontend/e2e/**` 的用例质量仅做静态阅读。
- 外部依赖测试（pgvector、真实模型端点）未执行。
- 未纳入：上游框架模块、文档站点、`data/` 运行数据。
