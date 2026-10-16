# 用户中心（个人设置）实施计划

依据 `docs/superpowers/specs/2026-10-16-account-center-design.md`。改动分级：中级。
当前界面决策以用户最新确认和§10为准：保留应用导航，内容区内五分区设置；移除关于。下文原六分区与侧边栏替换为历史方案。
**并行开发前提**：另一会话正在开发权限管理（`access/**` 与 `features/access/*`）。本计划按"低冲突优先"排序，共享文件最后处理。

## 1. 改动范围与归属

### 本轮后端实施补充（2026-10-16）

用户授权先完成Phase 1后端接口；前端及浏览器验收留待后续。已重新核实test-compile通过，第一批AccountStoreTest/AuthControllerTest共13项通过，旧编译阻塞已解除。

- 安全分区已有“退出其他设备”需求，补齐契约：POST /api/me/sessions/revoke-others，无请求体，成功204；只撤销本人其他登录，保留当前会话。密码修改与撤销其他会话在同一数据库事务内完成。
- 默认模型沿用现有SessionModelController可用范围：有chat.use可选择当前聊天模型；其他聊天模型仅向现有settings.manage管理范围开放，不通过个人设置扩大模型选择权。默认智能体须有chat.use并通过现有AgentStore可见性检查；staging与他人不可见目标均拒绝。
- 概览按当前逐操作权限判断模块可见性（domain.view或既有manage兼容），无权限数量返回null、凭据列表为空；即使管理员也只统计本人资源。前端如何隐藏null项仍留Phase 2决定。
- 头像三种格式均校验内容和尺寸，WebP使用受限RIFF/帧头校验尺寸，不增加图像解码依赖；PNG/JPEG使用ImageIO。文件路径由服务端生成，读取与清理重新核验归属及符号链接。
- /about复用框架现有Version来源，不硬编码版本。用户操作说明按后端已交付范围追加到中英docs/v2，不宣称个人设置页面已接入。
- 一次性Think迁移补齐后端契约：GET/PUT preferences响应增加initialized（是否已有服务端偏好）；迁移时PUT携带If-None-Match: *，同事务内仅在偏好未初始化时写入，已初始化返回412。正常编辑不带该头。避免“默认false”和“用户已明确保存false”无法区分，也避免多设备旧本地值覆盖服务端值；前端实际迁移留Phase 2接入。

后端完成条件：第二批真实HTTP回归先取得404等缺失功能RED，再实现；覆盖身份隔离、验证失败无副作用、头像替换/删除、偏好部分更新、概览与凭据保密、版本来源；定向与模块测试及格式检查取得实际结果并记录。

**我独占（全新文件）**

- 后端：`account/AccountSelfServiceController.java`、`account/AvatarStore.java`、`account/AccountPreferencesStore.java`、`src/test/.../account/AccountSelfServiceTest.java`
- 前端：`src/features/account/**`（`SettingsSidebar`、`AccountPage`、六个分区组件与测试）、`src/api/account.ts`、`src/hooks/useAvatar.ts`
- 文档：本计划、设计文档、`docs/v2` 个人设置章节（追加）

**我改动但对方当前未碰**

- `auth/AccountStore.java`（加校验当前密码、撤销其他会话、仅改昵称、头像路径）
- `auth/AccountSelfService` 相关配置；`auth/ApiAuthenticationFilter.java`（保护前缀加 `/api/me`）
- `auth/AuthController.java`（`/api/auth/me` 扩展昵称/头像字段）

**共享文件（只追加、最后动）**

- `db/AdvisorSchema.java`：追加 `accounts.avatar_path`、`accounts.avatar_updated_at`、新表 `advisor_account_preferences`
- `app/AppShell.tsx`：只加"用户菜单项 + 设置侧边栏分支"
- `main.tsx`：只加 `/account` 子路由
- `i18n/locales/{zh-CN,en}/app.json`：定点插入 `user.settings` 与 `account.*`
- `docs/v2/{zh,en}/docs/others/technical-advisor-login.md`：追加章节

**明确不碰**

