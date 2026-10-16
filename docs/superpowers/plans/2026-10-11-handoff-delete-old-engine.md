# 交接：删掉旧记录协议，只留 DeepSeek 会话引擎

| 项 | 内容 |
| --- | --- |
| 日期 | 2026-10-11 |
| 状态 | 生产写入已切到内核。旧类还在磁盘上。删除未开始 |
| 权威做法 | `docs/superpowers/plans/2026-10-11-single-deepseek-session-engine.md` 的 Task 4 |
| 不要当现状用 | `docs/superpowers/plans/2026-10-13-step3-deletion-inventory.md` |
| 本次修订 | 复核全仓库后并入 5 处残留（第 7 节），执行阶段由 4 个收成 2 个（第 8 节） |
| 改动分级 | **高级改动**（`.ai-spec/change-levels.md` §三）：命中「`agentscope-core` 公共 API / 序列化状态格式 / 事件 payload」与「破坏性变更」。需兼容性分析（第 9 节）、回滚方案（第 10 节）、分阶段验证节点（第 8 节），门禁 `mvn -B -T1 clean verify` |
| 提交授权 | **未授权**。`.ai-spec/change-levels.md` §四.8：未经用户明确授权不 commit、不 push。本次执行到阶段二 Step 8 为止 |

用户原话：保留新建的 DeepSeek 会话引擎，替换旧引擎，并下线删除。一次做完，不要再留翻译层，也不要按清单里的「本批不删」再推迟。

本轮用户说「停下，写交接」。代码停在删除之前。

修订原因：原交接的范围是 core + harness。照原文执行会有 5 处残留，其中 2 处（examples、extensions）会让根 reactor 编译不过。残留的处置见第 7 节，阶段编排见第 8 节。

---

## 1. 做完是什么样

生产只使用 `io.agentscope.core.session.log`。一次调用追加类型化内核事件。模型历史只从 `MessageDerivation.history` / `SessionLog.deriveMessages()` 来。

`io.agentscope.core.session` 生产源码只留：

- `SessionKey`
- `AtomicSessionStorage`
- `InMemorySessionLogStore`（改成内核工厂，不再实现旧 `open()`）

`SessionLogException` 移到 `io.agentscope.core.session.log`，原文件删除。

**工厂抽象落在 `session/log/`（本轮定的设计点）**：`SessionLogStore` 被删后，`ReActAgent` / `HarnessAgent` / builder 仍需要一个能同时容纳 `InMemorySessionLogStore` 与 `WorkspaceSessionLogStore` 的类型。按「`session` 包只留 3 个类，`log/` 包不受限」，工厂接口迁到 `io.agentscope.core.session.log.SessionLogStore`（沿用简单名，减少改动面），方法集为内核方法：`openWriter` / `appendExternal` / `readKernel` / `owner` / 列表。旧 `open()` 不随迁。

**注意原交接的一处笔误**：`SessionAnswer` 不在 core，它在 `agentscope-harness` 的 `io.agentscope.harness.agent.session.SessionAnswer`。core 侧的白名单是 **3 个类**，架构断言不要照抄 4 个。

存储实现留下，它们不是旧引擎：`NativeSessionLogStore`、`LocalSessionStorage`、`SqliteSessionStorage`、`StoreSessionStorage`。向量库里的 `payloadJson` 不删。前端不在这次范围里。旧磁盘日志不迁移。

### 1.1 有意保留的载荷名（不是遗留物）

删除只删**类型**，不删**事件名**。下面这些名字在删除后仍然是内核词表的一部分，不要顺手「统一」：

| 名字 | 状态 | 写入 / 读取点 |
| --- | --- | --- |
| `turn/start`、`turn/resumed` | **唯一在写的回合开始事件**（阶段一 Step 3 后成类型化 `TurnStart`） | 写入：`DurableSession.start` |
| `run/start` | **已无写入点**，只剩读取方 | 只有待删的 `CallLog` 写过。读取方 `ChatHistory:77`、`ChatSessionsTest:70`、`CompactionMiddlewareTest:89` 已经取不到值 → 阶段一 Step 6、阶段二 Step 3 必须改读 `turn/start` / `TurnStart` |
| `run/end` | **仍在写**，不透明 `PayloadData`，与 `turn/end` 同义 | 写入：`DurableSession.finish`（同一次 finish 同时写 `turn/end` 与 `run/end`）。读取方 `ChatHistory`、`ChatItems:240` |
| `state/checkpoint` | **仍在写**，唯一检查点名 | 写入：`DurableSession.checkpointNow` |
| `state/snapshot` | **只读别名**，全仓库没有写入点 | 仅 `ReActAgent`、`DurableSession` 的读取端接受它 |

`DurableSession.finish` 同时写 `turn/end`（经 codec 成类型化 `TurnEnd`）和 `run/end`（不透明 `PayloadData`）。同一语义两个名字是设计决定，不是待清理的重复；但 `run/start` 不是同类——它已经**没有生产者**，只是消费者还没跟上。

---

## 2. 已经落地的

这些已经改完，并通过下面这一条命令（exit 0）：

```bash
mvn -pl agentscope-core,agentscope-harness -am test \
  -Dtest=AgentSessionTest,ReActHistoryFromLogTest,SessionExecutionIdentityTest,ReActSessionLogTest \
  -DfailIfNoTests=false -q
```

- `ReActAgent` 用 `sessionLogStore.openWriter(...)` 打开 `DurableSession`。历史来自 `workingState`。没有 store，或 `legacySessionHistory(true)`，才走内存 `AgentState`。
- `SessionHistoryMode.java` 已删除。替代物是 `ReActAgent` / `HarnessAgent` 上的 `boolean sessionHistoryLegacy` 和 `Builder.legacySessionHistory(boolean)`。
- `AgentSession` 的 submit / steer / inject / respond / resume 写成内核 `SessionInputQueue.Input`，经 `appendExternal` 进主日志。不再使用 `SessionInbox`、`SessionExecution`、`CallLog`。
- `SessionEventCodec` 能编解码 `SessionInputQueue.Input`。短命外部写入的 run id 是 `external-<uuid>`。
- `openWriter` 把 run id 放进 `NativeSessionLogStore` 的 `owners`，所以旧 `head().owner()` 仍能看见正在写的人。`detach` 会清掉。`DurableSession.finish` 会 `close()`，否则第二次调用报 session 已被占用。
- `readState` 只读最后一条 `state/checkpoint` 或 `state/snapshot`，不把派生历史盖回去。`workingState` 仍会为模型调用叠历史。检查点类型必须是 `state/checkpoint`，旧测试里的 `SessionProjection.restore` 还认这个名字。
- `HarnessAgent` 的 transcript / inspect、`SessionSearchTool`、`ChatHistory`、`ChatItems` 已不再调用 `SessionViews` / `SessionProjection` / `SessionInteractions`。
- `DefaultAgentManager` 不再放入 `SessionExecution` / `SessionExportSink`。
- `ToolExecutor`、`ActionObservations`、`AgentSpawnTool`、`ConversationCompactor`、`VerificationService` 已改读 `DurableSession`。

