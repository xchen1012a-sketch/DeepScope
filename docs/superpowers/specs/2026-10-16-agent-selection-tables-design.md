# 智能体能力选择列表统一设计（工具 / 技能 / 知识库）

| 项 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 编制日期 | 2026-10-16 |
| 文档状态 | 已与用户确认（2026-10-16） |
| 依据 | 前端规范列表统一写法、`docs/v2/zh/docs/others/technical-advisor-login.md` 管理列表格式、技能/知识库设计文档的选用规则 |
| 改动分级 | 中级（跨前后端、内部接口扩展、用户可见行为变化） |

---

## 1. 问题

智能体管理页（`AgentPage` → `AgentDetailPage`）的三个能力页签与创建页的能力选择器，各自手写了一套搜索框、列表容器与分页：

- 工具：`features/agent/components/ToolSelectionSection.tsx`（内含「工具 / MCP」两个子栏，页大小 8 / 5）
- 技能：`features/agent/components/SkillSelectionSection.tsx`（页大小 8）
- 知识库：`features/knowledge/KnowledgeSelection.tsx`（页大小 8）
- 创建页：`features/agent/components/CreationSettingsSection.tsx`（无分页，`max-h` 滚动）

违反的既有规范：`DataTable` / `TableToolbar` / `Pagination` 是统一写法，页面不得自行实现表头、外框、空态与分页；文案必须经 `t()`。现状三处搜索栏、三处 `div` 列表、三处 `slice` 分页均为自建，且存在硬编码中文（`搜索技能名称或介绍...`、`已选用 N 项`、`未找到与“…”匹配`）。

同时，选择器当前拉取**全量**资源再在浏览器内搜索和分页。产品文档明确「旧无分页接口保留给现有选择器兼容使用，管理列表不再调用它们进行分页」，而本次按用户决定把选择器也切到服务端分页，因此需要一个契约扩展，而不是继续在前端切片。

## 2. API 契约

不新增路由。四个既有分页路由（管理列表在用）扩展支持选择器用途；非分页的旧选择器接口原样保留。

| 路由 | 入参（新增部分加粗） | 响应 |
| --- | --- | --- |
| `GET /api/advisor/tools` | `page`、`size`、`q`、**`origin`**（白名单 `http` / `declared` / `mcp`，逗号分隔）、**`purpose=selection`**、**`agentId` 或 `draftId`** | `PageResult<CatalogTool>`；选择器下每项 `canEdit=false` |
| `GET /api/advisor/skills` | `page`、`size`、`q`、**`purpose=selection`**、**`agentId` 或 `draftId`** | `PageResult<SkillSummary>`；选择器下强制只返回 `status=published` |
| `GET /api/advisor/knowledge` | `page`、`size`、`q`、**`purpose=selection`**、**`agentId` 或 `draftId`** | `PageResult<LibrarySummary>` |
| `GET /api/advisor/mcp-servers` | `page`、`size`、`q`、**`purpose=selection`**、**`agentId` 或 `draftId`** | `PageResult<McpServer>`；选择器下每项增加 `toolSummaries: [{toolId,name,description,approved,permission}]` |

规则：

- 归属沿用现有 `WorkspaceAuthorization.selectionOwner()`：按被编辑智能体/草稿的所有者解析资源归属；未给 `agentId`/`draftId` 时归属当前账号。
- 权限仍只要求 `agent.manage`（不需要工具/技能/知识库的管理权限），与现有选择器一致。
- 智能体或草稿不可编辑 → `404`；无 `agent.manage` → `403`；`size` 不在 1..100 或 `page < 1` → `400`；`origin` 越界 → `400`。
- `origin` 只在选择器场景解析，管理列表不传时行为不变。
- `toolSummaries` 只在 `purpose=selection` 时下发，管理列表响应体不变。
- 非分页接口（不带 `page` 的 `purpose=selection`）行为完全不变，继续返回数组。

## 3. 后端设计

1. `access/WorkspaceAuthorization.java`：`purpose=selection` 分支不再对带 `page` 的请求直接 400；其余校验（`agent.manage`、owner 解析、`agentId` 与 `draftId` 互斥）保持不变。
2. `tool/AdvisorToolController.java`：
   - 分页 `tools` / `mcp-servers` 识别 selection：owner 取 `WorkspaceAuthorization.owner(...)`，`admin=false`，返回项 `canEdit=false`；
   - `serverView` 增加选择器分支，输出 `toolSummaries`（管理列表不传该字段）。
3. `tool/ToolCatalog.pageTools`：增加 `origin` 白名单条件（逗号分隔，逐个占位符绑定）。
4. `skill/AdvisorSkillController.java`：分页 `skills` 识别 selection，`admin=false`、归属取 selection owner、强制 `status=published`、`canEdit=false`。
5. `knowledge/AdvisorKnowledgeController.java`：分页 `knowledge` 识别 selection，归属取 selection owner、`admin=false`、`canEdit=false`。
6. 复用 `JdbcPage`（`fetch`/`validate`）与 `PageResult`，不新增分页机制。