- `access/**`、`features/access/*`（对方在改；我只只读调用 `AccessGuard.allowed/granted` 与现有 `AccessPoints.*`）
- `tool/ToolCatalog.java`、各模块 Controller（对方正在加权限校验；我复用其现有分页方法读取本人资源计数）
- 共享组件本体（`DataTable`/`TableToolbar`/`DialogFooter`/`Pagination`）

## 2. 实施顺序

**Phase 1 · 后端（除 `AdvisorSchema` 的幂等追加外不碰共享文件）**

> 顺序修正（2026-10-16 自审）：本段原先写"不碰共享文件"却又列出 `AdvisorSchema` 步骤，自相矛盾。现在的口径是：`AdvisorSchema` 允许**只追加**、且必须与对方语句分区放置；其余共享文件（`AppShell`/`main.tsx`/i18n/`docs/v2`）仍留到 Phase 3。
> 红绿顺序修正：`AccountSelfServiceTest` 必须**先写并确认失败**，再实现控制器与两个 store；第一批（`AccountStore`/`/api/auth/me`）当时是先实现后补测，属偏离，补验方式见第 6 节。

1. `AccountStore` 增量：`verifyPassword`、`revokeOthers(userId, keepToken)`、`setNickname`、`profile(userId)`、`setAvatar`/`clearAvatar`。
2. `AccountPreferencesStore`：`read` / `write`（`thinkEnabled`、`defaultModelId`、`defaultAgentId`）。
3. `AvatarStore`：类型白名单（PNG/JPEG/WebP）、≤512KB、PNG/JPEG 用 `ImageIO` 校验边长 ≤1024、服务端生成文件名、替换删旧、读取/删除。
4. `AdvisorSchema` 追加列与表（幂等、只追加）。
5. `ApiAuthenticationFilter` 保护前缀加 `/api/me`。
6. `AccountSelfServiceTest`（MockMvc + H2）**先写红**，覆盖契约矩阵，含 `/api/me/*` 无 token 401 与 `/api/advisor/models/active` 仍公开。
7. `AccountSelfServiceController`：`PUT profile`、`PUT password`、`POST/GET/DELETE avatar`、`GET/PUT preferences`、`GET overview`、`GET about`；`AuthController./api/auth/me` 扩展 `nickname`/`avatarUrl`/`avatarUpdatedAt`。

**Phase 2 · 前端（全新目录）**

> 并行集成注意（2026-10-16 自审发现）：对方正在 `WorkspaceAuthorization` 里引入**逐路由权限点**（`AccessPoints.operation(path, method, "agent")`，并出现 `agent.create` / `agent.edit` 这类新点）。因此个人中心的"权限概览"必须**按 `/api/auth/me.permissions` 动态渲染**，标签取自 i18n 且**未知权限点回退显示原始代号**，绝不硬编码现有九个权限点；"我的资源"计数也只按后端返回的可见性渲染，不自行推断权限名。

8. `api/account.ts`、`useAvatar`（blob → objectURL，token 不进 URL）。
9. `features/account/`：`SettingsSidebar`、`AccountPage`（子路由分发）与六个分区组件。
10. `AccountPage.test.tsx`、`useAvatar`/`api/auth` 用例。

**Phase 3 · 共享文件接入（逐文件核对 diff 后提交式修改）**

11. `AppShell.tsx`：`inAccount` 分支 + 用户菜单项。
12. `main.tsx`：`/account` 重定向与六个子路由。
13. i18n 定点插词（zh-CN 与 en 同步）+ `AppShell.test.tsx` 侧边栏替换用例。
14. `docs/v2` 中英追加「个人设置」章节；必要时补 `.ai-spec/frontend/spec.md`。

## 3. 测试方案

| 范围 | 命令 |
| --- | --- |
| 后端新用例 | `mvn -T1 -pl technical-advisor-app/backend -am test -Dtest=AccountSelfServiceTest` |
| 后端模块 + 格式 | `mvn -T1 -pl technical-advisor-app/backend -am test` + `spotless:check`（格式化只用 `-DspotlessFiles=` 限定本次文件） |
| 前端定向 | `npx vitest run src/features/account src/app/AppShell.test.tsx src/api` |
| 前端全量 | `npm test` |
| 前端 lint / 构建 | `npm run lint`、`npm run build` |
| E2E | 不启动浏览器（除用户另行要求） |

