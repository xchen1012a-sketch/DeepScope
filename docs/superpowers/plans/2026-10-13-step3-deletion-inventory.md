# 第三步删除对照

日期：2026-10-11。删除只按本文。本文的依据是当天对工作区 `*.java` 的检索，以及 `2026-10-13-session-phase-c-cutover.md` 第 4.1 节的 C2 清单。记忆里的「桥、旧日志、JSON 事件」不作为删除范围。

## 检索

对 C2 清单里的每个类型名，在仓库 `*.java` 中检索。命中数包含定义处。`payloadJson` 另检，向量库里的同名局部变量不算会话日志。

判定：

| 判定 | 含义 |
| --- | --- |
| 已删 | 源文件不在，生产代码无引用 |
| 本批不删 | 检索仍能找到生产调用。删了当前树编不过 |
| 不是旧实现 | 名字相近，但是新内核或存储能力，C2 清单把它和旧日志写在一起 |

## 对照

### 记录层（`agentscope-core/.../session/`）

| 类型 | 源文件 | 检索 | 判定 |
| --- | --- | --- | --- |
| `JournalSessionLog` | 已不在 | 生产代码 0。只剩测试类名 `JournalSessionLogTest` | 已删 |
| `SessionHeader` | 已不在 | 0 | 已删 |
| `SessionRecorder` | 在 | `ReActAgent` 等仍调用 `append` / `start` | 本批不删 |
| `SessionLog` | 在 | 记录协议接口，`SessionRecorder`、`SessionInbox`、两个 Store 都实现或持有它 | 本批不删 |
| `SessionLogStore` | 在 | `ReActAgent`、`HarnessAgent`、测试均传入 | 本批不删 |
| `InMemorySessionLogStore` | 在 | core / harness / examples 测试仍 `new` | 本批不删 |
| `AtomicSessionStorage` | 在 | `AtomicSessionStore`、`LocalFilesystem.sessionStorage` 仍使用 | 不是旧实现。它是内核的存储能力 |
| `SessionEvent` | 在 | 记录协议的事件信封。内核另有 `session.log.SessionEvent` | 本批不删 |
| `SessionEventTypes` | 在 | 记录协议词表。内核另有同名类 | 本批不删 |
| `SessionEventCodecRegistry` | 在 | `SessionProjection` 仍调用 | 本批不删 |
| `SessionExecution` | 在 | `ReActAgent`、`AgentSession` 仍调用 | 本批不删 |
| `SessionInbox` | 在 | `AgentSession`、`SessionExecution` 仍调用 | 本批不删 |
| `SessionInteractions` | 在 | 恢复与身份测试仍调用 | 本批不删 |
| `SessionViews` | 在 | `HarnessAgent.sessionTranscript`、`SessionRecorder` 仍调用 | 本批不删 |
| `SessionProjection` | 在 | `SessionRecorder` 构造时读取 | 本批不删 |
| `SessionTurns` | 在 | `SessionExecution`、崩溃恢复测试仍调用 | 本批不删 |
| `SessionStateAccess` | 在 | 管理端状态仍调用 | 本批不删 |
| `SessionRecovery` | 在 | `HarnessAgent` 仍调用 | 本批不删 |
| `SessionMigration` | 在 | 分叉与基线导入仍调用 | 本批不删 |
| `SessionModels` | 在 | `ReActAgent` 模型调用仍包一层 | 本批不删 |
| `SessionTranscriptExport` | 在 | 导出测试与调用仍在 | 本批不删 |
| `SessionLogExporter` | 在 | `SessionRecorder` 持有 | 本批不删 |
| `SessionExportSink` | 在 | `SessionRecorder` 从上下文读取 | 本批不删 |
| `SessionModelPolicy` | 在 | `SessionModels` 使用 | 本批不删 |
| `SessionKey` | 在 | 所有打开日志的调用都要它 | 本批不删 |
| `SessionHistoryMode` | 在 | `ReActAgent`、`HarnessAgent` 仍分支 | 本批不删 |
| `SessionLogException` | 在 | 记录协议的失败类型 | 本批不删 |

`SessionAnswer` 不在 C2 删除清单里。`AgentSession` 的对外签名还用它。保留。

### 桥

| 类型 | 源文件 | 检索 | 判定 |
| --- | --- | --- | --- |
| `NativeSessionLog` | 已不在 | 生产代码 0。记录协议的打开逻辑在 `NativeSessionLogStore` 内部 | 已删 |
| `NativeSessionLogStore` | `session/log/NativeSessionLogStore.java` | `HarnessAgent` 默认日志、`ReActAgent`、上面两个 Store | 本批不删。这是内核存储的入口，不是待删的旧日志 |

`toSurfaceEvent` 已不在源码里。当前桥把记录事件的 `data()` 交给内核，读回时再装回记录信封。翻译方法没了，适配类还在，因为调用方的类型仍然是 `io.agentscope.core.session.SessionLog`。

### Harness 存储

| 类型 | 源文件 | 检索 | 判定 |
| --- | --- | --- | --- |
| `WorkspaceSessionLogStore` | 在 | `SessionSearchTool`、`ChatSessions`、多处测试 | 本批不删 |
| `LocalSessionStorage` | 在 | `LocalFilesystem` 返回它，sqlite 测试使用 | 不是旧日志。它实现 `AtomicSessionStorage` |
| `SqliteSessionStorage` | 在 | 测试与本地文件系统使用 | 同上 |
| `StoreSessionStorage` | 在 | redis 集成测试使用 | 同上 |

### `payloadJson`

会话事件记录 `SessionEvent` 已没有 `payloadJson` 字段。`RecordedPayloadTest` 断言序列化结果里不出现这个字段。向量库 `PgVectorStore`、`MilvusStore`、`ElasticsearchStore` 里的 `payloadJson` 是向量载荷，不删。

## 什么时候删

「本批不删」只描述 2026-10-11 这一轮检索当时的决定：这些类型还有生产调用，那一轮不能删。它不是永久保留。

| 对象 | 删除时机 |
| --- | --- |
| `JournalSessionLog`、`SessionHeader` | 已经删了 |
| `NativeSessionLog` | 已经删了。打开会话仍由保留的 `NativeSessionLogStore` 完成 |
| `SessionRecorder` 和记录包里其余标成「本批不删」的类 | 再下一批。`ReActAgent` 和 `AgentSession` 改为内核追加与派生之后再删。这是 C2 正文 |
| `NativeSessionLogStore` | 不删。它是内核存储的入口 |
| `AtomicSessionStorage`、`LocalSessionStorage`、`SqliteSessionStorage`、`StoreSessionStorage` | 不删。检索证明它们是内核的存储能力，C2 清单把它们和旧日志写在一起了 |

## 本批执行

检索允许删除、且源文件已经不在的，只有：

- `JournalSessionLog`
- `SessionHeader`

本批不再删其他文件。C2 第 4.1 节的其余名字都还有生产引用，按该节直接删会把 `ReActAgent` 和 `HarnessAgent` 的活路径拆掉。

## 下一批的门

`NativeSessionLog` 已删。`InMemorySessionLogStore` 和 `WorkspaceSessionLogStore` 打开的是 `AtomicSessionStore` 上的 `NativeSessionLogStore`。记录协议仍由这个存储类内部实现，因为 `SessionRecorder` 还要 `SessionLog`。

要删 `SessionRecorder` 和记录包里其余类，得先把 `ReActAgent`、`AgentSession` 改到内核追加与派生。那是 C2 正文，不是本批。
