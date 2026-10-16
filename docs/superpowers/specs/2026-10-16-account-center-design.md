# 用户中心（个人设置）设计

2026-10-16界面修订：用户已确认保留全局应用侧边栏，个人设置导航放入主内容区，使用现有Page/PageHeader/Card/Button组成宽版工作区；删除关于，仅保留资料、安全、偏好、我的资源、会话五分区。沿用Page的1440px最大容器，导航192–224px，表单控件最大560px，窄屏堆叠。此决定覆盖本文原先“整体替换侧边栏/六分区”的历史设计，当前实施记录见计划§10。

| 项 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 编制日期 | 2026-10-16 |
| 文档状态 | 待评审 |
| 依据 | 现有账号/权限/RBAC 实现、会话与附件下载先例、前端 Page/分组导航写法 |
| 改动分级 | 中级（跨前后端、新增内部接口与存储、用户可见行为变化） |
| 已确认决策 | 承载形态=路由页 `/account`，且**进入后由设置侧边栏整体替换应用侧边栏**（ChatGPT 式）；头像=文件上传 + 鉴权端点；附加项=全选；改密=撤销其他设备会话 |

---

## 1. 现状

- 侧边栏底部只有「首字母头像 + 用户名 + 退出登录」，没有用户中心（`app/AppShell.tsx`）。
- `accounts` 表已有 `nickname`、`remark`、`status`、`builtin`，**没有头像**；`AccountStore` 已有 `updateProfile`、`setPassword`、`revokeAll`，但**只有管理员**能通过 `PUT /api/advisor/users/{userId}`（需 `rbac.manage`）改别人。
- `GET /api/auth/me` 只返回 `userId/username/roles/permissions/isAdmin/createdAt`。
- 认证过滤器只保护 `/api/advisor/**`、`/api/auth/me`、`/api/auth/logout`（`auth/ApiAuthenticationFilter.java`）。
- 已有可复用能力：鉴权下载先例 `GET /api/advisor/sessions/attachments/{attachmentId}/content`；会话列表 `GET /api/advisor/sessions` 与删除 `DELETE /api/advisor/sessions/{sessionId}`；工具视图已派生 `hasCredential`；`login_sessions` 按 `token_hash + user_id` 存储，撤销按用户整表删除。
- 语言偏好已在顶栏（`localStorage`）；「Think 思考过程」开关存浏览器本地（`features/chat/conversationModel.ts`）；会话级选模有，**个人默认没有**。

## 2. 入口与信息架构

- 入口：侧边栏用户菜单新增「个人设置」（`user.settings`），点击进入 `/account`。
- **侧边栏整体替换（关键交互）**：处于 `/account/*` 时，`AppShell` 不再渲染应用侧边栏，改为渲染**设置侧边栏**——顶部一行「设置 / Settings」+ 右上角关闭（X，语义为「返回应用」），下面六个分区项，底部不再显示用户菜单与折叠按钮。关闭或浏览器后退即恢复应用侧边栏与进入前的页面。
- **路由**：`/account` 重定向到 `/account/profile`；分区用子路由：
  `/account/profile`、`/account/security`、`/account/preferences`、`/account/assets`、`/account/sessions`、`/account/about`；未知分区重定向到 `profile`。子路由保证可深链、可刷新、可分享，侧边栏高亮由当前子路由决定。
- 右侧主体由 `AppShell` 的既有 `<main><Outlet/></main>` 承载，应用顶栏（面包屑 + 语言切换）保留：面包屑显示「个人设置 / <分区名>」。
- 权限：**不挂任何权限点**，登录即可；与 `/settings`（模型设置，需 `settings.manage`）严格分开。
- 移动端：设置侧边栏折叠为顶部一行——`← 返回` + 「个人设置」标题 + 分区下拉（`AppSelect`）。

## 3. API 契约

自助命名空间 `/api/me/*`，全部要求已登录（无 token → 401），不需要任何权限点。

