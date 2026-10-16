# 阶段 C 实施计划 —— 会话内核切换

| 项 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 编制日期 | 2026-10-13 |
| 文档状态 | 待评审 |
| 依据 | `2026-10-12-session-dsh-replication-tds.md` 第 8 章；路线 2 |
| 改动等级 | 高级（公共 API、序列化格式、事件载荷） |
| 前置 | 阶段 A 内核（105 测试）+ 阶段 B 后端 B1–B5（215 测试），全量 verify 中 core 与 harness 均 SUCCESS |

---

## 1. 总览

阶段 C 把 A/B 已交付的新内核接进运行路径，替换旧实现，并让前端接上新后端。

### 1.1 三个阶段

| 阶段 | 干什么 | 可回退 |
| --- | --- | --- |
| **C1 接上** | 新内核接进 `Agent`/`AgentSession`；模型历史改由日志派生；写请求信封 | 是 |
| **C2 删旧** | 删除 27 个旧实现文件 | **否（唯一不可回退点）** |
| **C3 前端与文档** | 会话页接新后端；`docs/v2` EN/ZH 同步 | 是 |

**提交拆两次**：C1+C2 一次（代码），C3 一次（前端与文档）。理由是一次改动只承担一类风险；C1 与 C2 同提交是因为删旧后必须立即由新实现承接，中间不留双轨。

### 1.2 为什么只分三块

方案第 8.1 节已确立原则：真正卡住别人的只有内核接口，切得越细接口就绪越晚。C1 内部的三件事（接日志、改历史来源、写信封）**必须一起做才有可读产出**——只接日志不换历史来源，模型看到的仍是 checkpoint；只换历史来源不写信封，请求无法重放。它们的中间状态没有可验收的价值，因此合成一块，其内部检查点留在实施时使用，不在计划里占位。

### 1.3 明确不做

- 长期记忆、压缩策略的语义改造（独立立项：压缩要从"改写上下文"改为"遮蔽日志"，牵动记忆刷写）；
- 旧日志的迁移与导入（TDS 第 1.4 节：不兼容既有磁盘日志）；
- admin starter 与 agui starter 的数据源改造（它们本就读 `AgentStateStore` 旧语义，见 4.3）。

---

## 2. 前期事实核查（已完成，非推测）

### 2.1 前端不会因删旧实现而断流

- 前端只调用 `/api/advisor/*`（`sessions`、`sessions/{id}`、`sessions/attachments` 等），数据来自 `advisor/session/SessionStore` 的**自有 JDBC 表**；
- `technical-advisor-app/backend` 与 `agentscope-agui-spring-boot-starter` 均**零引用** `io.agentscope.core.session`，只依赖 `ReActAgent`、`Agent`、`RuntimeContext`、`AgentState`。

结论：前端读的是应用层自有会话表，删核心会话类不直接破坏前端；**不需要为过渡期设计兼容层**。

### 2.2 `ReActAgent` 的兜底链可用

`ReActAgent.java:733-770` 的三段分支：`SessionRecorder.from(ctx)` 非空 → 用 checkpoint；否则 → `stateStore`；再否则 → 内存 `stateCache`。因此**没有会话日志时程序仍可运行**。

### 2.3 `HarnessAgent` 会自动提供日志存储

`HarnessAgent.java:2724-2726`、`:2869`：未显式设置 `sessionLogStore` 时回落到工作区文件系统。因此"默认 `EVENT_LOG` 却无日志存储"的组合不成立。

**三条结论**：C1 无断流风险，无既有 bug，可直接开始。C1 的真实风险是**功能回归**（派生结果与旧实现不一致），由 C1 的对照测试承接。

---

## 3. C1 详细设计 —— 接上

### 3.1 设计原则