## 4. 文档影响

用户可见变化：新增个人中心与自助接口、`/api/auth/me` 增字段、头像上传、改密撤销其他会话 → 同步 `docs/v2` EN/ZH；`.ai-spec/frontend/spec.md` 若引入"鉴权图片"写法则补规则。

## 5. 步骤清单

当前执行更新：后端、前端及布局修订均已授权并执行。下方旧交接章节保留历史，当前界面与最终验证以§10为准，不再受旧ModelManagementTest编译阻塞。

- [x] 设计与 UI 草图（最新确认：`/account`保留全局导航、内容区五分区设置、宽版布局、移除关于）
- [x] Phase 1 后端第一批：`AccountStore`（校验密码 / 撤销其他会话 / 仅改昵称 / 头像路径 / profile）、`AdvisorSchema`（头像列 + 偏好表）、`ApiAuthenticationFilter`（`/api/me` 保护前缀）、`AuthController./api/auth/me` 扩展、`AccountStoreTest`/`AuthControllerTest` 用例
  - 第一批现已实际补跑13项通过；先实现后补测属于历史顺序偏离，不能把补跑说成已取得RED。
- [x] Phase 1 后端第二批：`AvatarStore`、`AccountPreferencesStore`、`AccountSelfServiceController`（profile/password/avatar/preferences/overview/about及退出其他设备）、`AccountSelfServiceTest`，接口缺失RED后实现GREEN
- [x] 后端模块与依赖verify完整结束（19模块SUCCESS，后端544项通过，含账号相关39项；零失败、零错误、零跳过）
- [x] Phase 2 前端组件与用例全绿（当前完整82个测试文件、715项通过）
- [x] Phase 3 共享文件接入（AppShell / 路由 / i18n / AppShell 用例）
- [x] 前端全量 test / lint / build 通过（lint 0错误、45警告）
- [x] `docs/v2` EN/ZH 同步
- [x] 自检 diff，确认未触碰对方文件
- [x] 汇报改动文件、检查结果与剩余缺口

## 6. 第一批历史记录（已由本轮补验更新）

**已落地（2026-10-16 第一批）**

- `auth/AccountStore.java`：`Profile` 记录 + `profile`、`verifyPassword`、`setNickname`（不动管理员备注）、`setAvatar`/清空、`revokeOthers(userId, keepToken)`。
- `db/AdvisorSchema.java`：`accounts.avatar_path`、`accounts.avatar_updated_at`、新表 `advisor_account_preferences`（幂等追加，未改他人语句）。
- `auth/ApiAuthenticationFilter.java`：保护前缀新增 `/api/me`。
- `auth/AuthController.java`：`/api/auth/me` 增 `nickname`、`avatarUrl`（`/api/me/avatar?v=<updated>`）、`avatarUpdatedAt`。
- 用例：`AccountStoreTest.selfServiceProfilePasswordAndSessionRevocation`、`AuthControllerTest.selfServiceRoutesRequireATokenAndReportTheProfileFields`。

**检查与结果**

| 检查 | 结果 |
| --- | --- |
| `mvn -T1 -pl technical-advisor-app/backend -am compile` | 通过（主源码） |
| `spotless:check`（本次文件） | 通过 |
| 后端测试执行 | **阻塞**：并发会话改了 `ModelCatalog.insert` 签名而 `ModelManagementTest` 未同步，模块 testCompile 失败；与本任务文件无关，需对方补完后重跑 |

**剩余缺口**

- Phase 1 第二批（头像/偏好/自助控制器）与 `AccountSelfServiceTest` 未开始；第二批按红绿顺序执行（先写测试确认失败）。
- 前端 Phase 2/3 未开始。
- 第一批用例受上述阻塞尚未执行；对方补完 `ModelManagementTest` 后立即补跑，若失败按 TDD 修正而不是改断言。
- 未 commit、未 push。

**规范自审与纠正（2026-10-16）**

