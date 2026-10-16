---
title: Session operations, events and recovery
description: Build background conversations, restore the UI, handle user input and continue interrupted tasks with AgentSession.
zh_link: /v2/zh/docs/harness/session-log
---

If you followed the [Quick Start](/v2/en/docs/quickstart), keep using `agent.call` for replies or `agent.streamEvents` for live output in ordinary multi-turn chat. HarnessAgent saves history and checkpoints for those calls by default. Persistence alone does not require `AgentSession`.

Use `AgentSession` when your application accepts a task now and lets it run in the background: the user can close the page, submit more work, add guidance, answer a question or continue after interruption. The application submits commands; the frontend independently reads committed progress. This guide follows a cost-comparison assistant through those interactions.

If AgentScope Service hosts your Agent, use its [Service Agent API](/v2/en/service/session-event-log). For a complete SDK application, see the [recoverable chat example](/v2/en/blogs/best-practices/session-chat).

<span id="concepts-and-basic-apis" />

<span id="scenario-1-chat-queueing-and-restart-recovery" />

## Start a background conversation

Configure a shared Builder, then let your application's session manager build and retain the runtime that owns the conversation. The following assumes `model` is configured as described in [Models](/v2/en/docs/building-blocks/model):

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;

HarnessAgent.Builder chatBuilder = HarnessAgent.builder()
        .name("Cost Assistant")
        .agentId("cost-assistant")
        .model(model)
        .workspace(Path.of("/data/chat-workspace"));

// The application session manager retains this runtime.
HarnessAgent agent = chatBuilder.build();
var ctx = RuntimeContext.builder()
        .userId("alice").sessionId("conversation-001").build();
var session = agent.session(ctx);

var task = session.submit("request-001", "Compare the operating costs of plans A and B");
System.out.println(task.turnId());
```

Return the task receipt from the HTTP handler. `submit` durably accepts the input and allocates a `turnId`; execution can still be queued, so its `runId` may be absent. Retry a failed submission request with the same request key and identical input to obtain the existing receipt. A different task needs a different key.

Keep the Agent alive after that response: closing it stops scheduling and interrupts active work. Close it when its owning session manager shuts down or deliberately releases the runtime. This differs from ordinary direct requests, whose caller builds and closes the Agent per request; see [Instance lifecycle](/v2/en/docs/building-blocks/agent#instance-lifecycle).

Further submissions queue in acceptance order while the session is busy. Interruption or pending human input parks the queue until the unfinished task continues. To observe a task from a CLI or worker:

```java
var outcome = session.await(task).block();
System.out.println(outcome.status());
session.transcript().messages().forEach(msg -> System.out.println(msg.getTextContent()));
```

`await` observes the submitted command's execution until completion, suspension, interruption or failure. It does not start, retry or cancel execution; a suspended task still needs an answer. `respond` and `resume` return new receipts to observe with `await`. Use `session.tasks()` to query task state. Disconnecting an observer does not cancel background work.

Choose one execution entry for a conversation: application-owned `call` / `streamEvents`, or `AgentSession` scheduling. Reading a submitted task's log observes it; calling `streamEvents` would start another execution. Obtaining `agent.session(ctx)` or reading history never starts inference, and the transcript includes earlier direct calls made with the same identity.

<span id="scenario-2-guide-execution-and-reconnect-the-frontend" />

## Add guidance or material while work is running

Suppose the assistant is comparing two plans and the user adds, "We only have two operators; prioritize staffing cost." This refines the current task instead of asking for another report. Send guidance while that task is running:

```java
session.steer(task, "Prioritize staffing cost; we only have two operators.");
session.inject("Additional material: plan A requires weekly maintenance; plan B is managed.");
```

`steer(task, ...)` binds the guidance to the expected task and rejects it if that task is no longer running. `steer(input)` instead targets whichever task is currently running. Guidance becomes available at the next reasoning step; it does not rewrite an already-sent model request or change a tool call's running arguments. Unconsumed guidance remains bound to the original turn across interruption.

`inject` persists reference material without starting execution. While idle, it waits for future work; if the running task has no further reasoning step, the material remains pending. Use `submit` when the user wants a separate comparison queued after the current one.

## Restore the UI after refresh or reconnection

Now imagine the assistant has finished looking up plan A, called a tool for plan B, and is streaming its comparison. Refreshing the page should restore the earlier messages, both tool calls, committed results and the current text prefix before new output appears. Reconnecting an event stream from a cursor alone cannot restore the content that preceded that cursor.

Build the frontend around a snapshot and a stream of subsequent committed events:

1. Read one committed prefix and build a snapshot containing complete messages, partial text, tool names and arguments, progress, results and pending interactions. Return a cursor covering exactly that prefix.
2. Render that snapshot, then observe events after its cursor. Update existing items by stable message/tool IDs, or refresh the snapshot when new commits arrive.
3. On a stream reconnect, continue after the last successfully applied cursor. If a page refresh lost the view, fetch a new snapshot before subscribing again.

The SDK provides the records from which your application builds that view:

```java
import io.agentscope.core.session.log.SessionEvent;