| 方法与路径 | 请求 | 响应 / 错误 |
| --- | --- | --- |
| `GET /api/auth/me` | — | 扩展返回：`nickname`、`avatarUrl`（无头像为 `null`）、`avatarUpdatedAt`；其余字段不变 |
| `PUT /api/me/profile` | `{nickname}` | 200 返回更新后的 me；昵称去空白、最长 40 字、允许清空；超长 400 |
| `PUT /api/me/password` | `{currentPassword, newPassword}` | 204；当前密码不符 400 `current_password_mismatch`；新密码不足 8 或超 256 → 400 `weak_password`；成功撤销该用户其他会话，当前会话保持有效 |
| `POST /api/me/sessions/revoke-others` | — | 204；只撤销本人其他设备会话，当前会话保持有效 |
| `POST /api/me/avatar` | multipart `file` | 200 `{avatarUrl, avatarUpdatedAt}`；仅 PNG/JPEG/WebP、≤512KB、边长 ≤1024；不合法 400 `invalid_avatar`；替换时删除旧文件 |
| `GET /api/me/avatar` | — | 200 图片字节 + `Content-Type` + `Cache-Control: private, max-age=86400` + `Vary: Authorization`；无头像 404 |
| `DELETE /api/me/avatar` | — | 204，回落首字母头像 |
| `GET /api/me/preferences` | — | `{thinkEnabled, defaultModelId, defaultAgentId, initialized}`，未设置时分别为 `false`、`""`（跟随系统）、`""`（自由模式）、`false` |
| `PUT /api/me/preferences` | 前三个字段的可选子集 | 200 返回完整偏好及`initialized=true`；不可用的模型/智能体或错误类型400；迁移时可携带`If-None-Match: *`，已有偏好则412且不改数据 |
| `GET /api/me/overview` | — | `{agents, skills, knowledge, tools}`：本人归属数量；账号无该模块权限的项为 `null`（前端显示「—」）。凭据清单：`credentials: [{toolId, name, origin, configured}]`，**只回配置状态，绝不回密文** |
| `GET /api/me/about` | — | `{name, backendVersion}`（后端取 Maven `${revision}`；前端版本由构建注入，不经后端） |

复用、不新增：会话列表与删除沿用 `/api/advisor/sessions`。

**认证过滤器改动（安全敏感）**：把 `/api/me` 加入 `ApiAuthenticationFilter.shouldNotFilter` 的保护前缀清单；必须补「无 token / 过期 token → 401」用例，并回归确认未误伤既有公开路径（`/api/advisor/models/active`）。

## 4. 存储与迁移

`AdvisorSchema` 增量（幂等 `ADD COLUMN IF NOT EXISTS` / `CREATE TABLE IF NOT EXISTS`）：

- `accounts.avatar_path TEXT NOT NULL DEFAULT ''`、`accounts.avatar_updated_at BIGINT NOT NULL DEFAULT 0`
- 新表 `advisor_account_preferences (user_id TEXT PRIMARY KEY, think_enabled INTEGER NOT NULL DEFAULT 0, default_model_id TEXT NOT NULL DEFAULT '', default_agent_id TEXT NOT NULL DEFAULT '', updated_at BIGINT NOT NULL)`
- 头像文件：`advisor.data-dir/avatars/<userId>/<uuid>.<ext>`，替换即删旧；路径由服务端生成，不接受客户端文件名。

**Think 偏好迁移**：登录后以服务端偏好为准；若`initialized=false`且浏览器本地已有值，则以`If-None-Match: *`条件请求一次性写入。服务器在账号行锁内检查偏好是否存在；已有值返回412，客户端重新读取而不覆盖。服务端已保存false与尚未初始化可区分；前端迁移接入属于Phase 2。

**默认项可用范围（后端实施补充）**：模型沿用现有会话选择范围，有chat.use可引用当前聊天模型，其他聊天模型还须有settings.manage管理范围；embedding不能作聊天默认。智能体须有chat.use，按现有AgentStore归属与管理范围可见，staging不可选。只保存个人默认，不改全局模型或已有会话。

**语言**：沿用现有 `localStorage`（同一份值），设置页复用 `LanguageSwitcher`，**不落库**——跨设备一致需要额外字段与同步策略，收益低；此项取舍写进文档。

## 5. 前端设计

