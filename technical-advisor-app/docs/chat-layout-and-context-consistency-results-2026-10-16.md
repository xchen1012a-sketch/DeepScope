# 新建会话、上下文与侧栏调整结果

日期：2026-10-16。对应计划：`docs/superpowers/plans/2026-10-16-chat-layout-and-context-consistency.md`。

## 交付内容

1. 新建页问候与输入区留出约 32px 间距，输入区拓宽至 768px 上限，欢迎态输入最小高度为 104px。继续复用 ComposerDock，实际会话保持紧凑样式。
2. 用户消息与代码块共用图标复制按钮，取消常驻文字，保留悬停名称、无障碍标签、复制成功对勾和失败重试。
3. 手动压缩已适配 Harness 的原生会话日志：获取原生写租约、恢复真实上下文、生成摘要并写回原生检查点。保留旧状态模式；摘要使用实际 agentId 回查历史，页面原聊天记录保留。
4. 会话主标题与近期列表使用同一会话名称缓存。仅 SessionNav 发起列表轮询，主标题订阅该缓存；折叠侧栏时保留查询，避免独立轮询或显示时差。
5. 侧栏使用固定布局，近期会话占用剩余空间，复用 Radix ScrollArea 的 scroll 模式显示滚动条，停止滚动后隐藏。
6. 工作空间和设置互斥展开，搜索仅在展开时显示；匹配现有且有权限的静态菜单。折叠整栏时清空菜单搜索，避免旧搜索条件遮住权限入口。
7. 按用户补充，边框、白底和轻阴影包住分组标题及内容整个容器；工作空间与近期会话之间增加间距，设置采用相同方式。矮屏缩紧间距并对菜单提供限高内部滚动兜底，隐藏额外滚动条，保留底部入口可达性。

主要文件：`ChatPage.tsx`、`ComposerDock.tsx`、`CopyTextButton.tsx`、`useSessionList.ts`、`useAdvisorConversation.ts`、`SessionNav.tsx`、`AppShell.tsx`、`SessionCompactionService.java` 及对应行为测试。docs/v2 EN/ZH 已同步。

## 验证结果

| 验证 | 结果 |
| --- | --- |
| 前端相关回归 | 7 个文件、106 条通过；最终分组标题与内容整体边框补充后，导航 9 条再检查通过 |
| 前端全量回归 | 当次 82 个文件、723/724 通过；个人设置测试在共享工作区接口变化期间有 1 条断言失配，更新后该文件重跑 19/19 通过 |
| 后端相关回归 | 会话压缩、会话模型、命令、命名与会话控制器 5 类、42 条通过，0 失败/错误/跳过 |
| 原生压缩 | 真实 Harness + 临时原生日志复现旧状态为空但历史存在，验证摘要回查 ID、持久化、聊天记录保留和失败释放租约 |
| 前端 Lint | 0 error，45 条存量 warning |
| TypeScript / Vite | 通过，保留大分块提示 |
| 后端与依赖 Spotless | 全模块检查通过；早期曾被共享工作区 AvatarStore 格式变更影响，最终重新检查通过 |
| diff 检查 | 通过 |
| 只读审查 | 原生历史引用 ID 与矮屏溢出两项发现均已修复并复查 |

日志：`/tmp/chat-layout-frontend-final.log`、`/tmp/chat-navigation-group-frame-tests.log`、`/tmp/chat-layout-delivery-all-tests.log`、`/tmp/chat-layout-account-final-recheck.log`、`/tmp/chat-layout-backend-final.log`、`/tmp/chat-layout-frame-lint.log`、`/tmp/chat-layout-frame-build.log`、`/tmp/chat-layout-spotless-full.log`。

## 运行边界

未启动浏览器，未做浏览器 E2E 或真实视口人工验收；未启停用户应用后端、未修改运行数据库、未提交或推送。原生压缩修复需重新构建并启动新版后端后生效。测试仅使用隔离 H2、临时文件及本地假模型。
