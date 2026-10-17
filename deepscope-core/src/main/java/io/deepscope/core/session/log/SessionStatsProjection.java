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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-session counts and wall times, derived from committed events.
 *
 * <p>This is what the conversation page's stats bar reads: turns, steps, model time, tool time,
 * first-token latency, decode time, and output tokens.
 *
 * <p>The step lifecycle is the counting authority, not the assembled message. The loop records
 * exactly one step close per entered step, so completed, failed, cancelled, and truncated steps all
 * count. Counting assembled messages instead would overcount a truncated step's empty message and
 * miss a cancelled step that never assembled one.
 *
 * <p>Wall-time figures pair recorded boundaries: model time is step start to assembled message,
 * first token is the first non-empty delta, decode spans first token to assembled message on steps
 * that also report output tokens, and tool time pairs a call with its result by call identity. A
 * cancelled step assembles no message, so its partial stream time stays uncounted rather than being
 * guessed at.
 */
public final class SessionStatsProjection {

    /** The state key this projection publishes. */
    public static final String KEY = "sessionStats";

    /** The state version, bumped when the fields or fold semantics change. */
    public static final long STATE_VERSION = 1L;

    /**
     * The accumulated figures.
     *
     * @param turns distinct turns with at least one closed step
     * @param steps closed steps
     * @param llmMs summed model wall time over message-assembling steps
     * @param toolMs summed matched call-to-result wall time
     * @param ttftMs summed first-token latency over {@code ttftSteps}
     * @param ttftSteps steps carrying a recorded first token
     * @param decodeMs summed decode wall time over usage-reporting steps
     * @param decodeTokens summed reported output tokens over the same steps
     */
    public record SessionStats(
            int turns,
            int steps,
            long llmMs,
            long toolMs,
            long ttftMs,
            int ttftSteps,
            long decodeMs,
            long decodeTokens) {

        /** The figures before any committed event. */
        public static SessionStats empty() {
            return new SessionStats(0, 0, 0L, 0L, 0L, 0, 0L, 0L);
        }
    }

    /** Contribute this projection to a registry. */
    public ProjectionRegistry.Registration register(ProjectionRegistry registry) {
        return registry.register(unit());
    }

    /**
     * The fold unit.
     *
     * @return the unit to register
     */
    public ProjectionRegistry.Unit<State> unit() {
        return new ProjectionRegistry.Unit<>() {
            @Override
            public String key() {
                return KEY;
            }

            @Override
            public long stateVersion() {
                return STATE_VERSION;
            }

            @Override
            public State init(SessionLog log) {
                return State.empty();
            }

            @Override
            public State apply(State state, SessionEvent event) {
                return fold(state, event);
            }
        };
    }

    /**
     * The fold state: the published figures plus the in-flight boundaries they accrue from.
     *
     * <p>Kept as its own type so the published view stays a strict subset, which is what a persisted
     * checkpoint stores and a carrier reads.
     */
    public record State(
            SessionStats totals, Long lastTurn, OpenStep openStep, Map<String, Long> pendingCalls) {

        public State {
            pendingCalls = Map.copyOf(pendingCalls);
        }

        /** The state before any committed event. */
        public static State empty() {
            return new State(SessionStats.empty(), null, null, Map.of());
        }

        /** The published figures. */
        public SessionStats view() {
            return totals;
        }
    }

    /**
     * The open step's boundary facts.
     *
     * @param turn the enclosing turn
     * @param step the step number
     * @param startTime when the step opened
     * @param firstTokenTime when the first non-empty delta arrived, or null
     */
    public record OpenStep(long turn, long step, long startTime, Long firstTokenTime) {}

    private static State fold(State state, SessionEvent event) {
        SessionEventData data = event.data();
        if (data instanceof SessionExecutionData.StepStart start) {
            return new State(
                    state.totals(),
                    state.lastTurn(),
                    new OpenStep(start.turn(), start.step(), event.time(), null),
                    state.pendingCalls());
        }
        if (data instanceof SessionExecutionData.AssistantAttempt attempt) {
            return firstTokenFrom(state, attempt.turn(), attempt.step(), attempt.stream());
        }
        if (data instanceof SessionEventData.AssistantMessageData assistant) {
            return assembled(state, event.time(), assistant);
        }
        if (data instanceof SessionExecutionData.ToolCall call) {
            Map<String, Long> pending = new HashMap<>(state.pendingCalls());
            pending.put(call.callId(), event.time());
            return new State(state.totals(), state.lastTurn(), state.openStep(), pending);
        }
        if (data instanceof SessionEventData.ToolResultData result) {
            return toolResult(state, event.time(), result);
        }
        if (data instanceof SessionExecutionData.StepEnd end) {
            boolean newTurn = state.lastTurn() == null || state.lastTurn() != end.turn();
            SessionStats totals = state.totals();
            SessionStats next =
                    new SessionStats(
                            totals.turns() + (newTurn ? 1 : 0),
                            totals.steps() + 1,
                            totals.llmMs(),
                            totals.toolMs(),
                            totals.ttftMs(),
                            totals.ttftSteps(),
                            totals.decodeMs(),
                            totals.decodeTokens());
            return new State(next, end.turn(), null, state.pendingCalls());
        }
        if (data instanceof SessionExecutionData.TurnEnd) {
            // A call whose result never landed belongs to a cancelled or failed turn, and results
            // always land inside their turn, so leftovers are dropped instead of accumulating.
            return state.pendingCalls().isEmpty()
                    ? state
                    : new State(state.totals(), state.lastTurn(), state.openStep(), Map.of());
        }
        return state;
    }

