# 会话模块：按序展示过程、整轮折叠与耗时技术方案

日期：2026-10-14（Asia/Shanghai）\
状态：2026-10-14 用户批准推荐方案；2026-10-15 已完成会话专项实现和验收，全仓范围外检查失败见实施结果。\
范围：`technical-advisor-app` 主会话、应用层 AG-UI 展示记录及相关测试。

## 1. 结论与必须明确的差异

复用项目已有的 `ConversationPart[]` 顺序记录、`ThinkingLine`、`ToolLine` 和 `TurnGroup`。改造重点是统一回合投影、补齐真实处理时间、整组折叠，无需新建会话引擎、数据库表或状态管理库。

目标交互：执行时按“说明文字 → 对应调用 → 下一段说明文字 → 后续调用”的顺序显示；最终正文保留在外面，其余过程整体收起。折叠入口以用户截图为依据，显示“用时 1分钟 22秒 ›”，点击后展开完整过程。

**检索发现：当前不能可靠地在最终回答第一个字出现时判断它是最终回答。** 普通说明文字和最终回答都走 `TEXT_MESSAGE_CONTENT`；模型可以先输出文字再请求工具。`AgentResultEvent` 在最终结果已经生成后、`AgentEndEvent` 前出现，并非最终正文开始信号。

因此本方案推荐：**正常运行结束并确认最终结果后自动折叠；耗时仍统计到最终正文实际开始输出的时刻。** 折叠时刻与计时截止时刻是两件事。此推荐与之前“总结开始时立即折叠”的产品描述存在差异，需在实施前确认，不能默认为已接受。

如果必须严格做到“最终回答首字出现时就折叠”，需要另外设计应用层明确的最终输出阶段，使生成链路在输出前已经决定本阶段只生成最终回答。现有通用 ReAct 链路没有这一保证；仅增加一个前端状态字段或在服务端转发普通文字时加标识，无法解决判定问题。

## 2. 项目检索事实

以下为本次读取当前工作区源码确认的事实；工作区已有多项未提交修改，本方案以当前文件为准，不将旧交接文档视作当前完成状态。

| 已有能力 / 缺口 | 当前源码依据 |
|---|---|
| 主会话由 `buildBlocks(events, running)` 生成回合，再交给 `TurnGroup`；旧数据仍有独立的实时文字、思考补充展示 | [ConversationPanel.tsx](/Users/agent/deepscope-java/technical-advisor-app/frontend/src/features/chat/ConversationPanel.tsx:348)，同文件 453–494 行 |
| 展示记录已有 `partsVersion: 1`，分为 `text`、`reasoning`、`tool`、`event`，带 `id/runId/status`；文本可带 `messageId` | [advisor.ts](/Users/agent/deepscope-java/technical-advisor-app/frontend/src/api/advisor.ts:198) |
| 工具发起占位，参数和迟到的结果更新原位置；不会因结果返回而挪动调用行 | [presentationParts.ts](/Users/agent/deepscope-java/technical-advisor-app/frontend/src/features/chat/model/presentationParts.ts:41) |
| 新顺序记录分支强制 `collapsedByDefault: false`，其 `process` 排除了全部普通文字；`TurnGroup` 遇到 `ordered` 直接转入另一套渲染 | [blocks.ts](/Users/agent/deepscope-java/technical-advisor-app/frontend/src/features/chat/model/blocks.ts:197)，[TurnGroup.tsx](/Users/agent/deepscope-java/technical-advisor-app/frontend/src/features/chat/components/TurnGroup.tsx:56) |
| 顺序分支只把连续工具组成小组，收起后仍显示前三条和失败工具；不会把中间文字、思考一起整轮折叠 | [TurnGroup.tsx](/Users/agent/deepscope-java/technical-advisor-app/frontend/src/features/chat/components/TurnGroup.tsx:104) |
| `RunState.startedAt` 用于首 Token 采样，发送初期赋值后，在路由完成、调用流接口前再次赋值；不能直接认定为从点击发送起的整轮起点 | [useAdvisorConversation.ts](/Users/agent/deepscope-java/technical-advisor-app/frontend/src/features/chat/useAdvisorConversation.ts:311)，同文件 733–765 行 |
| 客户端目前不分类 `RUN_STARTED/RUN_FINISHED`；`running: false` 还会在取消、错误后的 `finally` 设置，不能等同正常完成 | [aguiClient.ts](/Users/agent/deepscope-java/technical-advisor-app/frontend/src/features/chat/aguiClient.ts:213)，[useAdvisorConversation.ts](/Users/agent/deepscope-java/technical-advisor-app/frontend/src/features/chat/useAdvisorConversation.ts:834) |
| 正常落库由前端发送 `/api/advisor/sessions/{sessionId}/messages`；服务端 `AguiRoundPersister` 另有断线兜底，默认延迟 15 秒冲刷 | [AdvisorSessionController.java](/Users/agent/deepscope-java/technical-advisor-app/backend/src/main/java/io/deepscope/advisor/session/AdvisorSessionController.java:283)，[AguiRoundPersister.java](/Users/agent/deepscope-java/technical-advisor-app/backend/src/main/java/io/deepscope/advisor/session/AguiRoundPersister.java:36) |
| `advisor_events.payload` 为 JSON 文本；`appendAdvisorMessage` 已存储顺序记录，但没有整轮时间与最终正文身份 | [AdvisorSchema.java](/Users/agent/deepscope-java/technical-advisor-app/backend/src/main/java/io/deepscope/advisor/db/AdvisorSchema.java:93)，[TimelineStore.java](/Users/agent/deepscope-java/technical-advisor-app/backend/src/main/java/io/deepscope/advisor/session/TimelineStore.java:161) |
| 工具行已有详情、状态、耗时；现有 `formatRunDuration` 输出 `ms/s/m`，不符合截图的中文时间格式 | [ToolLine.tsx](/Users/agent/deepscope-java/technical-advisor-app/frontend/src/features/chat/components/ToolLine.tsx:31)，[conversationModel.ts](/Users/agent/deepscope-java/technical-advisor-app/frontend/src/features/chat/conversationModel.ts:392) |