| 项 | 结论 | 纠正 |
| --- | --- | --- |
| 契约先行（中级流程） | 遵守：设计文档 + 4 项决策确认后才写代码 | — |
| 计划先于编码（中级流程） | 遵守：`2026-10-16-account-center.md` 先于代码 | 计划自相矛盾处已修正 |
| 红绿顺序（TDD） | **偏离**：第一批先实现后补测，且未跑红 | 第二批起先写测试确认失败；第一批在阻塞解除后立即补跑 |
| 共享文件只追加 | 遵守：`AdvisorSchema` 仅追加语句，未改他人语句 | 修正计划里"不碰共享文件"的错误口径 |
| 格式化最小化 | 遵守：只用 `-DspotlessFiles=` 限定自己文件，未跑全量 `spotless:apply` | 后续把正则改为**显式文件路径**，避免误匹配 |
| 不 stash / 不 reset | 遵守：全程未使用 | — |
| i18n 定点插入 | 尚未涉及（Phase 3） | — |
| 未验证不宣称通过 | 遵守：完成记录明确写"未执行" | 把 checklist 状态从"完成"改为"已写未验证" |

## 7. 第一批历史交接（仅保留当时现场，不作为当前状态）

> 面向接手者。先读 `docs/superpowers/specs/2026-10-16-account-center-design.md`（契约 + UI 草图见其 §10）与本文件 §1–§3，然后照下面顺序做。**不要重读整仓、不要重做第一批。**

### 7.1 现场状态

- 分支：`feat/advisor-next`；**未 commit、未 push**，全部改动在工作区。
- **并行会话正在做权限管理**：改 `access/**`（`AccessCatalog`/`AccessGuard`/`AccessPoints`/`AdvisorAccessController`/`WorkspaceAuthorization`）与 `features/access/*`，并给 `WorkspaceAuthorization` 加逐路由权限点。**这些文件一行都不要动。**
- 工作区没有可回退的提交：**严禁** `git stash` / `checkout` / `reset` / `clean`，会连坐对方未提交的工作。

### 7.2 第一批已完成（代码已写，**用例未验证**）

| 文件 | 改动 | 位置 |
| --- | --- | --- |
| `auth/AccountStore.java` | 新增 `record Profile(userId, username, nickname, avatarPath, avatarUpdatedAt, createdAt)`；`profile(userId)`；`verifyPassword(userId, password)`（未知账号走 dummy hash，防时序泄漏）；`setNickname(userId, nickname)`（**不覆盖**管理员写的 `remark`）；`setAvatar(userId, path)`（传空串即清空并将 `avatar_updated_at` 归零）；`revokeOthers(userId, keepToken)`（按 `token_hash <> keepToken` 删除，**不是** `revokeAll`） | 42、229、256、275、286、300 行附近 |
| `db/AdvisorSchema.java` | 幂等追加 `accounts.avatar_path`、`accounts.avatar_updated_at`、新表 `advisor_account_preferences(user_id, think_enabled, default_model_id, default_agent_id, updated_at)` | 60–70 行附近 |
| `auth/ApiAuthenticationFilter.java` | 保护前缀新增 `"/api/me"`（无 token → 401） | 62 行 |
| `auth/AuthController.java` | `AccountResponse` 增 `nickname` / `avatarUrl` / `avatarUpdatedAt`；`me()` 读 `accounts.profile(...)`，`avatarUrl` = `/api/me/avatar?v=<avatarUpdatedAt>`，无头像为 `null` | 49–58、84 行附近 |
| `src/test/.../auth/AccountStoreTest.java` | 新用例 `selfServiceProfilePasswordAndSessionRevocation`：昵称/头像读写与清空、`remark` 不被覆盖、`verifyPassword` 正误、`revokeOthers` 只终止其他会话 | — |
| `src/test/.../auth/AuthControllerTest.java` | 新用例 `selfServiceRoutesRequireATokenAndReportTheProfileFields`：`/api/me/profile` 无 token → 401；`/api/auth/me` 返回 `nickname` 与 `avatarUrl` 前缀 | — |

设计文档与 UI 草图：`docs/superpowers/specs/2026-10-16-account-center-design.md`。

