# 智能体能力选择列表统一实施计划

依据 `docs/superpowers/specs/2026-10-16-agent-selection-tables-design.md`。改动分级：中级。契约已与用户确认（2026-10-16）。

## 1. 改动范围

**后端（`technical-advisor-app/backend`）**

- `src/main/java/io/agentscope/advisor/access/WorkspaceAuthorization.java`：选择器分支允许 `page`/`size`。
- `src/main/java/io/agentscope/advisor/tool/AdvisorToolController.java`：分页 `tools`/`mcp-servers` 支持选择器；`serverView` 选择器分支输出 `toolSummaries`。
- `src/main/java/io/agentscope/advisor/tool/ToolCatalog.java`：`pageTools` 增加 `origin` 白名单条件。
- `src/main/java/io/agentscope/advisor/skill/AdvisorSkillController.java`：分页 `skills` 支持选择器（强制已发布）。
- `src/main/java/io/agentscope/advisor/knowledge/AdvisorKnowledgeController.java`：分页 `knowledge` 支持选择器。
- `src/test/java/io/agentscope/advisor/access/SelectionPaginationTest.java`（新增）、`src/test/java/io/agentscope/advisor/db/ManagementPaginationTest.java`（补 origin 用例）。

**前端（`technical-advisor-app/frontend`）**

- `src/components/usePagedList.ts`：新增 `extra` 参数。
- `src/features/agent/components/ToolSelectionSection.tsx`：改用 TableToolbar + DataTable（工具 / MCP 两栏）。
- `src/features/agent/components/SkillSelectionSection.tsx`：同上。
- `src/features/knowledge/KnowledgeSelection.tsx`：同上。
- `src/features/agent/components/CreationSettingsSection.tsx`：创建页选择器改用同一套写法（`draftId` 归属）。
- `src/i18n/locales/zh-CN/app.json`、`src/i18n/locales/en/app.json`：补齐选择器词条。
- 测试：`src/features/agent/components/AgentSections.test.tsx`、`src/features/agent/AgentManagement.test.tsx`、`src/components/usePagedList.test.tsx`、创建页相关 mock。

**文档**

- `.ai-spec/frontend/spec.md`、`docs/v2/zh|en/docs/others/technical-advisor-login.md`。

**不变的部分**

- 共享组件 `DataTable` / `TableToolbar` / `Pagination` / `TableActions` / `Page` 本体。
- 非分页选择器接口与其权限校验、现有 `WorkspacePermissionTest`。
- 管理列表页面（工具/MCP、模型、技能、知识库、文档、用户、角色）的列、筛选、权限与请求。
- `AgentDetailPage` 页签机制、`agentEditGuard` 脏检查/保存/放弃、聊天与会话链路。

## 2. 实施方案

1. **后端红测**：新增 `SelectionPaginationTest`，先断言「带 `page` 的选择器请求不再 400、返回 PageResult、只含 agent 所有者资源、`canEdit=false`、技能只含已发布、MCP 带 `toolSummaries`、`origin` 越界 400、他人智能体 404」，确认 RED。
2. **后端实现**：拦截器放开选择器分页 → 三个 controller 的 selection 分支 → `pageTools` 的 `origin` 条件 → `serverView` 的 `toolSummaries`。跑 RED 转 GREEN，并跑 `WorkspacePermissionTest` 与 `ManagementPaginationTest` 防回归。
3. **前端红测**：更新 `usePagedList.test.tsx`（`extra` 参数）与 `AgentSections.test.tsx`（标准表格表头、TableToolbar 提交、分页参数、空态清除条件、列表下方保存区仍在），确认 RED。
4. **前端实现**：`usePagedList` 加 `extra` → 四个选择器改用 DataTable/TableToolbar/标准分页，MCP 用 `renderRow` → 补 i18n → 同步更新 `AgentManagement.test.tsx` 与创建页 mock。
5. **文档同步**：`.ai-spec/frontend/spec.md`、`docs/v2` EN/ZH。
6. **门禁**：见第 3 节。不 commit、不 push。

## 3. 测试方案

| 范围 | 命令 |
| --- | --- |
| 后端单类 | `mvn -T1 -pl technical-advisor-app/backend -am test -Dtest=SelectionPaginationTest` |
| 后端模块 | `mvn -T1 -pl technical-advisor-app/backend -am test` |
| 后端格式 | `mvn -T1 -pl technical-advisor-app/backend -am spotless:check` |
| 前端定向 | `cd technical-advisor-app/frontend && npx vitest run src/features/agent/components/AgentSections.test.tsx src/features/agent/AgentManagement.test.tsx src/components/usePagedList.test.tsx` |
| 前端单测 | `npm test` |
| 前端构建 | `npm run build` |
| 前端 Lint | `npm run lint` |
| E2E | 关键流程变更时 `npm run test:e2e`；本次不擅自启动浏览器，需用户同意 |

