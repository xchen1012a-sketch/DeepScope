# 只保留 DeepSeek 会话引擎

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 生产路径只使用 `io.agentscope.core.session.log` 这一套会话引擎，旧记录协议下线并删除。

**Architecture:** 一次调用打开内核日志，追加类型化事件，模型历史只从 `deriveMessages()` 来。`NativeSessionLogStore` 只负责把这份内核日志落到现有存储上。旧的记录协议、字符串事件和来回翻译不再存在。本计划一次做完，中间不保留双轨。

**Tech Stack:** Java，Reactor，现有 JUnit 5，Maven 多模块。

**Spec:** 用户需求（2026-10-11）：保留新建的 DeepSeek 会话引擎，替换旧引擎并下线删除。依据检索是当天工作区 `*.java`，不是旧删除清单里的「本批不删」。

## Global Constraints

- 会话引擎只保留 `io.agentscope.core.session.log`。
- 模型历史只有一个来源：内核日志的 `deriveMessages()`，经 `MessageDerivation.history` 按消息 id 去重。
- 生产代码不再调用旧包 `io.agentscope.core.session` 里的记录协议（见文末删除清单）。
- `AgentSession` 的对外方法签名保持不变：`submit`、`steer`、`inject`、`respond`、`interrupt`、`resume`、`await`、`tasks`、`transcript`、`pending`。
- `ReActAgent` 的公开调用方式保持不变。
- 权限、计划、工具上下文仍由 `AgentState` 在进程内持有。跨进程恢复需要的那一份快照，写成内核日志上的一条 `PayloadData`，类型名 `state/snapshot`。模型消息不从这条快照读取。
- 不改前端。界面重做不在本计划内。
- 不兼容旧磁盘日志。旧格式不迁移。
- 一次提交完成替换和删除。中间状态允许编译失败，结束时必须通过文末验证命令。

---

## 检索结论（2026-10-11）

项目里有两套会话引擎，生产走的是旧的那套。

新引擎在 `agentscope-core/src/main/java/io/agentscope/core/session/log/`。它已经能追加、派生、排队输入、修复回合、分叉、查询和检索。`SessionLog`（内核类）是内存中的日志。`SessionJournalStore` 是落盘口，`AtomicSessionStore` 和 `LocalSessionStore` 是它的两个实现。

旧引擎在 `agentscope-core/src/main/java/io/agentscope/core/session/`（不含 `log/` 和 `AtomicSessionStorage`）。生产接线是：

| 调用方 | 现状 | 文件 |
| --- | --- | --- |
| `ReActAgent` | 打开旧 `SessionLogStore`，用 `CallLog` 写字符串事件，再用 `MessageDerivation` 读历史 | `ReActAgent.java` 约 364、733、825、2550、2723、2764、3075、4802、5114 行 |
| `AgentSession` | `SessionInbox` + `SessionExecution` + `SessionTurns` + `SessionViews` + `SessionProjection` | `AgentSession.java` |
| `HarnessAgent` | `sessionLog()` 返回旧 `SessionLog`；转写和检查走 `SessionViews`、`SessionProjection`、`SessionRecovery` | `HarnessAgent.java` 278–292 行 |
| `SessionSearchTool` | 打开旧 `SessionLogStore`，用 `SessionViews.transcript` | `SessionSearchTool.java` |
| `NativeSessionLogStore` | 实现旧 `SessionLogStore`。写入时把旧信封翻成内核事件，读出时 `toRecordedEvent` 再翻回去 | `NativeSessionLogStore.java` |
| `CallLog` | 旧 `SessionRecorder` 的改名。缓冲字符串事件，经 `RecordedPayloads` 翻译后调用 `appendKernel` | `session/log/CallLog.java` |

`CallLog` 和 `SessionLog.appendKernel` 是半成品翻译层。执行本计划时删掉它们，不要在上面继续加方法。

`SessionHistoryMode.LEGACY` 仍能让 `ReActAgent.acquireCallExecution` 跳过日志。删除该枚举后，没有配置日志存储时走现有的内存 `AgentState` 路径；配置了日志存储时只走内核日志。

存储不是第二套引擎，保留：

