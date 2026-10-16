# 会话过程折叠实施计划

> 执行方式：当前会话按步骤实施；采用 executing-plans、TDD 与 verification-before-completion。用户已批准技术方案并要求开始编码；不重复请求批准，不 commit、不 push。

**目标：** 按实际顺序展示过程，正常结果确认后整组折叠，并展示服务端测得的最终正文之前的处理耗时。

**架构：** 复用应用层 AG-UI enricher、顺序 parts 和 JSON payload。服务端确认最终正文身份与时间，客户端统一回合投影和折叠，不改变模型运行循环。

**技术栈：** Java / JUnit / Spring、React / TypeScript / Vitest / Playwright。

**设计依据：** [技术方案](../specs/2026-10-14-conversation-process-collapse-design.md)。

## 约束

- 保留当前混合工作区；任务开始的相关文件副本位于 `/tmp/advisor-conversation-collapse-baseline`。
- 不修改 core、harness、AG-UI 扩展公共事件和运行语义，不新增依赖或数据库表。
- 普通说明文字不能被提前误判为最终正文；仅正常结果确认后折叠。
- 真实时间缺失时不显示 0 秒；中断、错误、挂起、无法关联结果均保持展开。
- 新字段可选，中英文同步，主会话与共享子智能体组件有回归测试。

## Task 1：证明最终正文身份和计时

文件：`AguiRoundPersister.java`、应用层展示元数据辅助类、`AguiRoundPersisterTest.java`。

- [x] 写失败测试：普通文字、工具、最终多文本块、原生 AgentResult 后正常结束，应提供最终 messageId 集合；不同的结果 Msg ID 不影响原生 replyId 关联。
- [x] 运行定向 Maven 测试，确认缺少展示事件导致断言失败。
- [x] 在现有累积器内接入展示元数据，记录同一服务端时钟、原生模型调用和文本对应关系；新增字段如设计表。
- [x] 证明 82 秒处理时间不含最终输出时间；错误、挂起、结果改写和子运行均不产生成功折叠身份。
- [x] 运行定向测试确认通过。

## Task 2：保存与 API 对账

文件：`ConversationParts.java`、`TimelineStore.java`、`AdvisorSessionController.java` 及对应测试。

- [x] 写失败测试：正常保存和兜底保存保留 presentation；非法时间、版本、正文身份返回 400，不能取消兜底。
- [x] 在正常保存之前读取匹配 runId 的服务端元数据，新客户端不能覆盖服务端时间；旧客户端和旧 payload 保持兼容。
- [x] 补 JSON payload 可选字段，无数据库迁移；保存成功后清理正确运行的累积器。
- [x] 测试正常、兜底、重试和非法请求。

## Task 3：客户端状态与整轮投影

文件：`advisor.ts`、`aguiClient.ts`、`useAdvisorConversation.ts`、`model/blocks.ts`、`model/presentationParts.ts` 及对应测试。

- [x] 写失败测试：解析生命周期与 presentation，过程说明文字也进入折叠区域，最终多块正文留在外面。
- [x] 新增可选展示类型，按 runId 隔离；区分成功终态、错误、取消和挂起；正常终态与保存错误分开处理。
- [x] 保持 parts 顺序及锚点、工具原位更新，避免新旧实时内容重复。
- [x] 运行会话相关 Vitest 用例。

## Task 4：耗时入口与交互

文件：`TurnGroup.tsx`、`ConversationPanel.tsx`、`conversationModel.ts`、双语词典、组件测试及会话 E2E。

- [x] 写失败测试：82 秒显示中文耗时；全部过程折叠，手动选择保留；定位优先展开；缺失时间不显示 0 秒。
- [x] 替换主会话工具局部预览，复用现有 ThinkingLine/ToolLine，适配共享 SubagentView。
- [x] 折叠保持阅读锚点，底部跟随与历史阅读分别处理。
- [x] 添加会话 E2E：真实页面接收确定性本地 SSE fixture，完成、刷新、失败和定位行为一致。

## Task 5：文档、门禁及审查

- [x] 同步旧会话设计条款及 docs/v2 EN/ZH 使用说明。
- [x] 已执行前端 `npm test`、`npm run build`、`npm run lint`、`npm run test:e2e`；会话专项通过，全仓检查有范围外失败，不能称全部门禁通过，详见实施结果。
- [x] 后端模块测试与 Spotless；应用增量载荷没有修改库模块公共契约，运行范围以模块及相邻依赖为准；若公共契约范围扩大，按规范升级全量 verify。
- [x] 文档检查、任务增量 diff 审查；实际失败和环境限制写入结果，不声称未运行的检查通过。

## 执行记录

- 2026-10-14：用户授权执行推荐方案。相关混合工作区已备份，尚未实现。

- 2026-10-14：用户要求暂停。前端全量 47 文件/398 用例通过，build 通过，lint 0 error/40 warnings；advisor 后端模块 290 用例全部通过。会话 E2E 2 个在等待 textbox 时失败，文档 validate 237 项失败，审查发现纯空白最终 TextBlock 使保存校验400，未修复。功能未完成验收。
- 已停止编码及进一步验证，审查代理已中断；续做以 [暂停交接](../../../technical-advisor-app/docs/handoff-2026-10-14-conversation-collapse.md) 为入口。

- 2026-10-15：已续做并完成会话专项实现、审查修复和验收；上述勾选表示步骤已执行，不表示全仓所有检查通过。会话单测262/262、会话E2E 3/3、advisor后端293/293、构建与Spotless通过；lint 0 error/46 warnings。全量前端单测408通过/1失败（知识库翻译键），全量E2E 11通过/3失败（原chat-ui fixture未模拟models/active）。文档检查237项与任务前两页覆盖比较一致，无新增；broken-links被未改动的旧交接文档MDX语法阻断。
- 取舍记录：EOF且未观测到服务端时间的展示元数据允许省略runStartedAt，只记录unknown终态；completed仍必须有真实start/end。这样既保留异常终态，也不使用浏览器时间冒充服务端采样。
- 完成记录：[2026-10-15 实施结果](../../../technical-advisor-app/docs/conversation-collapse-results-2026-10-15.md)。未提交、未推送、未部署。

- 2026-10-15真实回归：发现截图第一轮原生MAX_ITERATIONS总结被误记unknown，且无模型中间普通文字。已新增limited终态、显式上限提示、真实工具动作说明，并只对截图历史轮次依据原生正文/终态证据修复展示元数据。266会话测试、27后端/API测试、构建及带测试打包通过；真实payload组件检查通过。用户选择等待当前子任务结束再重启后端，不允许打断运行。

- 2026-10-15服务核验：用户确认任务执行完毕后，数据库无running会话；发现后端已于11:57:37加载11:57:25构建、包含limited修复的JAR（PID99275），无需重复重启。5173 HTTP200、18080认证接口预期401；前端模块含新说明/告警代码。未启动浏览器，无自动化待执行。