现有能力说明：不能把全部会话概括成“工具统一在顶部”。当前已有按序展示分支；顶部集中和折叠差异来自新旧记录的两套处理规则。此次应收敛规则，而不是从头另建一套。

## 3. 推荐实现路径与取舍

| 选择 | 优点 | 限制 | 建议 |
|---|---|---|---|
| 仅改前端，结束时按最后一段文字推断最终正文 | 改动小 | 无可靠终态、历史真实时间；中断文字可能被误判 | 不采用为完整方案 |
| 应用层补展示元数据，正常结果确认后整轮折叠 | 复用现有事件扩展和存储链路，真实时间可回放 | 折叠发生在结果确认后，不能保证总结首字时折叠 | 推荐本轮采用，需确认上述交互差异 |
| 增加明确最终输出阶段，再在首字时折叠 | 能建立可靠的提前阶段边界 | 改变生成编排，可能涉及工具选择、额外模型调用和运行语义 | 若首字折叠是硬要求，单独完善此路径后评审 |

推荐方案属于应用前后端协作的中级改动。保持 `deepscope-core` 和 AG-UI 扩展源码不变；只读取它们现有事件，在 advisor 应用层扩展展示信息。

## 4. 顺序与折叠投影

### 4.1 一份顺序记录，两种显示状态

保持 `parts` 数组为显示顺序来源。一次连续文字输出是一个段落单元，不按浏览器自动换行拆分，也不通过文本关键词猜测调用归属。

- 执行中：按原顺序渲染文字、思考行、工具行和过程事件。
- 多个调用连续发生：依次显示在前一段说明下方；未输出说明时保留实际发生位置。
- 工具结果：按已有工具 ID 更新原行，不新增第二条返回行。
- 正常完成且有明确最终正文：此前文字、思考、工具及普通过程事件进入同一个可折叠区域，最终正文显示在外面。
- 没有最终正文或运行未正常完成：保持过程展开。

调整 `TurnProjection` 的含义：`ordered` 保存完整顺序，`process` 必须包含中间 `advisor_message`，不能继续排除全部普通文字。新增最终正文集合（拟命名 `finalBlocks`）以支持同一次最终回复中的多个文本块，不仅保留最后一个块。

`TurnGroup` 不再因为存在 `ordered` 而绕过整轮折叠。主会话的完成状态采用统一回合外壳；结束后不再露出“前三条工具预览”。单条工具详情继续复用 `ToolLine`。

### 4.2 可操作内容与定位

