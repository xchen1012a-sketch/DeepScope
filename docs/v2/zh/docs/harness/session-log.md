---
title: 会话操作、事件与恢复
description: 从后台聊天出发，学习提交任务、补充信息、回复待办和中断恢复，并用会话日志恢复页面、排查执行及配置存储。
en_link: /v2/en/docs/harness/session-log
---

用户让 Agent 比较两份方案，等待期间又补充了一条要求，随后关闭页面。再次打开时，应用应该展示之前的回答、工具执行结果和当前进度；如果任务曾被中断，还应该能从保存的状态继续。

这涉及两件事：**谁负责把任务继续执行下去，以及执行过程保存在哪里**。`AgentSession` 提供提交、引导、回复和恢复等会话操作；Session Log 保存输入、消息、工具执行和工作状态。前端读取这些记录，就能在重新连接后恢复页面，而不必重新发起任务。

本文从这个聊天场景逐步介绍用法，再说明事件查询、存储配置和扩展方式。可以配合[可恢复聊天示例](/v2/zh/blogs/best-practices/session-chat)实际体验。通过 AgentScope Service 使用托管 Agent 的应用，直接接入 [Service Agent API](/v2/zh/service/session-event-log) 的 HTTP/SSE 接口即可。

<span id="基本概念与-api" />

## 先选择调用方式

普通问答、多轮聊天，以及随当前请求结束的流式回复，继续使用 `agent.call(input, ctx)` 或 `agent.streamEvents(input, ctx)`。HarnessAgent 默认也会保存这些调用的消息历史和 checkpoint；仅为了记住上一轮对话，不需要改用 `AgentSession`。这类请求推荐从共享 Builder 创建新 Agent，执行结束后关闭，见[快速开始](/v2/zh/docs/quickstart)。

当任务需要在页面关闭后继续，或需要排队、运行中补充要求、回复待办和中断后续做时，再使用 `agent.session(ctx)`。返回的 `AgentSession` 是这段会话的操作入口，由框架持有后台执行；HTTP 请求或前端观察连接结束，不会因此取消任务。