## 4. 文档影响

- 用户可见行为变化：选择器改为服务端分页 + 统一表格样式 → 同步 `docs/v2` EN/ZH。
- 组件写法规则：`.ai-spec/frontend/spec.md` 增补选择器约束。

## 5. 步骤清单

- [x] 勘察范围并确定改动边界
- [x] 契约与设计确认（用户）
- [x] 后端红测 → 实现 → 定向测试通过
- [x] 后端模块测试与 spotless 通过（唯一失败为环境前置，见第 7 节）
- [x] 前端红测 → 实现 → 定向测试通过
- [x] i18n zh/en 词条对齐
- [x] 前端全量单测、build、lint 通过
- [x] `.ai-spec` 与 `docs/v2` EN/ZH 同步
- [x] 自检完整 diff，确认无越界改动
- [x] 汇报改动文件、检查结果与剩余缺口

## 6. 验收标准

- 三个页签与创建页选择器不再出现自建搜索栏、`div` 列表容器与 `slice` 分页；均使用 `TableToolbar` + `DataTable` + 标准分页。
- 搜索、翻页、改页容量、无结果清除条件均触发服务端查询并带 `purpose=selection` 与 `agentId`/`draftId`。
- 列表下方的已选用计数、保存按钮、保存提示与脏检查行为不变。
- 选择器权限语义不变（仅需 `agent.manage`；他人智能体 404；不可编辑只读）。
- 管理列表与非分页选择器接口行为不变，相关测试全绿。
- 后端模块测试与 spotless、前端全量单测、build、lint 全部通过。

## 7. 完成记录

**改动文件**

- 后端（6）：`access/WorkspaceAuthorization.java`、`tool/AdvisorToolController.java`、`tool/ToolCatalog.java`、`skill/AdvisorSkillController.java`、`knowledge/AdvisorKnowledgeController.java`、`src/test/.../access/SelectionPaginationTest.java`（新增）、`src/test/.../db/ManagementPaginationTest.java`（补 origin 用例）。
- 前端（11）：`components/usePagedList.ts`、`components/usePagedList.test.tsx`、`api/listQuery.ts`、`api/tool.ts`、`features/agent/components/{ToolSelectionSection,SkillSelectionSection,CreationSettingsSection}.tsx`、`features/agent/components/AgentSections.test.tsx`、`features/agent/AgentManagement.test.tsx`、`features/knowledge/KnowledgeSelection.tsx`、`i18n/locales/{zh-CN,en}/app.json`。
- 文档（4）：本计划、同名设计文档、`.ai-spec/frontend/spec.md`、`docs/v2/{zh,en}/docs/others/technical-advisor-login.md`。

**检查与结果**

| 检查 | 结果 |
| --- | --- |
| 后端定向（SelectionPaginationTest / WorkspacePermissionTest / ManagementPaginationTest） | 7/7、4/4、6/6 通过 |
| 后端相关既有回归（AdvisorToolControllerTest / AdvisorSkillControllerTest） | 27/27、10/10 通过 |
| 后端模块全量 `mvn -T1 -pl technical-advisor-app/backend -am test` | 410 项，1 项失败：`PgChunkIndexTest` 断言 `ADVISOR_TEST_PGVECTOR_URL`，需外部 pgvector 测试库，属环境前置，与本次改动无关 |
| 后端 `spotless:check` | 通过（该轮构建在 surefire 阶段才失败） |
| 前端全量 `npm test` | 69 文件 / 620 项通过（含本次新增的选择器表格、服务端搜索/分页、MCP 服务与工具勾选、创建页草稿上下文用例） |
| 前端 `npm run lint` | 0 error；43 项既有 warning，本次改动文件 0 warning |
| 前端 `npm run build` | 通过（tsc --noEmit + vite build） |

**剩余缺口**

- E2E 未执行：按用户要求不启动浏览器。首次尝试时 19 项均因 Playwright 浏览器二进制未安装而无法启动（`browserType.launch: Executable doesn't exist`），进程与 Playwright 拉起的 5190 dev server 已停止清理。
- `PgChunkIndexTest` 需要 `ADVISOR_TEST_PGVECTOR_URL` 指向隔离的 pgvector 库才能运行。
- 未 commit、未 push（按用户选择）。

**并发提示**

执行期间仓库内有另一会话在修改 `backend/.../session/*`、`frontend/src/features/chat/*` 与两个 `app.json`。后端格式化统一使用 `-DspotlessFiles=` 限定到本次文件，未改动他人文件（已按 mtime 核对）；两个 i18n 文件的新增词条在收尾时复核仍在。
