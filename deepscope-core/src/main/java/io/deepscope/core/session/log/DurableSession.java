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
package io.deepscope.core.session.log;

import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.event.AgentEvent;
import io.deepscope.core.event.CustomEvent;
import io.deepscope.core.event.ExternalExecutionResultEvent;
import io.deepscope.core.event.RequireExternalExecutionEvent;
import io.deepscope.core.event.RequireUserConfirmEvent;
import io.deepscope.core.event.UserConfirmResultEvent;
import io.deepscope.core.message.GenerateReason;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.ToolResultBlock;
import io.deepscope.core.middleware.ModelCallInput;
import io.deepscope.core.state.AgentState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

/**
 * One execution's writer for a kernel session log.
 *
 * <p>Events appended here are kernel records. Model history is derived from those records. Callers
 * do not build a second envelope.
 */
public final class DurableSession implements AutoCloseable {

    /** RuntimeContext key holding the open writer for the current call. */
    public static final String CONTEXT_KEY = "deepscope.session.durable";

    /** RuntimeContext key holding the logical turn id for the current call. */
    public static final String TURN_ID_KEY = "deepscope.session.turnId";

    /** RuntimeContext key holding the parent execution a subagent should cite. */
    public static final String PARENT_KEY = "deepscope.session.parent";

    /** RuntimeContext key holding the messages this call should admit. */
    public static final String INPUTS_KEY = "deepscope.session.inputs";

    private final NativeSessionLogStore store;
    private final String sessionId;
    private final KernelJournal journal;
    private final String runId;
    private final String turnId;
    private final Object lock = new Object();
    private final boolean resumedTurn;
    private boolean closed;
    private long turnNumber = 1;
    private long stepNumber;
    private boolean stepOpen;
    private String resultStatus;
    private String resultReason;
    private final Set<String> recordedMessages = new HashSet<>();
    private final Map<String, String> actionIds = new HashMap<>();

    DurableSession(
            NativeSessionLogStore store,
            String sessionId,
            KernelJournal journal,
            String runId,
            String turnId) {
        this.store = Objects.requireNonNull(store, "store");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.turnId = turnId;
        boolean seenTurn = false;
        long turns = 0;
        for (SessionEvent event : journal.committed()) {
            if (event.data() instanceof SessionExecutionData.TurnStart) {
                turns++;
            }
            if (runId.equals(event.executionRunId())) {
                throw new SessionLogException(
                        "Run ID already used; prepare a new execution to resume");
            }
            if (turnId != null && turnId.equals(event.turnId())) {
                seenTurn = true;
                if (event.data() instanceof SessionExecutionData.TurnEnd end
                        && end.reason() == SessionExecutionData.TurnEndReason.COMPLETED) {
                    throw new SessionLogException("Turn already completed; use a new turn ID");
                }
            }
        }
        this.resumedTurn = seenTurn;
        this.turnNumber = turns + 1;
        SessionLog loaded = new SessionLog();
        for (SessionEvent event : journal.committed()) {
            loaded.append(event);
        }
        for (Msg message : MessageDerivation.history(loaded)) {
            if (message.getId() != null) {
                recordedMessages.add(message.getId());
            }
        }
    }

    /** The writer bound to this call, or null when the call has no session log. */
    public static DurableSession from(RuntimeContext rc) {
        return rc != null && rc.get(CONTEXT_KEY) instanceof DurableSession session ? session : null;
    }

    public String sessionId() {
        return sessionId;
    }

    public String runId() {
        return runId;
    }

    public String turnId() {
        return turnId;
    }

    /** Append one recorded fact as its kernel payload. */
    public void append(String type, Object payload) {
        append(new SessionEventData.PayloadData(type, opaqueFields(payload)));
    }