- `AtomicSessionStorage`
- `LocalSessionStorage`、`SqliteSessionStorage`、`StoreSessionStorage`
- `AtomicSessionStore`、`LocalSessionStore`、`SessionJournalStore`
- `InMemorySessionLogStore`、`WorkspaceSessionLogStore`：改成打开内核日志的工厂，类留下，旧接口去掉
- `SessionKey`：会话身份
- `SessionAnswer`：`AgentSession.respond` 的公开参数
- `SessionLogException`：挪到 `io.agentscope.core.session.log`，存储和调用方继续用它表示提交失败

## 目标形状

```text
ReActAgent / AgentSession
        │  append(SessionEventData) / deriveMessages()
        ▼
DurableSession          （新建，一次执行的写入句柄）
        │  KernelJournal.commitValidated + flush
        ▼
NativeSessionLogStore
        │
        ▼
SessionJournalStore  →  AtomicSessionStore / LocalSessionStore
```

`DurableSession` 是本计划新建的唯一写入类型。放在 `io.agentscope.core.session.log`。

```java
public final class DurableSession implements AutoCloseable {
    public static final String CONTEXT_KEY = "agentscope.session.durable";
    public static final String TURN_ID_KEY = "agentscope.session.turnId";

    public static DurableSession from(RuntimeContext rc);

    public String turnId();
    public String runId();
    public SessionLog kernel();
    public void append(SessionEventData data);
    public List<Msg> history();
    public void close();
}
```

`append` 在已持有的写句柄上提交一条内核事件并 `flush`。`kernel()` 返回这份会话已提交事件装好的 `session.log.SessionLog`。`history()` 是 `MessageDerivation.history` 作用在该日志上的结果。

同一会话上，执行线程持有写句柄时，`AgentSession` 仍要能追加输入。做法是 `NativeSessionLogStore` 记住当前打开的写句柄，并提供：

```java
public void appendExternal(String sessionId, SessionEventData data, String turnId);
```

它在写句柄自己的锁内追加并刷盘，不另开 writer。执行方下一次 `kernel()` 从存储重读，能看见这条输入。没有活跃写句柄时，`appendExternal` 自己短暂打开、写入、关闭。

`SessionInputQueue.pending()` 今天不会把 steer / inject 标成已用。给它补一个方法，并让 `pending()` 跳过已标记的请求：

```java
public void applied(String requestId);
```

已用标记是内核日志上的 `PayloadData`，类型 `input/applied`，字段 `requestId`。`pending()` 同时用 `TurnStart` 事件上的 `turnId` 判断提交是否已经开跑，不能只比较回合数字。

## 事件对照

生产代码只追加下表左列。右列是今天旧写入器在写的名字，实现时按左列写，不要再发右列。

| 内核事件 | 替换的旧名字 | 写入点 |
| --- | --- | --- |
| `TurnStart(turn, resumed)` | `turn/start`、`turn/resumed` | 一次调用开始 |
| `UserMessageData` | `user/message`、`input/received` | 用户输入、steer、inject |
| `SystemMessageData` | `system/message` | 系统提示进入历史时 |
| `AssistantMessageData` | `assistant/message`、`turn/output` | 一轮模型结果定稿时 |
| `ToolResultData` | `tool/result` | 工具结果进入历史时 |
| `StepStart` / `StepEnd` | `step/start`、`step/end` | 每个推理步的前后 |
| `TurnEnd(turn, reason)` | `turn/end`、`run/end` | 调用结束 |
| `RequestHeader` | `request/header` | 每次模型请求 |
| `SessionInputQueue.Input` | inbox 的 submit / steer / inject | `AgentSession` 接受命令时 |
| `PayloadData("state/snapshot")` | `state/checkpoint` | 仅工作状态，不含被当作历史的消息正文 |
| `PayloadData("interaction/requested")` 与 `interaction/resolved` | 同名旧事件 | 权限询问和外部工具 |
| `PayloadData("tool/chunk")`、`subagent/*`、`verification/result` | 同名旧事件 | 工具流、子智能体、校验 |

`TurnEndReason` 与今天 `CallLog.outcome` 的对应：`completed` → `COMPLETED`，`suspended` → `SUSPENDED`，`interrupted` → `INTERRUPTED`，`failed` → `ERROR`，取消 → `ABORTED`。

回合号从 1 起，在同一次 `DurableSession` 里递增。`turnId` 仍是 `AgentSession` 今天生成的字符串，写在内核事件的 `turnId` 字段上。

