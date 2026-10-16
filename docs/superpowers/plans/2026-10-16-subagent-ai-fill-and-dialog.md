# 子智能体 AI 补齐与弹窗收口实施计划

改动分级：中级（跨前后端新增接口、共享 Dialog 组件调整、用户可见行为变化）。契约已在对话中与用户确认（2026-10-16）。

## 1. 改动范围

**后端（`technical-advisor-app/backend`）**

- `src/main/java/io/agentscope/advisor/agent/SubagentDraftService.java`（新增）：按名称（可选用途）生成子智能体定义；front matter 只写 `description`（名称由文件名决定），空回复/失败返回空串。
- `src/main/java/io/agentscope/advisor/agent/AdvisorAgentController.java`：新增 `POST /api/advisor/agents/{agentId}/subagents/draft`。
- `src/test/java/.../agent/SubagentDraftServiceTest.java`（新增）、`AdvisorAgentControllerTest.java`（补接口用例）。

**前端（`technical-advisor-app/frontend`）**

- `src/components/ui/dialog.tsx`：`DialogHeader` 去掉分隔线，新增 `DialogFooter`，`DialogBody` 内边距随之微调。
- 14 个文件的手写弹窗底部操作区替换为 `DialogFooter`：`access/{RolesPage,UsersPage}`、`agent/components/{CreateAgentDialog,SubagentsSection,WorkspaceSection}`、`knowledge/components/KnowledgeListPage`、`settings/components/ModelEditorDrawer`、`skill/SkillsPage`、`tool/{ToolsPage,components/{McpChangesDialog,McpEditorDialog,ToolEditorPanel,ToolImportDialog,ToolTestPanel}}`。
- `src/features/agent/components/SubagentsSection.tsx`：新建不预填名称与模板；定义内容区新增「AI 补齐」按钮、隐私提示、失败提示与覆盖确认；删除本地模板常量与 new 模式的模板请求。
- `src/api/agent.ts`：新增 `draftSubagent`。
- `src/i18n/locales/{zh-CN,en}/app.json`：新增子智能体词条，删除已作废的 `agent.subagents.templateHint`。
- 测试：`src/features/agent/components/AgentSections.test.tsx`。

**文档**

- `.ai-spec/frontend/spec.md`（弹窗写法）、`docs/v2/{zh,en}/docs/others/technical-advisor-login.md`。

**不变的部分**

- `GET /agents/{id}/subagents/template` 接口保留（页面不再调用）；子智能体卡片列表、删除确认、工作区文件读写、`useAgentEditor` 脏检查链路、其它弹窗业务逻辑。

## 2. 实施方案

1. **契约**：`POST /api/advisor/agents/{agentId}/subagents/draft`，body `{name, purpose?}`。
   - 200 `{"content": "---\ndescription: …\n---\n\n…"}`；400 `missing_name`；404 智能体不可见；502 `draft_failed`（无模型或模型空回复）；名称来自文件名，故 front matter 不写 `name`。
2. **后端实现**：`SubagentDraftService` 复用 `ContextModelCalls.auxiliary`，提示模型输出「描述：/正文：」两段；缺失描述时回退用途或名称，正文缺失时整段作为正文；用 `AgentSpecLoader.parse` 验证生成物可被框架加载。
3. **前端实现**：共享 `DialogFooter` 抽出来后全量替换手写 footer；子智能体弹窗命名、内容从空开始，`AI 补齐` 仅在有名称时可用，已有内容先确认再整段替换，隐私提示常驻，失败保留原文。
4. **文档同步**：`.ai-spec/frontend/spec.md` 增补弹窗写法；`docs/v2` EN/ZH 补子智能体 AI 补齐与弹窗样式说明。

## 3. 测试方案

| 范围 | 命令 |
| --- | --- |
| 后端单类 | `mvn -T1 -pl technical-advisor-app/backend -am test -Dtest=SubagentDraftServiceTest` |
| 后端控制器 | `mvn -T1 -pl technical-advisor-app/backend -am test -Dtest=AdvisorAgentControllerTest` |
| 后端模块 + 格式 | `mvn -T1 -pl technical-advisor-app/backend -am test` + `spotless:check` |
| 前端定向 | `npx vitest run src/features/agent/components/AgentSections.test.tsx` |
| 前端全量 | `npm test` |
| 前端 lint / 构建 | `npm run lint`、`npm run build` |
| E2E | 按用户要求不启动浏览器 |