    /** Record the first non-empty delta of an open step, tolerating an in-step retry. */
    private static State firstTokenFrom(
            State state, long turn, long step, List<AssistantStreamRecord> stream) {
        OpenStep open = state.openStep();
        if (open == null || open.turn() != turn || open.step() != step) {
            return state;
        }
        Long first = firstTokenTime(stream);
        if (open.firstTokenTime() != null || first == null) {
            return state;
        }
        return new State(
                state.totals(),
                state.lastTurn(),
                new OpenStep(open.turn(), open.step(), open.startTime(), first),
                state.pendingCalls());
    }

    /**
     * Accrue model wall time for the open step's assembled message.
     *
     * <p>Closing the boundary here means a duplicate message cannot accrue twice.
     */
    private static State assembled(
            State state, long time, SessionEventData.AssistantMessageData assistant) {
        OpenStep open = state.openStep();
        if (open == null || open.turn() != assistant.turn() || open.step() != assistant.step()) {
            return state;
        }
        Long firstToken =
                open.firstTokenTime() != null
                        ? open.firstTokenTime()
                        : firstTokenTime(assistant.stream());
        SessionStats totals = state.totals();
        long llmMs = totals.llmMs() + Math.max(0L, time - open.startTime());
        long ttftMs = totals.ttftMs();
        int ttftSteps = totals.ttftSteps();
        long decodeMs = totals.decodeMs();
        long decodeTokens = totals.decodeTokens();
        if (firstToken != null) {
            ttftMs += Math.max(0L, firstToken - open.startTime());
            ttftSteps += 1;
            Long outputTokens = outputTokens(assistant.usage());
            if (outputTokens != null) {
                decodeMs += Math.max(0L, time - firstToken);
                decodeTokens += outputTokens;
            }
        }
        SessionStats next =
                new SessionStats(
                        totals.turns(),
                        totals.steps(),
                        llmMs,
                        totals.toolMs(),
                        ttftMs,
                        ttftSteps,
                        decodeMs,
                        decodeTokens);
        return new State(next, state.lastTurn(), null, state.pendingCalls());
    }

    private static State toolResult(
            State state, long time, SessionEventData.ToolResultData result) {
        Long dispatched = state.pendingCalls().get(result.callId());
        if (dispatched == null) {
            return state;
        }
        Map<String, Long> pending = new HashMap<>(state.pendingCalls());
        pending.remove(result.callId());
        SessionStats totals = state.totals();
        SessionStats next =
                new SessionStats(
                        totals.turns(),
                        totals.steps(),
                        totals.llmMs(),
                        totals.toolMs() + Math.max(0L, time - dispatched),
                        totals.ttftMs(),
                        totals.ttftSteps(),
                        totals.decodeMs(),
                        totals.decodeTokens());
        return new State(next, state.lastTurn(), state.openStep(), pending);
    }

    /**
     * When the first non-empty text delta arrived, expanded from the packed runs.
     *
     * @param stream the compact provider stream
     * @return the absolute time of the first non-empty delta, or null when none carried text
     */
    public static Long firstTokenTime(List<AssistantStreamRecord> stream) {
        for (AssistantStreamRecord record : stream) {
            if (record instanceof AssistantStreamRecord.TextChunks run) {
                long time = run.time0();
                for (int index = 0; index < run.texts().size(); index++) {
                    if (index > 0) {
                        time += run.dt().get(index - 1);
                    }
                    String delta = run.texts().get(index);
                    if (!delta.isEmpty()) {
                        return time;
                    }
                }
            }
        }
        return null;
    }

    private static Long outputTokens(SessionEventData.Usage usage) {
        return usage == null ? null : usage.outputTokens();
    }
}
