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

import io.deepscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.deepscope.core.agui.adapter.strategy.AguiStreamContext;
import io.deepscope.core.agui.event.AguiEvent;
import io.deepscope.core.event.AgentEvent;
import io.deepscope.core.event.ToolResultEndEvent;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side last-resort persistence for one advisor round.
 *
 * <p>The normal path is unchanged: the frontend still posts the finished round to {@code POST
 * /messages} (which calls {@link #markFlushed(String)} to cancel the pending flush). When the
 * client dies mid-round (refresh, disconnect, stop) nothing arrives on that channel, so this
 * enricher — which sees the same AG-UI event stream on the server — accumulates the round's text,
 * reasoning, tool calls, usage and route info, and after a grace delay writes them to the timeline
 * itself and closes the session into the interrupted terminal state.
 *
 * <p>Subagent process records ({@code subagentRuns}) stay a frontend responsibility: they are
 * assembled from many custom subagent events and the degraded server record intentionally omits
 * them rather than re-implement that assembly.
 */
public final class AguiRoundPersister implements AguiEventEnricher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AguiRoundPersister.class);

    /** AG-UI custom event name carrying one model call's token usage (see the enricher). */
    static final String TOKEN_USAGE_EVENT = "token_usage";

    /** AG-UI custom event name carrying advisor process events (route and friends). */
    static final String ADVISOR_EVENT_NAME = "advisor_event";

    /**
     * Grace delay before the fallback flush: the frontend normally posts the finished round within
     * a second or two, so anything still pending after this window means the client is gone.
     */
    static final long DEFAULT_FLUSH_DELAY_MILLIS = 15_000;

    /** Accumulators untouched for this long (no terminal event at all) are force-flushed. */
    static final long STALE_ACCUMULATOR_MILLIS = 15 * 60 * 1000L;

    private final TimelineStore timeline;
    private final SessionStore sessions;
    private final long flushDelayMillis;
    private final java.util.function.LongSupplier clock;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "agui-round-persister");
                        thread.setDaemon(true);
                        return thread;
                    });

    private final ConcurrentHashMap<String, Round> rounds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private volatile io.deepscope.advisor.tool.ToolRuntimeCoordinator coordinator;
    private volatile java.util.function.Predicate<String> activeRunCheck = session -> false;
    private volatile java.util.function.BiPredicate<String, Runnable> recoveryGuard =
            (session, operation) -> {
                if (activeRunCheck.test(session)) return false;
                operation.run();
                return true;
            };

    public void setActiveRunCheck(java.util.function.Predicate<String> check) {
        activeRunCheck = check;
    }

    public void setRecoveryGuard(java.util.function.BiPredicate<String, Runnable> guard) {
        recoveryGuard = guard;
    }

    public void reconcileStale(long now) {
        try {
            flushStale(now);
            for (var session : sessions.staleRunning(now)) {
                recoveryGuard.test(
                        session.sessionId(),
                        () -> {
                            synchronized (this) {
                                Round staleRound = rounds.get(session.sessionId());
                                if (staleRound != null)
                                    flush(session.sessionId(), staleRound.runId);
                                else {
                                    try {
                                        sessions.interruptIfStale(session.sessionId(), now);
                                    } catch (SQLException error) {
                                        throw new IllegalStateException(error);
                                    }
                                }
                            }
                        });
            }
        } catch (SQLException | RuntimeException error) {
            log.warn("Failed to reconcile stale advisor sessions", error);
        }
    }

    private static final class Round {
        final ConversationParts parts = new ConversationParts();
        final String runId;
        final int turn;
        final ConversationPresentation presentation;

        Round(String runId, int turn) {
            this.runId = runId;
            this.turn = turn;
            this.presentation = new ConversationPresentation(runId);
        }

        final StringBuilder text = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final Map<String, StringBuilder> argsBuffers = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> toolCalls = new LinkedHashMap<>();
        final List<Map<String, Object>> usage = new ArrayList<>();
        volatile String mode = "";
        volatile String domainName = "";
        volatile long updatedAt = System.currentTimeMillis();
    }

    public void setCoordinator(io.deepscope.advisor.tool.ToolRuntimeCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public AguiRoundPersister(TimelineStore timeline, SessionStore sessions) {
        this(timeline, sessions, DEFAULT_FLUSH_DELAY_MILLIS);
    }

    /** Test constructor: a zero delay flushes synchronously on the scheduler thread. */
    AguiRoundPersister(TimelineStore timeline, SessionStore sessions, long flushDelayMillis) {
        this(timeline, sessions, flushDelayMillis, System::currentTimeMillis);
    }

    AguiRoundPersister(
            TimelineStore timeline,
            SessionStore sessions,
            long flushDelayMillis,
            java.util.function.LongSupplier clock) {
        this.timeline = timeline;
        this.sessions = sessions;
        this.flushDelayMillis = flushDelayMillis;
        this.clock = clock;
        scheduler.scheduleWithFixedDelay(
                () -> reconcileStale(clock.getAsLong()), 60, 60, TimeUnit.SECONDS);
    }

    @Override
    public synchronized List<AguiEvent> enrich(
            AgentEvent source, List<AguiEvent> events, AguiStreamContext context) {
        if (source != null && source.getSource() != null && !source.getSource().isBlank())
            return events;
        String sessionId = context.getThreadId();
        String runId = context.getRunId();
        Round current = rounds.get(sessionId);
        if (events.stream().anyMatch(event -> event instanceof AguiEvent.RunStarted)
                && current != null
                && !current.runId.equals(runId)) {
            flush(sessionId);
        }
        Round observed = round(sessionId, runId);
        if (!observed.runId.equals(runId)) return events;
        observed.presentation.accept(source, events, clock.getAsLong());
        boolean finished = false;
        Round round = null;
        for (AguiEvent event : events) {
            if (event instanceof AguiEvent.TextMessageStart
                    || event instanceof AguiEvent.ReasoningStart
                    || event instanceof AguiEvent.TextMessageEnd
                    || event instanceof AguiEvent.ReasoningEnd
                    || event instanceof AguiEvent.ReasoningMessageStart
                    || event instanceof AguiEvent.ReasoningMessageEnd)
                round = round(sessionId, runId);
            if (event instanceof AguiEvent.TextMessageContent content) {
                round = round(sessionId, runId);
                round.text.append(content.delta() == null ? "" : content.delta());
            } else if (event instanceof AguiEvent.ReasoningMessageContent content) {
                round = round(sessionId, runId);
                round.reasoning.append(content.delta() == null ? "" : content.delta());
            } else if (event instanceof AguiEvent.ToolCallStart start) {
                round = round(sessionId, runId);
                toolCall(round, start.toolCallId()).put("startedAt", System.currentTimeMillis());
                if (start.toolCallName() != null && !start.toolCallName().isBlank()) {
                    toolCall(round, start.toolCallId()).put("name", start.toolCallName());
                }
            } else if (event instanceof AguiEvent.ToolCallArgs args) {
                round = round(sessionId, runId);
                round.argsBuffers
                        .computeIfAbsent(args.toolCallId(), ignored -> new StringBuilder())
                        .append(args.delta() == null ? "" : args.delta());
            } else if (event instanceof AguiEvent.ToolCallResult result) {
                round = round(sessionId, runId);
                Map<String, Object> toolCall = toolCall(round, result.toolCallId());
                toolCall.put("result", result.content() == null ? "" : result.content());
                toolCall.put("endedAt", System.currentTimeMillis());
                String state = toolState(source);
                toolCall.put("status", state == null ? "done" : state);
            } else if (event instanceof AguiEvent.Custom custom) {
                round = custom(round, sessionId, custom);
            } else if (event instanceof AguiEvent.RunFinished
                    || event instanceof AguiEvent.RunError) {
                finished = true;
            }
            if (round != null) {
                if (event instanceof AguiEvent.ToolCallArgs args)
                    toolCall(round, args.toolCallId())
                            .put("args", round.argsBuffers.get(args.toolCallId()).toString());
                round.parts.accept(event, round.toolCalls);
                round.updatedAt = System.currentTimeMillis();
            }
        }
        if (finished) {
            scheduleFlush(sessionId);
            if (coordinator != null) {
                coordinator.releaseLease(sessionId, runId);
            }
        }
        if (finished || events.stream().anyMatch(event -> event instanceof AguiEvent.RunStarted)) {
            Map<String, Object> presentation = observed.presentation.snapshot();
            if (presentation != null) {
                var value = new LinkedHashMap<String, Object>(presentation);
                value.put("presentationVersion", 1);
                var enriched = new ArrayList<AguiEvent>(events);
                enriched.add(
                        new AguiEvent.Custom(
                                sessionId, runId, ConversationPresentation.EVENT_NAME, value));
                return List.copyOf(enriched);
            }
        }
        return events;
    }

    public synchronized boolean belongsToAnotherRun(String sessionId, String runId) {
        Round round = rounds.get(sessionId);
        return round != null && !round.runId.equals(runId);
    }

    public synchronized Map<String, Object> presentation(String sessionId, String runId) {
        Round round = rounds.get(sessionId);
        return round != null && round.runId.equals(runId) ? round.presentation.snapshot() : null;
    }

    /** Route info and per-step usage ride on custom events; both feed the persisted payload. */
    private Round custom(Round round, String sessionId, AguiEvent.Custom custom) {
        String runId = custom.runId();
        if (ADVISOR_EVENT_NAME.equals(custom.name())) round = round(sessionId, runId);
        if (ADVISOR_EVENT_NAME.equals(custom.name())
                && custom.value() instanceof Map<?, ?> value
                && "route".equals(value.get("kind"))) {
            round = round(sessionId, runId);
            Object mode = value.get("mode");
            Object skill = value.get("skillName");
            round.mode = mode == null ? "" : mode.toString();
            round.domainName = skill == null ? "" : skill.toString();
        } else if (TOKEN_USAGE_EVENT.equals(custom.name())
                && custom.value() instanceof Map<?, ?> value
                && value.get("delta") instanceof Map<?, ?> delta) {
            round = round(sessionId, runId);
            round.usage.add(usageSample(value, delta));
        }
        return round;
    }

    private static Map<String, Object> usageSample(Map<?, ?> value, Map<?, ?> delta) {
        Map<String, Object> sample = new LinkedHashMap<>();
        Object modelCall = value.get("modelCall");
        Object replyId = modelCall instanceof Map<?, ?> call ? call.get("replyId") : null;
        sample.put(
                "stepId",
                replyId == null || replyId.toString().isBlank() ? "usage" : replyId.toString());
        long input = longValue(delta.get("inputTokens"));
        long cacheRead = longValue(delta.get("cachedTokens"));
        long cacheWrite = longValue(delta.get("cacheCreationTokens"));
        sample.put("uncachedInput", Math.max(0, input - cacheRead - cacheWrite));
        sample.put("cacheRead", cacheRead);
        sample.put("cacheWrite", cacheWrite);
        sample.put("output", longValue(delta.get("outputTokens")));
        sample.put("projectedTokens", input);
        if (delta.get("time") instanceof Number time) {
            sample.put("timeSeconds", time.doubleValue());
        }
        if (value.get("contextWindow") instanceof Number window) {
            sample.put("contextWindow", window.longValue());
        }
        if (value.get("modelName") instanceof String model && !model.isBlank()) {
            sample.put("modelName", model);
        }
        if (value.get("firstTokenMs") instanceof Number firstToken) {
            sample.put("firstTokenMs", firstToken.longValue());
        }
        if (value.get("outputTokensPerSecond") instanceof Number rate) {
            sample.put("outputTokensPerSecond", rate.doubleValue());
        }
        return sample;
    }

    /** Same state rule as the page: only an explicit success is "done"; the rest are failures. */
    private static String toolState(AgentEvent source) {
        if (!(source instanceof ToolResultEndEvent end) || end.getState() == null) {
            return null;
        }
        return "success".equals(end.getState().getValue()) ? "done" : "failed";
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private Round round(String sessionId, String runId) {
        return rounds.computeIfAbsent(
                sessionId,
                ignored -> {
                    try {
                        int turn =
                                timeline.listEntries(sessionId).stream()
                                        .mapToInt(TimelineStore.TimelineEntry::turn)
                                        .max()
                                        .orElse(1);
                        return new Round(runId, turn);
                    } catch (SQLException error) {
                        throw new IllegalStateException("Cannot identify the run turn", error);
                    }
                });
    }

    public synchronized int turn(String sessionId, String runId) {
        Round round = rounds.get(sessionId);
        return round != null && round.runId.equals(runId) ? round.turn : 0;
    }

    public synchronized boolean hasPending(String sessionId) {
        return rounds.containsKey(sessionId);
    }

    private static Map<String, Object> toolCall(Round round, String toolCallId) {
        String id = toolCallId == null || toolCallId.isBlank() ? "unknown" : toolCallId;
        return round.toolCalls.computeIfAbsent(
                id,
                ignored -> {
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("id", id);
                    return call;
                });
    }

    private void scheduleFlush(String sessionId) {
        Round scheduledRound = rounds.get(sessionId);
        ScheduledFuture<?> existing = pending.remove(sessionId);
        if (existing != null) {
            existing.cancel(false);
        }
        pending.put(
                sessionId,
                scheduler.schedule(
                        () -> {
                            synchronized (this) {
                                if (rounds.get(sessionId) == scheduledRound) flush(sessionId);
                            }
                        },
                        flushDelayMillis,
                        TimeUnit.MILLISECONDS));
    }

    /**
     * The frontend posted the finished round itself: drop the accumulator and cancel the pending
     * fallback so the round is persisted exactly once.
     */
    public synchronized void markFlushed(String sessionId) {
        Round round = rounds.get(sessionId);
        if (round != null) markFlushed(sessionId, round.runId);
    }

    public synchronized void markFlushed(String sessionId, String runId) {
        Round round = rounds.get(sessionId);
        if (round == null || !round.runId.equals(runId)) return;
        ScheduledFuture<?> existing = pending.remove(sessionId);
        if (existing != null) {
            existing.cancel(false);
        }
        rounds.remove(sessionId);
        if (coordinator != null) {
            coordinator.releaseLease(sessionId, runId);
        }
    }

    /** Writes the accumulated round (if any) and closes the session into the interrupted state. */
    public synchronized void flush(String sessionId) {
        flush(sessionId, null);
    }

    public synchronized void flush(String sessionId, String expectedRunId) {
        Round round = rounds.get(sessionId);
        if (expectedRunId != null && (round == null || !round.runId.equals(expectedRunId))) return;
        ScheduledFuture<?> existing = pending.remove(sessionId);
        if (existing != null) {
            existing.cancel(false);
        }
        if (round == null || !hasContent(round)) {
            if (round != null) {
                rounds.remove(sessionId, round);
                if (coordinator != null) coordinator.releaseLease(sessionId, round.runId);
            }
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", round.text.toString());
            if (round.reasoning.length() > 0) payload.put("reasoning", round.reasoning.toString());
            payload.put("mode", round.mode);
            payload.put("domainName", round.domainName);
            payload.put("partsVersion", 1);
            payload.put("parts", round.parts.snapshot());
            Map<String, Object> presentation = round.presentation.snapshot();
            if (presentation == null)
                presentation =
                        Map.of(
                                "runId",
                                round.runId,
                                "outcome",
                                "unknown",
                                "finalMessageIds",
                                List.of());
            payload.put("presentationVersion", 1);
            payload.put("presentation", presentation);
            timeline.saveAdvisorRound(
                    sessionId, payload, materializeToolCalls(round), round.usage, null, round.turn);
            rounds.remove(sessionId, round);
            if (coordinator != null) coordinator.releaseLease(sessionId, round.runId);
            log.info("Flushed an abandoned advisor round for session {}", sessionId);
        } catch (SQLException | RuntimeException error) {
            // The scheduler swallows uncaught exceptions, so guard everything: log loudly
            // instead of losing the failure silently on the flush thread.
            log.warn("Failed to flush the abandoned round for session {}", sessionId, error);
        }
    }

    /** Force-flushes accumulators whose stream ended without any terminal event (hard cancel). */
    public void flushStale(long now) {
        for (Map.Entry<String, Round> entry : rounds.entrySet()) {
            if (entry.getValue().updatedAt < now - STALE_ACCUMULATOR_MILLIS
                    && !activeRunCheck.test(entry.getKey())) {
                recoveryGuard.test(
                        entry.getKey(), () -> flush(entry.getKey(), entry.getValue().runId));
            }
        }
    }

    private static boolean hasContent(Round round) {
        return round.text.length() > 0
                || round.reasoning.length() > 0
                || !round.toolCalls.isEmpty()
                || !round.usage.isEmpty();
    }

    private static List<Map<String, Object>> materializeToolCalls(Round round) {
        List<Map<String, Object>> calls = new ArrayList<>(round.toolCalls.size());
        for (Map.Entry<String, Map<String, Object>> entry : round.toolCalls.entrySet()) {
            Map<String, Object> call = new LinkedHashMap<>(entry.getValue());
            StringBuilder args = round.argsBuffers.get(entry.getKey());
            if (args != null && args.length() > 0) {
                call.put("args", args.toString());
            }
            calls.add(call);
        }
        return calls;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