var log = session.log(); // or agent.sessionLog(ctx)
var committed = log.events(); // every committed event, in sequence order
long watermark = log.lastSeq().value(); // the last committed position, -1 when the log is empty
// Build the application's snapshot from committed, and return watermark with it.

// On a later read, skip what the snapshot already covered.
for (SessionEvent event : log.events()) {
    if (event.seq().value() <= watermark) continue;
    System.out.printf("%d %s%n", event.seq().value(), event.type());
    // Apply or publish this committed event before advancing the consumer cursor.
}
```

`SessionLog.events()` returns every committed event in sequence order, `size()` is the committed event count and the next free position, `lastSeq()` is the last committed position, and `eventAt(seq)` reads one position. A reader resuming from a saved position keeps the `Seq` it last applied and filters newer events out of `events()`. None of these reads executes models or tools.

`session.transcript()` returns the complete committed messages as an `AgentSession.Transcript`; it does not reconstruct unfinished text or tool argument fragments. Those come from the `AssistantStreamRecord` runs embedded in `assistant/message`, and tool progress from `tool/chunk`, projected together with the committed messages and results. Do not assemble a snapshot from separate reads at different positions and then attach the newest cursor: that can skip content.

The [chat example](/v2/en/blogs/best-practices/session-chat) implements this application layer in `ChatHistory` and `ChatItems`. Its snapshot includes `items`, `messages`, `pending` and a consistent `cursor`. Its SSE endpoint sends filtered `committed` notifications, and the browser throttles snapshot refreshes in response; it does not apply raw model chunks directly. These snapshots and HTTP/SSE endpoints belong to the example, not a unified SDK snapshot API.

Restoring a page only reads committed history. Do not resubmit the task, replay old input or call the Agent to reconstruct the UI. SDK storage operations block; WebFlux applications should schedule them on `Schedulers.boundedElastic()`.

<span id="scenario-3-answer-hitl-requests"></span>

## Answer HITL requests

If the cost comparison needs a missing quote or approval to purchase, the Agent can suspend and persist an interaction request. Display that request from `session.pending()` and submit the user's answer using its request ID:

```java
session.pending().forEach((requestId, request) ->
        System.out.println(requestId + " " + request.data().get("kind")));