1. **内核只暴露两个动作：追加、派生。** `ReActAgent` 不再直接操作存储或序列化状态，只调用 `SessionLog.append*` 与 `deriveMessages()`。
2. **模型历史是派生的，不是存下来的。** 这是本次替换的核心：删掉"从 checkpoint 反序列化出工作副本"这条路。
3. **可重放是硬要求。** 每次请求的信封（模型、参数、工具清单）必须入日志，否则"当时发了什么"无从重建。
4. **对外签名不变。** `AgentSession` 的 `submit`/`steer`/`inject`/`respond`/`interrupt`/`resume`/`await` 及 `ReActAgent` 的公开方法一个都不改，只换内部实现。这条保证了 C1 自身可回退。

### 3.2 接线点（实测）

`agentscope-core/ReActAgent.java` 共 29 处旧 API 引用，集中在四处：

| 位置 | 现状 | 目标 |
| --- | --- | --- |
| `acquireCallExecution`（:814-871） | 建 `SessionRecorder`；`SessionExecution.prepare`；`start(...)` 写 `run/start`+`turn/start`+`input/received` | 取会话的 `SessionLog`；写 `TurnStart`；输入写成 `UserMessageData` |
| `activateSlotForContext`（:733-770） | `recorder.initial().restore()` 取 checkpoint 反序列化为 `AgentState` | **`log.deriveMessages()`** —— 历史来源替换 |
| `reasoning(iter)`（:2747-2861） | `recorder.step(iter)`；`SessionExecution.beforeStep` 合入 inject/steer；`SessionModels.wrap` 的 `prepareModel`/`model/chunk`/`model/end` | 写 `StepStart`/`StepEnd`；inject/steer 写成带来源的 `UserMessageData`；写 `AssistantMessageData`（含思考块与嵌入流）+ `RequestHeader` + `RequestContext` |
| `SessionRecorder.captureState`（:320-387） | 前缀 diff 生成 `context/replaced`+`message/*`+`state/checkpoint` | **不再写 checkpoint**；工作状态改由投影重建 |

### 3.3 历史来源替换的设计

这是 C1 最核心的一处改动，单独设计。

**现状**：`ReActAgent` 在 `activateSlotForContext` 里把 `AgentState` 从 checkpoint 反序列化出来，`AgentState.context` 即"模型看到的历史"，运行中作为可变工作副本被反复追加。日志只记录过程，模型历史另有来源。

**目标**：历史唯一来源是日志。每次要发请求时，用 `SessionLog.deriveMessages()` 得到消息列表。

**关键设计点**：

1. **工作副本退出历史角色。** `AgentState` 仍存在（工具上下文、权限、计划等运行期状态要用），但**不再承载模型历史**。历史由日志派生。
2. **写入即记录，不是"先改副本再补日志"。** 现状是 `ReasoningContext` 聚合出助手消息后追加进 `state.contextMutable()`，再由 `captureState` 的 diff 反推日志。目标改为：聚合出消息后**直接追加 `AssistantMessageData` 事件**，派生历史自然包含它。
3. **system prompt 的处理。** 现状由 `MessageUtils.prependSystemMessage` 在发送前拼上。目标改为：渲染后的系统提示作为**表面节点 0** 记录（`SystemMessageData`），发送时由 `deriveMessages()` 提供，**不再在发送前临时拼接**——否则同一份内容有两个家。
4. **注入与引导。** 现状 `SessionExecution.beforeStep` 把 inject/steer 合入 `state.contextMutable()`。目标改为写成 `UserMessageData`，用 `source` 区分直接提问（null）与注入（如 `steering`、`session_input`）。派生历史按位置自然包含它们。
5. **中间件与预处理器的接口不变。** `MiddlewareChain`、`firePreReasoning`、`ModelRequestPreparer` 收到的仍是消息列表（即 `deriveMessages()` 的结果），所以这些扩展点不需要改签名。

**为什么必须一次做完**：若只把来源换成日志、却仍在发送前拼接 system prompt，则日志里的历史与实发内容不一致，"可重放"不成立；若只写 `AssistantMessageData` 却仍以 `AgentState.context` 为准，则两条来源会分叉。两者都不能作为中间状态。

### 3.4 请求信封的设计