### 7.3 接手第一件事（严格按序）

1. **判断阻塞是否解除**：并发会话把 `ModelCatalog.insert` 改成 `(EndpointDraft, Integer, String, boolean)`，而 `model/ModelManagementTest.java` 仍用旧签名 → 模块 `testCompile` 失败。
   `mvn -T1 -pl technical-advisor-app/backend -am test-compile`
   - 仍失败且报错在**非本任务**文件：**不要修他们的文件**，先做 §7.4 前端，稍后再试。
   - 通过：进入下一步。
2. **补跑第一批用例**（补红→绿）：
   `mvn -T1 -pl technical-advisor-app/backend -am test -Dtest='AccountStoreTest,AuthControllerTest'`
   失败时**改实现、不改断言**（第一批属"先实现后补测"的已知偏离，见 §6 自审表）。
3. **第二批先写红测**：新建 `src/test/java/io/agentscope/advisor/account/AccountSelfServiceTest.java`（`@SpringBootTest` + H2 + MockMvc，照 `access/WorkspacePermissionTest` 写法），先覆盖并确认失败：
   - 昵称：改成功 / 清空 / 超 40 字 → 400；
   - 改密：当前密码错 → 400；新密码 <8 或 >256 → 400；成功后**其他会话 401、当前会话仍 200**；
   - 头像：类型不符 → 400；>512KB → 400；上传后 `GET /api/me/avatar` 200 且 `Content-Type` 正确；删除后 404；
   - 偏好：默认值、部分更新、`defaultModelId`/`defaultAgentId` 不可用 → 400；
   - 概览：只统计本人归属，无权限模块为 `null`，凭据清单**不含密文**；
   - 安全：`/api/me/*` 无 token → 401；`/api/advisor/models/active` 仍公开。
4. **第二批实现**（顺序即依赖顺序）：
   `account/AccountPreferencesStore.java` → `account/AvatarStore.java`（类型白名单 PNG/JPEG/WebP、≤512KB、PNG/JPEG 用 `ImageIO` 校验边长 ≤1024、服务端生成 `data-dir/avatars/<userId>/<uuid>.<ext>`、替换删旧、读取/删除）→ `account/AccountSelfServiceController.java`（`PUT /api/me/profile`、`PUT /api/me/password`、`POST|GET|DELETE /api/me/avatar`、`GET|PUT /api/me/preferences`、`GET /api/me/overview`、`GET /api/me/about`）。
   - 控制器可 `new AvatarStore(...)` / `new AccountPreferencesStore(...)`（照 `AdvisorToolController` 的写法），**不要改 `AdvisorConfiguration`**。
   - 计数与凭据走现有分页方法按 `owner_user_id` 读取（`SkillCatalog.page`、`KnowledgeCatalog.page`、`ToolCatalog.pageTools`、`AgentStore`）；**不要给 `ToolCatalog` 加方法**（对方在改它）。
5. 后端绿了再进前端 Phase 2（全新目录）与 Phase 3（共享文件）。

### 7.4 前端接手要点

- Phase 2 全部落在新文件：`src/api/account.ts`、`src/hooks/useAvatar.ts`、`src/features/account/**`。头像用 `apiResponse` 取 blob → `URL.createObjectURL`，**token 不进 URL**，卸载时 `revokeObjectURL`。
- Phase 3 才动共享文件且**只追加**：`app/AppShell.tsx`（`pathname.startsWith('/account')` 时用 `SettingsSidebar` 替换应用侧边栏；用户菜单加「个人设置」）、`main.tsx`（`/account` 重定向 + 六个子路由）、`i18n/locales/{zh-CN,en}/app.json`（**定点文本插入**，禁止脚本重排整文件，两边都要过 `i18n.test.ts`）、`docs/v2/{zh,en}/docs/others/technical-advisor-login.md`（追加章节）。
- 权限展示必须**动态**：渲染 `/api/auth/me.permissions`，未知权限点回退显示原始代号（对方已在引入 `agent.create` / `agent.edit` 等新点）。

### 7.5 纪律（已踩过的坑）