session.respond("external-request-id", "The annual quote is USD 12,000.");
session.respond("confirmation-request-id", true);
```

These illustrate two request kinds: strings answer `external_execution` requests; booleans answer confirmation requests. Use the actual persisted IDs for your interaction. The framework resolves the original turn and tool identity, constructs the existing HITL input and continues that turn. Unknown, resolved or mismatched requests are rejected.

For a confirmation denial, use `SessionAnswer.reject(reason)`. `SessionAnswer.Output` accepts custom result blocks, and `SessionAnswer.Confirmation` supports permission rules or edited tool arguments. Parallel answers can be submitted with `respond(Map<String, SessionAnswer>)`; they must belong to one turn.

Direct calls can also use the [existing HITL APIs](/v2/en/docs/building-blocks/agent#human-in-the-loop); human confirmation alone does not require session scheduling. Reply to inline Service interactions through their owning service's actions endpoint.

<span id="scenario-4-continue-after-interruption"></span>

## Continue after interruption

To pause the comparison, request interruption and wait for the running execution to stop. `interrupt()` is a cooperative request, not a completion acknowledgment. A CLI or worker can wait and then continue the original task:

```java
session.interrupt();
var stopped = session.await(task).block();
if ("interrupted".equals(stopped.status())) {
    var continued = session.resume(stopped.turnId());
}
```

In a web application, return after requesting interruption and let the UI observe task state before offering continuation. If the page knows the active run ID, use `session.interrupt(runId)` to avoid stopping a newer execution from a stale page. `resume` restores the checkpoint and unapplied original input, preserves the `turnId` and starts a new run. Applications do not construct empty input, replay messages or set turn metadata. Only the latest unfinished task can continue; completed or cancelled tasks cannot resume, and pending interactions require `respond` first.

After an application restart, build the same Agent configuration and obtain the session with the same `userId`, stable `agentId`, `sessionId` and storage namespace. Read its history immediately, then explicitly start dispatching accepted work:

```java
session.start(); // Dispatch queued work; interrupted tasks still need resume(turnId).
```

`submit`, `respond` and `resume` start dispatch automatically. Read-only history pages do not need `start()`. Durable recovery needs the same tools, credentials and working files as the original task.

A checkpoint restores Agent working state. It does not recreate an operating-system thread, resume a network request or restore a tool's internal progress. After a forced process exit, the previous writer lease must release or expire before another runtime can write. Inspect the saved state before continuing if a tool may have changed external data.

### Reconciling uncertain tool outcomes

Suppose `create_order` created an order, but the process stopped before committing the tool result. The log shows the call was requested, but not whether it succeeded; retrying could create a duplicate order.

The SDK has no API that lists uncertain tool calls. Read the committed log and decide which calls still have no result, then read the saved state:

```java
import io.agentscope.core.session.log.StateSnapshot;

StateSnapshot snapshot = agent.inspectSession(ctx);
System.out.println(snapshot.asOfSeq()); // the committed position this state was read from
// Decide from the log which call IDs still have no result, and check the order system.
```

Record what you actually verified, naming those call IDs and the reason:

```java
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.Map;

var result = ToolResultBlock.builder()
        .id("call-id-from-the-log").name("create_order")
        .output(TextBlock.builder().text("Order ORD-42 confirmed created").build())
        .state(ToolResultState.SUCCESS).build();
agent.reconcileToolOutcomes(ctx, Map.of(result.getId(), result), "Verified order-system records");
var continued = session.resume(task.turnId());
```

`reconcileToolOutcomes` records a `tool/reconciled` payload holding the reason and the reconciled call IDs; it does not execute those tools again, and it does not store the result content or save a checkpoint. Continuation is explicit. If the outcome is still unknown, investigate it rather than inventing an empty successful result. Recovery is not arbitrary history rollback.

## Sessions, turns and runs

A session is the continuing conversation. A turn is one logical request, and a run is one execution of that request. One run can contain many model calls, tool calls and messages. In conversation `S1`, the workflow might be:

| Action | turnId | runId |
| --- | --- | --- |
| Submit the cost comparison | New `T1` | New `R1` once dispatched |
| Refresh the page or steer toward staffing cost | `T1` | Still `R1` |
| Interrupt execution | `T1` | `R1` ends |
| Continue the comparison | `T1` | New `R2` |
| Suspend for approval, then answer | `T1` | `R2` ends; new `R3` |
| Submit a separate report | New `T2` | New `R4` once dispatched |

The `sessionId` remains `S1`. Submission idempotency keys, interaction request IDs and event IDs serve different purposes: retrying a submission, answering a pending request and deduplicating a record. They are not interchangeable with turn IDs. This example uses suspended HITL; inline confirmation can continue within the same live run. Choose the operation that matches the user's intent and let the framework assign identities. Model calls, tool calls, messages and interaction requests also have their own IDs for correlating fragments, results and answers.

<span id="history-events-and-checkpoints" />

<span id="scenario-5-inspect-execution-export-records-or-change-storage" />

<span id="event-envelope-and-reads" />

## Inspect execution and read events

Use `session.log()` or `agent.sessionLog(ctx)` to inspect a request across its runs. Group by `turnId`, then examine the model, tool and state events within each `executionRunId`:

```java
import io.agentscope.core.session.log.SessionEvent;