**现状**：只有 `request/prepared` + `model/dispatch` 这类过程记录，没有"与历史无关的完整请求信封"快照。

**目标**：每次请求把信封整体入日志：调用配置（provider/model/参数）、适配器解析出的默认值、**装配好的工具清单**。

**设计要点**：

1. **快照时机**：本 loop 实例的第一个请求写一次；信封变化时再写一次。原因取值对齐 DSH 的 `initial`/`resume`/`change`/`series`（见 `SessionRequestData.RequestHeaderReason`）。
2. **信封不含系统提示**。系统提示是表面节点 0，理由是"一个事实只有一个家"。
3. **工具清单记名字**（`toolNames`），因为工具 schema 的完整定义由工具注册表提供；记名字足以让"这一轮有哪些工具"可重建，也足以支撑动态增删工具的审计。
4. **路由元数据单独记**（`RequestContext`：provider/model/contextWindow），只在变化时写。它是会话页上下文环的数据源（上下文窗口容量）。

### 3.5 生命周期与崩溃恢复的设计

1. **回合与步骤**：`TurnStart`/`StepStart`/`StepEnd`/`TurnEnd` 四类，写点分别对齐现状的 `run/start`+`turn/start`、`recorder.step`、步骤结束、回合结束。
2. **回合结束原因**：用 `TurnEndReason` 枚举，其中 `INTERRUPTED`/`FORKED` 只由恢复与分叉合成，正常路径不发出。这条让"真实的结束"与"修补出来的结束"可区分。
3. **崩溃恢复**：不再靠 checkpoint，改为折日志找未闭合的回合与"有调用无结果"的工具，用 `TurnRepair.interruptedTurnClosers` 补记。两种文案按原因分叉（中断 / 分叉）。
4. **分支**：用 `SessionFork.buildForkSeed`，复制闭区间前缀 + 打继承标记 + 只关闭被截断的开放尾部。

### 3.6 状态持久化的处置

**本次不写 `state/checkpoint`。** 运行期工作状态（工具上下文、权限、计划）的持久化改造属于"记忆与压缩"独立立项（TDS 决策 D2）。

**但必须保证**：`AgentStateStore` 的既有语义不被破坏（AG-UI 与 advisor 依赖它，见 2.2/2.3），即 `ReActAgent` 在无日志时仍走 `stateStore` 分支。

**这是 C1 的边界**：C1 把**模型历史**的来源换成日志；工作状态的持久化维持在现状，留待独立立项。这样 C1 的范围可控，且不会把记忆模块一起拖进来。

### 3.7 `AgentSession` 与 `HarnessAgent`

| 组件 | 设计 |
| --- | --- |
| `AgentSession` | 对外方法签名不变；内部把 `SessionExecution`/`SessionInbox` 替换为新内核的追加与派生。待处理输入仍**先落盘**（保住"页面关闭不丢"），但作为普通事件族，不再有独立序号 |
| `HarnessAgent` | `session()` 取得 `AgentSession` 的方式不变；`sessionLog`/`sessionTranscript`/`inspectSession` 改接到 `SessionQuery`/`SessionSearch` |
| `SessionSearchTool` | 接到 `SessionSearch` + `SessionQuery`（B5 已交付） |
| 子智能体（`DefaultAgentManager`） | 子会话标识与继承边界改用 `SessionFork` |

### 3.8 C1 的验证设计

**C1 的验收核心是一条对照测试**：同一段会话，旧实现与新实现跑出的**模型消息列表逐条一致**。不一致必须解释清楚才继续。

分层测试：

| 层 | 覆盖 |
| --- | --- |
| 对照 | 同会话下 `deriveMessages()` 与旧 `AgentState.context` 逐条一致（文本、角色、顺序、工具调用与结果配对） |
| 接线 | 一次 `call()` 后日志含 `TurnStart`+`Step*`+`UserMessageData`+`AssistantMessageData`；系统提示是表面节点 0 |
| 信封 | 请求信封入日志；模型/工具清单变化时写新快照；系统提示不在信封里 |
| 注入 | inject/steer 落为 `UserMessageData` 且 `source` 正确；直接提问 `source` 为 null |
| 恢复 | 崩溃后补记；分叉继承边界正确 |
| 回归 | `AgentSessionTest`、`ReActAgent` 既有测试全绿（改断言须说明理由） |
| 端到端 | 提交、排队、引导、注入、应答、中断、继续 |

