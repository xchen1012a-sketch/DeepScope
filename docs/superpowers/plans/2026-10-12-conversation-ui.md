# 会话 UI 实施计划

**目标**：落实已审查的连续对话画布与逐行模型菜单，Think 使用滑动开关。

**设计依据**：本会话已批准的 conversation-sketch.html 草图。

**实现方式**：React / Tailwind；复用现有 TanStack Query 模型接口、Radix Dropdown Menu 和 Dialog。当前工作区 develop 已有用户改动，原位增量修改，不提交、不推送。

## 范围与约束

- 修改会话布局、输入区、过程抽屉与相关中英词条；新建会话和首页复用同一模型菜单。
- 保持会话发送、流式响应、附件、停止、提案裁决、过程定位与统计计算契约。
- 模型目录和切换仍要求 settings.manage；Think 不依赖模型设置权限。运行期间禁止调整当前轮的模型与 Think。
- 保留真实模型回报的回退显示；模型切换失败时显示错误并保留当前模型。
- 已有未提交的后端、Markdown、时间线模型等修改保持原状。

## 执行步骤

- [x] 模型菜单：先增加 ModelPicker.test.tsx，验证逐行选择、Think 状态、受限权限和失败行为；观察失败，再实现 ModelPicker.tsx。ComposerDock 移出重复模型请求；ComposerToolbar 仅负责附件，ChatPage/HomePage 复用 ModelPicker。
  - 验证：`npm test -- src/features/chat/components/ModelPicker.test.tsx src/features/chat/components/ComposerDock.test.tsx`
- [x] 连续画布：给 ProcessDrawer 的入口、返回定位与关闭交互增加测试；合并 AppShell/ConversationPanel 顶栏，减少侧栏横线、消息分隔和过程卡片边框；将轨迹与子智能体放入按需打开的抽屉。输入区统计收进详情，保留上下文环。
  - 验证：`npm test -- src/features/chat/ProcessPanel.test.tsx src/features/chat/components/ProcessDrawer.test.tsx`
- [x] 同步 docs/v2 EN/ZH 的会话页说明；运行 `npm test`、`npm run lint`、`npm run build`，使用项目现有浏览器模拟接口检查桌面/窄屏与键盘操作，最后做独立代码审查。

完成标准：菜单真实模型列表与 Think 状态正确；发送和过程定位保持可用；界面符合草图，无窄屏溢出；前端门禁通过。

## 审查后的 UI 修订

- 紧凑单行输入区；紫色发送按钮与紫色浅底访客气泡。
- 轨迹/子智能体使用浅灰背景、白色卡片、轻阴影；页签沿用工具/MCP 分段样式。
- 用量详情与上下文均使用 Radix Popover 气泡弹窗；不增加输入区高度。
- 模型切换期间共享 mutation 状态，阻止按钮与 Enter 提前发送。
- 第二轮按用户要求不启动浏览器，执行组件交互测试、全套单元测试、lint、build。

## 验证结果

- 全套前端单元测试：21 文件、182 用例通过。
- Lint：0 errors，25 个既有 warnings；类型检查与生产构建通过。
- 第一轮浏览器模拟接口验证：3 用例通过。用户追加 UI 修订后未再启动浏览器，最终弹层与发送竞态通过组件交互回归。
- 独立审查：模型切换前提前发送的问题已修复，复核无新增可执行问题。
- 文档全站检查：237 个存量缺失导航/链接错误；与修改前比较，新增错误为 0。
- 没有提交或推送；用户已有改动保留。