---

### Task 1: 内核写入句柄

**Files:**

- Create: `agentscope-core/src/main/java/io/agentscope/core/session/log/DurableSession.java`
- Modify: `agentscope-core/src/main/java/io/agentscope/core/session/log/NativeSessionLogStore.java`
- Modify: `agentscope-core/src/main/java/io/agentscope/core/session/log/SessionInputQueue.java`
- Modify: `agentscope-core/src/main/java/io/agentscope/core/session/InMemorySessionLogStore.java`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/session/WorkspaceSessionLogStore.java`
- Test: `agentscope-core/src/test/java/io/agentscope/core/session/log/DurableSessionTest.java`

**Interfaces:**

- Consumes: `SessionJournalStore`、`KernelJournal`、`SessionLog.append`、`MessageDerivation.history`
- Produces: `DurableSession.from`、`DurableSession.append`、`DurableSession.history`、`NativeSessionLogStore.appendExternal`、`SessionInputQueue.applied`

- [ ] **Step 1: 写失败测试**

`DurableSessionTest` 用 `InMemorySessionLogStore` 背后的 `AtomicSessionStore`：

1. `append(new TurnStart(1))` 再 `append(UserMessageData.of(id, content))` 后，重新打开的 `history()` 含这一条用户消息。
2. 写句柄未关闭时，另一边 `appendExternal` 一条 `SessionInputQueue.Input`（steer）。原句柄的下一次 `kernel()` 能看见它。
3. `applied(requestId)` 之后，`pending()` 不再返回该 steer。

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn -pl agentscope-core test -Dtest=DurableSessionTest`

Expected: 编译失败，`DurableSession` 不存在。

- [ ] **Step 3: 实现句柄**

`NativeSessionLogStore` 不再实现旧 `SessionLogStore`。公开方法：

```java
public DurableSession open(SessionKey key, String runId, String turnId);
public void appendExternal(String sessionId, SessionEventData data, String turnId);
public SessionLog read(String sessionId);
```

`open` 创建会话（若不存在），以 `WRITE` 打开 `KernelJournal`，把已提交事件装进一个 `session.log.SessionLog`，交给 `DurableSession`。`read` 用 `READ` 装一份只读日志。

`InMemorySessionLogStore` 和 `WorkspaceSessionLogStore` 的 `open` 改为返回 `DurableSession` 或提供 `read` / `open` 上面这三个方法。列表方法仍返回 `List<SessionKey>`。

删掉 `NativeSessionLogStore` 里的 `toRecordedEvent`、旧 `commit(List<SessionEvent>)` 和对旧 `SessionLog` 的实现。

- [ ] **Step 4: 再跑 DurableSessionTest**

Expected: PASS。

---

### Task 2: ReActAgent 改写内核

**Files:**

- Modify: `agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java`
- Modify: `agentscope-core/src/main/java/io/agentscope/core/tool/ToolExecutor.java`
- Modify: `agentscope-core/src/main/java/io/agentscope/core/observation/ActionObservations.java`
- Test: `agentscope-core/src/test/java/io/agentscope/core/session/ReActHistoryFromLogTest.java`

**Interfaces:**

- Consumes: `DurableSession`
- Produces: 运行时上下文里的 `DurableSession.CONTEXT_KEY`。模型请求前的历史等于 `durable.history()`。

- [ ] **Step 1: 改 ReActHistoryFromLogTest 的断言**

一次 `call()` 之后，日志里要有 `TurnStart`、对应用户消息的 `UserMessageData`、`AssistantMessageData`、`TurnEnd`。系统提示若进入历史，类型是 `SystemMessageData`。断言不再查找 `run/start`、`input/received`、`state/checkpoint`。

- [ ] **Step 2: 换掉 ReActAgent 里的旧调用**

按下表替换。行号是 2026-10-11 的检索位置，实现时以符号为准。