---

## 4. C2 详细设计 —— 删旧

### 4.1 删除清单

**`agentscope-core/src/main/java/io/agentscope/core/session/`（26 个）**：
`SessionRecorder`、`JournalSessionLog`、`SessionLog`、`SessionLogStore`、`InMemorySessionLogStore`、`AtomicSessionStorage`、`SessionEvent`、`SessionEventTypes`、`SessionEventCodecRegistry`、`SessionExecution`、`SessionInbox`、`SessionInteractions`、`SessionViews`、`SessionProjection`、`SessionTurns`、`SessionStateAccess`、`SessionRecovery`、`SessionMigration`、`SessionModels`、`SessionTranscriptExport`、`SessionLogExporter`、`SessionExportSink`、`SessionModelPolicy`、`SessionHeader`、`SessionKey`、`SessionHistoryMode`、`SessionLogException`。

**`agentscope-harness/.../agent/session/`（4 个）**：
`LocalSessionStorage`、`SqliteSessionStorage`、`StoreSessionStorage`、`WorkspaceSessionLogStore`（寻址能力迁入新后端）。

**保留**：`SessionAnswer`（`AgentSession` 对外签名依赖）。

### 4.2 删除顺序与验证

1. 先按引用倒序删（叶子先删，减少中间编译失败次数）；
2. 每删一批编译一次，确保无残留引用；
3. 全删后跑全量 `mvn -B -T1 clean verify`；
4. 加一条架构测试：断言 `io.agentscope.core.session` 包下不再有类（防止回填）。

### 4.3 为什么 admin / agui 不在本次范围

两者现读 `AgentStateStore`（旧语义），而 C1 已明确不改造工作状态持久化（3.6）。强行纳入会让本次范围从"会话模块"扩到"后台管理"，风险与工作量翻倍。标记为遗留，待确认使用情况后独立立项。

### 4.4 回滚

C2 是唯一不可回退点。回滚方式：恢复被删文件 + 从旧日志格式重开。因 TDS 已声明不兼容旧日志，**旧磁盘数据不保留**，所以回滚是"回到旧代码"而非"回到旧数据"。

---

## 5. C3 详细设计 —— 前端与文档

### 5.1 前端

**按 TDS 第 5 章的契约做增量装配**，关键设计：

1. **四种窗口变更**：整窗重建、尾部追加、历史前插、**尝试结算**。
2. **结算语义**：一次模型尝试结束时，先移除该尝试的临时条目，再插入 durable 结果；**未受影响的节点身份不变**。
3. **前插不动已有节点**：向上翻历史不得改变已渲染节点的身份，否则内容跳动或重复。
4. **深度思考**：助手消息**自带思考内容块**（C1 已保证），所以刷新后能还原；流式期间按块累加、按动画帧发布。
5. **工具行**：调用标识跨三阶段稳定；卡片只从日志原始记录派生；未注册的落到通用卡片。
6. **传输层不变**：仍走 AG-UI 协议，只换流内承载的内容。

**注意**：前端读的是 advisor 自有表（2.1），因此 C3 的前端工作**不等于**"接核心会话日志"——需先明确会话页要读哪些内容、由谁提供（advisor 表 or 新查询通路）。**这是 C3 开工前必须查清的第一件事。**

### 5.2 文档

- `docs/v2/{zh,en}/docs/harness/session-log.md`：事件目录、存储布局、恢复语义、扩展方式全部重写；
- `session-chat` 示例与受影响的最佳实践页同步；
- TDS 第 5 章的界面口径回写 `2026-10-11-conversation-stream-design.md`；
- EN/ZH 成对更新。

---

## 6. 风险