- 格式化只作用于自己的文件，且用**显式路径**，不要无锚正则：
  `mvn -q -pl technical-advisor-app/backend spotless:apply "-DspotlessFiles=.*AccountStore\.java"`
- **不要**跑全量 `spotless:apply`（会重排对方正在写的代码）。
- 门禁失败先判归属：报错文件不属于本任务就如实记录并继续，不要顺手"修"他们的代码。
- 汇报必须含：改动文件 / 检查与结果 / 剩余缺口；未执行的测试要明确写"未执行"。

## 8. 本轮后端交付结果（2026-10-16）

Phase 1后端已完成并验证，Phase 2前端与Phase 3页面接入未执行。详细文件、接口与验证记录见`technical-advisor-app/docs/account-center-backend-results-2026-10-16.md`。

- 第一批13项补验通过；第二批接口缺失RED为14项中13项404失败，之后实现并验证。
- 已交付资料、改密、退出其他设备、头像上传/读取/删除、偏好读取/部分更新、本人资源概览、版本信息；已有`/api/auth/me`增加资料字段。
- 独立只读审查发现的改密与旧密码登录竞争、头像缓存身份隔离、动画WebP兼容均已补回归并修复。
- Think一次性迁移增加initialized及If-None-Match条件创建；并发首次写入只能一个成功，部分编辑不丢字段，已保存false不能被旧本地true覆盖。
- `mvn -B -T1 -pl technical-advisor-app/backend -am verify -Dnet.bytebuddy.experimental=true`实际通过，19模块SUCCESS；后端544项、零失败/错误/跳过。真实pgvector使用隔离测试库，结束后已停止。
- Spotless检查在verify中通过；只格式化本任务显式文件。中英接口说明已同步。文档脚本12项通过，全站check仍为237项既有Service导航/链接缺失，未修改无关导航。
- 未编写前端、未启动浏览器、未执行真实模型/外部MCP、未重启应用、未提交或部署。偏好现在只提供保存接口，前端应用到新会话仍待后续阶段。

## 9. 前端实施接续（2026-10-16）

用户已授权继续前端阶段。按既有六分区设计先实现独立目录，再逐文件接入AppShell、路由、身份头像和词典；完成可访问的前端交付包含必要的Phase 3集成，不提前重启或部署。

- 关闭设置前先保留进入来源；分区切换使用replace并传递来源，避免关闭只退到上一个设置分区。直接打开设置回/chat；移动端使用返回与分区下拉。
- 权限概览动态读取me.permissions，已知词条翻译，未知代号原样显示；资源null项隐藏。模型选择复用现有模型接口，普通用户只取公开active摘要，管理范围读取聊天模型清单；智能体使用agents/available，不扩权。
- 服务端状态使用按账号隔离的Query key；头像仅鉴权blob和objectURL，切账号不复用，卸载/替换释放URL。昵称失焦/回车保存，失败留输入；密码与删除确认使用现有组件。
- Think首次迁移使用initialized和If-None-Match:*，412重新读取；服务端值优先。默认模型与智能体仅应用新会话，显式单次选择优先，不覆盖已有会话。语言复用现有浏览器偏好。
- 最近会话复用既有接口，默认显示10条；当前没有独立“会话列表页”，查看全部在本分区展开，不虚构路由。关于文档只链接真实可用文档站地址，版本来自package.json构建注入及/me/about。
- 验证：先取得入口与分区的行为RED，再补资料/改密/头像/偏好迁移/默认项/资源/会话测试；完整npm test、lint、build与diff检查。用户要求非必要不启动浏览器，本轮优先组件/接口自动化，未做视觉与E2E验收须明确记录。

前端阶段初次交付82文件709项通过；布局第一轮后82文件713项通过。当前最终布局与新增回归结果以§10为准。

## 10. 用户确认的布局修订

后续交互修订以§11最新需求为准；用户已将分隔线决定改回保留，权限概览改为折叠状态树，并要求展示真实账号及角色名称。

用户要求复用现有组件、放宽内容并删除关于；已确认的UI草图以本节覆盖旧侧边栏替换方案。