| 位置 | 改成 |
| --- | --- |
| `sessionLogEnabled` | `sessionLogStore != null`。删除 `SessionHistoryMode` |
| `acquireCallExecution` | `new DurableSession(...)` 放入上下文。没有 store 时走现有的无日志路径 |
| `activateSlotForContext` | `loaded` 的消息列表 = `durable.history()`。工作状态来自最后一条 `state/snapshot`，没有则用 `freshState` |
| `inputsForCall` | 若上下文里有待执行命令，用命令上的消息；已在 `history()` 里的 id 不再追加 |
| `reasoning` 开头 | `append(new StepStart(turn, step))`。然后 `SessionInputQueue.pending()`：steer / inject 追加到 `state` 并 `append(UserMessageData)`，再 `applied` |
| 模型调用前 | `append(RequestHeader)`，工具名来自本次请求的 tool schema |
| 助手消息定稿 | `append(AssistantMessageData)`，带 `agentName` |
| 工具结果进入上下文 | `append(ToolResultData)` |
| `settleSession` | 若本回合还有未 `applied` 的 steer，再跑一步；否则结束 |
| `releaseCallExecution` | `append(StepEnd)`（若步未关）和 `append(TurnEnd)`，再 `append(state/snapshot)`，然后 `close()` |
| `observe` | 确认、外部执行、工具块写成上表的 `PayloadData` |
| `SessionModels.wrap` | 删除。模型调用处直接用原模型，信封由上面的 `RequestHeader` 负责 |
| `SessionStateAccess` | 删除调用。管理端读历史改为 `durable.history()`；改工作状态则改内存 `AgentState` 并追加一条新的 `state/snapshot` |

`ToolExecutor` 的 `tool/chunk` 和 `ActionObservations` 的动作记录改为 `DurableSession.from(rc).append(new PayloadData(...))`。`from` 为空时不记。

- [ ] **Step 3: 跑历史测试**

Run: `mvn -pl agentscope-core test -Dtest=ReActHistoryFromLogTest,DurableSessionTest`

Expected: PASS。

---

### Task 3: AgentSession 与 Harness 读入口

**Files:**

- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/session/AgentSession.java`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/tool/SessionSearchTool.java`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/subagent/DefaultAgentManager.java`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/tool/AgentSpawnTool.java`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/compaction/ConversationCompactor.java`
- Modify: `agentscope-harness/src/main/java/io/agentscope/harness/agent/verification/VerificationService.java`
- Test: `agentscope-harness/src/test/java/io/agentscope/harness/agent/session/AgentSessionTest.java`

**Interfaces:**

- Consumes: `DurableSession`、`SessionInputQueue`、`appendExternal`
- Produces: 不变的 `AgentSession` 公开方法。`transcript()` 返回 `List<Msg>`，内容等于 `history()`。`HarnessAgent.sessionTranscript` 同样返回这份列表。

- [ ] **Step 1: 保持 AgentSessionTest 的两个行为**

不改测试意图：

1. `permissionAnswerUsesExistingHitlAndIsNotReplayedAsChatInput`：挂起、答复、打断、继续；继续后的历史里没有 `METADATA_CONFIRM_RESULTS`。
2. `steeringAcceptedDuringFinalModelCallGetsAnotherStepInTheSameRun`：运行中 `steer` 使同一次 run 再推理一步，消息里出现 `new requirement`。

若断言类型从 `SessionViews.Transcript` 变成 `List<Msg>`，只改取值方式，不改行为。

- [ ] **Step 2: 重写 AgentSession 内部**

删除对 `SessionInbox`、`SessionExecution`、`SessionTurns`、`SessionViews`、`SessionProjection` 的使用。

| 方法 | 行为 |
| --- | --- |
| `submit` | `SessionInputQueue.submit`，经 `appendExternal` 落盘，然后启动执行。上下文放入 `TURN_ID_KEY` |
| `steer` / `inject` | 有活跃写句柄时 `appendExternal` 对应的 `Input`。没有正在跑的任务时 `steer` 仍抛出现有的「没有正在运行的任务」 |
| `respond` | 从 `interaction/requested` 且尚未 `interaction/resolved` 的事件解析请求。答复消息作为继续执行的输入，不把确认结果写进模型历史 |
| `interrupt` | `active.interrupt()`，执行结束时 `TurnEnd` 的原因是 `INTERRUPTED` |
| `resume` | 仅当最新回合的 `TurnEnd` 还没出现，或原因是 `SUSPENDED` / `INTERRUPTED`。用同一个 `turnId` 再打开 `DurableSession`，`TurnStart(turn, true)` |
| `await` / `tasks` | 状态从内核事件推导：有 `TurnStart` 无 `TurnEnd` 为 `running`；`TurnEnd` 的 reason 映射成今天的 status 字符串 |
| `transcript` | `MessageDerivation.history` |
| `pending` | 未解决的 `interaction/requested` |
| `inspect` | 删掉公开的 `SessionProjection` 返回。调用方改读 `transcript()` |

`HarnessAgent.sessionLog` 改为返回 `session.log.SessionLog`（只读 `read`）。`sessionTranscript` 返回 `history()`。`reconcileToolOutcomes` 改为追加 `ToolResultData` 和一条说明原因的 `PayloadData`，不再调用 `SessionRecovery`。

`SessionSearchTool` 用 `SessionSearch` 和 `SessionQuery.readEvents`，展示文本用 `MessageDerivation`。

`DefaultAgentManager` 不再放入 `SessionExecution`。子会话用 `SessionFork`，父会话用 `PayloadData("subagent/spawned")` 和 `PayloadData("subagent/completed")`。

`ConversationCompactor` 只判断 `DurableSession.from(rc) != null`。`VerificationService` 从内核事件里找最后一条 `verification/result`。

- [ ] **Step 3: 跑会话测试**

Run: `mvn -pl agentscope-harness -am test -Dtest=AgentSessionTest,DurableSessionTest,ReActHistoryFromLogTest`

Expected: PASS。

---

### Task 4: 删除旧引擎并清编译

**Files:**

- Delete: 下面删除清单中的每一个文件
- Modify: 清单中文件的剩余引用（测试、例子、`ChatHistory`、`ChatSessions`）
- Test: 全量 `agentscope-core` 与 `agentscope-harness` 测试

**Interfaces:**

- Consumes: Task 1–3 的生产路径已经不再引用这些类型
- Produces: `io.agentscope.core.session` 下只剩下 `SessionKey`、`SessionAnswer`、`AtomicSessionStorage`、`InMemorySessionLogStore`，以及 `log/` 包

- [ ] **Step 1: 删除这些文件**

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
agentscope-core/src/main/java/io/agentscope/core/session/SessionHistoryMode.java
```