| # | 风险 | 对策 |
| --- | --- | --- |
| R1 | `ReActAgent` 6510 行、29 处引用，接线面广 | 内核只暴露"追加 + 派生"；改动集中在 3.2 的四处 |
| R2 | 历史来源换了之后模型看到的内容与旧实现不一致 | 3.8 的对照测试是闸门；不一致必须解释后才继续 |
| R3 | 系统提示"两个家"（日志 + 发送前拼接）导致不可重放 | 3.3 第 3 点：只在日志里，发送前不拼 |
| R4 | 工作状态持久化的改造被误纳入 | 3.6 划清边界：C1 只管模型历史 |
| R5 | 删除面大（30 个文件） | 倒序删、每批编译、架构测试防回填；C2 独立提交 |
| R6 | C3 前端实际要读什么尚未查清 | 5.1 末段列为 C3 开工前第一件事 |

---

## 7. 门禁

- `mvn -B -T1 clean verify` 全量通过（含 javadoc）。已知离线环境限制：`agentscope-extensions-jev` 依赖 `playwright:1.45.0` 未缓存，需联网；
- `mvn -T1 -pl <module> -am spotless:check`；
- Ubuntu + Windows 双平台语义一致（**Windows 至今未验，须补**）；
- C3 另跑前端门禁：`npm test` + `npm run lint`。

---

## 8. 待评审确认

1. **3.6 的边界**：C1 只换模型历史来源、暂不改造工作状态持久化（记忆/压缩独立立项）——是否同意；
2. **C1 与 C2 同提交**：删旧后必须立即由新实现承接，不留双轨——是否同意；
3. **C3 开工前需查清**：会话页要读哪些内容、由 advisor 自有表还是新查询通路提供。

---

## 附录 A：C1 对照测试规格（可直接落地）

本附录把第 3.8 节的"对照测试"细化为可写的用例。**这是 C1 的闸门：本组测试全绿之前不得进入 C2。**

### A.1 可用的测试基建（已实测）

`agentscope-core/src/test/java/io/agentscope/core/session/ReActSessionLogTest.java` 提供了现成骨架：

- `CaptureModel`：记录每次 `stream(msgs, tools, options)` 收到的 `List<Msg>`。**这是"模型实际看到什么"的权威观测点。**
- `context()`：`RuntimeContext.builder().userId("u").sessionId("s").build()`。
- `InMemorySessionLogStore`（旧）——C1 后应改用新的 `LocalSessionStore`（`@TempDir`）。
- 断言范式：`assertTrue(second.calls.get(0).stream().anyMatch(m -> text.equals(m.getTextContent())))`。

### A.2 前置：一个必须解决的测试前提

**当前无 `sessionLogStore` 时 `ReActAgent` 走内存 `stateCache` 兜底（第 2.2 节），没有 `SessionLog` 可派生。** 因此对照测试必须先给 agent 配一个日志来源。C1.1 的实施需要在 `ReActAgent.Builder` 上提供新内核的日志接入点（例如 `sessionLog(SessionLog)` 或沿用 `sessionLogStore` 但返回新类型）。

**这是 C1.1 的第一个具体编码动作**：确定日志接入点，并让对照测试拿到同一个 `SessionLog` 实例，从而可以对比 `model.calls` 与 `log.deriveMessages()`。

### A.3 用例清单

