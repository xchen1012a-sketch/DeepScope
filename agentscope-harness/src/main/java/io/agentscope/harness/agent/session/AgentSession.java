/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.session;

import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.log.DurableSession;
import io.agentscope.core.session.log.MessageDerivation;
import io.agentscope.core.session.log.SessionEvent;
import io.agentscope.core.session.log.SessionEventData;
import io.agentscope.core.session.log.SessionExecutionData;
import io.agentscope.core.session.log.SessionInputQueue;
import io.agentscope.core.session.log.SessionLog;
import io.agentscope.core.session.log.SessionLogException;
import io.agentscope.core.session.log.SessionLogStore;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A session's durable task operations and read views. Commands are recorded in the kernel log.
 * This runtime owns execution subscriptions. Obtaining a handle never starts inference.
 */
public final class AgentSession implements AutoCloseable {
    /** Command receipt. A queued task may not yet have a runId. */
    public record Task(String commandId, String turnId, String runId, String status, String error) {
        public boolean settled() {
            return !status.equals("queued")
                    && !status.equals("running")
                    && !status.equals("starting");
        }
    }

    public record InputReceipt(String inputId, String turnId) {}

    /** Messages derived from the kernel log. */
    public record Transcript(List<Msg> messages) {}

    private final HarnessAgent agent;
    private final RuntimeContext context;
    private final SessionKey key;
    private final SessionLogStore store;
    private Disposable dispatcher;
    private AgentRun<AgentEvent> active;
    private String activeCommand;
    private volatile String error;
    private volatile boolean closed;

    public AgentSession(HarnessAgent agent, RuntimeContext context) {
        this.agent = Objects.requireNonNull(agent);
        this.context = RuntimeContext.builder(context).build();
        this.context.put(DurableSession.TURN_ID_KEY, null);
        this.key = agent.sessionKey(this.context);
        this.store = agent.sessionStore();
    }

    public String sessionId() {
        return key.sessionId();
    }

    public SessionLog log() {
        return kernel();
    }

    public Transcript transcript() {
        return new Transcript(MessageDerivation.history(kernel()));
    }

    public Map<String, String> pending() {
        var pending = new LinkedHashMap<String, String>();
        for (SessionEvent event : kernel().events()) {
            if (!(event.data() instanceof SessionEventData.PayloadData payload)) {
                continue;
            }
            if ("interaction/requested".equals(payload.type())) {
                Object id = payload.fields().get("requestId");
                Object kind = payload.fields().get("kind");
                if (id != null) {
                    pending.put(String.valueOf(id), kind == null ? "" : String.valueOf(kind));
                }
            }
            if ("interaction/resolved".equals(payload.type())) {
                Object id = payload.fields().get("requestId");
                if (id != null) {
                    pending.remove(String.valueOf(id));
                }
            }
        }
        return Map.copyOf(pending);
    }

    public String executionError() {
        return error;
    }

    /** Start dispatching durable queued tasks, for example after application restart. */
    public synchronized void start() {
        ensureOpen();
        if (dispatcher == null) {
            dispatcher =
                    Schedulers.boundedElastic()
                            .schedulePeriodically(this::dispatch, 0, 100, TimeUnit.MILLISECONDS);
        }
    }

    public Task submit(String input) {
        return submit(UUID.randomUUID().toString(), input);
    }

    public Task submit(Msg input) {
        return submit(List.of(input));
    }

    public Task submit(List<Msg> input) {
        return submit(UUID.randomUUID().toString(), input);
    }

    public Task submit(String idempotencyKey, String input) {
        return submit(idempotencyKey, List.of(new UserMessage(Objects.requireNonNull(input))));
    }

    public Task submit(String idempotencyKey, List<Msg> input) {
        ensureOpen();
        String id = requireId(idempotencyKey);
        if (input.isEmpty()) {
            throw new IllegalArgumentException("A new task requires input");
        }
        String turn =
                UUID.nameUUIDFromBytes(("turn:" + id).getBytes(StandardCharsets.UTF_8)).toString();
        List<Msg> frozen = freeze(id, input);
        // A retry with the same id and the same content is the same submission: it must not record
        // the input twice nor start a second execution, which would collide with the writer that is
        // still running the first one. The same id with different content is a caller mistake.
        for (SessionInputQueue.Input already : new SessionInputQueue(kernel()).accepted()) {
            if (!already.requestId().equals(id)) {
                continue;
            }
            if (!sameContent(already.messages(), frozen)) {
                throw new IllegalArgumentException(
                        "Request id " + id + " was already used for different input");
            }
            return receipt(id, already.turnId());
        }
        accept(new SessionInputQueue.Input(id, SessionInputQueue.SUBMIT, turn, frozen, true));
        start();
        return receipt(id, turn);
    }