后台执行需要对应的生命周期：应用的会话管理器或任务 worker 可以从共享 Builder 构建 Agent，但应持有它直到后台工作结束或应用退出，不能在提交接口返回时就关闭。具体资源管理方式见[实例生命周期](/v2/zh/docs/building-blocks/agent#实例生命周期)。

<span id="场景一聊天排队与重启恢复" />

## 提交任务，让它在后台执行

假设要做一个“比较方案”的聊天助手。应用启动时配置共享 Builder，由会话管理器构建并持有运行实例，再用用户和会话身份取得 `AgentSession`。下面的 `model` 是已经配置好的[模型](/v2/zh/docs/building-blocks/model)：

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;

HarnessAgent.Builder agentBuilder = HarnessAgent.builder()
        .name("方案助手")
        .agentId("proposal-assistant")
        .model(model)
        .workspace(Path.of("/data/proposal-workspace"));

// 由应用的会话管理器持有，后台任务结束或应用退出时再关闭。
HarnessAgent agent = agentBuilder.build();
RuntimeContext ctx = RuntimeContext.builder()
        .userId("alice").sessionId("conversation-001").build();
var session = agent.session(ctx);

var task = session.submit("request-001", "比较方案 A 和方案 B，先给出初步结论。");
System.out.println(task.turnId());
```

`submit` 会先持久保存输入，再安排执行。返回的 `task` 是接收回执，不是模型回复；任务还在排队时，`runId` 可能尚未分配。Web 接口通常把回执返回前端，再让前端独立读取进度。

这里的 `request-001` 应是业务为这次提交生成的唯一请求标识。网络超时后重试时，沿用相同标识和输入，就会得到同一次提交的回执；用户主动发起另一个任务时，应使用新标识，也可以调用不带标识的 `submit(input)`，由框架生成。

如果用户在比较尚未结束时又提交“根据结论起草一封邮件”，这就是另一项任务，会按接收顺序排队。当前任务等待人工答复或被中断时，队列仍会保留，先处理原任务的待办或继续原任务，再执行后续任务。`session.tasks()` 可以读取任务状态。

在命令行或后台代码中，可以等待这次执行结束，然后读取已保存的消息：

```java
var outcome = session.await(task).block();
System.out.println(outcome.status());
session.transcript().messages()
        .forEach(message -> System.out.println(message.getTextContent()));
```

`await` 只负责观察。它会在本次执行完成、挂起、中断或失败后返回；`suspended` 表示 Agent 正在等用户答复，不代表整个任务完成。取消对 `await` 的订阅也不会取消后台任务。

即使此前一直通过 `agent.call(input, ctx)` 对话，仍可以用相同身份取得 `session` 并读取 `transcript()`，不必再次提交输入。**同一会话选择一套执行方式：直接调用，或由 AgentSession 调度。** 已提交任务的进度通过日志观察；再调用 `streamEvents` 会启动另一次执行。

<span id="场景二运行中补充信息与前端续传" />

## 在执行过程中补充要求或材料

比较正在进行时，用户说“重点看运维成本”。这句话是在调整当前任务，而不是要求再做一次比较。处理这条用户消息的请求可以调用 `steer`：

```java
session.steer(task, "重点比较运维成本，而不是只看采购价格。");
```

框架会把这条要求关联到原任务，在下一次推理步骤交给模型。已经发出的模型请求和正在执行的工具参数不会被改写。传入 `task` 还能检查目标是否仍是当前任务，避免用户在旧页面发出的要求误落到后来的任务上。应在任务仍运行时调用，而不是等前面的 `await` 返回后再调用。

如果补充的是背景资料，例如“团队只有两名运维人员”，可以使用 `inject`：

```java
session.inject("补充材料：团队只有两名运维人员，没有夜间值班安排。");
```

`inject` 保存材料供后续推理使用，但不会单独启动任务。Agent 正在工作且还有后续步骤时，可以在当前执行中读取；会话空闲或当前执行已没有后续步骤时，材料留到后续执行。未被消费的 `steer` 则仍属于原任务，中断后继续该任务时再读取。

因此，业务界面可以把“发送新任务”和“补充当前任务”做成不同操作：新任务调用 `submit`，调整当前工作调用 `steer`，只存入参考资料调用 `inject`。用户不需要理解底层 turn 或 run，也不需要手工设置这些标识。

## 刷新页面后，先恢复内容，再继续接收事件

页面重连与任务执行是两条独立链路。用户刷新页面时，后台可能已经完成了一次工具查询，写出半段比较结论，又开始执行第二个工具。此时仅接收“从现在开始”的新片段，页面就会缺少之前的内容。

先区分两种读取需求。只展示已经保存的完整消息时，使用 `session.transcript()` 即可：它返回一个 `AgentSession.Transcript`，其中只有完整消息列表。但它不是一个完整的流式聊天界面快照：进行中的文本和工具参数不在其中，这些内容来自 `assistant/message` 内嵌的 `AssistantStreamRecord`，工具进度则来自 `tool/chunk`。

如果要恢复“写到一半的回复”和工具卡片，应用需要把已提交事件整理成页面视图。[聊天示例](/v2/zh/blogs/best-practices/session-chat)中的 `ChatHistory` 和 `ChatItems` 已实现这部分：除了完整历史，还恢复文本前缀、ToolCall 参数、ToolResult、执行进度和待答请求。这是示例提供的应用层视图，可以作为你接入前端时的参考。

接入时，围绕同一份快照完成以下流程：

1. **打开或刷新页面，先取快照。** 后端选定一个已提交日志位置，读取截至该位置的记录，生成页面内容，并一起返回 cursor。例如 cursor 对应第 120 条事件，快照就应该包含截至第 120 条的消息和工具状态。
2. **渲染完成后，从这个 cursor 继续观察。** 即使获取快照期间后台已产生第 121～125 条事件，续读也会把它们补上。消息和工具应按稳定 ID 更新原条目，避免同一个结果重复显示。
3. **区分短暂断线与页面刷新。** 页面仍保留内容时，可以从最后已处理的事件位置续读；刷新导致页面状态丢失时，应重新获取完整快照。只在浏览器保存一个 cursor，并不能恢复 cursor 之前的内容。

聊天示例把快照暴露为 `GET /api/sessions/{id}`，再通过 `GET /api/sessions/{id}/stream?after={cursor}` 提供 SSE。它收到 `committed` 通知后会合并频繁通知并重新读取快照，让页面始终显示后端整理好的视图；并不是把每条原生事件都直接交给浏览器拼接。这些 HTTP 接口属于示例应用，SDK 本身提供的是会话与日志读取 API。

重新连接时只做读取，**不要再次调用 `submit` 或 `streamEvents`**。浏览器的连接负责观察已有任务，任务是否继续由会话管理器负责。使用 Service 时，可以直接采用其公共快照、SSE 和 cursor 协议，见 [Service 事件接入](/v2/zh/service/sse-events)。

<span id="场景三回复-hitl-待办" />

## 回复 Agent 的问题或确认请求

假设 Agent 在生成建议前需要询问部署环境，或在执行工具前需要用户确认。通过 `AgentSession` 提交的任务可以挂起等待回答；问题保存在日志里，用户刷新页面、甚至服务重启后仍能看到。

用 `session.pending()` 读取尚未答复的交互。返回值的 key 是 `requestId`，页面应把它与对应的问题一起保存，提交答案时原样带回：

```java
session.pending().forEach((requestId, request) ->
        System.out.println(requestId + " " + request.data().get("kind")));
```

对于 `ask_user` 或外部执行产生的 `external_execution` 请求，把用户答案或真实执行结果交给 `respond`。下面的标识需要替换成页面所回答的待办 ID：

```java
var answered = session.respond("external-request-id", "部署在自建 Kubernetes 集群。");
```

如果待办类型是 `confirmation`，用布尔值表示同意或拒绝，例如 `session.respond(requestId, true)`。需要附带拒绝原因时，可传 `SessionAnswer.reject(reason)`。框架会查找待办所属的原任务和工具调用，应用无需自己拼接工具 ID，也无需再调用 `resume`；`respond` 已经安排了接续执行。

`respond` 返回新的执行回执。需要等待这次接续时，使用 `session.await(answered)`；原提交的 `await(task)` 观察的是挂起前的那次执行。未知、已处理或答案类型不匹配的待办会被拒绝。

需要自定义工具结果块时使用 `SessionAnswer.Output`；需要提交修改后的参数或权限规则时使用 `SessionAnswer.Confirmation`。同一任务内多个并行工具的答案可以用 `respond(Map<String, SessionAnswer>)` 一起提交。Service 的在线交互通过其所属服务的 actions 接口答复。

这是对原有 HITL 机制的会话封装：Agent 仍在原有的工具确认或外部输入位置暂停，Session Log 让待办与后续答案可持久恢复。直接使用 `call` 的应用也可以继续使用[原有 HITL API](/v2/zh/docs/building-blocks/agent#人机交互)，不必仅为人工确认更换执行方式。

<span id="场景四中断后继续原任务" />

## 中断后继续原任务

用户点击“停止”时，调用 `session.interrupt()` 请求当前执行在协作检查点停止。它不是立即终止工具的命令，方法返回也不代表执行已经停下；应等待任务状态变成 `interrupted`，再提供“继续”操作。

下面展示同一任务的停止与接续。Web 应用通常把这两步放在两个用户操作中；这里用 `await` 表示等待停止完成：

```java
session.interrupt();
var stopped = session.await(task).block();
if ("interrupted".equals(stopped.status())) {
    var continued = session.resume(stopped.turnId());
    System.out.println(continued.turnId());
}
```

前端已经知道正在显示的 `runId` 时，可以使用 `session.interrupt(runId)`，避免旧页面的停止操作误中断另一轮执行。`resume` 会恢复已保存的工作状态与尚未应用的原输入，继续原任务；业务无需重发用户消息、构造空输入或设置 turnId。

恢复使用的是 **checkpoint**：执行过程中保存的会话工作状态。它让 Agent 能从已提交状态接着推理，但不会恢复原 Java 线程、网络请求或工具内部执行进度，也不会回滚工具已经产生的外部效果。普通工具所需的凭据、工作文件和外部资源也必须仍然可用。

当前只能继续最新的未完成任务。已经完成或取消的任务不能 `resume`；有待答交互时应先 `respond`。如果之前通过 `agent.call` 或 `agent.streamEvents` 直接执行，中断仍使用运行实例上的 `agent.interrupt`；日志负责保存状态，会话入口负责安排后续任务，二者不是互相替代的机制。

### 服务重启后继续

重启后用相同的 `agentId`、`userId`、`sessionId` 和日志后端重新构建 Agent，再取得 `session`。此时可以直接读取历史和待办；读取本身不会触发模型调用。

```java
session.transcript().messages()
        .forEach(message -> System.out.println(message.getTextContent()));
session.start(); // 应用启动时，重新开启已接收任务的调度。
```

`start()` 会检查持久队列，但不会擅自恢复已中断的任务；由应用决定何时调用 `resume`，或等待用户回答待办。`submit`、`resume`、`respond` 本身会自动开启调度，因此正常交互不必每次手工调用 `start()`。

关闭所属 `agent` 会停止调度并中断其持有的执行，已提交日志和队列由持久后端保留。如果进程被强制终止，可能还需要等待旧执行的写入租约释放或到期，新的执行才能安全接手。

### 核对结果未知的工具

假设工具已经在订单系统创建了订单，但进程在记录工具结果之前退出。日志能说明工具曾被调用，却不能证明订单创建成功还是失败；直接重试可能重复创建订单。

SDK 没有列出“结果未知的工具调用”的 API。需要自己读取已提交日志，判断哪些调用还没有结果，并读取已保存的工作状态：

```java
import io.agentscope.core.session.log.StateSnapshot;

StateSnapshot snapshot = agent.inspectSession(ctx);
System.out.println(snapshot.asOfSeq()); // 这份状态读取到的已提交位置
// 从日志判断哪些 callId 仍没有结果，再去业务系统核对。
```

核对完成后，写明依据并记录这些 callId：

```java
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.Map;

// ID 与名称来自日志中待核对的调用，结果来自对订单系统的实际查询。
var result = ToolResultBlock.builder()
        .id("call-id-from-the-log").name("create_order")
        .output(TextBlock.builder().text("已核实订单 ORD-42 创建成功").build())
        .state(ToolResultState.SUCCESS).build();
agent.reconcileToolOutcomes(ctx, Map.of(result.getId(), result), "已核对订单系统记录");
var continued = session.resume(task.turnId());
```

`reconcileToolOutcomes` 会写入一条 `tool/reconciled` 载荷，其中是核对原因和对应的 callId；它不会再次执行这些工具，也不会保存结果正文或 checkpoint。之后再显式 `resume` 原任务。实际结果仍未知时，不应填一个空的成功结果来绕过检查。

<span id="sessionturn-和-run" />

## session、turn 和 run 如何对应

可以把 session 理解为一段持续对话，turn 理解为用户提交的一项任务，run 理解为完成该任务的一次实际执行。一次 run 中可以多次推理、调用多个工具，并生成多条消息；它不等于一次模型调用或一条 AssistantMessage。

例如，用户在会话 `S1` 中要求“比较方案，发邮件前先让我确认”：

| 用户操作或执行状态 | turnId | runId |
| --- | --- | --- |
| 提交比较任务，Agent 开始推理并查询资料 | `T1` | `R1` |
| 刷新页面，恢复消息和工具卡片 | `T1` | 仍是 `R1` |
| 补充“重点比较成本”（`steer`） | `T1` | 仍是 `R1` |
| 中断任务，之后点击继续 | `T1` | `R1` 结束，新建 `R2` |
| Agent 挂起等待确认，用户答复后继续 | `T1` | `R2` 结束，新建 `R3` |
| 完成后提交另一项任务 | 新建 `T2` | 新建 `R4` |

表中的 `sessionId` 始终是 `S1`。这里展示的是挂起式 HITL；如果采用执行仍保持存活的在线等待方式，收到答复后也可以继续原 run。业务通过 `submit`、`steer`、`respond` 和 `resume` 表达意图，框架负责关联这些身份。

日志还使用稳定的 `agentId` 和 `userId` 区分 Agent 与用户。不要把提交请求的幂等 key、HITL 的 `requestId`、工具调用 ID 或事件 `eventId` 混作 turnId：它们分别用来防止重复提交、回答待办、关联工具结果和去重事件。

<span id="场景五排查执行导出记录和更换后端" />

<span id="历史事件与-checkpoint" />

## 事件结构与读取

前面的页面恢复、待办展示和中断续做，都依赖同一份 Session Log。它保存“执行中发生了什么”，再从记录中生成不同视图：`transcript()` 用于消息历史，`pending()` 用于待办，`inspect()` 用于恢复检查。checkpoint 保存继续执行所需的工作状态；上下文压缩后，界面历史仍可保留更早的消息，模型后续使用的工作上下文则可能已经缩短。

`AgentEvent` 是 `streamEvents` 产生的实时通知，适合展示当前请求；`SessionEvent` 是已持久保存的记录，适合历史读取、恢复和导出。实时通知到达不代表对应日志已经提交，需要可靠重放时应以 Session Log 的已提交内容为准。Service 的公共 SSE 还会对事件做面向客户端的整理，它的 cursor 与 SDK 原生序号不是同一个协议。

排查某次任务时，可以按 `turnId` 查看它跨多次执行的记录，再按 `executionRunId` 细看一次执行。下面读取全部已提交事件，不会触发模型或工具：

```java
import io.agentscope.core.session.log.SessionEvent;

var log = session.log(); // 也可以使用 agent.sessionLog(ctx)。
for (SessionEvent event : log.events()) {
    System.out.printf("%d %s turn=%s run=%s%n",
            event.seq().value(), event.type(), event.turnId(), event.executionRunId());
}
```

`seq` 是当前会话内从 0 开始连续递增的持久序号，排序和续读都使用它。`events()` 按序号返回全部已提交事件；需要续读时保存最后一条已处理事件的 `seq`，下次只处理序号更大的事件，也可以用 `eventAt(seq)` 读取单个位置。`eventId` 用于接收端去重，不能当作顺序号。

这些日志读写和 `AgentSession` 的同步操作会访问存储。在 WebFlux handler 中，应使用 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 调度，避免阻塞网络事件线程；`await` 自身已安排好状态轮询。

### 事件目录

普通聊天应用不需要逐项处理下面所有事件。需要构建时间线、查询工具结果或做诊断时，再按关注的内容读取对应类型。

| 关注什么 | 事件类型 |
| --- | --- |
| 产生模型可见消息的类型 | `system/message`、`developer/message`、`user/message`、`assistant/message`、`tool/result`；只有这些类型会生成模型历史，也只有它们可以携带表面位置 |
| 任务边界 | `turn/start`、`turn/resumed`、`turn/end`；续做会写 `turn/resumed` 而不是 `turn/start`，`turn/end` 记录关闭该 turn 的 `TurnEndReason` |
| 推理步骤 | `step/start`、`step/end`；一个步骤是一次模型调用及其触发的全部工具执行 |
| 工具请求与未产生消息的模型尝试 | `tool/call`（按 `callId` 与 `tool/result` 配对）、`assistant/attempt` |
| 请求信封与路由信息 | `request/header`、`request/context`；后者只在该路由或其容量变化时记录 |
| 继承历史与后续工作的分界 | `session/end-seed` |
| 已接收输入 | `input/submitted`、`input/steered`、`input/injected`、`input/applied`、`input/rejected`：分别表示提交的任务、对运行中工作的引导、留待后用的材料、某次执行已消费该输入的标记，以及被拒绝的请求及原因。`resume`、`respond` 等其他已接收类型使用同一条 `SessionInputQueue.Input` 记录 |
| `input/received` | 有意保留的不透明载荷名，由 `DurableSession.start` 在每个 turn 起始写入，载荷字段是 `messages`；它不属于上面的已接收输入族 |
| 保存的工作状态 | `state/checkpoint`；有意保留的不透明载荷名 |
| 执行结果与中断请求 | `run/end`、`run/stop_requested`；有意保留的不透明载荷名 |
| 只读别名 | `state/snapshot`；在读取 `state/checkpoint` 的位置同样被接受，但没有写入者 |
| 已无写入者的保留名 | `run/start`；turn 由 `turn/start` 或 `turn/resumed` 开启，读 `run/start` 只会读到空 |
| 人工待办及其答复 | `interaction/requested`、`interaction/resolved` |
| 工具决策、下发、进度、动作边界与模型重试 | `tool/decision`、`tool/dispatch`、`tool/chunk`、`action/start`、`action/end`、`model/dispatch`、`model/retry` |
| 上下文、验证与结果核对 | `context/build`、`compaction/start`、`compaction/end`、`verification/result`、`tool/reconciled` |
| 子 Agent 与父会话的关联 | `subagent/spawned`、`subagent/completed`、`subagent/linked`；完整子 Agent 过程在其自己的日志中 |
| 标题、展示提示与应用扩展 | `session/title`、`presentation/hint`、`extension/<name>` |

记录范围是模型适配器可见的请求与输出、工具和框架执行过程，不包括模型服务内部过程，也不会自动备份被引用的文件。原始请求和工具结果可能包含业务数据，面向浏览器的接口应挑选需要展示的字段。

<Accordion title="集成时需要的 SessionEvent 字段">

每条 `SessionEvent` 的载荷都会冻结为 JSON，后续修改原对象不会改变已记录内容。

| 字段 | 用途 |
| --- | --- |
| `seq` | 日志中的位置；从 0 起连续，追加时等于日志长度 |
| `time` | Unix 毫秒时间戳；排序仍使用 `seq` |
| `type` | 事件类型；必须与载荷自身的类型一致 |
| `data` | 类型化载荷，即 `SessionEventData` |
| `surfaceOp` | 在模型可见表面上的位置；只有产生消息的事件才携带，仅日志事件为 null |
| `sourceEventSeqs` | 本条事件派生自或被其遮蔽的更早位置；没有引用时为空 |
| `ignorable` | 读取方不认识 `type` 时是否可以跳过该记录；只有扩展类型为 true |
| `eventId` | 记录方身份，用于去重 |
| `executionRunId` / `turnId` | 拥有该记录的执行与 turn；没有时为 null。部分管理操作没有 turnId |

</Accordion>

## 存储位置与后端配置

默认情况下，HarnessAgent 使用 `WorkspaceSessionLogStore`，日志跟随 Workspace 的 Filesystem 保存。**Workspace 可以使用本地磁盘，也可以使用分布式存储。** 创建新 Agent 实例或重启应用后，只要身份与存储配置保持一致，就能找到原会话。

| 配置 | 去哪里查找记录 |
| --- | --- |
| 本地 Filesystem | 当前身份解析后的 Filesystem 根目录下 `.agentscope-runtime/` |
| RemoteFilesystem | 对应 BaseStore namespace 下的 `__agentscope_session_log_v1__` 分区 |
| 单独配置日志后端 | `.sessionLogStore(store)` 指定的后端 |
| AgentScope Service 托管 | 由服务配置原生日志和公共事件存储，见 [Service 会话日志](/v2/zh/service/session-event-log) |

Linux 和 macOS 的本地存储会先同步临时文件，再通过原子替换提交记录，并同步父目录。Windows 的 Java 文件系统不能打开目录来执行这种同步，因此本地 Session 存储改用 `.agentscope-runtime/journal.sqlite3` 中的 SQLite 事务，启用回滚日志和 `synchronous=EXTRA`。写入仍需通过原子的版本比较，存储错误也会返回给调用方。应用继续通过 SDK 读取记录；备份 Windows 工作区时，应先暂停所有写入再复制数据库，或者使用 SQLite 的一致性备份。POSIX 文件和 Windows 数据库存放的是同一套逻辑对象，但物理格式不同，跨平台迁移历史时需要进行日志迁移，或者使用共享后端。

这里的“当前身份”由 `SessionKey(userId, agentId, sessionId)` 和 Filesystem 的 namespace 共同确定。开启用户或会话隔离后，实际根目录会随身份变化，因此不能把所有会话都理解为写入 Workspace 下同一个固定目录。多副本必须连接同一份日志存储，并使用一致的身份与 namespace；仅让每个副本使用同名的本地目录并不能共享会话。

### 将日志存到共享后端

如果工作文件放在本机或沙箱，但希望会话日志跨节点保存，可以单独配置日志后端。下面的 `sharedStore` 是应用已经配置好、支持原子版本写的 `BaseStore`：

```java
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.util.List;

var logStore = new WorkspaceSessionLogStore(
        new RemoteFilesystem(sharedStore, List.of("my-app", "session-history")));
HarnessAgent.Builder sharedLogBuilder = HarnessAgent.builder()
        .name("方案助手").agentId("proposal-assistant")
        .model(model).workspace(Path.of("/data/proposal-workspace"))
        .sessionLogStore(logStore);
```

随后从这个 Builder 构建 Agent 即可，调用方式不变。可选后端及连接方式见[分布式存储集成](/v2/zh/integration/distributed/index)。内存后端适合测试，进程退出后不会保留历史。

共享存储不会让直接调用的同会话请求自动排队，这类请求仍需应用协调。使用 AgentSession 时，输入由 `SessionInputQueue` 以 `input/*` 记录写入同一份日志并据此排队，执行由已启动的会话调度器负责。会话列表也受当前 namespace 范围限制，SESSION 隔离下的列表不能枚举其他会话。

### 自定义后端与备份

一般应用只需选择现成后端。需要接入自己的存储时，可以实现 `SessionLogStore`，或实现 `AtomicSessionStorage` 并通过 `AtomicSessionStore` 为内核提供服务。它们需要支持原子版本比较写入、执行写入租约与旧写入者隔离，以及有序幂等提交；普通文件的读写接口不足以保证这些语义。

自定义 Filesystem 应提供 `sessionStorage` 能力，或者单独指定日志后端。已接收的输入不需要第二套后端：`SessionInputQueue` 把它作为 `input/*` 事件写入同一份日志，因此存储只需服务这一份日志。需要列出会话时，使用 `SessionLogStore.list(RuntimeContext)`。

备份建议使用后端一致性快照，或先暂停写入，再保存会话记录。工作文件、沙箱内容、外部产物和工具依赖需要另外备份，只有日志不足以完整恢复所有外部环境。

<Accordion title="需要运维或实现后端时：日志对象与已接收输入">

每个会话在日志根目录或分区下保存如下对象。会话的内核 ID 是 `k_` 加上逻辑身份路径 `agents/s_<agent>/sessions/s_<user>/s_<session>` 的 Base64URL 编码：

```text
sessions/
  k_<身份路径的 base64url>/
    header.json
    events/<seq>.json
```

`header.json` 保存格式版本和已提交事件数量，`events/<seq>.json` 每条保存一个已提交事件，记录形式与导出相同。这些对象并非供应用逐行解析的 JSONL；POSIX 本地对象带有版本前缀，Windows 则把逻辑路径、版本和载荷保存为 SQLite 数据行，应通过 SDK 读取。

输入不再使用单独的日志：`submit`、`steer`、`inject` 分别把 `input/submitted`、`input/steered`、`input/injected` 写进同一份日志，`SessionInputQueue.accepted()` 按顺序返回全部已接收记录。一条记录是否已被应用由它的位置决定，而不是由另一个组件写下的标记决定：会开启 turn 的记录，在它之后出现对应的 `turn/start` 或 `turn/resumed` 时即视为已应用；引导和材料会一直留在 `SessionInputQueue.pending()` 中，直到某次执行用 `input/applied` 标记已消费。`SessionInputQueue.reject(requestId, reason)` 记录被拒绝请求的标识与原因。

备份需覆盖会话头及其可达的全部事件对象，使用后端一致性快照或先暂停写入。工作文件、沙箱内容、外部产物和工具依赖需要另外备份。

同一 Agent 会复用相同身份的 AgentSession，并使用首次创建时的 RuntimeContext 副本调度。用户身份、namespace 和会话级配置应保持稳定；之后每条消息通过会话操作传入。

</Accordion>

## 导出到其他系统

如果要把执行历史送到审计系统或数据仓库，可以用 `SessionExport` 把已提交记录渲染成 JSONL。导出与后端无关：头一行标明格式版本和会话，之后每行是一个已提交事件，记录形式与存储相同，因此导出结果和存储内容描述的是同一批事实。导出只读取历史，不会重新运行 Agent。

```java
import io.agentscope.core.session.log.LocalSessionStore;
import io.agentscope.core.session.log.SessionExport;
import io.agentscope.core.session.log.SessionQuery;
import io.agentscope.core.session.log.SessionSeq;
import java.nio.file.Path;

var query = new SessionQuery(new LocalSessionStore(Path.of("/data/session-logs")));
String jsonl = SessionExport.of(query, "conversation-001");

// 只导出到某个已提交位置（含该位置）。
SessionExport.Bounded bounded =
        SessionExport.upTo(query, "conversation-001", SessionSeq.Seq.of(41));
String prefix = bounded.jsonl();
SessionSeq.Seq through = bounded.throughSeq();
```

`of` 导出整个会话，会话不存在时抛出异常；`upTo` 返回截至某个位置（含）的内容和该位置，该位置上没有事件时抛出异常。被遮蔽的事件也会导出：日志有意保留它们，丢弃会丢掉用户已经看到的对话。渲染结果是确定的，同一段已提交前缀总会生成相同的行；导出失败后重试时，目标端按每行携带的 `eventId` 去重即可。Service 已提供公共事件导出与 SSE，使用托管接口的业务通常不必自行实现这一层。

## 记录应用自己的事件

例如，人工复核或业务校验发生在工具或中间件中，希望它也出现在执行时间线上，可以从本次运行的 `RuntimeContext` 取得内核写入者 `DurableSession`，再追加这条事实：

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.log.DurableSession;
import java.util.Map;
import reactor.core.publisher.Mono;

static Mono<Void> recordReview(RuntimeContext context, String note) {
    DurableSession session = DurableSession.from(context);
    if (session == null) return Mono.empty();
    return session.record("acme/review_note", Map.of("note", note));
}
```

将返回的 `Mono` 组合进工具或中间件的调用链，才能等待这次提交；如果调用方本身就是同步的，可以用 `append(type, payload)` 立即写入。载荷字段按不透明 JSON 保存，其含义由应用自己解释。内核目录之外的事件类型会作为可跳过的扩展记录保存，内核也没有编解码注册机制可以注册它们：应用自定义类型既不会让恢复失败，应用也无法让恢复依赖某个自定义类型。不要在调用结束后继续保留该写入者。

## 分支会话

需要从当前对话尝试另一条路线时，可以用内核的 `SessionFork` 构造子会话的起始事件（fork seed）。分支复制一段前缀、标记继承切点，只为前缀留下的未闭合尾部补上关闭事件；源日志不会被修改。

```java
import io.agentscope.core.session.log.SessionFork;
import io.agentscope.core.session.log.SessionSeq;

// sourceEvents 是源会话的已提交事件，boundary 是最后一条被复制的事件（含）。
SessionFork.Seed seed =
        SessionFork.buildForkSeed(sourceEvents, SessionSeq.Seq.of(41), "conversation-001");

seed.events();              // 复制的前缀、继承标记，以及为未闭合尾部生成的关闭事件
seed.inheritedEventCount(); // 复制的前缀长度；从该位置起的事件属于子会话
seed.parentSession();       // 源会话 ID
```

`buildForkSeed(source)` 复制整份日志，`buildForkSeed(source, boundary)` 复制到指定位置（含）。`SessionFork.inheritedEventCountOf(events)` 可以从恢复出来的起始事件重新折算出继承切点，`SessionFork.isChildOwned(seq, inheritedEventCount, size)` 用来判断某个位置是子会话自己产生的还是继承来的。内核没有把完整 `AgentState` 导入为新会话起始状态的 API。

构造起始事件前应停止源执行。子会话从复制的前缀开始，其后的内容由子会话自己产生；分支不会带来源会话的沙箱或外部资源。

<span id="完整示例可恢复的-web-chat" />

## 把这些能力接到完整应用中

[可恢复聊天示例](/v2/zh/blogs/best-practices/session-chat)可以离线运行，也可以接入真实模型。建议先启动它，发送多步任务，在工具执行和文本生成期间刷新页面；再试一次待办答复、中断与继续。这样可以直接看到：提交接口负责表达用户意图，后台会话负责执行，快照与事件负责恢复页面，checkpoint 负责继续任务。