复用当前 `OUTSIDE_KINDS` 中追问、提案、错误、交付物等可见内容，避免折叠隐藏用户需要处理的事项。展示投影必须给每个块一个唯一归属，防止它同时出现在过程组和组外。

过程展开时保持事件顺序；收起时把过程区域替换成入口。需要用户处理的内容继续可见，不被包入过程区域。时间线点击过程块时，先展开该轮，再定位并高亮。

沿用已有 `partId` 锚点，不因从实时转成历史或折叠而重新编号。定位展开的优先级高于上次手动收起，避免现有 `manual` 状态阻止定位。

`SubagentView` 同样复用 `TurnGroup`，实施时须明确按主会话策略传参，保持它现有只读视图与耗时口径；不能顺带套用主会话的新计时字段，子智能体现有数据并不具备相同的最终正文起点。

## 5. 最终正文与运行终态

### 5.1 已验证的事件边界

- [AgentResultEvent.java](/Users/agent/deepscope-java/deepscope-core/src/main/java/io/deepscope/core/event/AgentResultEvent.java:22)：最终 `Msg` 在运行收尾才提供。
- [AgentLifecycleEventConverter.java](/Users/agent/deepscope-java/deepscope-extensions/deepscope-extensions-protocol/deepscope-extensions-agui/src/main/java/io/deepscope/core/agui/adapter/strategy/AgentLifecycleEventConverter.java:70)：`AgentStartEvent` 转 `RUN_STARTED`，`AgentEndEvent` 转 `RUN_FINISHED`，挂起工具可能携带 interrupt outcome。
- [AgentEventConverterRegistry.java](/Users/agent/deepscope-java/deepscope-extensions/deepscope-extensions-protocol/deepscope-extensions-agui/src/main/java/io/deepscope/core/agui/adapter/strategy/AgentEventConverterRegistry.java:96)：原生事件转换结果即使为空，也会进入应用层 enricher，可以利用这一扩展点读取 `AgentResultEvent`。
- [GenerateReason.java](/Users/agent/deepscope-java/deepscope-core/src/main/java/io/deepscope/core/message/GenerateReason.java:36)：除正常停止外还有工具挂起、权限询问、中断、直接工具返回等原因，不能将所有结果都当作成功总结。

### 5.2 应用层确认规则（拟新增）

在 `AguiRoundPersister` 的现有运行累积器内补充展示元数据，避免新建一套与兜底记录脱节的状态：

1. 只处理主运行；按 `threadId + runId` 隔离，排除带子智能体来源的原生事件。
2. 记录模型调用与文本块之间的关系，并记录各块第一个非空正文增量的服务端接收时间。
3. 收到 `AgentResultEvent` 后读取实际最终结果和 `GenerateReason`；首版自动折叠仅接受 `MODEL_STOP` 且包含可见正文的结果。
4. 根据最终结果与最后相关模型输出校验确定最终文本块集合；收到正常 `RUN_FINISHED`、且无挂起结果后发布“最终结果已确认”的应用级 `CUSTOM` 事件。
5. 错误、取消、权限等待、工具挂起、无法关联的最终结果：不发可折叠的最终确认，保留过程。

**关联不能假定为 `result.getId() == AG-UI messageId`。** 已检索到：

- [TextBlockEventConverter.java](/Users/agent/deepscope-java/deepscope-extensions/deepscope-extensions-protocol/deepscope-extensions-agui/src/main/java/io/deepscope/core/agui/adapter/strategy/TextBlockEventConverter.java:32) 的 AG-UI messageId 是 `replyId + "-" + blockId`。
- [ReActAgent.java](/Users/agent/deepscope-java/deepscope-core/src/main/java/io/deepscope/core/ReActAgent.java:3040) 为模型调用生成独立 `callId`。
- [ReasoningContext.java](/Users/agent/deepscope-java/deepscope-core/src/main/java/io/deepscope/core/agent/accumulator/ReasoningContext.java:166) 生成最终消息时使用累积的模型消息 ID。

因此采用原生文本事件的 `replyId/blockId` 与已转换的 AG-UI messageId 建立对应表；最终结果与模型输出的关联必须通过测试证明。常规无工具最终回复可结合最后相关模型调用及可见正文一致性确认；遇到 middleware 改写最终结果、直接工具返回等未证明路径，保守降级为展开，不做全文搜索替换或按 ID 前缀猜测。