var log = session.log(); // or agent.sessionLog(ctx)
for (SessionEvent event : log.events()) {
    System.out.printf("%d %s turn=%s run=%s%n",
            event.seq().value(), event.type(), event.turnId(), event.executionRunId());
}
```

`events()` reads the committed log without executing models or tools; keep the last `seq` you processed to continue from that position on the next read. History records what happened, while checkpoints preserve working state for later execution; compacting working context does not erase earlier history.

`AgentEvent` carries live notifications; `SessionEvent` carries durable facts. A live notification is not a commit acknowledgment. Native `seq` starts at 0 and is contiguous within a session; use it for ordering and `eventId` for deduplication. Service public SSE has its own cursor, which must not be interchanged with native sequence numbers.

### Event types

The catalog below helps locate the facts needed for a timeline, diagnosis or application projection:

| Types | What they describe |
| --- | --- |
| `system/message`, `developer/message`, `user/message`, `assistant/message`, `tool/result` | Message-producing types. Only these derive model-visible history, and only they may carry a surface placement |
| `turn/start`, `turn/resumed`, `turn/end` | Turn boundaries. A resumed continuation writes `turn/resumed` instead of `turn/start`; `turn/end` carries the `TurnEndReason` that closed the turn |
| `step/start`, `step/end` | Reasoning steps: one model call plus the tool executions it requested |
| `tool/call`, `assistant/attempt` | The model's request for one tool invocation, paired by `callId` with `tool/result`, and a model attempt that settled without producing a message |
| `request/header`, `request/context` | The request envelope (reason, tool names, config, adapter defaults) and route metadata, logged only when the route or its capacity changes |
| `session/end-seed` | The boundary between inherited history and later lifecycle-owned work |
| `input/submitted`, `input/steered`, `input/injected`, `input/applied`, `input/rejected` | Accepted input: a submitted task, guidance for running work, material kept for later, the marker that an execution consumed one, and a refused request with its reason. Other accepted kinds such as `resume` and `respond` use the same `SessionInputQueue.Input` record |
| `input/received` | Deliberately retained opaque payload name, written by `DurableSession.start` at the start of every turn, with the payload field `messages`. It is not part of the accepted-input family above |
| `state/checkpoint` | Saved Agent working state. A deliberately retained opaque payload name |
| `run/end`, `run/stop_requested` | Execution outcome and interruption request. Deliberately retained opaque payload names |
| `state/snapshot` | Read-only alias: accepted wherever `state/checkpoint` is read, but nothing writes it |
| `run/start` | Retained name with no writer. A turn opens with `turn/start` or `turn/resumed`, so reading `run/start` yields nothing |
| `interaction/requested`, `interaction/resolved` | Pending interactions and applied answers |
| `tool/decision`, `tool/dispatch`, `tool/chunk`, `action/start`, `action/end`, `model/dispatch`, `model/retry` | Tool decisions, dispatch, progress, action boundaries, model dispatch and retries |
| `context/build`, `compaction/start`, `compaction/end`, `verification/result`, `tool/reconciled` | Working-context changes, verification results and reconciled tool outcomes |
| `subagent/spawned`, `subagent/completed`, `subagent/linked` | Child links; full child execution lives in its own log |
| `session/title`, `presentation/hint`, `extension/<name>` | Recorded title, presentation hints and application-defined facts |

Events cover adapter-visible requests and output, not provider internals or backups of referenced files. Keep raw requests, tool results and checkpoints in an authorized diagnostic interface; filter what you expose to a browser.

<Accordion title="SessionEvent envelope">

Events are immutable, with payloads frozen as JSON when accepted.

| Field | Meaning |
| --- | --- |
| `seq` | Position in the log; contiguous from 0, and equal to the log length at append |
| `time` | Unix epoch milliseconds; use `seq` for ordering |
| `type` | Event type; it must equal the payload's own type |
| `data` | The typed payload, a `SessionEventData` |
| `surfaceOp` | Placement on the model-visible surface; present exactly for message-producing events, null for log-only ones |
| `sourceEventSeqs` | Earlier positions this event derives from or shadowed; empty when it cites none |
| `ignorable` | Whether a reader that does not recognize `type` may skip the record; true exactly for extension types |
| `eventId` | Caller identity of the record, used for deduplication |
| `executionRunId`, `turnId` | The execution and the turn that own the record; null when it has none. Administrative events may have no turn |

</Accordion>

## Storage and backend configuration

Harness uses `WorkspaceSessionLogStore` by default, so storage follows the Workspace Filesystem. With `LocalFilesystem`, records live in `.agentscope-runtime/` under the root resolved for the current identity. With `RemoteFilesystem`, they occupy the `__agentscope_session_log_v1__` partition in the corresponding `BaseStore` namespace.

On Linux and macOS, local storage commits forced temporary files through atomic replacement and syncs their parent directories. On Windows, the Java filesystem provider cannot open directories for this sync, so local Session storage uses SQLite transactions in `.agentscope-runtime/journal.sqlite3`, with a rollback journal and `synchronous=EXTRA`. Writers still perform an atomic version comparison, and storage errors propagate to the caller. Read records through the SDK; when backing up a Windows workspace, pause all writers before copying the database, or use a consistent SQLite backup. The POSIX file layout and the Windows database hold the same logical objects but are different physical formats, so moving history between them requires a log migration or a shared backend.

`SessionKey(userId, agentId, sessionId)` identifies a conversation inside that backend. Preserve both the key and namespace across restarts. Within one Agent, the same identity reuses an `AgentSession` with a copy of its initial `RuntimeContext`; establish stable session-level configuration when first obtaining the session.

<span id="shared-storage" />

Working files and session records can use separate backends. For example, keep files local while storing logs and accepted commands in an application-configured `sharedStore` with atomic versioned writes:

```java
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.util.List;

