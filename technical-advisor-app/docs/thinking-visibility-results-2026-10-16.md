# 思考过程展示解耦修复

根因：ChatPage 与 ConversationPanel 将账号展示偏好 thinkEnabled 用作模型 thinking 的默认值，关闭展示会同时关闭模型思考及程度选择。

修复：移除两处模型开关对展示偏好的依赖；模型思考仍使用已选/本地缓存状态，程度保留原独立选择。账号 thinkEnabled 仅过滤展示投影中的 thinking 块（包括侧栏使用的投影），控制实时 ThinkingLine，不丢弃流式状态或历史事件。隐藏时保留等待反馈，最终回答与工具链路不变；重新展示可直接恢复原始记录。现有 API/数据库保持兼容，界面帮助文案及类型说明明确其展示语义。

验证：

- 回归实际复现：账号展示 false 覆盖本地模型 thinking=true，模型入口缺少 Think。日志 `/tmp/thinking-visibility-red.log`。此前两次测试因本机测试环境无 localStorage 而失败，已改为在存储读取边界模拟偏好，不计为行为 RED 证据。
- 最终 ChatPage.test.tsx + ConversationPanel.questions.test.tsx 共 17 项通过，覆盖新建/已有会话的思考保留、Max 程度保留、发送参数 thinking=true/reasoningEffort=max、历史思考恢复/隐藏及实时思考恢复/隐藏。日志 `/tmp/thinking-visibility-final.log`。
- 前端构建成功；局部 ESLint 0 错误、1 条既有 setDraft effect 依赖警告。日志 `/tmp/thinking-visibility-build.log`、`/tmp/thinking-visibility-lint.log`。
- 没有启动浏览器、调用模型或重启后端。开发页面可直接热更新。