`AgentSessionTest` 2 个、`ReActHistoryFromLogTest`、`SessionExecutionIdentityTest` 6 个、`ReActSessionLogTest` 5 个，在上述命令里通过。

没有跑过：`agentscope-core` / `agentscope-harness` 全量测试，chat 例子测试，以及最后一轮里的 `SessionInboxCrashRecoveryTest`。

---

## 3. 还没做的就是删除

`2026-10-13-step3-deletion-inventory.md` 写的是 2026-10-11 当时「还有生产调用、那一轮不能删」。它现在是过期快照。`SessionHistoryMode` 已经不在源码里，清单仍写着「在」。`ReActAgent` 也不再调用 `SessionRecorder`。不要按那张表把旧类留下。

按 `2026-10-11-single-deepseek-session-engine.md` Task 4 删除这些文件：

```text
agentscope-core/src/main/java/io/agentscope/core/session/log/CallLog.java
agentscope-core/src/main/java/io/agentscope/core/session/log/KernelHistory.java
agentscope-core/src/main/java/io/agentscope/core/session/RecordedPayloads.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionLog.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionLogStore.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionEvent.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionEventTypes.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionEventCodecRegistry.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionExecution.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionInbox.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionInteractions.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionViews.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionProjection.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionTurns.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionStateAccess.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionRecovery.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionMigration.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionModels.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionModelPolicy.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionTranscriptExport.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionLogExporter.java
agentscope-core/src/main/java/io/agentscope/core/session/SessionExportSink.java
```

`SessionLogException.java` 移到 `io.agentscope.core.session.log` 后删原文件。

`NativeSessionLogStore` 里删掉 `OpenSession`、`toRecordedEvent`、旧 `commit`、`appendKernel`。它不再 `implements SessionLogStore`。

---

## 4. 删之前必须先拆的生产引用

旧辅助类（`CallLog`、`SessionExecution`、`SessionInbox`、`SessionViews`、`SessionProjection`、`SessionTurns`、`SessionRecovery`、`SessionStateAccess`、`SessionMigration`、`SessionTranscriptExport`）生产路径已经不用。它们还互相引用，测试也还 `new` 它们。生产上真正挡住删除的是下面四条。

### 4.1 旧 `SessionLog` / `SessionLogStore`

`NativeSessionLogStore` 仍实现旧接口，内部类 `OpenSession` 仍把内核事件翻成旧 `SessionEvent`（`toRecordedEvent`）。这就是还在的翻译层。

还在调用旧 `open()` / `sessionLog()` / `head()` / `scan()` / `readAfter()` 的生产代码：

| 位置 | 现在依赖什么 |
| --- | --- |
| `ReActAgent.sessionLog` | `sessionLogStore.open(...)`，返回旧 `SessionLog` |
| `HarnessAgent.sessionLog` | 转给上面 |
| `AgentSession` | 构造时拿这份旧日志。`steer` / `await` / `receipt` / `dispatch` / `requireIdle` 用 `head().owner()` 和 `leaseUntil()`。`leaseUntil()` 在实现里恒为 `Long.MAX_VALUE`，有 owner 就表示有写者 |
| `SessionSearchTool.sessionHistory` | `store.open` 后看 `head().seq() == 0` |
| `ChatHistory` | `log.head()` + `log.scan`，并用 `KernelHistory` 判断能不能读内核 |
| `ChatSessions` | `session.log().head()` |

改法：读改成 `readKernel` / `NativeSessionLogStore.read`。写者是否存在改成读 `owners`（在 store 上加 `owner(sessionId)`）。序号改成内核 `SessionLog.size()`。扫描改成 `events()`。`sessionLog()` 的返回类型改成 `io.agentscope.core.session.log.SessionLog`。

`InMemorySessionLogStore`、`WorkspaceSessionLogStore` 留下。它们的旧 `open()`（返回旧 `SessionLog`）**在阶段一保留**：阶段一 Step 8 的跨协议对账要用它当参照物。它随 `SessionLogStore` 接口在阶段二一起消失，**不要提前改它的返回类型**——改了就等于当场废掉对账参照物。

生产路径已经不经过它：`ReActAgent.sessionLog` 走 `readKernel`，「有没有写者」走 `store.owner(...)`。阶段一为此在 `SessionLogStore` 上加了一个 `default owner(SessionKey, RuntimeContext)`（默认 null，三个 store 各自转发到 `NativeSessionLogStore.owner(sessionId)`）。它是归属读口，不是翻译层；阶段二 Step 3 要把它一起处理掉。

工作区存储按 `RuntimeContext` 选 `AtomicSessionStorage`，所以 `openWriter` / `appendExternal` / `readKernel` / `owner` **必须带 context**，否则 steer 会写到另一份 store。

`SessionLogStore` 现在是函数式接口，唯一抽象方法是 `open`。只实现 `open` 的 lambda 不会得到内核写入，默认 `openWriter` 会抛 `UnsupportedOperationException`。`ReActSessionLogTest` 里那个路由 store 必须是匿名类，转发 `open`、`openWriter`、`readKernel`。删掉旧 `open` 之后，这个测试要改成转发新方法。

### 4.2 `RecordedPayloads`

`DurableSession.append(String, Object)` 仍调用 `RecordedPayloads.toData`。`start` / `step` / `checkpointNow` / `finish` 仍用字符串事件名。磁盘上的字节已经是内核事件，但写入 API 还是字符串。

删 `RecordedPayloads` 时，这些调用改成直接 `append` 类型化对象：`TurnStart`、`TurnEnd`、`StepStart`、`StepEnd`、`UserMessageData`、`AssistantMessageData`、`SystemMessageData`、`ToolResultData`、`RequestHeader`、`PayloadData`。不要把 `toData` 换个类名留着。

