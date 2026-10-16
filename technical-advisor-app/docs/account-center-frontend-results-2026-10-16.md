# 用户中心前端交付结果

日期：2026-10-16。范围：用户中心前端、入口集成及用户后续确认的交互优化。本轮为头像扩容、真实角色名称和并发安全会话删除补充后端支持，详见下文。

## 已交付

- 新增 `/account` 路由及五个分区：资料、安全、偏好、我的资源、会话；About 分区按用户要求移除。
- 用户菜单增加个人设置；保留现有应用侧边栏，在主内容区增加设置导航，形成宽版双栏工作区。桌面支持关闭返回来源页，直接打开时返回 `/chat`，移动端使用返回和分区下拉。
- 资料使用短昵称输入框和右侧保存按钮（或回车），失焦不提交；头像64px，支持鉴权更换/删除，无常驻描述。权限复用只读折叠状态树，未知代号保留。
- “登录名”改为“账号”，与用户管理共用accounts.username；角色显示关联角色管理名称，非legacy role标识。重新进入或聚焦页面会读取最新资料。
- 安全支持修改密码、其他设备退出确认、行内成功/失败反馈。
- 偏好支持Think、默认模型、默认智能体和语言；旧本地Think值使用`If-None-Match: *`一次性迁移，412重新读取服务端值。
- 我的资源按后端可见性显示本人资源数量，凭据只显示配置状态，不显示密钥或引用。
- 会话默认最近10条，可展开全部；删除所有覆盖确认的完整清单并二次确认。批删使用新安全删除入口，运行中记录在服务端拒绝，部分失败刷新实际剩余项后可重试。
- 已删除关于页面及其前端请求、词条和版本注入；旧地址回到资料页。后端已有版本接口保留兼容。
- 新会话读取服务端默认值；默认智能体/模型在创建前检查可用性，模型绑定失败会删除已创建的会话后再返回错误，避免孤儿会话和重复创建。

## 主要文件

新增：`src/api/account.ts`、`src/hooks/useAvatar.ts`、`src/features/account/**`及对应测试。

接入：`src/app/AppShell.tsx`、`src/main.tsx`、`src/api/auth.ts`、`src/lib/accountIdentity.ts`、`src/features/chat/ChatPage.tsx`、`src/features/chat/ConversationPanel.tsx`、中英文`app.json`。

布局复用Page、Card、Button、Input、AppSelect、DataTable及现有确认弹窗；移除重复大标题/副标题，头部只保留左箭头返回。Page新增默认关闭的fill选项，右卡占可用视口且内部滚动；分隔线按用户最终决定保留。沿用1440px最大容器，常规控件最大560px，昵称输入及保存组合最大360px。保留全局应用导航。

复用PermissionTree新增readOnly分支，原授权编辑行为保留；不改access管理后端、共享表格本体及他人业务改动。后端增量只涉及头像上限与验证、AuthController的roleNames、SessionStore的条件删除及账号自助安全删除入口；未回滚工作区其他任务修改。

## 验证

| 检查 | 结果 |
| --- | --- |
| 入口/页面行为RED | `shell-red.log`、`page-red.log`、`defaults-red.log`实际复现缺失入口/分区/默认项行为 |
| 账号与布局定向GREEN | `roles-session-front.log`，7文件54项通过；包含共享权限树原编辑回归、批删失败重试与角色名称展示 |
| 前端全量测试 | `refinement-final-tests.log`，82个测试文件、726项通过 |
| 后端相关回归 | `roles-session-green.log`，42项通过，含头像10MiB/8192边界、角色改名、本人/运行中删除保护 |
| 后端模块verify | `refinement-backend-verify.log`，BUILD SUCCESS，551项零失败、零错误、零跳过；真实pgvector用独立临时PostgreSQL，结束已停止 |
| 类型检查 | `npm run build`中的`tsc --noEmit`通过 |
| 构建 | `refinement-final-build.log`，Vite构建通过；后端verify包含打包及Spotless检查 |
| Lint | `refinement-final-lint.log`，0错误、45警告；未禁用规则 |
| 差异检查 | `git diff --check`通过 |
| 文档脚本 | `refinement-doc-tests.log`，12项通过；此前全站`npm run check`报告237项既有Service导航/链接缺失，未修改无关导航或重复运行该检查 |

日志根目录：/tmp/account-center-frontend-20261016。只读布局审查指出设置页再次点击个人设置后无法一次返回业务页，已真实回归复现并修复为replace保留原状态；最终全量包含该修复。旧关于地址重定向资料页、不再请求版本接口也有回归覆盖。

本轮行为RED：refinement-red.log三个界面断言失败（标题、失焦提交、批删入口）；permission-tree-red.log未默认折叠；avatar-limits-red.log8192px合法图返回400；roles-session-red.log缺roleNames与安全删除入口。均在相应实现后取得GREEN。只读审查指出旧会话删除接口存在运行状态竞争，本轮改为新入口调用同一Store的条件DELETE；取消/部分失败/首项删除时切账号有专门用例。无需操作真实用户记录。

未启动浏览器、未执行Playwright/E2E、未重启或部署应用。视觉布局和真实服务器联动仍需在后续现场验收中确认；本轮以组件、接口客户端和路由行为测试为依据。全站文档检查的237项既有缺失不属于本次个人设置文档新增内容。
