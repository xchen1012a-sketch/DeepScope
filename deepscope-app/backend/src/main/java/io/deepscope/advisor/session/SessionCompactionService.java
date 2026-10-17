/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deepscope.advisor.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.agent.BasicChatRuntime;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.agui.registry.AguiAgentRegistry;
import io.deepscope.core.message.Msg;
import io.deepscope.core.session.log.DurableSession;
import io.deepscope.core.state.AgentState;
import io.deepscope.core.state.AgentStateStore;
import io.deepscope.harness.agent.HarnessAgent;
import io.deepscope.harness.agent.memory.MemoryFlushManager;
import io.deepscope.harness.agent.memory.compaction.CompactionConfig;
import io.deepscope.harness.agent.memory.compaction.ConversationCompactor;
import io.deepscope.harness.agent.memory.compaction.TokenCounterUtil;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-click context compaction for a whole session, triggered from the composer's context panel.
 *
 * <p>Reuses the framework compaction building blocks in forced mode: the session's persisted
 * {@link AgentState} is loaded from the agent's own state store, the conversation prefix is
 * distilled into a summary, and the compacted context is written back and saved immediately — so a
 * page refresh during or after the run never loses the result. A per-session lock plus the
 * persisted run status keep compaction and streaming runs mutually exclusive.
 */
public final class SessionCompactionService {

    private static final Logger log = LoggerFactory.getLogger(SessionCompactionService.class);

    /** Outcome codes shared with the REST layer. */
    public static final String OUTCOME_COMPACTED = "compacted";

    public static final String OUTCOME_SKIPPED = "skipped";
    public static final String OUTCOME_CONFLICT = "conflict";
    public static final String OUTCOME_FAILED = "failed";

    /** Reason codes shared with the frontend, which renders them as localized hints. */
    public static final String REASON_SESSION_RUNNING = "session_running";

    public static final String REASON_ALREADY_COMPACTING = "already_compacting";
    public static final String REASON_TOO_SHORT = "too_short";
    public static final String REASON_UNAVAILABLE = "unavailable";

    /** One compaction attempt; {@code reason} explains every non-compacted outcome. */
    public record CompactionResult(String outcome, int inputTokens, int keptTokens, String reason) {

        static CompactionResult compacted(int inputTokens, int keptTokens) {
            return new CompactionResult(OUTCOME_COMPACTED, inputTokens, keptTokens, null);
        }

        static CompactionResult skipped(String reason) {
            return new CompactionResult(OUTCOME_SKIPPED, 0, 0, reason);
        }

        static CompactionResult conflict(String reason) {
            return new CompactionResult(OUTCOME_CONFLICT, 0, 0, reason);
        }

        static CompactionResult failed(String reason) {
            return new CompactionResult(OUTCOME_FAILED, 0, 0, reason);
        }
    }

    private final SessionStore sessions;
    private final TimelineStore timeline;
    private final AguiAgentRegistry registry;
    private final ObjectMapper json;
    private final Set<String> compacting = ConcurrentHashMap.newKeySet();
    private java.util.function.BiPredicate<String, Runnable> runGuard =
            (session, operation) -> {
                operation.run();
                return true;
            };

    public void setRunGuard(java.util.function.BiPredicate<String, Runnable> guard) {
        runGuard = guard;
    }

    public SessionCompactionService(
            SessionStore sessions,
            TimelineStore timeline,
            AguiAgentRegistry registry,
            ObjectMapper json) {
        this.sessions = sessions;
        this.timeline = timeline;
        this.registry = registry;
        this.json = json;
    }

    /**
     * Compacts one session synchronously. The caller must have verified ownership. Running
     * sessions and sessions already being compacted are rejected as conflicts.
     */
    public CompactionResult compact(SessionStore.SessionRow session) {
        if (SessionStore.STATUS_RUNNING.equals(session.status())) {
            return CompactionResult.conflict(REASON_SESSION_RUNNING);
        }
        if (!compacting.add(session.sessionId())) {
            return CompactionResult.conflict(REASON_ALREADY_COMPACTING);
        }
        try {
            var result =
                    new java.util.concurrent.atomic.AtomicReference<>(
                            CompactionResult.conflict(REASON_SESSION_RUNNING));
            runGuard.test(session.sessionId(), () -> result.set(doCompact(session)));
            return result.get();
        } finally {
            compacting.remove(session.sessionId());
        }
    }