`finish` 里的 `turn/end` 经 `RecordedPayloads` 会变成 `TurnEnd`。`ChatHistory.statusOf` 如果只认 `PayloadData` 的 status 字段，挂起可能被读成 `completed`。删的时候对一下 chat 测试。

`finish` 还写 `run/end`（不透明 `PayloadData`），这是有意保留的，见 1.1。

### 4.3 `SessionModels`

`ReActAgent` 推理流和 `modelForCall`，以及 `ContextModelCalls`，仍调用 `SessionModels.wrap`。包装器会调 `DurableSession.prepareModel`（写出 `request/header`）并追加 `model/chunk`、`model/end`。

计划要求删掉 `wrap`，模型调用用原模型，请求信封由 `RequestHeader` 负责。若只删调用、不把 `prepareModel` 挪到推理路径，`ReActHistoryFromLogTest` 里「请求信封写进日志」会失败。`SessionModelPolicy` 只有 `SessionModels` 在用，随它一起删。

### 4.4 `KernelHistory`

只有 `ChatHistory` 和 `OpenSession` 使用。`OpenSession` 删掉后，`ChatHistory` 直接读内核 `SessionLog`，再删这个接口。

---

## 5. 会跟着编译失败的测试

这些还在用旧 `head` / `scan` / `readAfter` / `CallLog` / `SessionViews` / `SessionProjection`。改断言到内核事件，或删掉只覆盖旧协议的用例。不要为了让它们编译而把旧类留回来。

core 与 harness：

- `ReActSessionLogTest`：`SessionViews.transcript`、`log.head()`、路由 store
- `ReActHistoryFromLogTest`：`log.scan`
- `SessionExecutionIdentityTest`：`readAfter`。直接 `agent.call` 的事件 run id 是智能体那次；`AgentSession.submit` 的事件 run id 是 `external-<uuid>`，不要断言两者相同
- `SessionInboxCrashRecoveryTest`：`CallLog` 已改过一处为 `openWriter`，其余仍看旧 `head()`。检查点类型保持 `state/checkpoint`
- `AdministrativeHistoryVocabularyTest`、`HistoryDerivedFromLogTest`、`RecordedPayloadTest`、`JournalSessionLogTest`
- `HarnessAgentTest`：`sessionLog(...).head().owner()`
- `HarnessActionObservationTest`、`JsonSessionDefaultLocationTest`、`CompactionMiddlewareTest`
- `SubagentPendingToolRecoveryTest`：`SessionProjection.read`
- `WorkspaceSessionLogStoreTest`：`open(...).head().seq()`
- `SqliteSessionStorageTest`
- `SessionTranscriptExportTest`
- `LegacyStateLoader` 只有注释提到 `SessionMigration`，改注释即可

examples 与 extensions（原交接漏了，见第 7 节残留 1 与 5）：

- `agentscope-examples/agents/agentscope-chat/src/main/.../ChatItems.java`：`import io.agentscope.core.session.SessionEvent` 并把它放进公开 API（`Entry`、`read(List<SessionEvent>)`、`apply`、`message`、`tool`），`:240` 按 `run/end` 分支
- `agentscope-examples/agents/agentscope-chat/src/test/.../ChatItemsTest.java`：同样 import 旧 `SessionEvent`
- `agentscope-examples/agents/agentscope-chat/src/main/.../ChatHistory.java`：除旧 `head`/`scan` 外，`:191` 还有一个 `record Prefix(SessionLog.Head, List<SessionEvent>) implements SessionLog` —— 例子自己实现了一份旧协议
- `agentscope-examples/agents/agentscope-chat/src/test/.../ChatSessionsTest.java`：按 `run/start` 计数
- `agentscope-extensions/agentscope-extensions-redis/src/test/.../RedisSessionStorageIntegrationTest.java`：import 旧 `SessionEvent`，并调用 `RecordedPayloads.toData`

`HarnessAgentTest`、`HarnessBuilderReuseTest` 的 `SessionHistoryMode.LEGACY` 已换成 `.legacySessionHistory(true)`。计划写的「改为不设置日志存储」没有做；当前布尔值会跳过日志。两种做法不要混在同一次删类里。

---

## 6. 坑

- Spotless 在编译前失败。改过的模块先 `mvn ... spotless:apply`。静态 import 在普通 import 前面。
- 测 harness 要带 `-am`，否则 core 快照不在 reactor 上，`agentscope-extensions-agui` 解析失败。
- **`-pl ... -am` 只构建依赖，不构建下游。** examples 与 extensions 是 core 的下游，用原交接的验证命令永远看不到它们的破坏。阶段的收口必须走根 reactor。
- 主代码签名改了而测试没重编，会 `NoSuchMethodError`（`inspectAgentState` 从 `SessionStateAccess.Snapshot` 换成 `StateSnapshot` 时出现过）。用 `-am` 或先 clean。
- **删类之后增量编译不会清理旧 `.class`，会伪装成代码错误**：`ChatSessions.java` 源码已干净（架构断言按类型扫全仓库通过），但 `agentscope-examples/agents/agentscope-chat/target/classes/.../ChatSessions.class` 里仍留着 `io/agentscope/core/session/SessionLogStore` 的引用，运行时抛 `NoClassDefFoundError: io/agentscope/core/session/SessionLogStore`，8 个用例全 error。判据：**源码干净 + 产物里还引用旧类 = 陈旧 class**，用 `mvn ... clean` 消除，不要去改源码。排查方法：`grep -rl "io/agentscope/core/session/<旧类>" <模块>/target`
- Java 编译器不因未使用 import 失败。
- `DurableSession` 的锁不是可重入的。`append` 的同步块里不能再调 `close`。`finish` 是先追加再 `close`。
- `SessionEventData` 没有 `get`。读载荷用 `PayloadData.fields()`。
- `Map.of` 的 value 为 null 会 NPE。`turn/end` 的 reason 用 `LinkedHashMap`。
- 同一份 `AtomicSessionStorage` 上两个 `NativeSessionLogStore` 不共享内存里的 writers map。CAS 仍在存储层。
- `appendExternal` 不能复用固定 run id `"external"`，构造器会抛 “Run ID already used”。
- 确认结果消息（`Msg.METADATA_CONFIRM_RESULTS`）不写入历史检查点。
- `SessionRecovery.reconcile` 已收成一条 `tool/reconciled`。没找到剩余生产调用方。不确定工具调用的对账行为没有保留。`ChatHistory` 的 uncertain tool calls 目前是空集合。
- **架构断言按类型（import）扫，不按字符串事件名扫。** `run/start`、`run/end`、`state/checkpoint` 是有意保留的载荷名（1.1），按字符串断言会把它们误杀，逼下一轮的人去「清理」，反而重建翻译层。
- **`state/snapshot` 没有写入点。** 只在 `ReActAgent`、`DurableSession` 的读取端被接受。不要顺手给它补写入。
- **`SessionFormatVersion` 的注释承诺迁移链，实现是版本不等直接拒绝**（`AtomicSessionStore`、`LocalSessionStore` 抛 `FormatUnsupportedException`）。「旧格式不迁移」目前靠拒绝实现。不要补 v0→v1 迁移，那会立刻变成新的翻译层。
- **`RedisSessionStorageIntegrationTest` 由 `@EnabledIfEnvironmentVariable(AGENTSCOPE_TEST_REDIS_URL)` 门控，默认跳过。** 运行时不会发现它，只有编译能发现。修完必须靠编译验证。
- 权威计划 Task 4 的 `git add` 漏了 `agentscope-extensions`。照抄会提交出一棵根构建红的树。