| # | 用例 | 断言 |
| --- | --- | --- |
| A1 | 单轮对话 | `model.calls.get(0)` 与 `log.deriveMessages()` 逐条一致（角色 + 文本 + 顺序） |
| A2 | 多轮对话（同一 agent 连续两次 `call`） | 第二次的 `calls.get(0)` 含第一轮的用户消息与助手回复，且与派生结果一致 |
| A3 | 重建 agent 后继续 | 新 agent 的 `calls.get(0)` 含全部历史，与派生结果一致（**这条直接针对"历史来源替换"**） |
| A4 | 系统提示位置 | 派生历史的第一条是 `SYSTEM` 角色；且日志中它是**表面节点 0**（`system/message`），而非信封内容 |
| A5 | 注入（inject） | 注入文本出现在派生历史中，其 `user/message` 的 `source` 非空；直接提问的 `source` 为 null |
| A6 | 工具调用配对 | 助手消息含 `ToolUseBlock`，随后的 `tool/result` 携带同一 `callId`；派生历史里工具结果与调用配对 |
| A7 | 思考块随消息走 | 助手消息内容含 `ThinkingBlock`；重开日志后派生结果仍含它（**防 A 阶段修过的解码丢失回归**） |
| A8 | 请求信封可重放 | 日志含 `request/header`；其 `toolNames` 与本次 `stream()` 收到的 `tools` 名称集合一致 |
| A9 | 重开后统计可还原 | 经 `LocalSessionStore` 关闭再打开，`SessionStatsProjection` 的 `llmMs`/`ttftSteps` 与关闭前一致 |
| A10 | 崩溃恢复 | 构造一个未闭合回合的日志，`TurnRepair.interruptedTurnClosers` 补记后 `turn/end` 原因为 `INTERRUPTED` |

### A.4 对照口径（避免"看起来一致"）

- **比什么**：角色、文本内容、工具调用/结果的 `callId` 配对、顺序。**不比** `Msg.id`（派生会重新生成）与 `timestamp`。
- **不比消息对象身份**：对照比的是内容序列，不是同一性。
- **差异必须可解释**：任何不一致先判断是"实现缺陷"还是"设计有意变更"（例如系统提示从发送前拼接改为表面节点 0，位置相同但来源不同）。**只有在能明确归因后才可改断言，并在提交信息中写明理由。**

### A.5 已知会让 A 组变红的三处设计变更

以下三处是 C1 的**有意变更**，对照测试会因此出现差异，须按 A.4 归因后再调整：

1. **系统提示来源**：由"发送前拼接"改为"表面节点 0"（第 3.3 节第 3 点）。位置应相同，但日志中多出 `system/message` 事件。
2. **注入与引导的表示**：由"合入 `AgentState.context`"改为"带 `source` 的 `user/message`"（第 3.3 节第 4 点）。内容应相同，`source` 是新增信息。
3. **不再写 `state/checkpoint`**：日志中不再出现 `state/checkpoint` 事件（第 3.6 节）。派生历史不应因此改变；若有改变，说明工作状态与模型历史仍然耦合，属缺陷。

---

## 附录 B：生命周期事件核对表（C1.3，实测）

以实测清单为准：旧词汇 62 个，新内核 14 个。

### B.1 已一致，无需改动（9 个）

`turn/start`、`turn/end`、`step/start`、`step/end`、`tool/result`、`user/message`、`assistant/message`、`system/message`、`request/header`。

**注意后四个是前两轮 C1 改名的结果**，不是原来就一致。

### B.2 新内核有、旧没有（5 个，需补）

| 新事件 | 用途 | 旧实现里对应什么 |
| --- | --- | --- |
| `assistant/attempt` | 未产生表面消息的模型尝试（失败/重试/取消），内嵌流 | 无对应：旧实现只记过程（`model/retry`），不保留可重放的尝试流 |
| `developer/message` | 增量会话变更（工具增删），用表面节点表达 | 无对应：旧实现没有"工具清单变化"的日志表达 |
| `request/context` | 路由元数据（provider/model/contextWindow） | 部分对应 `model/dispatch`，但后者不含上下文窗口容量 |
| `tool/call` | 模型请求的工具调用 | 对应 `tool/requested`（需改名） |
| `session/end-seed` | 继承切点标记 | 旧实现用 `migration/baseline` 兼职，语义不同 |

### B.3 旧有、需映射或合并（重点：三处语义差异）

**差异 1：run 与 turn 的两级结构折叠为一层**

| 旧 | 新 |
| --- | --- |
| `run/start` + `run/end`（一次实际执行） | 无独立 run：`turn/start` + `turn/end`（回合 = 一次执行） |
| `run/stop_requested` | `turn/end` 的 `ABORTED` 原因 |
| `turn/resumed` | `turn/start`（同一回合的新执行续接） |