`SessionLogException.java` 移到 `io.agentscope.core.session.log`，删除原文件。

- [ ] **Step 2: 修剩余编译错误**

测试若仍 `new CallLog` 或扫描 `run/start`、`inbox/started`，改为 `DurableSession` 和内核事件类型。例子 `ChatHistory`、`ChatSessions` 改读 `session.log.SessionLog`。

`HarnessBuilderReuseTest` 和 `HarnessAgentTest` 里的 `SessionHistoryMode.LEGACY` 改为不设置日志存储。

- [ ] **Step 3: 架构断言**

在 `agentscope-core` 加一个测试：编译期内 `io.agentscope.core.session` 包的生产类只有 `SessionKey`、`SessionAnswer`、`AtomicSessionStorage`、`InMemorySessionLogStore`。用源码树列举 `.java` 文件名断言，防止旧类被加回去。

- [ ] **Step 4: 验证**

```bash
mvn -pl agentscope-core,agentscope-harness -am test -Dtest=DurableSessionTest,ReActHistoryFromLogTest,AgentSessionTest,SessionExecutionIdentityTest
```

Expected: PASS。

然后：

```bash
mvn -pl agentscope-core,agentscope-harness -am test
```

Expected: 失败的用例为 0。跳过保持原样。

- [ ] **Step 5: 提交**

```bash
git add agentscope-core agentscope-harness agentscope-examples docs/superpowers/plans/2026-10-11-single-deepseek-session-engine.md
git commit -m "refactor(session): keep only the DeepSeek session engine"
```

## 自检

- 需求「只留 DeepSeek 会话引擎」由 Task 2、Task 3 的生产接线覆盖。
- 需求「替换并下线删除」由 Task 4 的删除清单覆盖。
- 存储类不在删除清单里。
- `CallLog` 在删除清单里，避免把翻译层留下来。
- 前端不在本计划里。
- 工作状态快照留在同一份内核日志里，不另开引擎。

## 执行说明

四个任务同一次做完。Task 4 结束前不要停下来留着旧 `SessionLog`。不要按 `2026-10-13-step3-deletion-inventory.md` 的「本批不删」再推迟；那份清单记录的是旧协议还有调用方时的状态，本计划就是去掉那些调用方。
