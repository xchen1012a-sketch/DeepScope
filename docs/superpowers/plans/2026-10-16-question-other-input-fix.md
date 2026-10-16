# 卡片追问的智能体入口与“其他”输入修复

用户已授权直接修复；本次为应用内部中级修复，保持既有问答 API 与智能体下一轮生效规则。

## 已核对的原因

- `ComposerDock.tsx` 的 `isInputReplaced` 分支专门额外渲染了 AgentPicker，所以结构化卡片底部仍可切换智能体。
- `QuestionCard.tsx` 的系统“其他”可展开输入，但模型放入 `question.options` 的 Other/其他会走普通单选的立即提交路径；两个入口行为不同。
- 中文词典已有“其他”，截图显示英文 UI 提示。中文问题的卡片系统提示应跟随问题语言，不能只翻译模型给出的选项正文。

## 改动与验收

1. 删除替代输入卡片下的智能体入口；普通输入状态保留既有选择器。
2. 只在卡片展示层合并明确的 Other/其他及常见“请说明”同义标签，保留普通业务选项原 ID；统一为自定义文字入口，不改历史数据或服务端问题 ID。
3. 中文问题使用 zh-CN 卡片提示，其他问题继续当前界面语言；词典保持 EN/ZH，不在 JSX 硬编码文案。
4. 选择“其他”后显示并聚焦输入框，不自动提交；单选、多选都要求自定义文字非空后才能确认。多选保留同时选中的业务选项；仅自定义回答发送空 selectedOptionIds 和真实 customText，沿用现有接口。
5. 范围：`QuestionCard.tsx`、`ComposerDock.tsx` 及两者现有测试；产品说明同步 `docs/v2/{en,zh}/docs/others/technical-advisor-login.md`。
6. 一次 RED 定向复现，再修复并运行对应测试、构建和相关文件 lint；无实际失败不重复、不扩大整仓测试、不启动浏览器。

## 完成记录

以上改动已实现。相关 77 项测试、类型检查/构建通过，相关文件 lint 为 0 errors、3 条既有 warnings；未启动浏览器、未重复整仓验证。详见 `technical-advisor-app/docs/question-other-input-fix-results-2026-10-16.md`。
