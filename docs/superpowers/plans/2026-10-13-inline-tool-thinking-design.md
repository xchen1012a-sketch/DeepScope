# 工具与思考按对话顺序展示：调研与技术方案

状态：用户已批准，按实施清单完成编码与验证；不启动浏览器。

## 结论

推荐在一轮对话中保留有序的正文、思考和工具片段，只把连续的过程片段折叠。工具在实际发生的位置显示：通常紧跟上一段助手说明；正文作为边界，不能跨正文合并过程组。第一段正文前发生的思考/工具保持在正文前，不人为调换因果顺序。

## 调研依据

- 本地DeepSeek Harness：`/Users/agent/deepseek-harness`，检视版本`d743267388`。
- `packages/client/ui-chat/src/client/conversation-nodes/process-groups.ts`：按节点顺序分组，遇到助手response就flush过程组；reasoning和response可以分开呈现。
- `packages/client/ui-chat/src/client/presentation-policy.ts`：运行/历史详情策略与折叠时机分离，最终答案不随过程隐藏。
- `packages/client/ui-chat/src/client/chat/ReasoningRow.tsx`、`ChatGroupSeat.tsx`：Think独立披露；过程组有稳定容器、运行状态及详情摘要。
- `packages/client/ui-tool/src/client/tool/components/ToolRow.tsx`：工具行显示目标/状态/摘要，展开后才格式化并挂载参数和结果。
- Codex官方App Server文档：https://developers.openai.com/codex/app-server/ 。回复、reasoning、commandExecution、mcpToolCall等都有独立item身份及started/completed/delta生命周期。这里借鉴其事件模型；不把协议文档当作桌面UI样式的证明。

## 当前问题的三个来源

1. `useAdvisorConversation.ts`的`streamingText`、`thinking.text`分别把整轮内容累加；`aguiClient.ts`丢弃正文messageId及边界，无法表达“正文A→工具→正文B”。
2. `toAdvisorMessageInput()`把整轮正文/思考/工具分别打包；`AdvisorSessionController`与`TimelineStore.appendRunArtifacts()`先存工具再存聚合回复；`AguiRoundPersister.Round`也按类型累加。刷新后的seq不能代表原始交错顺序。
3. `model/blocks.ts`分为process/finalText/outside；`TurnGroup`先画整轮process，再画finalText；`ConversationPanel`再单独画本轮streamingText/思考。这会造成工具集中、过程与正文分离。

工具与思考已有可折叠组件，可继续复用。主要改有序片段投影和落库展示记录，而不是重新造一套工具UI。

## 推荐交互

示例顺序：

访客：检查项目依赖

▸ 思考
助手：我先读取依赖配置。
▸ 已读取2个文件 · 1.2s
助手：发现版本不一致，继续检查引用位置。
▸ 已搜索3处引用 · 0.8s
▸ 思考
助手：最终结论……

- 正文一直可见；完成一轮后也不将中间说明塞进统一顶部折叠区。
- 单个工具用现有工具折叠行；连续多个工具用一个局部过程组，组内按发起顺序展示。工具返回更新原位置，不追加到列表末尾。
- 运行时显示当前动作、目标及进度。过程组可展开；完整日志默认收起，不因每次更新自动展开。用户手动展开后不自动收回。
- 已完成过程组默认一行摘要。仅在具有可靠起止时间时显示耗时；未知调用总量不显示虚假的进度分母。
- Think按真实思考阶段分段；运行时显示状态/可见内容预览，结束后可展开查看。只展示提供方实际给出的内容，不额外生成“思考解释”。
- 失败保留原位置与一行原因；权限确认、提问和需要用户操作的卡片保持直接可见。
- 不在正文被折叠时改变工具相对位置；主对话与右侧执行过程共享同一片段投影。

## 技术落地

### 1. 有序片段与身份

在应用层定义有序展示片段，种类为text/reasoning/tool及现有需要直接展示的过程记录。最小信息：稳定partId、runId/turn归属、顺序、类型、状态；保留messageId/toolCallId等原始关联。正文与思考内容、工具参数/结果复用已有结构。

- 消费AG-UI时保留START/CONTENT/END与messageId、runId，不能只留下delta。
- 相邻且同一片段的delta合并；出现工具或另一个片段后，即使再次收到同一messageId，也建立新的正文片段，防止跨工具拼接。
- toolCallId关联参数/结果；结果晚到只更新原工具节点。并行工具以开始顺序占位。
- 没有明确ID的思考阶段，按本次run内的真实阶段边界生成关联身份；不能用内容、数组展示序号或时间戳充当稳定身份。
- 服务器回放与实时片段通过原始关联字段对齐，避免结束时重新挂载、折叠状态丢失或锚点跳动。

### 2. 保存与回放

保留原生会话日志为对话事实来源；新的片段只是控制台展示投影。先检查现有SessionEvent的turn/step/messageId及content能否直接派生回放，不修改core/harness公共事件或持久化格式。

若现有应用回放需要快照，给advisor_message现有JSON payload增加可选、有版本的parts，继续复用当前表，不先新增数据库表。保留text/reasoning/toolCalls等兼容字段。新读侧优先parts，旧字段与单独工具行只作兼容读取，不重复渲染同一次调用。

前端正常保存与`AguiRoundPersister`断连兜底必须提供相同语义的有序投影，不能一个保存顺序、另一个恢复为聚合字符串。沿用现有落库去重与终态处理。