这项关联测试是实施第一阶段的可行性门槛；若无法稳定关联，先调整方案，不用“最后一段一定是总结”掩盖缺口。

客户端补充运行生命周期信号，区分：`running`、`completed`、`failed`、`cancelled`、`awaiting_action`。这些是拟新增的展示状态，不声称项目目前已有。SSE 断开但未收到正常终态，不作为成功结束。

## 6. 耗时与数据契约

### 6.1 计时口径

本方案把“开始处理”明确为服务端观察到本轮 `RUN_STARTED` 的时刻；截止为经确认的最终正文集合中第一个非空正文增量的时刻。

`处理耗时 = finalStartedAt - runStartedAt`

包含本次运行中的思考、工具执行和等待结果；不包含最终正文自身的输出时间、前端落库请求、服务端 15 秒兜底延迟。点击发送到 AG-UI 启动前的访客落库、路由时间不在此口径内；若要统计这部分，需另行约定应用请求起点，不能混用浏览器与服务端时间。

时间统一取同一服务端时钟，展示时不与客户端 `Date.now()` 相减。已有 `firstTokenMs`、`usage.timeSeconds` 和工具耗时合计均不能替代此数值。

### 6.2 拟新增的可选展示元数据

以下均为方案拟新增字段，当前 API 不存在：

| 字段 | 含义 |
|---|---|
| `presentationVersion: 1` | 独立于现有 `partsVersion` 的元数据版本 |
| `presentation.runId` | 本次运行标识，防止重试混用时间与最终正文 |
| `presentation.runStartedAt` | 服务端观测到的主运行开始时刻，毫秒；未观测到时可缺省，但 completed 必须提供 |
| `presentation.finalMessageIds` | 确认后的最终 AG-UI 文本消息 ID 集合 |
| `presentation.finalStartedAt` | 最终正文第一个非空增量时刻；无可靠最终正文则不写 |
| `presentation.runEndedAt` | 正常结束、错误或取消时刻；无终态则不写 |
| `presentation.outcome` | `completed / failed / cancelled / awaiting_action / unknown` |

新增应用级 `CUSTOM` 事件拟命名 `advisor_presentation`，只携带这些展示信息；不作为普通 `advisor_event` 过程卡片，不新增时间线可定位行。

服务端维护最终 messageId 集合，前端根据已有文本 `messageId` 将其映射到 `partId`。没有 messageId 的旧块不强行匹配。既有 `partsVersion: 1` 结构保持，新增元数据通过 `PostMessageInput` 可选字段和 `advisor_message.payload` 传递。

复用接口：

- `POST /agui/run/{agentId}`：继续原有 SSE，通过新 `CUSTOM` 事件提供展示元数据。
- `POST /api/advisor/sessions/{sessionId}/messages`：接收新增可选展示字段。
- `GET /api/advisor/sessions/{sessionId}`：随既有 `entries[].payload` 返回元数据。

正常写入时，优先使用服务端累积器保存的该 runId 元数据，客户端上传内容用于传递与对账，不能覆盖服务端真实计时。校验版本、运行身份、时间顺序、最终 messageId 引用范围和终态；非法新字段返回 400，旧请求不要求新字段。

### 6.3 正常写入与兜底一致

`ConversationParts`、`AguiRoundPersister`、`AdvisorSessionController` 和 `TimelineStore` 必须同步支持元数据。服务端先读取该轮采样，再走既有正常保存流程；不能在 `markFlushed` 删除累积器后才尝试读取时间。

兜底保存同一份元数据，结束时间取实际事件时刻，不取调度器执行 `flush()` 的时刻。断线保存若未确认最终正文，则不生成成功折叠入口。

当前正常写入和兜底互斥存在多个操作步骤，本方案不宣称它们已经具有完整事务级 exactly-once 保证。此次测试须覆盖新元数据在既有两条保存路径中的一致性；不顺带重构整个会话写入事务。

### 6.4 文案格式

按截图显示低强调文本及方向箭头，不以工具计数作为主标题：

- 82 秒：`用时 1分钟 22秒 ›`。
- 小于 1 秒：`用时 不足1秒 ›`。
- 秒数按毫秒向下取整，分钟进位；整分钟可省略零秒；超过一小时显示小时、分钟、秒。
- 展开后箭头改变方向，时间保持不变；所有文案通过中英文 i18n 词条输出。
- 时间缺失或不合法：入口显示“查看执行过程”，不显示虚假的 0 秒。