    private CompactionResult doCompact(SessionStore.SessionRow session) {
        String sessionId = session.sessionId();
        try {
            HarnessAgent harness = resolveHarness(session);
            if (harness == null
                    || (harness.getStateStore() == null && harness.sessionStore() == null)) {
                if (timeline.listEntries(sessionId).isEmpty()) {
                    return CompactionResult.skipped(REASON_TOO_SHORT);
                }
                return CompactionResult.failed(REASON_UNAVAILABLE);
            }
            AgentStateStore store = harness.getStateStore();
            RuntimeContext rc =
                    RuntimeContext.builder()
                            .userId(session.ownerUserId())
                            .sessionId(sessionId)
                            .build();
            var nativeStore = harness.sessionStore();
            try (DurableSession writer =
                    nativeStore == null
                            ? null
                            : nativeStore.openWriter(
                                    harness.sessionKey(rc),
                                    "manual-compact-" + java.util.UUID.randomUUID(),
                                    null,
                                    rc)) {
                AgentState state =
                        writer != null
                                ? writer.workingState(harness.inspectSession(rc).state())
                                : store.get(
                                                session.ownerUserId(),
                                                sessionId,
                                                "agent_state",
                                                AgentState.class)
                                        .orElse(null);
                if (state == null || state.contextMutable().isEmpty()) {
                    return CompactionResult.skipped(REASON_TOO_SHORT);
                }
                List<Msg> conversation = state.contextMutable();
                int inputMessages = conversation.size();
                int inputTokens = TokenCounterUtil.calculateToken(conversation);

                int keep = conversation.size() > 2 ? 2 : 0;
                CompactionConfig force =
                        CompactionConfig.builder()
                                .triggerMessages(1)
                                .triggerTokens(0)
                                .keepMessages(keep)
                                .keepTokens(0)
                                .build();
                io.deepscope.core.model.Model compactModel =
                        harness.getModel()
                                        instanceof
                                        io.deepscope.advisor.model.SwitchingChatModel switching
                                ? switching.forEndpoint(session.modelEndpointId())
                                : harness.getModel();
                MemoryFlushManager flush =
                        new MemoryFlushManager(harness.getWorkspaceManager(), compactModel);
                ConversationCompactor compactor = new ConversationCompactor(compactModel, flush);
                if (writer != null) rc.put(DurableSession.CONTEXT_KEY, writer);
                Optional<List<Msg>> result =
                        compactor
                                .compactIfNeeded(
                                        rc,
                                        List.copyOf(conversation),
                                        force,
                                        writer != null
                                                ? harness.sessionKey(rc).agentId()
                                                : harness.getName(),
                                        sessionId)
                                .block(Duration.ofMinutes(5));
                if (result == null || result.isEmpty()) {
                    return CompactionResult.skipped(REASON_TOO_SHORT);
                }
                List<Msg> compacted = result.get();
                List<Msg> previous = List.copyOf(conversation);
                conversation.clear();
                conversation.addAll(compacted);
                try {
                    if (writer != null) writer.checkpointNow(state, "manual_compaction");
                    else store.save(session.ownerUserId(), sessionId, "agent_state", state);
                } catch (RuntimeException error) {
                    conversation.clear();
                    conversation.addAll(previous);
                    throw error;
                }
                harness.clearStateCache(session.ownerUserId(), sessionId);
                int keptTokens = TokenCounterUtil.calculateToken(compacted);

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", "done");
                payload.put("manual", true);
                payload.put("inputMessages", inputMessages);
                payload.put("inputTokens", inputTokens);
                payload.put("keptMessages", compacted.size());
                payload.put("keptTokens", keptTokens);
                timeline.append(sessionId, TimelineStore.COMPACTION, payload);
                // append() leaves the session on the generic "running" status; a manual compaction
                // never changes the lifecycle, so restore the idle state explicitly.
                sessions.updateStatus(sessionId, SessionStore.STATUS_IDLE);
                log.info(
                        "Manual compaction done: session={}, {} msgs/{} tokens -> {} msgs/{}"
                                + " tokens",
                        sessionId,
                        inputMessages,
                        inputTokens,
                        compacted.size(),
                        keptTokens);
                return CompactionResult.compacted(inputTokens, keptTokens);
            }
        } catch (SQLException error) {
            log.warn("Manual compaction persistence failed: session={}", sessionId, error);
            return CompactionResult.failed(REASON_UNAVAILABLE);
        } catch (RuntimeException error) {
            log.warn("Manual compaction failed: session={}", sessionId, error);
            return CompactionResult.failed(REASON_UNAVAILABLE);
        }
    }

