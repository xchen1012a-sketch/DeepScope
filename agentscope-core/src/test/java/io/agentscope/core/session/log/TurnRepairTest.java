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
package io.agentscope.core.session.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins tail repair: closing a turn a crash or fork left open.
 *
 * <p>Repair supplies only what is missing. A recorded tool request without a durable result must not
 * be silently retried, so the synthetic result states what the log does and does not know.
 */
class TurnRepairTest {

    private static final long TIME = 1_700_000_000_000L;

    private static TextBlock text(String value) {
        return TextBlock.builder().text(value).build();
    }

    private static SessionEventData.AssistantMessageData assistantWithToolCall(String callId) {
        return new SessionEventData.AssistantMessageData(
                1L,
                1L,
                "a1",
                List.of(
                        ToolUseBlock.builder()
                                .id(callId)
                                .name("create_order")
                                .input(java.util.Map.of("sku", "x"))
                                .build()),
                List.of(),
                null,
                false);
    }

    private static SessionLog openTurnLog() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
        log.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));
        return log;
    }

    @Test
    @DisplayName("every way a turn can end has a reason the kernel can record")
    void everyTurnOutcomeHasAReason() {
        // The recording protocol's status strings must all map onto the kernel's reasons, or a
        // turn that ended one way would have to be recorded as having ended another.
        for (String status :
                java.util.List.of(
                        "completed",
                        "suspended",
                        "failed",
                        "cancelled",
                        "interrupted",
                        "blocked",
                        "max_tokens")) {
            assertTrue(
                    TurnRepair.reasonForStatus(status).isPresent(),
                    "the kernel must be able to record a turn that ended as " + status);
        }
        assertEquals(
                SessionExecutionData.TurnEndReason.SUSPENDED,
                TurnRepair.reasonForStatus("suspended").orElseThrow(),
                "a turn waiting for a person is not the same outcome as a blocked turn");
        assertTrue(
                TurnRepair.reasonForStatus("unknown-outcome").isEmpty(),
                "an unrecognized status must be reported rather than guessed");
    }

    @Test
    @DisplayName("a balanced log needs no repair")
    void balancedLogNeedsNoRepair() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
        log.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));
        log.appendLogOnly(TIME, new SessionExecutionData.StepEnd(1L, 1L));
        log.appendLogOnly(
                TIME,
                new SessionExecutionData.TurnEnd(1L, SessionExecutionData.TurnEndReason.COMPLETED));

        assertEquals(List.of(), TurnRepair.interruptedTurnClosers(log.events()));
    }

    @Test
    @DisplayName("an empty log needs no repair")
    void emptyLogNeedsNoRepair() {
        assertEquals(List.of(), TurnRepair.interruptedTurnClosers(List.of()));
    }

    @Test
    @DisplayName("a log whose turn is closed by a later turn start needs no repair")
    void laterTurnStartBalancesTheLog() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
        log.appendLogOnly(
                TIME,
                new SessionExecutionData.TurnEnd(1L, SessionExecutionData.TurnEndReason.COMPLETED));

        assertEquals(List.of(), TurnRepair.interruptedTurnClosers(log.events()));
    }

    @Test
    @DisplayName("an open turn is closed with an interrupted turn end")
    void openTurnIsClosedAsInterrupted() {
        SessionLog log = openTurnLog();

        List<SessionEvent> closers = TurnRepair.interruptedTurnClosers(log.events());

        SessionExecutionData.TurnEnd end =
                (SessionExecutionData.TurnEnd) closers.get(closers.size() - 1).data();
        assertEquals(1L, end.turn());
        assertEquals(SessionExecutionData.TurnEndReason.INTERRUPTED, end.reason());
    }

    @Test
    @DisplayName("an open step is closed before its turn")
    void openStepClosesBeforeItsTurn() {
        SessionLog log = openTurnLog();

        List<SessionEvent> closers = TurnRepair.interruptedTurnClosers(log.events());

        assertEquals(2, closers.size());
        assertTrue(closers.get(0).data() instanceof SessionExecutionData.StepEnd);
        assertTrue(closers.get(1).data() instanceof SessionExecutionData.TurnEnd);
    }

    @Test
    @DisplayName("a tool request with no recorded start becomes a not-started error result")
    void requestWithoutRecordedStartBecomesNotStarted() {
        SessionLog log = openTurnLog();
        log.append(TIME, assistantWithToolCall("call-1"), SurfaceOp.append(), List.of());

        List<SessionEvent> closers = TurnRepair.interruptedTurnClosers(log.events());

        SessionEventData.ToolResultData result =
                (SessionEventData.ToolResultData) closers.get(0).data();
        assertEquals("call-1", result.callId());
        assertEquals("create_order", result.toolName());
        assertTrue(result.isError());
        assertEquals("ToolNotStartedError", result.error().name());
        assertEquals(TurnRepair.TOOL_NOT_STARTED, result.error().code());
        assertTrue(
                textOf(result).contains("interrupted before"),
                "the wording states the call never reached a recorded start: " + textOf(result));
    }

    @Test
    @DisplayName("a recorded call without a result becomes an outcome-unknown error result")
    void recordedCallWithoutResultBecomesOutcomeUnknown() {
        SessionLog log = openTurnLog();
        log.append(TIME, assistantWithToolCall("call-1"), SurfaceOp.append(), List.of());
        log.appendLogOnly(
                TIME, new SessionExecutionData.ToolCall(1L, 1L, "call-1", "create_order", "{}"));

        List<SessionEvent> closers = TurnRepair.interruptedTurnClosers(log.events());

        SessionEventData.ToolResultData result =
                (SessionEventData.ToolResultData) closers.get(0).data();
        assertEquals("ToolOutcomeUnknownError", result.error().name());
        assertEquals(TurnRepair.TOOL_OUTCOME_UNKNOWN, result.error().code());
        assertTrue(
                textOf(result).contains("outcome is unknown"),
                "the wording states the outcome is unknown: " + textOf(result));
    }

    @Test
    @DisplayName("the outcome-unknown result cites the recorded call as its source")
    void outcomeUnknownCitesTheRecordedCall() {
        SessionLog log = openTurnLog();
        log.append(TIME, assistantWithToolCall("call-1"), SurfaceOp.append(), List.of());
        log.appendLogOnly(
                TIME, new SessionExecutionData.ToolCall(1L, 1L, "call-1", "create_order", "{}"));

        List<SessionEvent> closers = TurnRepair.interruptedTurnClosers(log.events());

        assertEquals(List.of(SessionSeq.Seq.of(3)), closers.get(0).sourceEventSeqs());
    }

    @Test
    @DisplayName("a not-started result cites no source")
    void notStartedCitesNoSource() {
        SessionLog log = openTurnLog();
        log.append(TIME, assistantWithToolCall("call-1"), SurfaceOp.append(), List.of());

        List<SessionEvent> closers = TurnRepair.interruptedTurnClosers(log.events());

        assertEquals(List.of(), closers.get(0).sourceEventSeqs());
    }

    @Test
    @DisplayName("a recorded result removes the pending request")
    void recordedResultRemovesPendingRequest() {
        SessionLog log = openTurnLog();
        log.append(TIME, assistantWithToolCall("call-1"), SurfaceOp.append(), List.of());
        log.appendLogOnly(
                TIME, new SessionExecutionData.ToolCall(1L, 1L, "call-1", "create_order", "{}"));
        log.append(
                TIME,
                new SessionEventData.ToolResultData(
                        "call-1",
                        "create_order",
                        List.of(
                                ToolResultBlock.builder()
                                        .id("call-1")
                                        .name("create_order")
                                        .output(text("ORD-42"))
                                        .state(ToolResultState.SUCCESS)
                                        .build()),
                        false,
                        null,
                        null),
                SurfaceOp.append(),
                List.of());

        List<SessionEvent> closers = TurnRepair.interruptedTurnClosers(log.events());

        assertEquals(2, closers.size(), "only the open step and turn remain to close");
        assertTrue(closers.get(0).data() instanceof SessionExecutionData.StepEnd);
    }

    @Test
    @DisplayName("synthetic closers continue the log sequence and reuse the last timestamp")
    void closersContinueSequenceAndReuseTime() {
        SessionLog log = openTurnLog();
        log.append(TIME, assistantWithToolCall("call-1"), SurfaceOp.append(), List.of());

        List<SessionEvent> closers = TurnRepair.interruptedTurnClosers(log.events());

        long next = log.size();
        for (SessionEvent closer : closers) {
            assertEquals(next++, closer.seq().value(), "closers continue the committed sequence");
            assertEquals(TIME, closer.time(), "closers reuse the last real timestamp");
        }
    }

    @Test
    @DisplayName("a fork cause states that the parent may have completed the call")
    void forkCauseStatesTheParentMayHaveCompleted() {
        SessionLog log = openTurnLog();
        log.append(TIME, assistantWithToolCall("call-1"), SurfaceOp.append(), List.of());
        log.appendLogOnly(
                TIME, new SessionExecutionData.ToolCall(1L, 1L, "call-1", "create_order", "{}"));

        List<SessionEvent> closers =
                TurnRepair.openTurnClosers(log.events(), TurnRepair.CloseCause.FORKED);

        SessionEventData.ToolResultData result =
                (SessionEventData.ToolResultData) closers.get(0).data();
        assertEquals("ToolOutcomeUnknownError", result.error().name());
        assertTrue(
                textOf(result).contains("parent session may have"),
                "the fork wording names the parent: " + textOf(result));

        SessionExecutionData.TurnEnd end =
                (SessionExecutionData.TurnEnd) closers.get(closers.size() - 1).data();
        assertEquals(SessionExecutionData.TurnEndReason.FORKED, end.reason());
    }

    @Test
    @DisplayName("a closed step discards its pending requests")
    void closedStepDiscardsPendingRequests() {
        SessionLog log = openTurnLog();
        log.append(TIME, assistantWithToolCall("call-1"), SurfaceOp.append(), List.of());
        log.appendLogOnly(TIME, new SessionExecutionData.StepEnd(1L, 1L));

        List<SessionEvent> closers = TurnRepair.interruptedTurnClosers(log.events());

        assertEquals(1, closers.size());
        assertTrue(
                closers.get(0).data() instanceof SessionExecutionData.TurnEnd,
                "a closed step's unanswered request is not repaired");
    }

    @Test
    @DisplayName("repair produces a log the fold accepts")
    void repairProducesAppendableClosers() {
        SessionLog log = openTurnLog();
        log.append(TIME, assistantWithToolCall("call-1"), SurfaceOp.append(), List.of());
        log.appendLogOnly(
                TIME, new SessionExecutionData.ToolCall(1L, 1L, "call-1", "create_order", "{}"));

        List<SessionEvent> closers = TurnRepair.interruptedTurnClosers(log.events());
        log.appendAll(closers);

        assertFalse(closers.isEmpty());
        assertEquals(log.size(), log.events().size());
        assertTrue(
                log.deriveMessages().size() >= 2, "the repaired history includes the error result");
    }

    /** Text carried by the first output block of a synthetic tool result. */
    private static String textOf(SessionEventData.ToolResultData result) {
        ToolResultBlock block = (ToolResultBlock) result.content().get(0);
        return ((TextBlock) block.getOutput().get(0)).getText();
    }
}
