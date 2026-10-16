# 卡片追问“其他”输入修复记录

依据：`docs/superpowers/plans/2026-10-16-question-other-input-fix.md`。

## 本轮修改

- `frontend/src/features/chat/components/ComposerDock.tsx`：移除卡片替换输入区下额外展示的 AgentPicker。普通输入模式的选择器保留；卡片退出后恢复。
- 同目录 `QuestionCard.tsx`：中文问题使用中文卡片系统提示；模型生成的 Other/其他及明确的“请说明”同义标签合并为系统自定义输入入口。原业务选项 ID 和服务端问题 ID 保留，历史记录不重写。
- 点击“其他”展开并聚焦输入框，不自动发送；单选/多选都必须填写非空文字后确认。多选保留业务选项；自定义答案沿用现有 customText 提交，普通业务单选仍点击即提交。
- 同目录 `QuestionCard.test.tsx` 及 `frontend/src/features/chat/ConversationPanel.questions.test.tsx`：补充中文卡片、模型 Other 归一化、输入聚焦、非空约束、旧卡片兼容、追问隐藏智能体入口和退出恢复的回归。
- `docs/v2/{en,zh}/docs/others/technical-advisor-login.md`：同步产品说明。无后端代码/API 变更。

## 定向验证

RED：新增行为在原代码下复现 9 项失败，日志 `/tmp/question-other-fix-red.log`。

修复后仅执行一轮相关验证：

- `npm test -- src/features/chat/components/QuestionCard.test.tsx src/features/chat/components/ComposerDock.test.tsx src/features/chat/ConversationPanel.questions.test.tsx`：3 个文件，77 项通过，退出码 0。日志 `/tmp/question-other-fix-tests.log`。
- `npm run build`：类型检查、Vite 构建通过；保留大包体积警告。日志 `/tmp/question-other-fix-build.log`。
- 仅上述修改的 4 个 TS/TSX 文件执行 ESLint：0 errors、ComposerDock 既有未使用 import 的 3 warnings，未清理其他任务的无关代码。日志 `/tmp/question-other-fix-lint.log`。
- 定向 diff 空白检查通过。

未启动浏览器，未运行整仓回归，未重启服务、提交或推送。源码和组件行为验证已通过，未宣称浏览器现场验收。