## 4. 文档影响

用户可见变化：新建子智能体不再预填、新增 AI 补齐与隐私提示、弹窗不再有上下两条分隔线 → 同步 `docs/v2` EN/ZH 与前端规范。

## 5. 步骤清单

- [x] 勘察范围并确认契约（用户确认触发条件/补齐策略/弹窗范围）
- [x] 共享 `DialogFooter` + 14 文件全量替换（620 前端用例零回归）
- [x] 后端 `SubagentDraftService` + 接口（先红后绿）
- [x] 子智能体弹窗改造 + i18n
- [x] 前端用例改写与新增（预填清空 / 补齐门禁与隐私提示 / 覆盖确认与失败保留）
- [x] 后端模块测试 + spotless
- [x] 前端全量 test / lint / build
- [x] 文档同步与完成记录

## 6. 完成记录

**接口契约（已实现）**

`POST /api/advisor/agents/{agentId}/subagents/draft`，body `{"name": "…", "purpose": "…"}`：

| 场景 | 结果 |
| --- | --- |
| 可编辑智能体 + 模型有回复 | 200 `{"content": "---\ndescription: …\n---\n\n…"}`（不写 `name`，名称由文件名决定） |
| 名称空或全空白 | 400 `{"code":"missing_name"}` |
| 智能体不可见 | 404（沿用"他人个人智能体一律隐藏"的既有语义） |
| 无模型 / 模型空回复 / 调用失败 | 502 `{"code":"draft_failed"}`，不写入任何文件 |

**检查与结果**

| 检查 | 结果 |
| --- | --- |
| `SubagentDraftServiceTest` | 5/5 通过（含用 `AgentSpecLoader.parse` 验证生成物可被框架加载、描述含冒号的 YAML 引号、空名不调模型、失败留空） |
| `AdvisorAgentControllerTest` | 25/25 通过（含新接口 200/400/502/404 四种路径） |
| 后端模块全量 `mvn -T1 -pl technical-advisor-app/backend -am test` | 425 项，1 项环境失败：`PgChunkIndexTest` 需 `ADVISOR_TEST_PGVECTOR_URL`（既有环境前置，与本次无关）；`spotless:check` 通过 |
| 前端 `AgentSections.test.tsx` | 12/12 通过（空白新建、补齐门禁与提示、覆盖确认、失败保留、**补齐中锁定输入**、MCP 勾选等） |
| 前端全量 `npm test` | 632/632 通过 |
| 前端 `npm run lint` | 0 error；43 项既有 warning（本次改动引入的未用 import 已清理；`SubagentsSection` 的未用 `Badge` 是 HEAD 就有的） |
| 前端 `npm run build` | 通过（tsc --noEmit + vite build） |
| 共享弹窗替换回归 | 14 个文件的 footer 全部替换为 `DialogFooter`，替换后立即跑全量用例零回归 |

**追加需求（同日）**

1. **去掉 AI 补齐类按钮的图标**：`SubagentsSection` 的「AI 补齐」、`SkillsPage` 两处「AI 起草」、`CreationPurposeForm` 的「AI 生成」全部改为纯文字；`Loader2` 的生成中状态保留（状态而非装饰）。导航、栅栏卡片、斜杠菜单、创建页区块标题上的 Sparkles 属装饰，未动。相应的 `Sparkles` 未用导入已清理。
2. **补齐进行中锁定输入**：`fill.isPending` 期间名称输入与定义内容文本域 `disabled`（`aria-busy`），保存按钮同时禁用，避免生成结果回来覆盖正在输入的文字；`onSuccess` 后自动解锁。

**剩余缺口**

- E2E 未执行（按用户要求不启动浏览器）。
- `PgChunkIndexTest` 需外部 pgvector 库。
- 并发会话的 `SettingsPage.test.tsx` 尚有其自身未完成用例失败；与本任务文件无交集。
- 未 commit、未 push。