- 全局AppShell侧边栏保持，个人设置在主内容内提供五分区二级导航。
- 使用原有Page的1440px最大容器，PageHeader提供统一页面标题与说明，Card承载当前分区；二级导航192–224px，内容自适应剩余宽度，不再额外限制在768px。字段控件最大560px，行分隔线、输入、按钮、下拉和表格沿用现有组件。
- 手机端分区下拉与全局导航分开，内容单列；旧/account/about重定向资料页，移除About页面、词条、前端API封装和仅为其提供的版本注入。
- 历史GET /api/me/about后端接口暂保留兼容；个人设置不再请求或展示版本。本次不改变后端账号功能。
- 核验现有保存、密码、头像、偏好与会话交互，执行前端测试、lint/build及文档脚本。未启动浏览器不代表视觉验收已完成。

最终验证：wide-final-tests.log完整82文件715项通过，wide-final-build.log构建通过，wide-final-lint.log为0错误45警告。wide-shared-doc-tests.log文档脚本12项通过；git diff --check通过。日志位于/tmp/account-center-frontend-20261016。

只读布局复核发现设置内再次点击全局“个人设置”会叠加历史，已用真实AppShell+AccountPage路由回归复现并修复：设置内入口replace并保留原进入状态，返回业务来源。reentry-red.log为返回后仍在设置，reentry-green.log两文件23项通过，修复已纳入最终全量。未运行浏览器、视觉或E2E验收，未重启部署或提交。

## 11. 资料与交互精简（本轮授权）

- 移除个人设置大标题、副标题和头像常驻描述；应用顶部只显示左箭头返回，不加入面包屑和语言按钮；语言仍可在偏好分区调整。
- Page复用新增可选fill布局，默认页面不变；个人设置右卡在可用视口内固定高度，标题不滚动，内容独立滚动并可键盘聚焦。保留全局导航和内容宽度。
- 设置项分隔线按用户最新要求恢复浅色细线。昵称输入缩短，右侧保存按钮显式提交，回车可保存，失焦不再提交。
- 头像限制从512 KiB/1024px放宽到10 MiB/8192px，前后端一致；PNG/JPEG按尺寸采样验证，避免为检查大图分配完整像素内存；UI只在校验失败时提示规则。
- 权限概览复用现有PermissionTree，增加readOnly展示分支，模块默认折叠、显示真实已授权数量，叶子显示已授权状态；未知点保留代号，不虚构未返回的权限。原角色授权编辑分支不变。
- “登录名”改为“账号”，继续读取与用户管理共用的accounts.username；GET /api/auth/me增加roleNames，使用既有AccessCatalog.roleIds/findRole获取当前有效关联角色的管理名称。保留原roles字段用于兼容身份判定，不将名称当权限代号。资料页重新进入/窗口重新聚焦时重新读取。
- 会话支持删除当前完整清单（非仅最近10项），确认框显示数量，取消不发删除。复查本人清单后仅处理确认ID，新建会话不纳入；切换token停止后续删除；部分失败刷新剩余行并支持重试。
- 原会话DELETE无运行状态条件，不能证明并发安全。本轮新增DELETE /api/me/sessions/{sessionId}（需chat.use，204成功、404不可见/不存在、409 sessions_running），复用SessionStore删除逻辑的新增条件模式，将owner和非running条件放在同一次DELETE，避免确认后启动的会话被删除。旧删除接口行为兼容；不改session其他正在开发逻辑。
- 覆盖显式保存、树折叠、批删确认/取消/部分失败/账号切换、头像扩容边界、账号改名及角色重命名、运行中删除拒绝与归属隔离；不操作真实用户会话、不启动浏览器。

最终证据：refinement-final-tests.log前端82文件726项通过，refinement-final-lint.log为0错误45警告，refinement-final-build.log构建通过；roles-session-green.log相关后端42项通过，refinement-backend-verify.log后端完整551项零失败/错误/跳过，Spotless与打包通过。临时PostgreSQL端口55437已停止，未更新应用运行库或重启服务。refinement-doc-tests.log文档脚本12项通过，git diff --check通过。以上日志均在/tmp/account-center-frontend-20261016。