    /**
     * The agent whose state store holds this session's context: the bound agent in lock mode,
     * otherwise the agent of the most recent routing decision, falling back to the basic chat
     * transport.
     */
    private HarnessAgent resolveHarness(SessionStore.SessionRow session) throws SQLException {
        String agentId = session.agentId();
        if (agentId == null || agentId.isBlank()) {
            agentId = lastRoutedAgentId(session.sessionId());
        }
        return registry.getAgent(agentId)
                .filter(HarnessAgent.class::isInstance)
                .map(HarnessAgent.class::cast)
                .orElse(null);
    }

    private String lastRoutedAgentId(String sessionId) throws SQLException {
        List<TimelineStore.TimelineEntry> entries = timeline.listEntries(sessionId);
        for (int index = entries.size() - 1; index >= 0; index--) {
            TimelineStore.TimelineEntry entry = entries.get(index);
            if (!"agent_route".equals(entry.kind())) {
                continue;
            }
            try {
                String routed = json.readTree(entry.payload()).path("agentId").asText("");
                if (!routed.isBlank()) {
                    return routed;
                }
            } catch (JsonProcessingException ignored) {
                // Unreadable route payload: keep scanning older entries.
            }
        }
        return BasicChatRuntime.AGENT_ID;
    }

    /**
     * Clears the agent's memory and state store for the given session if an agent runtime exists.
     */
    public void clearAgentState(SessionStore.SessionRow session) {
        try {
            Set<String> ids = new java.util.LinkedHashSet<>();
            if (session.agentId() != null && !session.agentId().isBlank())
                ids.add(session.agentId());
            for (var entry : timeline.listEntries(session.sessionId())) {
                var payload = json.readTree(entry.payload());
                if ("agent_route".equals(entry.kind())) {
                    String id = payload.path("agentId").asText("");
                    if (!id.isBlank()) ids.add(id);
                    else if ("basic".equals(payload.path("mode").asText()))
                        ids.add(BasicChatRuntime.AGENT_ID);
                }
            }
            List<HarnessAgent> targets = new java.util.ArrayList<>();
            for (String id : ids) {
                HarnessAgent harness =
                        registry.getAgent(id)
                                .filter(HarnessAgent.class::isInstance)
                                .map(HarnessAgent.class::cast)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Session runtime unavailable for"
                                                                + " clearing"));
                if (harness.getStateStore() == null)
                    throw new IllegalStateException("Session state store unavailable");
                targets.add(harness);
            }
            for (HarnessAgent harness : targets) {
                harness.getStateStore().delete(session.ownerUserId(), session.sessionId());
                harness.clearStateCache(session.ownerUserId(), session.sessionId());
            }
        } catch (SQLException | JsonProcessingException error) {
            throw new IllegalStateException("Failed to identify session state", error);
        }
    }

    public void clearSessionContext(SessionStore.SessionRow session) throws SQLException {
        java.util.concurrent.atomic.AtomicReference<SQLException> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        boolean permitted =
                runGuard.test(
                        session.sessionId(),
                        () -> {
                            try {
                                clearAgentState(session);
                                timeline.clearEntries(session.sessionId());
                            } catch (SQLException error) {
                                failure.set(error);
                            }
                        });
        if (!permitted) throw new IllegalStateException("session_busy");
        if (failure.get() != null) throw failure.get();
    }
}