在 `conversationModel.ts` 增加主回合耗时的格式化规则，复用测试设施；保持工具及子智能体既有 `formatRunDuration` 调用语义，避免影响它们的紧凑 `ms/s/m` 展示。

## 7. 状态、历史与滚动

| 场景 | 展示行为 |
|---|---|
| 正在执行 | 顺序展示过程，沿用单条思考/工具详情的点击展开 |
| 正常结束且最终正文确认 | 自动收起一次；入口显示固定处理耗时，最终正文留在外面 |
| 用户手动展开后发生元数据更新或历史替换 | 保持用户选择，不再次自动收起 |
| 时间线定位到收起区域 | 展开该轮后定位，优先级高于手动收起 |
| 无最终正文、取消、运行失败、待操作 | 保持过程可见；错误和待处理事项继续可见 |
| 某次工具失败但模型恢复并正常回答 | 可以整体折叠；入口保留简短异常提示，展开查看失败行 |
| 重新进入完成会话 | 从持久化元数据恢复默认收起状态与固定耗时 |

对于只有 `parts`、没有新元数据的记录，可在“回合已结束且无错误、挂起证据”的前提下使用末尾文本的兼容推断，但不能把这种推断声称为严格终态确认；时间缺失时只显示查看过程。已知失败、中断轮次优先保持展开。

更老的聚合记录缺少文字与工具交错信息，无法可靠还原“哪段文字对应哪次调用”。保留其已有顺序和可回看的过程，不自动拆分正文，也不通过 `createdAt` 人造交错关系。`createdAt` 是数据库追加时间，不能还原真实最终正文开始时间。

`ConversationPanel` 当前跟随最新内容的逻辑只依赖条目数、文本和运行状态，折叠还需要显式维护滚动位置：

- 用户正跟随底部时，折叠后使最终正文保持可见。
- 用户在查看历史或过程时，保持可见锚点及相对偏移，避免页面突然跳到末尾。
- 手动展开、收起和时间线定位分别处理，不能把所有高度变化都当作自动跟随。

## 8. 文件改动范围（计划，尚未实施）

| 文件 / 区域 | 计划职责 |
|---|---|
| `frontend/src/api/advisor.ts` | 新展示元数据类型与 `PostMessageInput` 可选字段 |
| `frontend/src/features/chat/aguiClient.ts` | 解析生命周期、interrupt outcome 和新展示事件 |
| `frontend/src/features/chat/useAdvisorConversation.ts` | 分离运行终态与保存状态；实时投影、正常上传、重试隔离 |
| `frontend/src/features/chat/model/presentationParts.ts` | 保留既有顺序和工具原位更新；接入最终文本身份所需信息 |
| `frontend/src/features/chat/model/blocks.ts` | 统一过程文字归属、最终正文集合、耗时和折叠条件 |
| `frontend/src/features/chat/components/TurnGroup.tsx` | 整轮折叠入口、箭头、手动展开及定位优先级 |
| `frontend/src/features/chat/ConversationPanel.tsx` | 折叠后的滚动锚定；避免新旧实时内容重复 |
| `frontend/src/features/chat/conversationModel.ts` | 主回合耗时格式化 |
| `frontend/src/features/chat/components/SubagentView.tsx` | 仅在共享组件接口变化时适配现有只读行为 |
| `frontend/src/i18n/locales/{en,zh-CN}/app.json` | 同步耗时、折叠及异常提示词条 |
| `backend/.../session/AguiRoundPersister.java` | 应用级结果确认、运行计时、展示事件和兜底保存 |
| `backend/.../session/ConversationParts.java` | 支持原生文本关联所需的累积信息与校验 |
| `backend/.../session/AdvisorSessionController.java` | 校验及对账新元数据，在累积器清理前读取 |
| `backend/.../session/TimelineStore.java` | 将元数据写入既有 JSON payload，兼容旧调用 |
| 对应已有测试及新增会话 E2E | 覆盖顺序、终态、计时、折叠、刷新与定位 |
| `docs/v2/{en,zh}/docs/others/technical-advisor-login.md` | 实施后同步用户可见的会话行为描述 |

表中 `frontend/`、`backend/` 均相对于 `/Users/agent/deepscope-java/technical-advisor-app/`。保留现有会话列表、路由、提案裁决、工具权限和原生模型历史的行为；仅在其展示接入点必要时调整。

