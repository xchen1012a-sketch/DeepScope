# 对话体验调整结果

日期：2026-10-16。依据 `docs/superpowers/plans/2026-10-16-chat-experience-cleanup.md` 和用户对根目录 `AGENTS.md` 的执行要求。按样式、交互、前后端链路推进，所有修改通过补丁完成。

## 完成内容

1. 上下文环视觉由28px缩为18px，保留32px操作区和原用量气泡。输入框继续自动增高，200px后内部滚动；边界滚轮不带动外层，保留修饰滚轮、Enter、Shift+Enter和IME。
2. 正文和模型说明较深，工具、分析和自动动作说明较浅；去掉批次/整轮额外失败计数及工具行下重复失败提示。真实失败状态、具体返回、整轮中断和需要用户处理的确认保留。
3. 按用户后续澄清及DeepSeek参考实现，思考默认收起，仅在“正在分析”同一行显示最新思考预览，新行滚入、长行横向跟随新增文字。点击才在原位置展开完整思考，手动开合跨增量和完成保持。灰色文字本身流光、无下划线，尊重减少动态效果。展开后使用固定240px高的连续滚动窗口，不分段翻页，底部跟随、上翻暂停、回到底部恢复；参数和工具结果保留原分段方式。
4. 新回答不再要求交付工具或归档阶段。主、子智能体模型请求隐藏旧交付schema，工具注册保留以兼容待处理调用；兼容调用只产普通正文，不创建新的交付归档。已有用户AGENTS.md未覆盖，自动生成的新模板和应用内部格式约束同步修正。
5. 历史交付只有元信息时隐藏，有唯一正文时转为普通消息；同轮完整段落/条目相等才去重。建议、冲突和真实来源链接保留，相反结论不会被子串去重误吞；前后端投影规则一致。
6. 主对话和子任务不再显示内部F/E引用徽标。Markdown按AST处理明确标记及已知编号前缀，代码、真实链接、路径和型号保持原文。事实内部ID在缺失时由服务端补齐，仍用于关联，不作为用户标签。
7. 子任务按名称、目标、真实状态及当前动作查看过程。仅选中活跃任务所属分页补齐持久化进度，完成、返回或卸载停止刷新。修复无ID事实/工具步骤互相覆盖及不完整快照合并丢步骤，旧运行快照不能覆盖终态。

## 核心文件

- 前端：`ContextRing`、`ComposerDock`、`ThinkingLine`、`ProcessText`、`TurnGroup`、`ActionBatch`、`ToolLine`、`Markdown`、`ConversationPanel`、`ProcessPanel`、`SubagentCard`、`SubagentView`、`model/legacyDeliverable`、`model/blocks`、`index.css`。
- 后端：`ReportActions`、`ReportPresentationMiddleware`、`AgentProvisioner`、`LegacyDeliverableText`、`SessionProcessProjector`。
- 文档：原计划及 `docs/v2/{en,zh}/docs/others/technical-advisor-login.md`。

## 验证证据

- 单行预览最终跟进：默认收起/逐行预览/原文保留3项先RED，修复后相关57项通过；全量65文件583项通过：`/tmp/chat-thinking-preview-tests.log`。构建通过：`/tmp/chat-thinking-preview-build.log`，Lint 0错误、44项存量警告：`/tmp/chat-thinking-preview-lint.log`。字符串方法按项目编译目标修正后，组件6项复测通过：`/tmp/chat-thinking-preview-final-targeted.log`，修改文件Lint通过。未启动浏览器，真实滚动观感尚未人工验证，未重启服务。

- 思考展示后续修正：新增连续长文本与跟随阅读用例先2项RED，修复后相关28项GREEN；历史思考测试同步改为连续展示。最终前端全量65文件579项通过：`/tmp/chat-thinking-correction-tests.log`；构建通过：`/tmp/chat-thinking-correction-build.log`；Lint 0错误、44项存量警告：`/tmp/chat-thinking-correction-lint.log`，最终修改文件单独Lint通过。未启动浏览器，未验证真实动画与滚轮观感，未重启服务。

- 前端全量65文件576项通过：`/tmp/chat-cleanup-all-tests.log`。构建通过：`/tmp/chat-cleanup-build.log`。Lint 0 errors、44项存量warnings：`/tmp/chat-cleanup-lint.log`。
- 最终后端相关54项通过，0失败/错误/跳过；相关文件Spotless通过，package成功：`/tmp/chat-cleanup-backend-scoped-final.log`。
- 测试先RED再GREEN覆盖：去额外提示、滚轮边界、就地思考/手动折叠/底部跟随、旧交付正文保留、相反结论与同轮去重、引用不误伤技术原文、无ID步骤与任务终态、报告schema兼容和用户工作区字节不变。
- 只读审查发现的3项P2已修复并复核：无ID工具候选串内容、子串去重误删相反结论、普通文本路径/型号被引用过滤误改。
- `git diff --check` 本次范围通过。

## 全工作区门禁边界

工作区同时包含其他任务的未提交修改，未覆盖或修正其内容。首次应用全量362项有1项新增pgvector集成测试因未配置隔离库失败；随后创建本任务专用空测试库并实际跑通该测试，未使用业务库，临时库已删除并核对不存在。

两次带隔离库的全量363项各有1项非本任务失败，分别为 `GuardedCatalogToolTest.anOldRuntimeCannotInvokeAMcpToolAfterRevocationEvenWithALiveConnection` 工具注册为空、`AgentCreationGenerationTest.cancelGenerationMarksOperationCanceled` 取消竞争返回failed。前者单独复跑通过，后者在最终相关54项中通过。之后全文件格式检查又被并行任务正在修改的AgentCreationActivationAndPreviewTest、GuardedCatalogToolTest、AdvisorKnowledgeController阻断。本次最终采用明确的相关测试和相关文件格式检查，不把全工作区门禁标为通过。

## 运行与视觉边界

未启动浏览器、未执行E2E或真实滚轮/动画视觉验收，组件测试不等于观感验收。未调用真实模型运行或付费服务。未删除历史数据、未改SDK公共契约、未新增数据库迁移、未提交或推送。

后端已产出新版JAR，但未启动或重启现有服务；运行时报告约束和新工具行为需使用新版后端后才生效，已开始的旧运行不据此宣称已切换。前端源码与构建产物已更新。