    public Task resume(String turnId) {
        ensureOpen();
        // Resume names the turn it continues. Later tasks may be queued behind it, so the target is
        // looked up by turn rather than assumed to be the newest submission.
        Task target = null;
        for (Task candidate : tasks()) {
            if (candidate.turnId().equals(turnId)) {
                target = candidate;
            }
        }
        if (target == null || ended(target.status())) {
            throw new IllegalStateException("Only an unfinished task can continue");
        }
        if (!pending().isEmpty()) {
            throw new IllegalStateException("Answer pending interactions first");
        }
        requireIdle();
        String id = UUID.randomUUID().toString();
        accept(new SessionInputQueue.Input(id, "resume", turnId, List.of(), false));
        launch(id, turnId, List.of());
        return receipt(id, turnId);
    }

    public Task respond(String requestId, String output) {
        return respond(requestId, SessionAnswer.text(output));
    }

    public Task respond(String requestId, boolean approved) {
        return respond(requestId, new SessionAnswer.Approval(approved, null));
    }

    public Task respond(String requestId, SessionAnswer answer) {
        return respond(Map.of(requestId, answer));
    }

    public Task respond(Map<String, SessionAnswer> answers) {
        ensureOpen();
        requireIdle();
        if (answers.isEmpty()) {
            throw new IllegalArgumentException("An answer is required");
        }
        var requests = interactionRequests();
        String turn = null;
        var results = new ArrayList<ToolResultBlock>();
        var confirmations = new ArrayList<ConfirmResult>();
        var ids = answers.keySet().stream().sorted().toList();
        for (String id : ids) {
            SessionEventData.PayloadData request = requests.get(id);
            if (request == null) {
                throw new IllegalArgumentException("No pending interaction: " + id);
            }
            String requestTurn = String.valueOf(request.fields().get("turnId"));
            if (request.fields().get("turnId") == null) {
                requestTurn = turnOfRequest(id);
            }
            if (turn != null && requestTurn != null && !turn.equals(requestTurn)) {
                throw new IllegalArgumentException("Answers belong to different tasks");
            }
            if (requestTurn != null) {
                turn = requestTurn;
            }
            if (request.fields().containsKey("protocol")) {
                throw new IllegalArgumentException(
                        "Reply through the interaction's owning service");
            }
            ToolUseBlock call = toolCall(request.fields().get("call"));
            SessionAnswer answer = answers.get(id);
            if ("external_execution".equals(request.fields().get("kind"))
                    && answer instanceof SessionAnswer.Output output) {
                results.add(ToolResultBlock.of(call.getId(), call.getName(), output.content()));
            } else if ("confirmation".equals(request.fields().get("kind"))
                    && answer instanceof SessionAnswer.Approval approval) {
                confirmations.add(
                        new ConfirmResult(approval.approved(), call, null, approval.reason()));
            } else if ("confirmation".equals(request.fields().get("kind"))
                    && answer instanceof SessionAnswer.Confirmation confirmation) {
                if (!call.getId().equals(confirmation.result().getToolCall().getId())) {
                    throw new IllegalArgumentException("Confirmation tool ID mismatch");
                }
                confirmations.add(confirmation.result());
            } else {
                throw new IllegalArgumentException("Answer type does not match interaction: " + id);
            }
        }
        if (turn == null) {
            throw new IllegalArgumentException("Pending interaction has no turn");
        }
        var messages = new ArrayList<Msg>();
        if (!results.isEmpty()) {
            messages.add(ToolResultMessage.builder().results(results).build());
        }
        if (!confirmations.isEmpty()) {
            messages.add(
                    UserMessage.builder()
                            .textContent("")
                            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmations))
                            .build());
        }
        String id =
                "answer-"
                        + UUID.nameUUIDFromBytes(
                                String.join("\n", ids).getBytes(StandardCharsets.UTF_8));
        List<Msg> frozen = freeze(id, messages);
        accept(new SessionInputQueue.Input(id, "respond", turn, frozen, false));
        launch(id, turn, frozen);
        return receipt(id, turn);
    }

    public InputReceipt steer(String input) {
        return steer(List.of(new UserMessage(input)));
    }

    public InputReceipt steer(List<Msg> input) {
        return steer(null, input);
    }

    public InputReceipt steer(Task task, String input) {
        return steer(task.turnId(), List.of(new UserMessage(input)));
    }

    private InputReceipt steer(String expectedTurn, List<Msg> input) {
        ensureOpen();
        if (store.owner(key, context) == null) {
            throw new IllegalStateException(
                    "No running task accepts steering; submit or resume explicitly");
        }
        Task running = runningTask();
        if (running == null) {
            throw new IllegalStateException(
                    "No running task accepts steering; submit or resume explicitly");
        }
        if (expectedTurn != null && !expectedTurn.equals(running.turnId())) {
            throw new IllegalStateException("Task is no longer running");
        }
        String id = UUID.randomUUID().toString();
        accept(
                new SessionInputQueue.Input(
                        id, SessionInputQueue.STEER, running.turnId(), freeze(id, input), false));
        return new InputReceipt(id, running.turnId());
    }

    public InputReceipt inject(String context) {
        return inject(List.of(new UserMessage(context)));
    }

    public InputReceipt inject(List<Msg> input) {
        ensureOpen();
        String id = UUID.randomUUID().toString();
        accept(
                new SessionInputQueue.Input(
                        id, SessionInputQueue.INJECT, null, freeze(id, input), false));
        return new InputReceipt(id, null);
    }

    public boolean interrupt() {
        return interrupt(null);
    }

    public boolean interrupt(String expectedRunId) {
        ensureOpen();
        AgentRun<AgentEvent> run;
        synchronized (this) {
            run = active;
        }
        if (run == null) {
            return false;
        }
        if (expectedRunId != null && !expectedRunId.equals(run.runId())) {
            throw new IllegalStateException("Execution is no longer active");
        }
        run.interrupt();
        return true;
    }

    public List<Task> tasks() {
        var tasks = new LinkedHashMap<String, Task>();
        for (SessionInputQueue.Input input : new SessionInputQueue(kernel()).accepted()) {
            if (SessionInputQueue.SUBMIT.equals(input.kind())) {
                tasks.put(input.turnId(), receipt(input.requestId(), input.turnId()));
            }
        }
        return List.copyOf(tasks.values());
    }

    public Mono<Task> await(Task task) {
        return Flux.interval(Duration.ZERO, Duration.ofMillis(100))
                .onBackpressureLatest()
                .concatMap(
                        tick ->
                                Mono.fromCallable(() -> task(task.commandId()))
                                        .subscribeOn(Schedulers.boundedElastic()))
                .filter(
                        value ->
                                value.settled()
                                        && !Objects.equals(
                                                value.runId(), store.owner(key, context)))
                .next();
    }

    public Task task(String commandId) {
        for (SessionInputQueue.Input input : new SessionInputQueue(kernel()).accepted()) {
            if (input.requestId().equals(commandId)) {
                return receipt(input.requestId(), input.turnId());
            }
        }
        Task latest = latestTask();
        if (latest != null && commandId.equals(latest.commandId())) {
            return latest;
        }
        throw new IllegalArgumentException("Unknown task submission");
    }

    private void accept(SessionInputQueue.Input input) {
        store.appendExternal(key, input, input.turnId(), context);
    }

    private SessionLog kernel() {
        return store.readKernel(key, context);
    }

    private Task latestTask() {
        List<Task> tasks = tasks();
        return tasks.isEmpty() ? null : tasks.get(tasks.size() - 1);
    }

    /** The task whose execution currently holds this session's write ownership, if any. */
    private Task runningTask() {
        for (Task task : tasks()) {
            if ("running".equals(task.status())) {
                return task;
            }
        }
        return null;
    }

    private static boolean ended(String status) {
        return "completed".equals(status) || "cancelled".equals(status);
    }

    /**
     * True when the last finished turn stopped without completing.
     *
     * <p>Queued work waits for an explicit {@link #resume(String)} in that case: an interrupted or
     * suspended turn was stopped deliberately, so following on automatically would restart work the
     * caller paused, and resuming would then collide with it.
     */
    private boolean awaitingExplicitContinuation() {
        SessionExecutionData.TurnEndReason reason = null;
        for (SessionEvent event : kernel().events()) {
            if (event.data() instanceof SessionExecutionData.TurnEnd end) {
                reason = end.reason();
            }
        }
        return reason == SessionExecutionData.TurnEndReason.INTERRUPTED
                || reason == SessionExecutionData.TurnEndReason.SUSPENDED;
    }

    private String turnOfRequest(String requestId) {
        for (SessionEvent event : kernel().events()) {
            if (event.data() instanceof SessionEventData.PayloadData payload
                    && "interaction/requested".equals(payload.type())
                    && requestId.equals(String.valueOf(payload.fields().get("requestId")))
                    && event.turnId() != null) {
                return event.turnId();
            }
        }
        Task latest = latestTask();
        return latest == null ? null : latest.turnId();
    }

    private Map<String, SessionEventData.PayloadData> interactionRequests() {
        var requests = new LinkedHashMap<String, SessionEventData.PayloadData>();
        for (SessionEvent event : kernel().events()) {
            if (!(event.data() instanceof SessionEventData.PayloadData payload)) {
                continue;
            }
            if ("interaction/requested".equals(payload.type())) {
                Object id = payload.fields().get("requestId");
                if (id != null) {
                    requests.put(String.valueOf(id), payload);
                }
            }
            if ("interaction/resolved".equals(payload.type())) {
                Object id = payload.fields().get("requestId");
                if (id != null) {
                    requests.remove(String.valueOf(id));
                }
            }
        }
        return requests;
    }

    private Task receipt(String commandId, String turnId) {
        String run = null;
        String status = "queued";
        for (SessionEvent event : kernel().events()) {
            if (turnId != null && turnId.equals(event.turnId())) {
                if (event.data() instanceof SessionExecutionData.TurnStart) {
                    run = event.executionRunId();
                    status = "running";
                }
                if (event.data() instanceof SessionExecutionData.TurnEnd end) {
                    status =
                            switch (end.reason()) {
                                case COMPLETED -> "completed";
                                case SUSPENDED -> "suspended";
                                case INTERRUPTED -> "interrupted";
                                case ABORTED -> "cancelled";
                                case ERROR -> "failed";
                                default -> end.reason().name().toLowerCase();
                            };
                }
            }
        }
        synchronized (this) {
            if (commandId.equals(activeCommand) && active != null) {
                run = active.runId();
                if ("queued".equals(status)) {
                    status = "starting";
                }
            }
        }
        if ("running".equals(status) && !Objects.equals(run, store.owner(key, context))) {
            status = "interrupted";
        }
        return new Task(commandId, turnId, run, status, null);
    }

    private synchronized void dispatch() {
        if (closed || active != null) {
            return;
        }
        try {
            if (store.owner(key, context) != null) {
                return;
            }
            if (awaitingExplicitContinuation()) {
                return;
            }
            for (SessionInputQueue.Input input : new SessionInputQueue(kernel()).pending()) {
                if (!SessionInputQueue.SUBMIT.equals(input.kind())) {
                    continue;
                }
                launch(input.requestId(), input.turnId(), input.messages());
                return;
            }
            if (dispatcher != null) {
                dispatcher.dispose();
                dispatcher = null;
            }
        } catch (RuntimeException failure) {
            error = failure.getMessage();
        }
    }

    private void launch(String commandId, String turnId, List<Msg> messages) {
        error = null;
        RuntimeContext rc =
                RuntimeContext.builder(context)
                        .runId(null)
                        .put(DurableSession.TURN_ID_KEY, turnId)
                        .put(DurableSession.INPUTS_KEY, messages)
                        .build();
        AgentRun<AgentEvent> run = agent.prepareRun(messages, rc);
        synchronized (this) {
            active = run;
            activeCommand = commandId;
        }
        run.stream()
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(
                        signal -> {
                            synchronized (this) {
                                if (active == run) {
                                    active = null;
                                    activeCommand = null;
                                }
                            }
                        })
                .subscribe(
                        event -> {},
                        failure -> {
                            error = failure.getMessage();
                            if (failure instanceof SessionLogException && error != null) {
                                error = failure.getMessage();
                            }
                        });
    }

    private static ToolUseBlock toolCall(Object raw) {
        if (raw instanceof Map<?, ?> fields && !fields.containsKey("type")) {
            var copy = new LinkedHashMap<String, Object>();
            fields.forEach((key, value) -> copy.put(String.valueOf(key), value));
            copy.put("type", "tool_use");
            raw = copy;
        }
        return JsonUtils.getJsonCodec().convertValue(raw, ToolUseBlock.class);
    }

    private void requireIdle() {
        if (store.owner(key, context) != null) {
            throw new IllegalStateException("Wait for the current execution to stop");
        }
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 200) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        return id;
    }

    /**
     * Whether two submissions carry the same content.
     *
     * <p>Compares rendered text, matching {@code SessionInputQueue}, because messages read back from
     * the log are rebuilt from JSON and two records of the same content need not be {@code equals}.
     */
    private static boolean sameContent(List<Msg> left, List<Msg> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!Objects.equals(
                    left.get(index).getTextContent(), right.get(index).getTextContent())) {
                return false;
            }
        }
        return true;
    }

    private static List<Msg> freeze(String commandId, List<Msg> input) {
        var result = new ArrayList<Msg>();
        for (int i = 0; i < input.size(); i++) {
            var json = new LinkedHashMap<String, Object>();
            Map<?, ?> encoded = JsonUtils.getJsonCodec().convertValue(input.get(i), Map.class);
            encoded.forEach((key, value) -> json.put((String) key, value));
            json.put("id", "input-" + commandId + "-" + i);
            result.add(JsonUtils.getJsonCodec().convertValue(json, Msg.class));
        }
        return List.copyOf(result);
    }

    private synchronized void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Session runtime is closed");
        }
    }

    @Override
    public void close() {
        AgentRun<AgentEvent> run;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            if (dispatcher != null) {
                dispatcher.dispose();
            }
            run = active;
        }
        if (run != null) {
            run.interrupt();
            try {
                run.termination().block(Duration.ofSeconds(10));
            } catch (RuntimeException timeout) {
                run.cancel();
            }
        }
    }
}