## 9. 实施顺序与验收

第一阶段：先证明原生最终结果可以关联展示文本，补正常、挂起、取消的终态测试，并验证开始与最终正文起点均来自同一时钟。关联未证明前，不动整轮折叠 UI。

第二阶段：补展示契约、正常保存与兜底保存，测试数据刷新后保留同一耗时；继续使用现有 JSON payload，无表结构迁移。

第三阶段：统一回合投影和 UI，补中间文字整体折叠、最终多文本块、手动展开、时间线定位、滚动及子智能体共享组件回归。

第四阶段：补会话 E2E，同步中英文说明，按项目门禁执行并记录实际结果。

关键测试用例：

1. `文字 A → 工具 1 → 文字 B → 工具 2 → 最终正文 C`：实时顺序正确；完成后 A、B 与两次调用全部收起，C 保留。
2. 工具结果迟到：更新原工具行，最终正文不移动、不重复。
3. 模型先输出文字后请求工具：不会提前把这段文字认定为最终正文。
4. 多块最终正文：整体保留，不丢失除最后块以外的内容。
5. 采样数据设定起点与正文起点相差 82,000ms：显示“用时 1分钟 22秒”；延长正文输出或兜底延迟不改变该值。
6. 错误、停止、SSE 非正常结束、待权限确认：不出现成功折叠状态。
7. 展开后实时转历史：保持展开；定位被手动收起的过程：重新展开且高亮正确。
8. 旧记录无时间：不显示 0 秒；旧聚合正文不伪造与调用的对应关系。
9. 重试使用新的 runId：不继承前一次运行的耗时和最终正文身份。
10. 服务端兜底与正常上传：都保存同一套元数据，非法元数据不会先清掉兜底累积器。

复用已有前端 `presentationParts.test.ts`、`blocks.test.ts`、`ProcessViews.test.tsx`、`aguiClient.test.ts`、`useAdvisorConversation*.test.tsx/ts`，以及后端 `ConversationPartsTest`、`AguiRoundPersisterTest`、`AdvisorSessionControllerTest`、`TimelineStoreTest`。

当前仓库已存在 [playwright.config.ts](/Users/agent/deepscope-java/technical-advisor-app/frontend/playwright.config.ts)，`testDir` 为 `e2e`、匹配 `*.e2e.ts`，测试服务端口为 5190。沿用该配置新增会话用例，不沿用旧交接文档中“没有 Playwright 配置”的过时结论。

计划验证命令，**本次未执行**：

```bash
# /Users/agent/deepscope-java/technical-advisor-app/frontend
npm test
npm run build
npm run lint
npm run test:e2e

# /Users/agent/deepscope-java
mvn -T1 -pl technical-advisor-app/backend -am test
mvn -T1 -pl technical-advisor-app/backend -am spotless:check

# /Users/agent/deepscope-java/docs
npm test
npm run validate
npm run broken-links
```

项目规范规定跨模块或公共契约变更需要全量检查。实施评审时须确认本次应用 SSE/消息载荷的增量契约是否触发全量 `mvn -B -T1 clean verify`，不能把定向测试自动当作最终门禁。

## 10. 评审确认与兼容性

2026-10-14 用户已授权按推荐方案执行：“最终结果确认后折叠，时间统计到最终正文开始”。若必须首字即折叠，应先补充明确最终输出阶段的编排方案，本方案不能作为该要求已经满足的依据。

新元数据为可选、独立版本；旧客户端忽略新 `CUSTOM` 事件，旧请求和旧 payload 仍可读取。最终身份或时间证据缺失时保守展开或不显示耗时，不回填推测数据。回滚到旧前端会忽略新元数据；旧后端需继续兼容未知可选字段，实施测试应证明降级路径。

既有 [2026-10-11 会话设计](/Users/agent/deepscope-java/docs/superpowers/specs/2026-10-11-conversation-stream-design.md:36) 写的是结束后按工具次数等收起，并以最后无工具正文作为最终回复。本次批准后须同步更新相应条款，避免两份设计同时对同一交互给出不同验收标准。

实现和实际验证结果见 [2026-10-15 实施结果](/Users/agent/deepscope-java/technical-advisor-app/docs/conversation-collapse-results-2026-10-15.md)。设计描述不替代验证证据。