---

## 7. 五处残留与处置

复核全仓库后确认：删掉第 3 节的文件只完成 core 生产**类型层**的下线，不等于项目只剩一套会话引擎。下面五处必须一起交代。旧包引用分布：core 41、harness 24、examples 7、extensions 3、advisor 0、distribution 0。

| # | 残留 | 性质 | 处置 |
| --- | --- | --- | --- |
| 1 | examples、extensions 仍引用旧包 | 同一个编译单元，不修则根构建红 | **并入**。阶段一改引用，阶段二验证走根 reactor |
| 2 | 旧事件名仍在用（`run/*`、`state/checkpoint`、`state/snapshot`） | 有意保留的设计决定 | **本轮只固化**：写进文档与断言白名单（1.1），不动实现 |
| 3 | `docs/v2/**/harness/session-log.md` 把将删的类写成公开 API | 删除的必要产物 | **并入**阶段二收口。`docs/v1` 的 `JsonSession`/`Session`/`SessionManager` 漂移是既存问题，**登记**不并入 |
| 4 | `technical-advisor-app` 的 `advisor_events` 是第二份会话事件记录（自有 `seq`/`turn`/`type`/`payload`，对旧包 0 引用） | 架构定性问题，与本次删除无因果 | **登记**为独立立项。本轮不动手 |
| 5 | examples 的 UI 读模型（`ChatItems`、`ChatHistory:191` 内部 `implements SessionLog`） | 源码硬引用，且改错不报错、只静默改变 UI 显示 | **并入**阶段一。纪律：只换类型，不动词汇 |

残留 1 与 5 不是新增范围，是原交接漏掉的编译单元。残留 2 与 4 硬塞进本轮会制造新的兼容代码或新功能，反而违背需求。

---

## 8. 执行计划：两个阶段

**为什么是 2 个而不是 1 个，也不是 4 个。**

原交接的 4 个阶段里，阶段三（删文件）与阶段四（断言 + 全量）之间没有可交付的中间态，合并成一个。剩下「拆引用」与「删除收口」两个阶段，理由是**旧类在场时才有独立参照物**：`OpenSession.toRecordedEvent` 能把内核事件翻回旧 `SessionEvent`，于是可以用旧读路径独立校验新写路径（跨协议对账）。类一删，这个人造参照物就没了，这是唯一一次不可逆的验证窗口。所以阶段一必须留、且必须在阶段一结束时做对账。

不再有第 3 个阶段：断言、文档对账、登记项都挂在阶段二的收口里，因为它们都只能在删除之后才有意义。

### 阶段一：拆引用（旧类仍在，编译保持绿）

**Files:**
- Modify: `agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java`
- Modify: `agentscope-core/src/main/java/io/agentscope/core/session/log/NativeSessionLogStore.java`
- Modify: `agentscope-core/src/main/java/io/agentscope/core/session/log/DurableSession.java`
- Modify: `agentscope-core/src/main/java/io/agentscope/core/session/InMemorySessionLogStore.java`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/session/AgentSession.java`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/session/WorkspaceSessionLogStore.java`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/tool/SessionSearchTool.java`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/context/ContextModelCalls.java`
- Modify: `agentscope-examples/agents/agentscope-chat/src/main/java/io/agentscope/examples/chat/ChatHistory.java`
- Modify: `agentscope-examples/agents/agentscope-chat/src/main/java/io/agentscope/examples/chat/ChatItems.java`
- Modify: `agentscope-examples/agents/agentscope-chat/src/main/java/io/agentscope/examples/chat/ChatSessions.java`
- Test: `agentscope-core/src/test/java/io/agentscope/core/session/ReActHistoryFromLogTest.java`
- Test: `agentscope-examples/agents/agentscope-chat/src/test/java/io/agentscope/examples/chat/ChatItemsTest.java`

**Interfaces:**
- Consumes: `DurableSession`、`SessionInputQueue.Input`、`NativeSessionLogStore.appendExternal`
- Produces: `NativeSessionLogStore.read(sessionId)`、`NativeSessionLogStore.owner(sessionId)`、`DurableSession.append(SessionEventData)`、`sessionLog()` 返回 `io.agentscope.core.session.log.SessionLog`

- [ ] **Step 1: 拆 4.1 的旧日志读写**

按 4.1 的改法改 `ReActAgent`、`HarnessAgent`、`AgentSession`、`SessionSearchTool`、`ChatHistory`、`ChatSessions`，并在 store 上加 `owner(sessionId)`。`InMemorySessionLogStore`、`WorkspaceSessionLogStore` 的 `open()` 不再返回旧 `SessionLog`，`openWriter` / `appendExternal` / `read` 带 context。

- [ ] **Step 2: 跑，确认这一步没改变行为**

```bash
mvn -pl agentscope-harness -am test \
  -Dtest=AgentSessionTest,SessionExecutionIdentityTest,HarnessAgentTest \
  -DfailIfNoTests=false -q
```

Expected: PASS。

- [ ] **Step 3: 拆 4.2，把字符串写入换成类型化 append**

`DurableSession.start` / `step` / `checkpointNow` / `finish` 改直接 `append` 类型化对象。保留 `run/end` 的不透明 `PayloadData`，见 1.1。

- [ ] **Step 4: 拆 4.3，去 `SessionModels.wrap` 并前移 `prepareModel`**

`ReActAgent` 四处调用点与 `ContextModelCalls` 改回原模型；`prepareModel` 写 `request/header` 的动作挪进推理路径。