var logStore = new WorkspaceSessionLogStore(
        new RemoteFilesystem(sharedStore, List.of("my-app", "session-history")));
HarnessAgent.Builder sharedLogBuilder = HarnessAgent.builder()
        .name("Cost Assistant").agentId("cost-assistant")
        .model(model).workspace(Path.of("/data/chat-workspace"))
        .sessionLogStore(logStore);
```

Replicas must share this backend, namespace and session identity. Shared storage does not automatically queue concurrent direct calls to the same conversation; the application must coordinate those. AgentSession receives and queues input as `input/*` records on the session log through `SessionInputQueue`, with execution owned by the started session scheduler. In-memory storage is process-local and cannot survive restart. Discovery through `SessionLogStore.list(ctx)` follows the current namespace: a session-isolated namespace does not enumerate other sessions. See [Workspace](/v2/en/docs/harness/workspace) for filesystem and namespace configuration.

<span id="custom-backends-and-backups" />

For a custom backend, implement `SessionLogStore`, or implement `AtomicSessionStorage` and serve the kernel through `AtomicSessionStore`. Preserve compare-and-set writes, writer leases, stale-writer fencing and ordered idempotent commits. A custom Filesystem must supply `sessionStorage` or use a separate log store; ordinary file reads and writes cannot provide atomic journal commits. Accepted input needs no second backend: `SessionInputQueue` records it as `input/*` events on the same log, so a store only has to serve that one log.

<Accordion title="Physical layout, accepted input and backups">

The logical object layout below is useful for operating a backend. A session's kernel id is `k_` followed by the Base64URL of its logical identity path `agents/s_<agent>/sessions/s_<user>/s_<session>`. POSIX local objects include a version prefix; Windows stores their logical paths, versions and payloads as SQLite rows. Read them through the SDK rather than treating them as application JSONL files.

```text
sessions/
  k_<base64url identity path>/
    header.json
    events/<seq>.json
```

`header.json` holds the format version and the committed event count; each `events/<seq>.json` holds one committed event in the same record form the export uses. Input is not a separate journal: `submit`, `steer` and `inject` write `input/submitted`, `input/steered` and `input/injected` into this same log, and `SessionInputQueue.accepted()` returns every accepted record in order. Whether an accepted record is applied is read from its position, not from a second marker written by another component: a record that starts a turn is applied once a `turn/start` or `turn/resumed` follows it, while guidance and material stay in `SessionInputQueue.pending()` until an execution consumes them with `input/applied`. `SessionInputQueue.reject(requestId, reason)` records a refused request id and its reason.

Back up the session header and every event object it covers, using a consistent backend snapshot or paused writers. Working files, artifacts and external dependencies need their own backups. Retention and archiving belong to the deployment.

</Accordion>

<span id="exporting-records" />

## Export records to another system

To feed an audit database or application event view, render committed records with `SessionExport`. The export is backend-neutral: a header line names the format version and the session, and every committed event follows on its own line in the same record form the store uses, so an exported session and a stored one describe the same facts. Export only reads; it never re-executes the Agent.

```java
import io.agentscope.core.session.log.LocalSessionStore;
import io.agentscope.core.session.log.SessionExport;
import io.agentscope.core.session.log.SessionQuery;
import io.agentscope.core.session.log.SessionSeq;
import java.nio.file.Path;

var query = new SessionQuery(new LocalSessionStore(Path.of("/data/session-logs")));
String jsonl = SessionExport.of(query, "conversation-001");

// A bounded export through one committed position, inclusive.
SessionExport.Bounded bounded =
        SessionExport.upTo(query, "conversation-001", SessionSeq.Seq.of(41));
String prefix = bounded.jsonl();
SessionSeq.Seq through = bounded.throughSeq();
```

`of` returns the whole session and throws when the session is not stored; `upTo` returns the lines through one position together with that position, and throws when no event occupies it. Shadowed events are exported deliberately: the log keeps them, and dropping them would lose the conversation a person already saw. The rendering is deterministic, so the same committed prefix always produces the same lines; re-export after a failure and deduplicate at the destination by the `eventId` each line carries. Hosted Service provides its own public event export and SSE endpoints.

<span id="custom-events" />

## Append application events

A business tool or middleware may want to record a review note alongside Agent events. Obtain the invocation's kernel writer from its `RuntimeContext` and append the fact:

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

Await the returned `Mono` as part of the tool or middleware pipeline, or use `append(type, payload)` when the caller is already synchronous and can accept the write happening immediately. Payload fields are stored as opaque JSON, so the application owns their meaning. A type outside the kernel catalog is stored as an extension record that a reader may skip, and the kernel has no codec registry to register it with: an application type cannot make recovery fail, and the application also cannot make recovery depend on one. Do not retain the writer for writes after its invocation ends.

<span id="migration-and-forking" />

## Fork a conversation

To try another route from the current conversation, build the child session's starting events with the kernel's `SessionFork`. A fork copies an inclusive prefix, marks the inherited cut, and closes only the tail the prefix left open; the source log is never modified.

```java
import io.agentscope.core.session.log.SessionFork;
import io.agentscope.core.session.log.SessionSeq;

// sourceEvents are the source session's committed events; the boundary is the last copied event.
SessionFork.Seed seed =
        SessionFork.buildForkSeed(sourceEvents, SessionSeq.Seq.of(41), "conversation-001");

seed.events();              // copied prefix, then the inherited marker, then any synthetic closers
seed.inheritedEventCount(); // the copied prefix length; positions from there belong to the child
seed.parentSession();       // the source session id
```

`buildForkSeed(source)` copies the whole log and `buildForkSeed(source, boundary)` copies through an explicit position, both inclusive. `SessionFork.inheritedEventCountOf(events)` folds a restored seed back to its inherited cut, and `SessionFork.isChildOwned(seq, inheritedEventCount, size)` tells whether a position was produced by the child rather than inherited. The kernel has no API that imports a complete `AgentState` as a new session's starting state.

Stop source execution before building the seed. The child starts from the copied prefix and produces everything after it itself; a fork does not carry over the source session's sandbox or external resources.

## Complete example: recoverable Web Chat

The [agentscope-chat walkthrough](/v2/en/blogs/best-practices/session-chat) connects these operations to HTTP handlers and a browser: submit a background task, restore partial messages and tools, reconnect SSE, answer pending input and continue after interruption or restart. It runs offline by default and can also use a real model. Follow its source to adapt the application projection and runtime ownership to your own chat or task UI.