这是 TDS 决策 D3 的实施点，**不是改名**：旧的 turn（逻辑任务）与 run（实际执行）是一对多，新设计把它们合并。改动会影响 `SessionTurns`、`AgentSession.await` 与恢复逻辑，属语义变更。

**差异 2：六个终态事件合并为一个带原因的 `turn/end`**

| 旧 | 新（`TurnEndReason`） |
| --- | --- |
| `turn/completed` | `COMPLETED` |
| `turn/suspended` | `BLOCKED`（待确认：挂起是否等同阻塞） |
| `turn/failed` | `ERROR` |
| `turn/cancelled` | `ABORTED` |
| `turn/interrupted` | `INTERRUPTED` |
| `turn/output` | 无对应：输出由 `assistant/message` 承载 |
| （触顶） | `MAX_TOKENS`（新增强） |
| （分叉） | `FORKED`（新增强，仅 fork 合成） |

**待确认**：`turn/suspended`（等用户答复）映射到 `BLOCKED` 是否正确，或需新增一个 `SUSPENDED` 原因。**这是本表唯一需要产品语义判断的一处。**

**差异 3：流式细节从独立事件改为嵌入助手消息**

| 旧 | 新 |
| --- | --- |
| `model/chunk` + `model/end` + `model/dispatch` | `assistant/message` 内嵌精确带时间的紧凑流（`AssistantStreamRecord`） |
| `tool/chunk` + `tool/dispatch` + `tool/decision` + `action/start` + `action/end` | `tool/call` + `tool/result`（含 `meta` 展示载荷与失败标识） |

这是 TDS 决策"一个尝试一次结算"的实施点：旧实现把流式过程逐块记录，新设计把一次尝试的精确流压成一条记录。

### B.4 旧有、新设计有意不保留（21 个）

| 家族 | 事件 | 处置 |
| --- | --- | --- |
| inbox（8 个） | `inbox/accepted`、`started`、`applied`、`opened`、`closed`、`rejected`、`handled` | 待处理输入退化为普通事件族（TDS 决策 D4）；轨迹状态需重新设计或降为诊断 |
| 输入（4 个） | `input/received`、`applied`、`discarded`、`turn/resumed` | 输入即 `user/message`；"已应用"由位置表达 |
| 交互（2 个） | `interaction/requested`、`resolved` | 扩展族（HITL 待办），新内核按扩展事件承载 |
| 压缩（2 个） | `compaction/start`、`end` | 扩展族；新设计的压缩是"遮蔽不删除"（表面替换） |
| 管理（4 个） | `task/changed`、`plan/changed`、`permission/changed`、`verification/result` | 扩展族；工作状态改由投影重建（TDS 决策 D2，独立立项） |
| 子智能体（3 个） | `subagent/spawned`、`completed`、`linked` | 扩展族；分叉改用 `SessionFork` |
| 其他（6 个） | `model/retry`、`context/build`、`context/replaced`、`migration/baseline`、`presentation/hint`、`state/restored` | 扩展族或由新机制替代（替换用表面 `SurfaceOp.Replace`） |

### B.5 结论与下一步

1. **13 个事件无需改动**（B.1 的 9 个 + 已改名的部分），改造成本低于计划书预估；
2. **5 个新事件需补**（B.2）；
3. **三处语义差异需实现**（B.3），其中 run→turn 折叠与终态合并会影响恢复逻辑，**必须先有对照测试**；
4. **21 个旧事件降为扩展族或不再保留**（B.4），但**降为扩展族不等于可以丢弃**：`task/plan/permission` 与 HITL 交互是现网功能，需在 C1 中以扩展事件形式保留，直到 D2 独立立项接管。

**本表唯一需要产品语义判断的问题**：`turn/suspended`（Agent 正在等用户答复）应映射为新内核的 `BLOCKED`，还是需要一个专有的 `SUSPENDED` 原因？

---

