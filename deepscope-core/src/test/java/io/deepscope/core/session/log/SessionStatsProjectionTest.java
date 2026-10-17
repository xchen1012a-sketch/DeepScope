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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.deepscope.core.message.TextBlock;
import io.deepscope.core.message.ToolResultBlock;
import io.deepscope.core.message.ToolResultState;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the whole-session figures the conversation page's stats bar and context ring read.
 *
 * <p>The step lifecycle is the counting authority, not the assembled message: a cancelled step
 * assembles no message but still entered the step, and a max-tokens step assembles an empty message
 * that produces no surface node.
 */
class SessionStatsProjectionTest {

    private static final long BASE = 1_700_000_000_000L;

    private ProjectionRegistry registry;
    private SessionLog log;

    @BeforeEach
    void setUp() {
        registry = new ProjectionRegistry();
        registry.register(new SessionStatsProjection().unit());
        log = new SessionLog();
    }

    /** The published figures, which are a strict subset of the fold state. */
    private SessionStatsProjection.SessionStats stats() {
        SessionStatsProjection.State state = registry.stateOf(log, SessionStatsProjection.KEY);
        return state.totals();
    }

    private static SessionEventData.AssistantMessageData assistant(
            long turn,
            long step,
            long streamStart,
            List<Long> dt,
            List<String> deltas,
            Long outputTokens) {
        return new SessionEventData.AssistantMessageData(
                turn,
                step,
                "a-" + turn + "-" + step,
                List.of(TextBlock.builder().text("answer").build()),
                List.of(new AssistantStreamRecord.TextChunks(streamStart, 0, dt, deltas)),
                outputTokens == null
                        ? null
                        : new SessionEventData.Usage(0L, outputTokens, 0L, 0L, null),
                false);
    }

    @Test
    @DisplayName("an empty log reports zeroed figures")
    void emptyLogReportsZeroes() {
        SessionStatsProjection.SessionStats value = stats();

        assertEquals(0, value.turns());
        assertEquals(0, value.steps());
        assertEquals(0L, value.llmMs());
        assertEquals(0, value.ttftSteps());
    }

    @Test
    @DisplayName("a closed step counts one step in a new turn")
    void closedStepCountsOneStep() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        log.appendLogOnly(BASE, new SessionExecutionData.StepStart(1L, 1L));
        log.appendLogOnly(BASE, new SessionExecutionData.StepEnd(1L, 1L));