- [ ] **Step 5: 跑请求信封专项，这是唯一能发现静默丢失的地方**

```bash
mvn -pl agentscope-core -am test -Dtest=ReActHistoryFromLogTest,DurableSessionTest -q
```

Expected: PASS。若 `request/header` 缺失，这一步会红；漏掉这一步就会静默丢信封。

- [ ] **Step 6: 迁移 examples 的读模型（残留 5），只换类型不动词汇**

`ChatItems` 的公开 API 从旧 `SessionEvent` 换成内核事件类型，`apply` / `message` / `tool` / `read` 同步改；`ChatHistory` 的 `record Prefix` 不再实现旧 `SessionLog`，直读内核 `SessionLog`；`ChatSessions` 改 `session.log.SessionLog`。`run/end`、`run/start`、`state/checkpoint` 的取值逻辑保持不变。

- [ ] **Step 7: 跑 examples 读模型测试**

```bash
mvn -pl agentscope-examples/agents/agentscope-chat -am test \
  -Dtest=ChatItemsTest,ChatSessionsTest -DfailIfNoTests=false -q
```

Expected: PASS。另外手工确认挂起态在 `ChatItems` 里仍显示为挂起（4.2 警告过的 `suspended` 被读成 `completed`）。

- [ ] **Step 8: 跨协议对账（本阶段不可跳过的一步）**

旧类还在，所以可以用旧读路径独立校验新写路径：对同一次 `call()`，用 `NativeSessionLogStore` 的旧 `open()`（此刻仍会 `toRecordedEvent`）读一遍，确认 `turn/start`、用户消息、`assistant/message`、`turn/end` 都在，且状态映射与阶段一之前一致。把这份对账的结论写进同一次提交的说明里。

```bash
mvn -pl agentscope-core,agentscope-harness -am test \
  -Dtest=ReActSessionLogTest,SessionInboxCrashRecoveryTest,AdministrativeHistoryVocabularyTest,HistoryDerivedFromLogTest,RecordedPayloadTest,JournalSessionLogTest \
  -DfailIfNoTests=false -q
```

Expected: PASS（这些用例此时仍走旧协议，是参照物）。

### 阶段二：删除与收口（一次删净 + 根 reactor 全量 + 文档对账 + 登记）

**Files:**
- Delete: 第 3 节清单里的 22 个文件
- Move: `session/SessionLogException.java` → `session/log/SessionLogException.java`
- Modify: `agentscope-core/src/main/java/io/agentscope/core/session/log/NativeSessionLogStore.java`
- Modify: 第 5 节点名的全部测试与 examples
- Create: `agentscope-core/src/test/java/io/agentscope/core/session/SessionPackageArchitectureTest.java`
- Modify: `docs/v2/zh/docs/harness/session-log.md`、`docs/v2/en/docs/harness/session-log.md`
- Modify: `docs/examples/execution-control/SessionRunGuide.java`

- [ ] **Step 1: 删文件、移异常类**

按第 3 节清单删除；`SessionLogException` 移入 `session/log/` 后删原文件。

- [ ] **Step 2: 清 `NativeSessionLogStore` 的翻译层**

删 `OpenSession`、`toRecordedEvent`、旧 `commit`、`appendKernel`，去掉 `implements SessionLogStore`。此后删 `KernelHistory`（4.4 的收尾）。

- [ ] **Step 3: 修第 5 节的全部编译错误**

core、harness、examples、extensions 一起修。断言改成内核类型：`TurnStart`、`UserMessageData`、`AssistantMessageData`、`TurnEnd`。`RedisSessionStorageIntegrationTest` 不再用旧 `SessionEvent` 与 `RecordedPayloads`。

两处阶段一带出来的收尾，必须在这一步一起做：

1. `SessionLogStore` 消失后，`AgentSession` 的五处 `store.owner(key, context)` 要改指向**保留下来的具体 store**（`InMemorySessionLogStore` / `WorkspaceSessionLogStore` 或它们新的共同类型）。不要为了留住 `default owner(...)` 而保留 `SessionLogStore`。
2. `run/start` 已经没有写入者。第 5 节里读它的断言（`CompactionMiddlewareTest`）与 examples 读模型（`ChatHistory`、`ChatItems`、`ChatSessionsTest`）改读 `turn/start` / `TurnStart`，并确认挂起态、running 态在 examples 读模型里仍能正确显示。

- [ ] **Step 4: 编译验证（redis 集成测试默认跳过，只有编译能发现它）**

```bash
mvn -q -DskipTests install -pl agentscope-core,agentscope-harness -am
mvn -q test-compile
```

Expected: 两条都成功。

- [ ] **Step 5: 加架构断言（残留 1 与 2 的固化）**

新建 `SessionPackageArchitectureTest`，两部分：

1. 枚举源码树，`agentscope-core/src/main/java/io/agentscope/core/session/` 的 `.java` 只允许 `SessionKey`、`AtomicSessionStorage`、`InMemorySessionLogStore`（**3 个**，不含 `SessionAnswer`）。
2. 扫全仓库 `**/*.java`，不允许出现 `import io.agentscope.core.session.<旧类型>`。**按类型匹配，不匹配字符串事件名**——`run/start`、`run/end`、`state/checkpoint` 是有意保留的。

- [ ] **Step 6: 根 reactor 全量**

```bash
mvn -q test
```

Expected: 失败用例 0。跳过保持原样。这一步会编译 examples 与 extensions，是唯一能证明「项目只剩一套引擎」的命令。原交接的 `-pl agentscope-core,agentscope-harness -am` 不能替代它。

- [ ] **Step 7: 文档对账（残留 3）**

`docs/v2/{zh,en}/docs/harness/session-log.md`：把 `SessionRecorder.from`、`SessionMigration.fork/exportSnapshot/exportState/importBaseline`、`SessionLogExporter`、`inbox/` 布局与 `inbox/started|applied` 的段落改写成内核 API（`DurableSession`、`SessionQuery`、`MessageDerivation`）；在事件表里声明 `run/start`、`run/end`、`state/checkpoint` 是**永久保留的内核载荷名**，`state/snapshot` 是只读别名。`docs/examples/execution-control/SessionRunGuide.java` 改用内核类型。

- [ ] **Step 8: 把两项独立立项落到第 9 节（不留代码债）**