- **`AppShell` 分叉**：`const inAccount = pathname.startsWith('/account')`。为真时渲染 `SettingsSidebar`（新组件），否则渲染现有应用侧边栏；顶栏与 `<main>` 不变。侧边栏宽度与现有折叠宽度解耦，设置侧边栏固定 ~240px（移动端整屏）。
- 新增 `features/account/`：`SettingsSidebar.tsx`、`AccountPage.tsx`（按子路由渲染分区）与分区组件 `ProfileSection`、`SecuritySection`、`PreferencesSection`、`AssetsSection`、`SessionsSection`、`AboutSection`。
- 路由：`main.tsx` 用嵌套路由声明六个分区；`/account` 重定向 `profile`；关闭按钮 `navigate(-1)` 且无历史时回退 `/chat`（用 `location.key === 'default'` 判定）。
- 新增 `api/account.ts`：profile / password / avatar / preferences / overview / about 的请求函数与类型。
- `api/auth.ts`：`MeResponse` 增加 `nickname`、`avatarUrl`、`avatarUpdatedAt`。
- 头像鉴权：`<img>` 不能带 Authorization，前端用 `apiResponse` 取 blob → `URL.createObjectURL`，封装为 `useAvatar()`（卸载时 revoke，刷新后按 `avatarUpdatedAt` 重新取）；**token 绝不进 URL**。
- 用户菜单与 i18n：`user.settings` + `account.*` 词条（zh-CN 与 en 同步）。
- 保存反馈、失败保留输入、确认弹窗复用现有组件（`DialogFooter`/`ConfirmActionDialog`/`Button`），不新造样式。

## 6. 测试方案

后端（新增 `account/AccountSelfServiceTest`，MockMvc + H2）：

- 昵称修改成功 / 清空 / 超 40 字 400；
- 改密：当前密码错 400、新密码过短 400、成功后**其他会话 401 且当前会话仍 200**；
- 头像：类型不符 400、超 512KB 400、上传后 `GET /api/me/avatar` 200 且 `Content-Type` 正确、删除后 404/204；
- 偏好：默认值、部分更新、引用不可用模型/智能体 400；
- 概览：只统计本人归属，无权限模块为 `null`，凭据清单不含密文；
- 安全：`/api/me/*` 全部在无 token 时 401；`/api/advisor/models/active` 仍公开。

前端（`.test.tsx`）：

- `AccountPage`：分区切换、昵称保存与校验、改密校验与"退出其他设备"提示、头像上传/删除、偏好保存、资源数量与凭据清单渲染、会话列表打开/删除、关于版本显示；
- **`AppShell.test.tsx`（侧边栏替换）**：进入 `/account/*` 后应用导航（工作空间 / 访问控制）消失、设置侧边栏出现且当前分区高亮；点 ✕ 或浏览器后退恢复到进入前的页面与应用侧边栏；无历史直接打开 `/account` 时 ✕ 回 `/chat`；移动端显示「返回 + 分区下拉」；用户菜单出现「个人设置」并可跳转；
- `api/auth` 与 `useAvatar`：me 字段解析、blob → objectURL 与 revoke。

门禁：后端 `mvn -T1 -pl technical-advisor-app/backend -am test` + `spotless:check`；前端 `npm test`、`npm run lint`、`npm run build`；E2E 不启动浏览器（除非你另行要求）。

## 7. 文档影响

- `docs/v2/{zh,en}/docs/others/technical-advisor-login.md`：新增「个人设置」章节（入口、六分区、改密影响、头像规则、偏好默认值、语言不落库的取舍）。
- `docs/superpowers/plans/`：实施计划与完成记录。
- `.ai-spec/frontend/spec.md`：若引入"鉴权图片"写法，补充规则条目。

## 8. 风险与回滚

- **`/api/me` 加入认证保护前缀**：安全敏感，必须有 401 用例；实现只增前缀，不动既有条目。
- **撤销其他会话**：按 `token_hash <> 当前` 删除，切勿用整表 `revokeAll`（会把当前会话一起踢掉）；用例覆盖。
- **头像文件**：服务端生成文件名与目录，限制类型与体积，删除旧文件避免堆积；路径不接受客户端输入，防目录穿越。
- **偏好迁移**：只做一次，避免本地值与服务端来回覆盖。
- **侧边栏替换**：`AppShell` 已按 `onChat` 对聊天页做全屏处理，`/account` 必须走普通顶栏且不能继承 `onChat` 的透明绝对定位；用例覆盖"聊天页 → 个人设置 → 返回聊天页"时布局与跳转都正确。
- 回滚：本次改动集中在一个提交内；新增列/表幂等，回滚代码后旧数据不受影响。