## 附录 C：记录层类型化改造（C-2 第二步）

### C.1 决定性事实：转换只有一个收口点

记录层写事件全部经由**同一个方法**：

- `SessionRecorder.append(String type, Object payload)`（约第 194 行），共 29 处调用点全部经过它；
- 它最后把 `payload` **序列化成 JSON 字符串**，装进旧 `SessionEvent`，再交给 `SessionLog.commit(...)`。

**因此 29 个调用点不需要逐个改。** 要改的是这一处方法产出的东西。

### C.2 真正的扩散源是类型定义，不是调用点

旧 `SessionEvent` 的载荷字段是 **JSON 字符串**（`payloadJson`）。类型化载荷装不进去。

所以改造的核心是：**把 `SessionEvent` 的载荷从 JSON 字符串改成类型化载荷**。一旦改了它，凡读取该字段的地方都要跟着改：

| 读取方 | 读什么 |
| --- | --- |
| `SessionViews.transcript` | 消息事件 → 派生历史 |
| `SessionProjection.read` | 检查点、未确定工具调用、活跃执行 |
| `SessionStateAccess` | 管理端状态快照与变更 |
| `SessionRecovery` | 中断收尾、取消 |
| `SessionTurns` | 回合与输入状态 |
| `SessionRecorder` 自身 | 幂等判断、去重 |

**桥（`NativeSessionLog.toSurfaceEvent`）里的翻译逻辑就是现成的「旧形状 → 新形状」对应表**，照它改读类型化载荷，没有猜的成分。

### C.3 旧事件类型 → 新内核类型对照

| 旧 | 新 | 说明 |
| --- | --- | --- |
| `message/user` | `user/message` | 已改名（C-1 完成） |
| `message/assistant` | `assistant/message` | 已改名；需带思考块 |
| `message/system` | `system/message` | 已改名 |
| `tool/result` | `tool/result` | 同名，需类型化 |
| `request/prepared` | `request/header` | 已改名；需带 `reason` |
| `turn/start`、`turn/resumed` | `TurnStart` | |
| `turn/{status}`（六种） | `TurnEnd` + 原因 | 已统一（C-1 完成） |
| `step/start`、`step/end` | `StepStart`、`StepEnd` | 需带回合与步骤号 |
| `run/start`、`run/end` | `PayloadData` | **保留**：见附录 B 的结论，它们携带回合层没有的信息（coverage、停止请求） |
| `state/checkpoint` | **不再写** | 工作状态改由投影重建（决策 D2，独立立项） |
| `input/received`、`input/applied` | **输入事件族** | 见附录 D；「已应用」由位置表达 |
| `context/replaced` 等 | 表面替换 | 用 `SurfaceOp.Replace`，不再自定义事件 |
| 其余（inbox、交互、子智能体、验证…） | `PayloadData` | 扩展族，保留 |

### C.4 施工顺序

1. `SessionEvent` 载荷改为类型化；
2. `append(type, payload)` 不再序列化，直接把载荷封进事件；
3. 按 C.2 的六个读取方逐个改读类型化载荷，**每改一处用旧测试对照**；
4. 桥的翻译逻辑随之失去存在理由 → 删除桥；
5. 删除旧的事件类型与旧存储实现（第三步）。

### C.5 已知风险

- **中途会编译不过。** 改了类型定义，编译会立刻在数十处报错，每处都要判断该读什么类型。若有余量不足，仓库会停在不可编译状态。
- **对照基准会短暂失效。** 旧测试断言的是旧事件名；改造期间一批测试会变红，**那批红测试就是待办清单**，不是失控。

### C.6 两个已验证的实现细节

1. **位置属于记录，不属于载荷。** 类型化载荷里没有序号，序号来自事件本身。任何需要「这个载荷在第几位」的判断，都要在折叠时把位置与载荷成对携带。
2. **回合有两种命名。** 输入按**回合标识**（字符串）归属，工作时间按**回合序号**（数字）记录。两者的对应必须在折叠里显式处理，否则「已应用」判断永远不成立。