第 9 节已登记这两项的边界与依据：`advisor_events` 与内核日志的关系定性、`docs/v1` 的 `JsonSession`/`Session`/`SessionManager`/`InMemorySession` 漂移清理。本步只需核对第 9 节的描述与本次删除后的实际状态一致；若其中一项需要独立实施文档，另建 `docs/superpowers/plans/` 下的立项文件并在此处留链接。本轮到此为止，不动手。

- [ ] **Step 9: 提交（本轮不执行）**

`.ai-spec/change-levels.md` §四.8 要求未经用户明确授权不 commit、不 push。本轮到此停下，把改动留在工作区，提交命令备查：

```bash
mvn spotless:apply
git add agentscope-core agentscope-harness agentscope-extensions agentscope-examples docs
git commit -m "refactor(session): keep only the DeepSeek session engine"
```

注意原计划 Task 4 Step 5 的 `git add` 漏了 `agentscope-extensions`，不要照抄。

---

## 9. 兼容性分析（高级改动必备）

| 面 | 结论 | 迁移路径 / 依据 |
| --- | --- | --- |
| core 公共 API | **破坏性**：删除 22 个公开类型，`SessionLog` / `SessionLogStore` 不再存在，`sessionLog()` 返回类型改为 `session.log.SessionLog` | 不提供 deprecated 过渡层（用户明令「不要再留翻译层」）。下游改用 `DurableSession` + `session.log.*` |
| harness 公共 API | `AgentSession` 的 `submit` / `steer` / `inject` / `respond` / `interrupt` / `resume` / `await` / `tasks` / `transcript` / `pending` **签名不变**；唯一收缩点是 `inspect` 不再返回 `SessionProjection` | `inspect` 的调用方改读 `transcript()`；须在 `docs/v2` EN/ZH 同步说明 |
| 序列化状态格式 | **不改变**。删的是写入 API（字符串 → 类型化），不是磁盘形状；`SessionFormatVersion.CURRENT` 保持 1，**不 bump** | 当前日志由内核事件构成（§4.2：磁盘字节已经是内核事件），新读取器继续可读 |
| 事件 payload | 生产不再写旧字符串名。`run/end`、`state/checkpoint` 作为不透明 `PayloadData` 保留；`state/snapshot` 是只读别名；**`run/start` 已无写入者**，读它的地方改读 `turn/start` / `TurnStart` | §1.1。断言按类型不按字符串 |
| payload 字段形状 | **风险点**：4.2 把 `state/checkpoint`、`run/end` 从 `RecordedPayloads` 转为直接 append，若字段名或类型变化，旧日志会被新读取器误读 | 保持字段名与类型逐字不变；阶段一 Step 8 的跨协议对账正好覆盖这一条 |
| 更早的旧磁盘日志 | **不兼容、不迁移**。版本检查直接拒绝（`AtomicSessionStore:121`、`LocalSessionStore:141` 抛 `FormatUnsupportedException`） | 与 Global Constraints 一致；本次不新增迁移链 |
| 存储后端 | `AtomicSessionStorage` / `Local` / `Sqlite` / `Store` 接口与行为不变 | 非删除范围 |
| 配置开关 | `SessionHistoryMode` 已在本变更**之前**删除，替代为 `legacySessionHistory(boolean)` | 见 §2，不属本次改动 |
| examples / UI 读模型 | 类型迁移 + 一处词汇迁移：running 判定由 `run/start` 改读 `TurnStart`；`run/end`、`state/checkpoint` 取值逻辑不变。对外游标 / SSE id 语义靠 `ChatHistory.ordinal()` 维持 1 基不变 | 阶段一 Step 6-7 实测结论 |
| `technical-advisor-app` | 不受影响（对旧包 0 引用，`advisor_events` 自有一套记录） | §7 残留 4，登记为独立立项 |

**兼容性结论**：这是一次核心库公开 API 的破坏性变更，且**不提供**兼容层。受影响的下游是本仓库内的 examples 与 extensions（已在阶段一、阶段二 Step 3 覆盖）。`technical-advisor-app` 与前端不受影响。磁盘格式不变，无需数据迁移。

## 10. 回滚方案（高级改动必备）

| 场景 | 回滚动作 |
| --- | --- |
| 阶段一进行中 | 阶段一不删文件，回滚 = 逐个还原本次改动的源文件。**不要用 `git checkout -- <目录>`**：会话前的改动未提交，按目录还原会退到 HEAD，丢掉整个 DeepSeek 切换的成果 |
| 阶段二删文件之后、提交之前 | **不可用 git 整体回滚**。原因有二：① 会话前的大量改动**从未提交**，`git checkout -- <路径>` 会把整个 DeepSeek 切换的成果一起退回 HEAD；② `agentscope-core/.../session/log/` 整个目录在 git 里是**未跟踪**的，`CallLog`、`KernelHistory`、`RecordedPayloads` 删除后**无法用 git 找回**。此窗口内只能向前修，出口条件是 `mvn -T1 test-compile` 成功 |
| 若将来已提交 | 单次提交，`git revert <sha>` 反向恢复 22 个文件与全部引用 |
| 磁盘数据 | **无需回滚**：格式未变、版本未 bump，回滚后的旧代码仍能读新写入的日志 |
| 不可逆窗口 | 阶段二 Step 1（删文件）到 Step 4（编译通过）之间是红树窗口。此窗口内不做任何其他改动，不并行其他任务。注意本表原先假设「删除前存在一个已提交的绿色基线」，**实际不存在**——阶段二执行前工作区已有大量未提交改动，因此该窗口内的删除在 `session/log/` 下确实不可逆 |

**回滚触发条件**：阶段一 Step 8 的跨协议对账出现状态映射不一致；或根 reactor 测试出现 §11.1 基线表之外的失败。

## 11. 明确不在本轮

以下七件不是删除，是独立立项或已确认的基线。登记，不动手：