## 9. 不做的事

- 不做主题/暗色、消息通知、数据导出、二步验证（无基建，另立项）。
- 不改 RBAC 用户管理页的既有行为与权限点。
- 不把语言偏好落库，不做跨设备同步。
- 不动模型设置（`settings.manage`）的权限边界。

## 10. UI 草图与交互（越简单越好，参照 ChatGPT 设置页）

### 10.1 设计原则

1. **一个分区一屏**：左列只放分区名，右侧只放该分区的内容，不做分区内再分页签。
2. **标签在左、控件在右**：一行一个设置，行高一致，次级说明用 12px 灰字，不写大段说明。
3. **即时保存**：能立刻生效的就立刻生效（Think、默认模型、默认智能体、昵称失焦/回车），只在"密码""删除头像"这类不可逆操作上要按钮与确认；保存结果用行内 12px 提示（成功绿、失败红），不弹 toast 堆叠。
4. **没有装饰**：不加卡片阴影、不加插画、不用大标题；分区标题就是一行 14px 加粗文本。
5. **危险操作最小化**：只有"删除头像"是危险动作，用既有确认弹窗（`ConfirmActionDialog`）。
6. **移动端**：左列折叠为顶部分区下拉（`AppSelect`），其余布局不变。

### 10.2 入口与侧边栏整体替换

```
应用侧边栏（现状）                      设置侧边栏（进入 /account 后）
┌────────────────────────┐            ┌────────────────────────┐
│  ◆ DeepScope      ⇤    │            │  设置                ✕ │  ← ✕ = 返回应用
│                        │            │                        │
│  ⌂ 首页                │            │  资料                  │  ← 当前项浅底加粗
│  ─ 工作空间            │   ──▶      │  安全                  │
│  ⬡ 智能体              │  点击       │  偏好                  │
│  ▤ 工具与 MCP          │ 「个人设置」 │  我的资源              │
│  ✦ 技能                │            │  会话                  │
│  ▤ 知识库              │            │  关于                  │
│  ─ 偏好设置            │            │                        │
│  ⚙ 模型设置            │            │                        │  ← 不显示用户菜单/折叠
│  (A) 张三              │            │                        │
└────────────────────────┘            └────────────────────────┘
        ▲ 浏览器后退 / 点 ✕ 即恢复原侧边栏与进入前的页面
```

交互细节：

- 进入方式：用户菜单「个人设置」→ `navigate('/account')`（默认落在 `profile`）。
- `AppShell` 依据 `pathname.startsWith('/account')` 切换侧边栏；**不新增布局层级、不做弹窗**，路由即状态。
- ✕ 与浏览器后退等价：`navigate(-1)`，无历史（直接打开该 URL）时回退 `/chat`。
- 设置侧边栏不显示折叠按钮、不显示底部用户菜单（返回应用即可继续），避免两个侧边栏语义混在一起。
- 键盘：✕ 有 `aria-label="返回应用"`；分区项是 `NavLink`，天然可 Tab 遍历。

### 10.3 桌面布局：/account/profile

```
┌────────────────────┬───────────────────────────────────────────────────┐
│  设置            ✕ │  偏好设置 / 个人设置                    [中/EN]    │ ← 应用顶栏保留
│                    ├───────────────────────────────────────────────────┤
│  资料              │  资料                                             │
│  安全              │  ──────────────────────────────────────────────   │
│  偏好              │  (A)  更换头像      [上传] [删除]                 │
│  我的资源          │  昵称             [ 张三            ]  已保存 ✓   │
│  会话              │  登录名            zhangsan          (只读)       │
│  关于              │  角色              开发者            (只读)       │
│                    │  创建时间          2026-10-01 09:12  (只读)       │
└────────────────────┴───────────────────────────────────────────────────┘
```

- 设置侧边栏仅一行标题 + 一行一个分区名；右侧每行 `label` + 控件，只读行灰字，不加边框与卡片。

### 10.4 资料