        assertEquals(1, stats().steps());
        assertEquals(1, stats().turns());
    }

    @Test
    @DisplayName("several steps in one turn count one turn")
    void severalStepsInOneTurnCountOneTurn() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        log.appendLogOnly(BASE, new SessionExecutionData.StepStart(1L, 1L));
        log.appendLogOnly(BASE, new SessionExecutionData.StepEnd(1L, 1L));
        log.appendLogOnly(BASE, new SessionExecutionData.StepStart(1L, 2L));
        log.appendLogOnly(BASE, new SessionExecutionData.StepEnd(1L, 2L));

        assertEquals(2, stats().steps());
        assertEquals(1, stats().turns());
    }

    @Test
    @DisplayName("steps in a later turn count another turn")
    void laterTurnCountsAnotherTurn() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        log.appendLogOnly(BASE, new SessionExecutionData.StepStart(1L, 1L));
        log.appendLogOnly(BASE, new SessionExecutionData.StepEnd(1L, 1L));
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(2L));
        log.appendLogOnly(BASE, new SessionExecutionData.StepStart(2L, 1L));
        log.appendLogOnly(BASE, new SessionExecutionData.StepEnd(2L, 1L));

        assertEquals(2, stats().turns());
        assertEquals(2, stats().steps());
    }

    @Test
    @DisplayName("an assembled message accrues model wall time")
    void assembledMessageAccruesModelTime() {
        log.appendLogOnly(BASE, new SessionExecutionData.StepStart(1L, 1L));
        log.append(
                BASE + 250,
                assistant(1L, 1L, BASE + 10, List.of(), List.of("done"), null),
                SurfaceOp.append(),
                List.of());

        assertEquals(250L, stats().llmMs());
    }

    @Test
    @DisplayName("first-token latency is measured to the first non-empty delta")
    void firstTokenLatencyIsMeasured() {
        log.appendLogOnly(BASE, new SessionExecutionData.StepStart(1L, 1L));
        log.append(
                BASE + 400,
                // The first delta is empty, so the second one defines the first token.
                assistant(1L, 1L, BASE + 100, List.of(50L), List.of("", "hello"), null),
                SurfaceOp.append(),
                List.of());

        assertEquals(150L, stats().ttftMs(), "the empty delta must not count as a token");
        assertEquals(1, stats().ttftSteps());
    }

    @Test
    @DisplayName("decode time and output tokens accrue from the first token to the message")
    void decodeTimeAndTokensAccrue() {
        log.appendLogOnly(BASE, new SessionExecutionData.StepStart(1L, 1L));
        log.append(
                BASE + 500,
                assistant(1L, 1L, BASE + 100, List.of(), List.of("hello"), 40L),
                SurfaceOp.append(),
                List.of());

        assertEquals(400L, stats().decodeMs());
        assertEquals(40L, stats().decodeTokens());
    }

    @Test
    @DisplayName("a step without a reported output-token count accrues no decode figures")
    void stepWithoutOutputTokensAccruesNoDecode() {
        log.appendLogOnly(BASE, new SessionExecutionData.StepStart(1L, 1L));
        log.append(
                BASE + 500,
                assistant(1L, 1L, BASE + 100, List.of(), List.of("hello"), null),
                SurfaceOp.append(),
                List.of());

        assertEquals(1, stats().ttftSteps(), "the first token is still recorded");
        assertEquals(0L, stats().decodeMs());
        assertEquals(0L, stats().decodeTokens());
    }

    @Test
    @DisplayName("a matched tool call accrues tool wall time")
    void matchedToolCallAccruesToolTime() {
        log.appendLogOnly(
                BASE, new SessionExecutionData.ToolCall(1L, 1L, "call-1", "search", "{}"));
        log.append(BASE + 320, toolResult("call-1", "search"), SurfaceOp.append(), List.of());

        assertEquals(320L, stats().toolMs());
    }

    @Test
    @DisplayName("a tool result with no recorded call accrues nothing")
    void unmatchedToolResultAccruesNothing() {
        log.append(BASE + 320, toolResult("ghost", "search"), SurfaceOp.append(), List.of());

        assertEquals(0L, stats().toolMs());
    }

    @Test
    @DisplayName("two calls pair with their own results")
    void twoCallsPairWithTheirOwnResults() {
        log.appendLogOnly(BASE, new SessionExecutionData.ToolCall(1L, 1L, "a", "one", "{}"));
        log.appendLogOnly(BASE + 50, new SessionExecutionData.ToolCall(1L, 1L, "b", "two", "{}"));
        log.append(BASE + 150, toolResult("b", "two"), SurfaceOp.append(), List.of());
        log.append(BASE + 400, toolResult("a", "one"), SurfaceOp.append(), List.of());

        assertEquals(100L + 400L, stats().toolMs());
    }

    @Test
    @DisplayName("a turn end drops tool calls whose result never landed")
    void turnEndDropsPendingCalls() {
        log.appendLogOnly(
                BASE, new SessionExecutionData.ToolCall(1L, 1L, "call-1", "search", "{}"));
        log.appendLogOnly(
                BASE,
                new SessionExecutionData.TurnEnd(1L, SessionExecutionData.TurnEndReason.ABORTED));

        // A late result from the same call must not accrue time after the turn closed.
        log.append(BASE + 900, toolResult("call-1", "search"), SurfaceOp.append(), List.of());

        assertEquals(0L, stats().toolMs());
    }

    @Test
    @DisplayName("a cancelled step counts as a step but accrues no model time")
    void cancelledStepCountsWithoutModelTime() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        log.appendLogOnly(BASE, new SessionExecutionData.StepStart(1L, 1L));
        log.appendLogOnly(BASE, new SessionExecutionData.StepEnd(1L, 1L));

        assertEquals(1, stats().steps());
        assertEquals(
                0L, stats().llmMs(), "a step that assembled no message has no model wall time");
    }

    @Test
    @DisplayName("an assembled message for another step does not accrue time")
    void messageForAnotherStepDoesNotAccrue() {
        log.appendLogOnly(BASE, new SessionExecutionData.StepStart(1L, 1L));
        log.append(
                BASE + 250,
                assistant(7L, 7L, BASE + 10, List.of(), List.of("elsewhere"), null),
                SurfaceOp.append(),
                List.of());

        assertEquals(0L, stats().llmMs());
    }

    @Test
    @DisplayName("an unrelated event returns the same state reference")
    void unrelatedEventKeepsStateIdentity() {
        SessionStatsProjection.SessionStats before = stats();
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));

        assertSame(before, stats(), "the registry relies on identity to skip notifications");
    }

    @Test
    @DisplayName("the projection declares a stable key and version")
    void projectionDeclaresKeyAndVersion() {
        assertEquals("sessionStats", SessionStatsProjection.KEY);
        assertEquals(1L, new SessionStatsProjection().unit().stateVersion());
        assertEquals(
                java.util.Optional.of(1L), registry.stateVersionOf(SessionStatsProjection.KEY));
    }

    private static SessionEventData.ToolResultData toolResult(String callId, String name) {
        return new SessionEventData.ToolResultData(
                callId,
                name,
                List.of(
                        ToolResultBlock.builder()
                                .id(callId)
                                .name(name)
                                .output(TextBlock.builder().text("ok").build())
                                .state(ToolResultState.SUCCESS)
                                .build()),
                false,
                null,
                null);
    }
}