1. **事件词汇统一**：把 `run/start`、`run/end`、`state/checkpoint`、`state/snapshot` 收敛成一套名字。需要新内核事件类型 + 数据迁移 + 全部消费者改造。本轮只把它们固化为已知保留项（1.1 + 断言白名单）。
2. **`advisor_events` 与内核日志合并**：`technical-advisor-app` 的 `TimelineStore` 自有一份会话事件记录（自有 `seq` / `turn` / `type` / `payload`）。是否算「第二套引擎」需要架构定性；若合并，是新功能。
3. **`docs/v1` 漂移清理**：`JsonSession`、`Session`、`SessionManager`、`InMemorySession` 早已不在源码里。与本次删除无因果。
4. **`WorkspaceSessionLogStore` 路由下归属读恒为空（阶段一发现，**非本次引入**）**：`LocalFilesystem.sessionStorage(rc)` 每次 `new LocalSessionStorage(...)`，而 `WorkspaceSessionLogStore.kernel(context)` 用 `AtomicSessionStorage` 的**对象身份**做缓存键，于是每次调用都新建一个 `NativeSessionLogStore`，`owners` / `liveWriters` 从不共享。后果：`AgentSession.steer`（要求 `owner != null`）在配置了 `WorkspaceSessionLogStore` 时恒抛「No running task accepts steering」；`requireIdle()` 与 `dispatch()` 的写者守卫失效；`latestTask` 把 running 误判成 `interrupted`。

   **非本次引入的证据**：阶段一之前 `AgentSession` 用的是构造期 `sessionLogStore.open(...)` 的 `head().owner()`，`open()` 同样经 `kernel(context)` 拿**当时那个**实例，而与真正持写句柄的实例不是同一个，所以同样是 null。`HarnessAgent` 的默认路径不受影响——`defaultKernelLog` 直接建一个 `NativeSessionLogStore`，不经过这条路由。修复要动生产设计（让 `sessionStorage` 返回稳定实例，或按已解析路径做缓存键），**不在本次范围**。

5. **`LocalSessionStorage.listPaths` 撞瞬态文件（阶段一发现，非本次引入）**：用 `Files.walk` 遍历，写者提交瞬间留下的 `journal-*.tmp` 消失时会抛 `NoSuchFileException` / `UncheckedIOException`。该文件本次一行未改（`git diff --stat` 为空）。修法是遍历时容忍条目消失（`walkFileTree` + `visitFileFailed` 继续）。后果：Local 工作区存储上的轮询读会随机失败，`SubagentPendingToolRecoveryTest` 与 `ChatSessionsTest` 各有一批失败同源。
6. **直接构造的 `PayloadData` 丢判别符（阶段一由读模型迁移暴露）**：`SessionEventData.PayloadData` 是裸 `Map<String, Object>`，而 `DurableSession.observe` 把 `ToolUseBlock` / `ToolResultBlock` 直接塞进 `"call"` / `"result"`，写盘丢 `type` 判别符 → `interaction/requested` 无法被任何读模型或前端解码。阶段一只修了走 `RecordedPayloads.freeze` 的那条路（`tool/chunk`、`action/end`）。单点修法：在 `PayloadData` 构造器里保全嵌套 `ContentBlock` 判别符。
7. **`model/chunk`、`model/end` 也没有写入者了（删 `SessionModels.wrap` 的设计后果）**：按 cutover 设计，模型流改由 `assistant/message` 内嵌 `AssistantStreamRecord` 承载。阶段二 Step 3 必须改写依赖这两个名字的断言（`CompactionMiddlewareTest` 改成断言 `model/dispatch`；`SessionExecutionIdentityTest` 已改）。

8. **示例读模型里的死分支（阶段二收口发现）**：`agentscope-examples/agents/agentscope-chat` 的 `ChatItems.java` 仍在 `model/end`、`migration/baseline`、`tool/requested` 这些**已无写入者**的名字上分支；`ChatHistory.ordinal()` 自造 1 基游标（内核 `seq` 是 0 基）。不影响编译与本次下线，属读模型侧的词汇债。

### 11.1 基线失败登记（`mvn -B -T1 clean verify` 不可达的原因）

**本轮不修，逐条登记。** 会话开始前工作区已有大量未提交改动，**没有干净基线可用**，因此「既有 / 本次引入」无法用 git 判定；下表按根因证据分类（相关文件 `git diff` 为空，或 HEAD 版本具有同样性质）。

| 失败 | 归类 | 根因 |
| --- | --- | --- |
| `ChatSessionsTest` **6/8**（`clean` 消除陈旧 class 后的实测值；此前记为 7/8、8/8 都是被陈旧 class 与增量编译干扰的读数） | 既有缺陷在 examples 上显形 | 4 条对齐第 1 条：`Another writer advanced session`、`No running task accepts steering`、`expected completed but was interrupted`、`Async resource cleanup failed after onComplete`；2 条对齐第 5 条：`NoSuchFileException .../events/journal-*.tmp` |
| `WorkspaceSessionLogStoreTest.localLogSurvivesIndependentBackendInstancesAndPreservesOwnership` | 既有 | 上面第 1 条 |
| `SubagentPendingToolRecoveryTest.childRecoversPendingCallAfterSubscriptionCancellation`（2 变体） | 既有 | 上面第 5 条 + 归属竞态 |
| `CompactionMiddlewareTest`（`run/start` 计数） | **本变更的预期后果，已修** | `run/start` 早已无写入者，断言改读 `turn/start` |
| `CompactionMiddlewareTest.nativeOverflowRecoveryKeepsOneExecutionAndRecordsBothModelCalls` | **不是基线，是断言改错了，已修（现 11/11 绿）** | 原断言数 `assistant/message`：删 `wrap` 后它只记**进入表面的**助手消息，摘要调用不产生表面消息，故恒为 1。改数 `model/dispatch` 事件得 3；改数**不同 `modelCallId`** 仍是 3——因为压缩的摘要调用也走 `prepareModel` 发一次 dispatch，而桩模型只被调用 2 次。结论：「恰好 2 条」不是内核保证的性质，原断言把旧记录方案的偶然特征当成了不变量。最终断言为「不同 `modelCallId` ≥ 2」并注明计数不精确的原因 |
| `HarnessAgentDistributedSandboxTest`（3 个） | 既有，原清单未列 | `Filesystem does not support durable session storage: SandboxBackedFilesystem` |
| `LocalFilesystemUserIsolationExampleTest.noDuplicateDataAtWorkspaceRoot` | 既有，原清单未列 | 工作区根出现 `.agentscope-runtime` |
| `SharedWorkspaceSkillStagingE2ETest.concurrentAgentsSharingWorkspaceBothComplete` | 既有，上面第 5 条的又一表现，**时序型**（有的运行出现、有的不出现） | 同一 `NoSuchFileException: .../events/journal-*.tmp`：并发 Agent 共享 workspace 时读路径撞上写者的瞬态临时文件 |

**完成判据因此修正为**：core 与 harness 全量测试无**新增**失败（与上表逐条对齐）；examples 与 extensions 编译通过且迁移用例（`ChatItemsTest`）绿；架构断言绿；`spotless:check` 与 license 头检查通过。`clean verify` 能否达标取决于上表清算，**不属本轮**。