```
头像        (A)   [更换] [删除]        支持 PNG/JPEG/WebP，≤512KB，建议正方形
昵称        [ 张三                    ]              已保存 ✓
登录名      zhangsan                                  （不可修改）
角色        开发者 / 访客                              （只读，来自角色）
创建时间    2026-10-01 09:12
```

交互：点「更换」→ 系统文件选择（`accept="image/png,image/jpeg,image/webp"`）；选完立即上传并就地替换预览，失败行内红字并保留旧头像；点「删除」→ 确认弹窗 → 回落首字母头像。昵称为空即清空昵称；超 40 字输入框直接截断并提示。

### 10.5 安全

```
修改密码
  当前密码      [                    ]
  新密码        [                    ]        至少 8 位
  确认新密码    [                    ]
                                              [ 修改密码 ]
  提示：修改后其他设备上的登录会立即失效，当前浏览器保持登录。

登录状态
  退出其他设备                                  [ 退出其他设备 ]
```

交互：三格任一为空则按钮禁用；新密码两次不一致行内报错；成功后清空三格并显示「已修改，其他设备已退出登录」。点「退出其他设备」→ 确认弹窗 → 成功提示「其他设备已退出」。

### 10.6 偏好

```
对话
  思考过程（Think）        [ 开 ●]      在回复前展示思考过程
  新会话默认模型           [ 跟随系统默认 ▾ ]
  新会话默认智能体         [ 自由模式（不锁定） ▾ ]

界面
  语言                     [ 简体中文 ▾ ]
```

交互：开关与下拉改变即保存（右下角 12px「已保存」闪烁 2 秒）；下拉只列当前账号可用项；没有可用模型时该项显示「未配置模型」并禁用。

### 10.7 我的资源

```
┌──────────┬──────────┬──────────┬──────────┐
│ 智能体 3 │ 技能 5   │ 知识库 2 │ 工具 4   │   ← 点击跳对应列表页
└──────────┴──────────┴──────────┴──────────┘

凭据清单（只读）
  工具            类型      凭据
  订单查询 API    工具      已配置
  合同 MCP         MCP       未配置
  无凭据时：还没有配置凭据的工具。
```

交互：无权限的模块不显示（不显示灰块），数量为 0 也照常显示；清单只显示"已配置/未配置"，不回显任何密钥。

### 10.8 会话

```
最近会话
  尽调材料整理          10-16 14:20     [打开]  [删除]
  合同风险核查          10-15 09:02     [打开]  [删除]
  空：还没有会话。
```

交互：「打开」进 `/chat/{id}`；「删除」用既有确认弹窗；默认只显示最近 10 条，超出用「查看全部」跳会话列表。

### 10.9 关于

```
DeepScope
前端 0.1.0 · 后端 2.0.5-SNAPSHOT
文档   [使用说明]  [前端规范]  [后端规范]
```

### 10.10 移动端

```
┌───────────────────────────┐
│ ← 返回   个人设置   [资料 ▾]│  ← 设置侧边栏折叠成顶部一行
├───────────────────────────┤
│  (A)  更换头像            │
│  昵称  [ 张三        ]    │
└───────────────────────────┘
```

- 移动端设置侧边栏不占整屏：顶部一行放「返回 + 标题 + 分区下拉」，内容立即跟随。

### 10.11 状态与反馈

| 状态 | 表现 |
| --- | --- |
| 加载中 | 分区内容用 `Skeleton`，不显示空态 |
| 保存中 | 该行控件禁用 + 行内 12px「保存中…」 |
| 保存成功 | 行内 12px 绿字「已保存」，2 秒后淡出 |
| 保存失败 | 行内 12px 红字`role="alert"`，保留用户输入 |
| 校验失败 | 出错字段下方红字，按钮禁用 |
| 空状态 | 会话/凭据用一行灰字，不用插图 |

### 10.12 组件与词条映射

| 用途 | 复用现有 |
| --- | --- |
| 页面外壳 / 面包屑 | `Page`、`AppShell` |
| 表单控件 | `Input`、`AppSelect`、`Button` |
| 确认弹窗 | `ConfirmActionDialog`（含 `DialogFooter`） |
| 骨架 | `Skeleton` |
| 词条 | 新增 `account.*` 与 `user.settings`，zh-CN 与 en 同步 |