    /** The opaque fields of a payload that is not already a map of them. */
    private static Map<String, Object> opaqueFields(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            map.forEach((key, value) -> fields.put(String.valueOf(key), value));
            return fields;
        }
        return payload == null ? Map.of() : Map.of("value", payload);
    }

    /** Logical outcome of a normally returned result. */
    public static String outcome(GenerateReason reason) {
        if (reason == null) {
            return "completed";
        }
        return switch (reason) {
            case TOOL_SUSPENDED,
                    PERMISSION_ASKING,
                    REASONING_STOP_REQUESTED,
                    ACTING_STOP_REQUESTED,
                    MIDDLEWARE_STOP_REQUESTED ->
                    "suspended";
            case INTERRUPTED -> "interrupted";
            case MAX_ITERATIONS, ALL_TOOLS_DENIED -> "failed";
            default -> "completed";
        };
    }

    public Set<String> acceptedMessageIds() {
        Set<String> ids = new HashSet<>();
        for (Msg message : history()) {
            if (message.getId() != null) {
                ids.add(message.getId());
            }
        }
        return Set.copyOf(ids);
    }

    public void result(Msg result) {
        if (result != null && result.getGenerateReason() != null) {
            resultReason = result.getGenerateReason().name();
            resultStatus = outcome(result.getGenerateReason());
        }
    }

    public Mono<Void> start(List<Msg> input, AgentState state) {
        return Mono.fromRunnable(
                () -> {
                    append(new SessionExecutionData.TurnStart(turnNumber, resumedTurn));
                    append(
                            new SessionEventData.PayloadData(
                                    "input/received",
                                    Map.of("messages", input == null ? List.of() : input)));
                });
    }

    public void step(int iteration) {
        if (stepOpen) {
            append(new SessionExecutionData.StepEnd(turnNumber, stepNumber));
        }
        stepNumber = iteration + 1L;
        stepOpen = true;
        append(new SessionExecutionData.StepStart(turnNumber, stepNumber));
    }

    public Mono<Void> checkpoint(AgentState state, String reason) {
        return Mono.fromRunnable(() -> checkpointNow(state, reason));
    }

    public void captureState(AgentState state, String reason) {
        checkpointNow(state, reason);
    }

    public void checkpointNow(AgentState state, String reason) {
        if (state != null) {
            List<Msg> context = state.getContext();
            Set<String> retainedIds = new HashSet<>();
            for (Msg item : context) {
                if (item.getId() != null && !item.getId().isBlank()) {
                    retainedIds.add(item.getId());
                }
            }
            for (Msg message : context) {
                if (message.getMetadata().containsKey(Msg.METADATA_CONFIRM_RESULTS)) {
                    continue;
                }
                if (message.getId() != null && !recordedMessages.add(message.getId())) {
                    continue;
                }
                if (message.hasContentBlocks(ToolResultBlock.class)) {
                    append(recordedToolResult(message));
                    for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                        append(
                                new SessionEventData.PayloadData(
                                        "interaction/resolved",
                                        Map.of(
                                                "requestId",
                                                result.getId(),
                                                "kind",
                                                "external_execution",
                                                "result",
                                                result)));
                    }
                    continue;
                }
                SessionEventData recorded = recordedMessage(message);
                if (recorded instanceof SessionEventData.SurfaceData surface
                        && appendCompactionReplaceIfApplicable(message, surface, retainedIds)) {
                    continue;
                }
                append(recorded);
            }
        }
        append(
                new SessionEventData.PayloadData(
                        "state/checkpoint",
                        Map.of(
                                "stateJson",
                                state == null ? "" : state.toJson(),
                                "reason",
                                reason,
                                "projectorVersion",
                                1)));
    }

    private boolean appendCompactionReplaceIfApplicable(
            Msg message, SessionEventData.SurfaceData surface, Set<String> retainedIds) {
        Object rawCovered =
                message.getMetadata() == null
                        ? null
                        : message.getMetadata().get("context.covered_messages");
        if (!(rawCovered instanceof java.util.Collection<?> coveredList) || coveredList.isEmpty()) {
            return false;
        }
        Set<String> coveredIds = new HashSet<>();
        for (Object item : coveredList) {
            if (item instanceof String id && !id.isBlank()) {
                coveredIds.add(id);
            }
        }
        if (coveredIds.isEmpty()) {
            return false;
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Session writer is closed");
            }
            SessionLog log = kernel();
            List<SessionSeq.Seq> nodes = log.surfaceNodes();
            int startIdx = -1;
            int endIdx = -1;
            for (int i = 0; i < nodes.size(); i++) {
                SessionSeq.Seq nodeSeq = nodes.get(i);
                SessionEvent nodeEvent = log.eventAt(nodeSeq);
                if (nodeEvent == null) {
                    continue;
                }
                if (i == 0 && SessionEventTypes.SYSTEM_MESSAGE.equals(nodeEvent.type())) {
                    continue;
                }
                Msg derived = log.deriveEventMessage(nodeEvent);
                String derivedId = derived != null ? derived.getId() : null;
                if (derivedId != null && coveredIds.contains(derivedId)) {
                    if (startIdx == -1) {
                        startIdx = i;
                    }
                    endIdx = i;
                } else if (startIdx != -1
                        && derivedId != null
                        && retainedIds.contains(derivedId)
                        && !derivedId.equals(message.getId())) {
                    break;
                }
            }
            if (startIdx == -1 || startIdx > endIdx) {
                return false;
            }
            SessionSeq.Seq startSeq = nodes.get(startIdx);
            SessionSeq.Seq endSeq = nodes.get(endIdx);
            List<SessionSeq.Seq> shadowed = List.copyOf(nodes.subList(startIdx, endIdx + 1));
            long time = System.currentTimeMillis();
            SessionSeq.Seq seq = SessionSeq.Seq.of(journal.committed().size());
            String eventId = UUID.randomUUID().toString();
            SessionEvent replacement =
                    owned(
                            SessionEvent.surface(
                                            seq,
                                            time,
                                            surface,
                                            SurfaceOp.replace(startSeq, endSeq),
                                            shadowed)
                                    .withIdentity(eventId, null, null));
            journal.commitValidated(replacement);
            journal.flush();
            return true;
        }
    }

    /** The stored payload for one message that carries no tool result, keyed by its role. */
    private static SessionEventData recordedMessage(Msg message) {
        String messageId = recordedMessageId(message);
        return switch (message.getRole()) {
            case ASSISTANT ->
                    new SessionEventData.AssistantMessageData(
                            0L,
                            0L,
                            messageId,
                            message.getContent(),
                            List.of(),
                            null,
                            false,
                            recordedSender(message));
            case SYSTEM -> new SessionEventData.SystemMessageData(message.getContent());
            default ->
                    new SessionEventData.UserMessageData(
                            messageId,
                            message.getContent(),
                            isCompactionSummary(message)
                                    ? new SessionEventData.MessageSource("__compaction_summary__")
                                    : null);
        };
    }

    private static boolean isCompactionSummary(Msg message) {
        return "__compaction_summary__".equals(message.getName())
                || (message.getMetadata() != null
                        && message.getMetadata().containsKey("context.covered_messages"));
    }

    /** The stored message identity; an absent identity is recorded as the placeholder name. */
    private static String recordedMessageId(Msg message) {
        String id = message.getId();
        return id == null || id.isBlank() ? "message" : id;
    }

    /** The stored sender attribution; a blank name means the message is unattributed. */
    private static String recordedSender(Msg message) {
        String name = message.getName();
        return name == null || name.isBlank() ? null : name;
    }

    /**
     * The stored payload for one message carrying tool results.
     *
     * <p>The recorded shape pairs a result with the message identity rather than the block identity.
     * The name is the <em>tool</em> the result came from, not the message attribution: a reader uses
     * it to tell which call has already run, so recording the sender here makes a caller re-issue a
     * tool that already returned.
     */
    private static SessionEventData recordedToolResult(Msg message) {
        String id = message.getId();
        return new SessionEventData.ToolResultData(
                id == null || id.isBlank() ? "call" : id,
                recordedToolName(message),
                message.getContent(),
                false,
                null,
                id == null ? null : Map.of("messageId", id));
    }

    /** The tool a result message belongs to; the sender only covers result-less messages. */
    private static String recordedToolName(Msg message) {
        for (ToolResultBlock block : message.getContentBlocks(ToolResultBlock.class)) {
            if (block.getName() != null && !block.getName().isBlank()) {
                return block.getName();
            }
        }
        String sender = recordedSender(message);
        return sender == null ? "tool" : sender;
    }

    public Mono<Void> prepareModel(
            ModelCallInput input, String callId, String purpose, AgentState state) {
        return Mono.fromRunnable(
                () -> {
                    checkpointNow(state, "before_model");
                    var config = new java.util.LinkedHashMap<String, Object>();
                    if (input.model().getModelName() != null) {
                        config.put("model", input.model().getModelName());
                    }
                    if (callId != null) {
                        config.put("modelCallId", callId);
                    }
                    if (purpose != null) {
                        config.put("purpose", purpose);
                    }
                    append(
                            new SessionRequestData.RequestHeader(
                                    nextHeaderReason(),
                                    false,
                                    input.tools() == null
                                            ? List.of()
                                            : input.tools().stream()
                                                    .map(
                                                            io.deepscope.core.model.ToolSchema
                                                                    ::getName)
                                                    .toList(),
                                    config,
                                    Map.of()));
                    append(
                            new SessionEventData.PayloadData(
                                    "model/dispatch",
                                    Map.of("modelCallId", callId, "attemptId", callId)));
                });
    }

    public void flushNow() {}

    public Mono<Void> record(String type, Object payload) {
        return Mono.fromRunnable(() -> append(type, payload));
    }

    public void actionStarted(String toolCallId, String actionId) {
        actionIds.put(toolCallId, actionId);
    }

    public String actionId(String toolCallId) {
        return actionIds.getOrDefault(toolCallId, "");
    }

    public Optional<SessionEventData.PayloadData> findLast(
            String type, Predicate<SessionEventData.PayloadData> predicate) {
        SessionEventData.PayloadData found = null;
        for (SessionEvent event : kernel().events()) {
            if (event.data() instanceof SessionEventData.PayloadData payload
                    && type.equals(payload.type())
                    && predicate.test(payload)) {
                found = payload;
            }
        }
        return Optional.ofNullable(found);
    }

    private SessionRequestData.RequestHeaderReason nextHeaderReason() {
        for (SessionEvent event : kernel().events()) {
            if (SessionEventTypes.REQUEST_HEADER.equals(event.type())) {
                return SessionRequestData.RequestHeaderReason.CHANGE;
            }
        }
        return SessionRequestData.RequestHeaderReason.INITIAL;
    }

    public Mono<Void> finish(AgentState state, String status) {
        return Mono.fromRunnable(
                () -> {
                    checkpointNow(state, "run_" + status);
                    String terminal =
                            "completed".equals(status) && resultStatus != null
                                    ? resultStatus
                                    : status;
                    if (stepOpen) {
                        append(new SessionExecutionData.StepEnd(turnNumber, stepNumber));
                        stepOpen = false;
                    }
                    var outcome = new java.util.LinkedHashMap<String, Object>();
                    outcome.put("turn", turnNumber);
                    outcome.put("status", terminal);
                    outcome.put("reason", resultReason);
                    append(
                            new SessionExecutionData.TurnEnd(
                                    turnNumber,
                                    TurnRepair.reasonForStatus(terminal)
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalArgumentException(
                                                                    "Turn closed with an"
                                                                        + " unrecognized status: "
                                                                            + terminal))));
                    append(new SessionEventData.PayloadData("run/end", outcome));
                    close();
                });
    }

    public void recordNow(String type, Object payload) {
        append(type, payload);
    }

    public void recordIfOpen(String type, Object payload) {
        if (!closed) {
            append(type, payload);
        }
    }

    public void append(String type, Object payload, boolean required) {
        append(type, payload);
    }

    /** Capture runtime facts that are not already stored at the model or tool boundary. */
    public void observe(AgentEvent event) {
        if (event.getExecution() != null && !runId.equals(event.getExecution().runId())) {
            return;
        }
        if (event.getSource() != null && !event.getSource().isBlank()) {
            return;
        }
        if (event instanceof RequireUserConfirmEvent confirmation) {
            for (var call : confirmation.getToolCalls()) {
                append(
                        new SessionEventData.PayloadData(
                                "interaction/requested",
                                Map.of(
                                        "requestId",
                                        call.getId(),
                                        "kind",
                                        "confirmation",
                                        "call",
                                        call)));
            }
            return;
        }
        if (event instanceof RequireExternalExecutionEvent external) {
            for (var call : external.getToolCalls()) {
                append(
                        new SessionEventData.PayloadData(
                                "interaction/requested",
                                Map.of(
                                        "requestId",
                                        call.getId(),
                                        "kind",
                                        "external_execution",
                                        "call",
                                        call)));
            }
            return;
        }
        if (event instanceof ExternalExecutionResultEvent external) {
            for (var result : external.getToolResults()) {
                append(
                        new SessionEventData.PayloadData(
                                "interaction/resolved",
                                Map.of(
                                        "requestId",
                                        result.getId(),
                                        "kind",
                                        "external_execution",
                                        "result",
                                        result)));
            }
            return;
        }
        if (event instanceof UserConfirmResultEvent confirmed) {
            for (var result : confirmed.getConfirmResults()) {
                append(
                        new SessionEventData.PayloadData(
                                "interaction/resolved",
                                Map.of(
                                        "requestId",
                                        result.getToolCall().getId(),
                                        "kind",
                                        "confirmation",
                                        "result",
                                        result)));
            }
            return;
        }
        String type =
                switch (event.getType().name()) {
                    case "REQUEST_STOP" -> "run/stop_requested";
                    case "REQUIRE_USER_CONFIRM", "REQUIRE_EXTERNAL_EXECUTION" ->
                            "interaction/requested";
                    case "USER_CONFIRM_RESULT", "EXTERNAL_EXECUTION_RESULT" ->
                            "interaction/resolved";
                    case "HINT_BLOCK", "SUBAGENT_EXPOSED" -> "presentation/hint";
                    default -> null;
                };
        if (event instanceof CustomEvent custom) {
            type =
                    switch (custom.getName()) {
                        case "context_build" -> "context/build";
                        case "task_verification" -> "verification/result";
                        case "action_observation" -> null;
                        default -> "extension/" + custom.getName();
                    };
        }
        if (type != null) {
            append(new SessionEventData.PayloadData(type, Map.of("event", event)));
        }
    }

    /** Append one kernel payload and flush it. */
    public void append(SessionEventData data) {
        Objects.requireNonNull(data, "data");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Session writer is closed");
            }
            long time = System.currentTimeMillis();
            SessionSeq.Seq seq = SessionSeq.Seq.of(journal.committed().size());
            SessionEvent event = owned(staged(seq, time, data));
            journal.commitValidated(event);
            journal.flush();
        }
    }

    /** Events committed so far, including appends from {@link NativeSessionLogStore#appendExternal}. */
    public SessionLog kernel() {
        synchronized (lock) {
            SessionLog loaded = new SessionLog();
            for (SessionEvent event : List.copyOf(journal.committed())) {
                loaded.append(event);
            }
            return loaded;
        }
    }

    /** Model history derived from the committed log. */
    public List<Msg> history() {
        return MessageDerivation.history(kernel());
    }

    /**
     * Working state for a resumed call. Message content comes from derived history. Permission,
     * plan, and tool context come from the latest snapshot in the same log.
     */
    public AgentState workingState(AgentState fresh) {
        AgentState loaded = null;
        for (SessionEvent event : kernel().events()) {
            if (!(event.data() instanceof SessionEventData.PayloadData payload)) {
                continue;
            }
            if (!payload.type().equals("state/snapshot")
                    && !payload.type().equals("state/checkpoint")) {
                continue;
            }
            Object json = payload.fields().get("stateJson");
            if (json instanceof String text && !text.isBlank()) {
                loaded = AgentState.fromJsonString(text);
            }
        }
        if (loaded == null) {
            loaded = fresh;
        }
        List<Msg> messages = history();
        if (!messages.isEmpty()) {
            loaded.contextMutable().clear();
            loaded.contextMutable().addAll(messages);
        }
        return loaded;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            store.detach(sessionId, this);
            journal.close();
        }
    }

    private static SessionEvent staged(SessionSeq.Seq seq, long time, SessionEventData data) {
        String eventId = UUID.randomUUID().toString();
        if (data instanceof SessionEventData.SurfaceData surface) {
            return SessionEvent.surface(seq, time, surface, SurfaceOp.APPEND, List.of())
                    .withIdentity(eventId, null, null);
        }
        boolean extension = !SessionEventTypes.isKnown(data.type());
        return new SessionEvent(
                seq, time, data.type(), data, null, List.of(), extension, eventId, null, null);
    }

    /** Stamp the execution and turn after the event is built. */
    SessionEvent owned(SessionEvent event) {
        return event.withIdentity(event.eventId(), runId, turnId);
    }
}