**验证方法上的一个坑（本轮踩到）**：`mvn -T1 test` 一旦 harness 失败，Maven 会**跳过所有依赖它的下游模块**（examples、`agentscope-extensions-redis`、`technical-advisor-app`），`-fae` 也治不了——下游照样 SKIPPED，于是 `ChatSessionsTest` 从头到尾一次都没运行。要看完整面必须用 `mvn -T1 test -Dmaven.test.failure.ignore=true`（它不跳过任何模块，只是不让构建失败）。

---

## 12. 验证命令汇总

阶段一（旧类仍在，作为参照物）：

```bash
mvn -T1 -pl agentscope-core,agentscope-harness -am test \
  -Dtest=DurableSessionTest,ReActHistoryFromLogTest,AgentSessionTest,SessionExecutionIdentityTest \
  -DfailIfNoTests=false
```

阶段二（删净之后）。`mvn -B -T1 clean verify` 因 §11.1 的基线失败**本轮不可达**，实际门禁是根 reactor 全量 + 逐条对齐基线：

```bash
mvn -T1 spotless:apply
mvn -T1 test-compile
mvn -T1 test
```

Expected: 失败用例集合与 §11.1 的基线表**逐条对齐，无新增**；`spotless:check` 与 license 头检查通过。出现表外失败即停下评估——注意 §10：本树**没有可用的整体回滚**，只能定位与向前修。

扩展模块结构未调整（只改了 `agentscope-extensions-redis` 的一个测试），`.github/scripts/check-shade-and-bom-sync.sh` 不适用。

用户没有授权提交，本轮停在阶段二 Step 8；提交命令见阶段二 Step 9。

---

## 13. 第二轮：基线清理（删除提交之后）

§11 与 §11.1 记的是**删除提交当时**的状态。下面这一轮把其中的真实缺陷修掉了，本节是当前事实；与 §11/§11.1 冲突时以本节为准。

### 13.1 已修并验证

| 原登记项 | 根因 | 修法 | 验证 |
| --- | --- | --- | --- |
| §11.4 归属读恒空 | `LocalFilesystem.sessionStorage` 每次 `new`，而 `WorkspaceSessionLogStore` 按对象身份缓存 → 每次不同 `NativeSessionLogStore`，`owners` 从不共享 | 两处缓存改为**进程内共享**：`LocalFilesystem.SESSION_STORAGES` 按解析路径、`WorkspaceSessionLogStore.KERNELS` 按存储身份（与 `LocalSessionStorage.LOCKS` 同一模式） | `WorkspaceSessionLogStoreTest` 7/7 绿 |
| §11.5 `listPaths` 撞瞬态文件 | `Files.walk` 对写者瞬态 `journal-*.tmp` 做二次 stat | 改 `walkFileTree` + `visitFileFailed` 返回 `CONTINUE`，用 `visitFile` 的 attributes 免二次 stat | `SubagentPendingToolRecoveryTest` 23/23、`SharedWorkspaceSkillStagingE2ETest` 3/3 绿 |
| §11.6 `PayloadData` 丢判别符 | — | **上一轮已修**（`frozenFields`/`blockPreserving`），本表原登记过期 | `PayloadDiscriminatorPreservationTest` 4/4 绿 |
| §11.1 Sandbox 不支持 durable storage | `defaultKernelLog` 无条件向 `filesystem.sessionStorage()` 要后端 | 捕获 `UnsupportedOperationException` → WARN + 返回 null（无日志、内存历史）；调用点两处相应判空（builder、`SessionSearchTool`） | `HarnessAgentDistributedSandboxTest` 5/5 绿 |
| §11.1 工作区根出现 `.agentscope-runtime` | 运行时日志按设计落在工作区根 | 断言放行该目录并写明理由；`.gitignore` 增补 `.agentscope-runtime/` 与 `technical-advisor-app/data/` | `LocalFilesystemUserIsolationExampleTest` 8/8 绿 |
| §11.2 advisor_events（定性） | — | 定性为**应用层投影/UI 视图**，`TimelineStore` javadoc 写明它不是会话事实来源、内核日志才是 | 文档，无需测试 |
| §11.8 示例读模型死分支 | `ChatItems` 仍在无写入者的名字上分支 | 删除 `model/end`、`migration/baseline`、`tool/requested` 三个 case | `ChatItemsTest` 2/2 绿 |

清理过程中在 `AgentSession` 里发现并修掉**四个真实缺陷**（都不是测试写错，而是归属读恢复可见后才暴露的）：

1. `submit` 缺幂等守卫 → 同一 requestId 重复提交会**启动第二次执行**（归属不可见时被掩盖成静默重复执行）。改为查 `SessionInputQueue.accepted()`：同 id 同内容返回既有回执，不同内容抛 `IllegalArgumentException`。内容比较沿用队列既定的 `sameContent`（逐条文本），**不要**用 `Msg.equals`（从日志重建的对象不保证相等）。
2. `steer` 用 `latestTask()`（最新提交＝排队那条）定位目标 → 改为按 `running` 状态取**正在运行**的任务。
3. `dispatch` 在上一回合被中断/挂起后仍自动启动排队工作 → 增加 `awaitingExplicitContinuation()` 守卫，排队工作等显式 `resume`。
4. `resume(turnId)` 要求目标必须是「最新」任务，排队任务会让它失效 → 改为按 turnId 查找该未完成任务。

### 13.2 仍未解决

| 项 | 症状 | 需要什么 |
| --- | --- | --- |
| `ChatSessionsTest.queuedTasksAreDistinctAndSteeringStaysInCurrentExecution:75` | 会话快照的 tool item 计数 **6**，期望 **3** | 归属读修好之前该断言从未被执行到，因此这是**示例读模型**侧的既有计数问题：需要查 `ChatItems` 如何把 `tool/call` 事件累加成 item（是否同一次调用被两个来源各计一次）。属读模型语义，建议独立改动 + 评审，不要为了变绿去改断言 |
| §11.1 `ChatSessionsTest` 其余用例 | **已全部转绿**（8 个里 7 绿） | — |
| §11.1 `HarnessAgentDistributedSandboxTest` 之外 | 全绿 | — |
| §11.1 `SharedWorkspaceSkillStagingE2ETest` | 由时序型转稳定绿（根因即 §11.5） | — |
| §11.1 `HarnessAgentDistributedSandboxTest` | 5/5 绿 | — |

**当前全量状态**：见本节末尾的读数；harness 与 extensions 已无失败，examples 仅剩上表一条。