旧记录：原生日志若保留边界，则从日志恢复；只有聚合字段时保持旧版展示，并标记为历史过程，不根据工具结束时间或文本内容猜测分段位置。

### 3. 展示投影与组件

- `RunState`保留有序parts；整轮text/usage等聚合数据作为兼容派生，不再主导消息位置。
- `buildBlocks`从process/finalText单一结构调整为有序正文与局部过程组序列。先有顺序，后按连续过程区间折叠。
- `TurnGroup`负责按序渲染；复用`ToolLine`、`ThinkingLine`、`BlockFrame`。本次只有局部过程组及工具详情两层披露，不增加复杂视图模式。
- `ConversationPanel`不再在回合尾部额外画一份整轮流式正文；实时和回放均走同一展示投影。
- 稳定ID驱动React key、折叠状态和锚点；右侧定位只展开目标所在过程组。工具列表在内容delta更新时不整组重建。
- 折叠按钮支持键盘与aria-expanded；只有用户接近底部时跟随滚动。展开内容或阅读旧消息时不强制跳底。

## 改动范围

| 层 | 文件/职责 |
| --- | --- |
| 流归一化 | frontend/features/chat/aguiClient.ts：保留身份、开始/结束及片段边界 |
| 状态与保存 | useAdvisorConversation.ts、api/advisor.ts：有序parts与兼容聚合 |
| 投影与主对话 | model/blocks.ts、TurnGroup.tsx、ConversationPanel.tsx：局部过程组按序渲染 |
| 复用组件 | ToolLine.tsx、ThinkingLine.tsx：摘要、展开状态及必要样式调整 |
| 应用回放 | AdvisorSessionController、TimelineStore、AguiRoundPersister：有序展示快照/原生日志投影 |
| 配套 | 过程面板共享定位、EN/ZH文案、对应双语说明与回归测试 |

不迁移DeepSeek Harness的插件/状态体系，不更换AG-UI或React栈，不扩展模型路由、工具执行或智能体配置。

## 验收与实施次序

1. 先固定同一组事件fixture：思考A→正文A→工具A/B→正文B→思考B→最终回复，定义应展示的片段顺序与折叠边界。
2. 打通真实事件边界和有序回放，再替换主对话投影；不能先做纯CSS移动后宣称完成。
3. 验证实时、结束、刷新、切换会话、断连兜底后顺序一致；并行工具乱序返回不换位；手动展开不被更新重置；旧记录不虚构顺序。
4. 验证无工具/只有思考/只有工具/工具失败及取消，正文不丢失、不重复；右侧定位展开正确的局部组。
5. 本次只交付顺序正确与局部折叠，不增加详情模式设置或新的可视化体系。

本方案需要前后端展示数据协同，但仅限technical-advisor-app。已审查原生日志可派生字段，按现有JSON快照契约完成应用层实现。

## 已批准实施清单

用户已批准编码。使用writing-plans整理落地步骤，随后按executing-plans逐项执行；不commit/push。范围为应用内部兼容扩展，按中级处理。

契约：POST /api/advisor/sessions/{id}/messages现有advisor请求增加可选partsVersion=1、parts数组；GET详情在对应advisor_message.payload原样返回。每个part有id、kind、runId和status；text/reasoning带text及可选messageId；tool带现有toolCalls形状的tool；event带原过程kind/payload/seq。数组顺序为开始顺序。保留text/reasoning/toolCalls/usage；旧客户端继续读取旧字段。正常与断连路径均保存相同片段语义。

- [x] 有序状态：测试思考→正文→并行工具→正文→思考→回复，工具乱序返回更新原位。新建前端model/presentationParts.ts、后端session/ConversationParts.java；消费真实AG-UI边界与IDs。
- [x] 保存回放：测试POST/GET保持parts、旧字段不重复绘制、断连兜底保持顺序。调整TimelineStore、AdvisorSessionController与AguiRoundPersister；只增JSON字段。
- [x] 局部折叠：测试正文始终在序列原位、两个工具组被正文分隔、运行更新保持手动展开与锚点。调整blocks/TurnGroup/ConversationPanel，复用现有ToolLine/ThinkingLine；右侧时间线复用同一块顺序。
- [x] 门禁与文档：前端测试/lint/build；后端对应控制器/TimelineStore/断连持久化测试与Spotless。EN/ZH同步，聚焦复审；不启动浏览器或真实模型验收。

原生日志检查结论：AssistantMessageData拥有turn/step/messageId及stream，但当前应用时间线与多智能体原生turn没有直接可用的统一对应契约。旧聚合记录本次不推测回填；新展示快照与原生会话事实分层，避免对旧会话制造虚假的步骤关联。

## 完成验证

前端24个文件、216项测试通过，构建通过，lint为0错误、25条既有警告。后端展示片段、正常保存、断连兜底、时间线与相邻AG-UI事件处理共30项测试通过，Maven package及Spotless检查通过。复审发现的提案seq与断连工具状态问题已补失败回归并修复；复审确认没有新的重要问题。未启动浏览器、未发真实模型验证请求，未修改原生日志格式或新增数据库表。

本地后端已更新并确认18080监听正常；恢复时保留回复原轮次，定位展开状态在高亮消失后保持。前端补充回归后全量216项测试、构建和lint再次通过。