## 4. 前端设计

1. `components/usePagedList.ts` 新增 `extra` 静态查询参数（`purpose=selection` 与 `agentId`/`draftId`），并入请求参数与 query key；管理列表既有调用不受影响。
2. 四个选择器改用 `TableToolbar`（草稿输入，按钮/回车提交）+ `DataTable`（表头、统一 loading/error/空态、首列勾选列、标准分页 10/20/50）：
   - 技能：名称（含已发布标记）、说明；
   - 知识库：名称与说明、已就绪文档数；
   - 工具子栏：名称、方法、状态（待配置/已禁用）、说明，`origin=http,declared`；
   - MCP 子栏：`renderRow` 渲染服务行（整服务勾选）与缩进的工具行（单工具勾选），数据来自分页服务列表的 `toolSummaries`；
   - 创建页：同一套写法，`draftId` 作为归属上下文。
3. 列表下方内容原样保留：已选用计数（移入 `TableToolbar` 右侧）、保存按钮（保存技能 / 保存知识库 / 保存选用）、保存成功提示。`useAgentEditor` 的脏检查、保存、放弃链路不变。
4. 工具可选性规则不变：`declared` 不可选；未审批或 `permission=deny` 未选中时禁用；服务被勾选时其工具行禁用且视为已选。
5. 文案全部经 `t()`，新增词条同时补 `zh-CN` 与 `en`（`i18n.test.ts` 校验键对齐、插值一致、无空值）。
6. 不新增共享"表格框架"，不新增依赖；差异数据形状直接使用 `DataTable` 的 `columns` 与 `renderRow`。

## 5. 兼容性

- 非分页 `purpose=selection` 接口与其权限校验保持原样，旧测试（`WorkspacePermissionTest`）必须继续通过。
- 管理列表分页接口新增参数均为可选，不传时响应体与排序不变。
- `McpServer.toolSummaries` 为新增可选字段，既有消费方（工具/MCP 管理页）不受影响。
- 前端管理列表页面（工具/MCP、模型、技能、知识库、文档、用户、角色）不在本次改动范围。

## 6. 测试方案

后端（新增 `access/SelectionPaginationTest.java`，MockMvc + H2）：

- `agent.manage` 账号请求 `tools?purpose=selection&page=1&size=10&agentId=...`：返回分页封装、只含该智能体所有者的工具、`canEdit=false`。
- 选择器分页不再返回 400；`size=101` 返回 400；`origin=unknown` 返回 400。
- 技能选择器只返回已发布；知识库选择器只返回该所有者可见的库。
- MCP 服务选择器返回 `toolSummaries`，含 `toolId`、`permission`、`approved`。
- 无 `agent.manage` → 403；他人智能体 → 404；非分页选择器响应不变。
- `ManagementPaginationTest` 增加 `origin` 过滤用例。

前端（Vitest）：

- `features/agent/components/AgentSections.test.tsx`：三个选择器渲染标准表格表头；搜索经 `TableToolbar` 提交后带 `q` 与 `purpose=selection` 重新请求；分页按钮提交 `page`；空结果提供清除条件；保存按钮与保存提示仍在列表下方。
- `features/agent/AgentManagement.test.tsx`：分页选择器请求纳入 `api.get` mock，保留"未保存勾选保护"与"保存中禁用"用例。
- `components/usePagedList.test.tsx`：`extra` 参数进入请求与 query key。
- 创建页：`CreationSettingsSection` 的分页请求纳入 mock。

## 7. 文档影响

- `.ai-spec/frontend/spec.md`：列表统一写法补充"选择器也必须复用 `DataTable` / `TableToolbar` / `Pagination`，禁止自建搜索栏与列表容器"。
- `docs/v2/zh/docs/others/technical-advisor-login.md` 与 `docs/v2/en/docs/others/technical-advisor-login.md`：智能体页签与列表格式章节写明选择器用同一套组件、服务端分页、选择器接口扩展。
- `docs/v2/zh/docs/others/technical-advisor-login.md` 中「旧无分页接口保留给现有选择器兼容使用」一句按新事实修正为"旧的数组接口保留兼容，页面改用分页接口"。

## 8. 风险与回滚

- 风险：把管理列表分页路由改坏。缓解：selection 分支全部走 `WorkspaceAuthorization.selection(request)` 判定，非 selection 路径代码不动，管理列表测试与前端 7 类列表测试全量回归。
- 风险：MCP 工具级勾选退化。缓解：`toolSummaries` 提供 `toolId`/`approved`/`permission`，勾选语义与禁用规则保持不变，并有单测覆盖。
- 回滚：本次改动集中在一个提交内；回滚该提交即可恢复，非分页接口未被移除。

## 9. 不做的事

- 不改共享组件本体，不新增表格框架或依赖。
- 不改 `AgentDetailPage` 页签机制、`agentEditGuard`、会话与聊天链路。
- 不删除非分页选择器接口（保留兼容）。
- 不调整管理列表的列、筛选与权限。
