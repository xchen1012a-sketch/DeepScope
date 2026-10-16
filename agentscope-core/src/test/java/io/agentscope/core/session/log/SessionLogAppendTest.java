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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the append and sequencing invariants of {@link SessionLog}.
 *
 * <p>These are the guarantees the rest of the subsystem is allowed to assume: positions are
 * contiguous from zero, a rejected append changes nothing, and the log is the only source of derived
 * history.
 */
class SessionLogAppendTest {

    private static final long TIME = 1_700_000_000_000L;

    private static TextBlock text(String value) {
        return TextBlock.builder().text(value).build();
    }

    private static SessionEventData.UserMessageData user(String id, String value) {
        return SessionEventData.UserMessageData.of(id, List.of(text(value)));
    }

    @Test
    @DisplayName("positions are contiguous from zero and equal the log length")
    void positionsAreContiguousFromZero() {
        SessionLog log = new SessionLog();

        SessionEvent first = log.append(TIME, user("m1", "hello"), SurfaceOp.append(), List.of());
        SessionEvent second = log.append(TIME, user("m2", "again"), SurfaceOp.append(), List.of());

        assertEquals(SessionSeq.Seq.of(0), first.seq());
        assertEquals(SessionSeq.Seq.of(1), second.seq());
        assertEquals(2, log.size());
        assertEquals(SessionSeq.Seq.of(2), log.nextSeq());
        assertEquals(SessionSeq.Cursor.of(1), log.lastSeq());
    }

    @Test
    @DisplayName("an empty log has no last event")
    void emptyLogHasCursorWithoutEvent() {
        SessionLog log = new SessionLog();

        assertEquals(0, log.size());
        assertFalse(log.lastSeq().isPresent());
        assertEquals(-1, log.lastSeq().value());
        assertThrows(IllegalStateException.class, () -> log.lastSeq().require());
    }

    @Test
    @DisplayName("out-of-order adoption is rejected and leaves the log unchanged")
    void outOfOrderAdoptionIsRejected() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "hello"), SurfaceOp.append(), List.of());

        SessionEvent skipped =
                SessionEvent.surface(
                        SessionSeq.Seq.of(5),
                        TIME,
                        user("m2", "later"),
                        SurfaceOp.append(),
                        List.of());

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> log.append(skipped));

        assertTrue(failure.getMessage().contains("not contiguous"), failure.getMessage());
        assertEquals(1, log.size());
        assertEquals(List.of(SessionSeq.Seq.of(0)), log.surfaceNodes());
    }

    @Test
    @DisplayName("a batch is atomic: one bad event commits none of them")
    void batchIsAtomic() {
        SessionLog log = new SessionLog();
        SessionEvent good =
                SessionEvent.surface(
                        SessionSeq.Seq.of(0), TIME, user("m1", "a"), SurfaceOp.append(), List.of());
        SessionEvent bad =
                SessionEvent.surface(
                        SessionSeq.Seq.of(2), TIME, user("m2", "b"), SurfaceOp.append(), List.of());

        assertThrows(IllegalArgumentException.class, () -> log.appendAll(List.of(good, bad)));

        assertEquals(0, log.size(), "a rejected batch commits nothing, not even its valid prefix");
        assertEquals(List.of(), log.surfaceNodes());

        SessionEvent after = log.append(TIME, user("m3", "c"), SurfaceOp.append(), List.of());
        assertEquals(SessionSeq.Seq.of(0), after.seq(), "the log stays usable after a rejection");
    }

    @Test
    @DisplayName("a batch that replaces part of the surface is rolled back together")
    void batchRollbackRestoresSurface() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "hello"), SurfaceOp.append(), List.of());

        SessionEvent replacement =
                SessionEvent.surface(
                        SessionSeq.Seq.of(1),
                        TIME,
                        user("m2", "rewritten"),
                        SurfaceOp.replace(SessionSeq.Seq.of(0), SessionSeq.Seq.of(0)),
                        List.of(SessionSeq.Seq.of(0)));
        SessionEvent invalid =
                SessionEvent.surface(
                        SessionSeq.Seq.of(3), TIME, user("m3", "x"), SurfaceOp.append(), List.of());

        assertThrows(
                IllegalArgumentException.class, () -> log.appendAll(List.of(replacement, invalid)));

        assertEquals(1, log.size());
        assertEquals(List.of(SessionSeq.Seq.of(0)), log.surfaceNodes(), "the surface is unchanged");
        assertEquals(
                0,
                log.replaceGeneration(),
                "a rolled-back replacement does not advance a generation");
    }

    @Test
    @DisplayName("a surface-eligible event requires a surface operation")
    void surfaceEligibleRequiresOperation() {
        SessionLog log = new SessionLog();

        IllegalArgumentException atRestore =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                SessionEvent.restore(
                                        SessionSeq.Seq.of(0),
                                        TIME,
                                        user("m1", "hi"),
                                        null,
                                        List.of(),
                                        false));
        assertTrue(atRestore.getMessage().contains("requires a surfaceOp"), atRestore.getMessage());

        SessionEvent withoutOp =
                new SessionEvent(
                        SessionSeq.Seq.of(0),
                        TIME,
                        SessionEventTypes.USER_MESSAGE,
                        user("m1", "hi"),
                        null,
                        List.of(),
                        false);
        IllegalArgumentException atAppend =
                assertThrows(IllegalArgumentException.class, () -> log.append(withoutOp));
        assertTrue(atAppend.getMessage().contains("requires a surfaceOp"), atAppend.getMessage());

        assertEquals(0, log.size());
    }

    @Test
    @DisplayName("a log-only event cannot carry a surface operation")
    void logOnlyEventCannotCarrySurfaceOperation() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SessionEvent.restore(
                                SessionSeq.Seq.of(0),
                                TIME,
                                new SessionExecutionData.TurnStart(1L),
                                SurfaceOp.append(),
                                List.of(),
                                false));
    }

    @Test
    @DisplayName("source event references must be unique and earlier")
    void sourceEventReferencesMustBeUniqueAndEarlier() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "hello"), SurfaceOp.append(), List.of());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        log.append(
                                TIME,
                                user("m2", "dup"),
                                SurfaceOp.append(),
                                List.of(SessionSeq.Seq.of(0), SessionSeq.Seq.of(0))),
                "duplicate source references are rejected");

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        log.append(
                                TIME,
                                user("m3", "future"),
                                SurfaceOp.append(),
                                List.of(SessionSeq.Seq.of(7))),
                "a source reference must precede the citing event");
    }

    @Test
    @DisplayName("the envelope rejects a payload whose type disagrees with the event type")
    void envelopeRejectsMismatchedPayload() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SessionEvent(
                                SessionSeq.Seq.of(0),
                                TIME,
                                SessionEventTypes.SYSTEM_MESSAGE,
                                user("m1", "hello"),
                                SurfaceOp.append(),
                                List.of(),
                                false));
    }

    @Test
    @DisplayName("a log-only payload may carry null values, because JSON records have them")
    void logOnlyPayloadMayCarryNulls() {
        SessionLog log = new SessionLog();
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("model", "fake");
        fields.put("usage", null);
        fields.put("finishReason", null);

        log.appendLogOnly(TIME, new SessionEventData.PayloadData("model/end", fields));

        SessionEventData.PayloadData stored =
                (SessionEventData.PayloadData) log.eventAt(SessionSeq.Seq.of(0)).data();
        assertEquals("fake", stored.fields().get("model"));
        assertTrue(
                stored.fields().containsKey("usage"),
                "a null-valued field is a recorded fact, not an absent one: " + stored.fields());
        assertNull(stored.fields().get("usage"));
    }

    @Test
    @DisplayName("a catalogued type cannot be marked ignorable")
    void cataloguedTypeCannotBeIgnorable() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SessionEvent(
                                SessionSeq.Seq.of(0),
                                TIME,
                                SessionEventTypes.TURN_START,
                                new SessionExecutionData.TurnStart(1L),
                                null,
                                List.of(),
                                true));
    }

    @Test
    @DisplayName("an assistant message cannot cite source events")
    void assistantMessageCannotCiteSources() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SessionEvent.surface(
                                SessionSeq.Seq.of(0),
                                TIME,
                                new SessionEventData.AssistantMessageData(
                                        0L, 0L, "a1", List.of(text("hi")), List.of(), null, false),
                                SurfaceOp.append(),
                                List.of(SessionSeq.Seq.of(0))));
    }

    @Test
    @DisplayName("log-only events do not enter the surface or the derived history")
    void logOnlyEventsStayOutOfHistory() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
        log.append(TIME, user("m1", "hello"), SurfaceOp.append(), List.of());
        log.appendLogOnly(
                TIME,
                new SessionExecutionData.TurnEnd(1L, SessionExecutionData.TurnEndReason.COMPLETED));

        assertEquals(3, log.size());
        assertEquals(List.of(SessionSeq.Seq.of(1)), log.surfaceNodes());
        assertEquals(1, log.deriveMessages().size());
    }

    @Test
    @DisplayName("derived history is a fresh read-only list over stable message objects")
    void derivedHistoryIsStableAcrossReads() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "hello"), SurfaceOp.append(), List.of());

        List<io.agentscope.core.message.Msg> first = log.deriveMessages();
        List<io.agentscope.core.message.Msg> second = log.deriveMessages();

        assertEquals(first, second);
        assertThrows(UnsupportedOperationException.class, () -> first.add(null));
    }

    @Test
    @DisplayName(
            "empty message-producing nodes project to no message but keep their surface position")
    void emptyNodesProjectToNoMessage() {
        SessionLog log = new SessionLog();
        log.append(
                TIME,
                new SessionEventData.SystemMessageData(List.of()),
                SurfaceOp.append(),
                List.of());
        log.append(TIME, user("m1", "hello"), SurfaceOp.append(), List.of());

        assertEquals(2, log.surfaceNodes().size(), "an empty node still occupies the surface");
        assertEquals(1, log.deriveMessages().size(), "but it contributes no wire message");
    }

    @Test
    @DisplayName("a tool result derives a tool-role message carrying its call identity")
    void toolResultDerivesToolMessage() {
        SessionLog log = new SessionLog();
        ToolResultBlock result =
                ToolResultBlock.builder()
                        .id("call-1")
                        .name("search")
                        .output(text("found"))
                        .state(ToolResultState.SUCCESS)
                        .build();
        log.append(
                TIME,
                new SessionEventData.ToolResultData(
                        "call-1", "search", List.of(result), false, null, null),
                SurfaceOp.append(),
                List.of());

        io.agentscope.core.message.Msg derived = log.deriveMessages().get(0);

        assertEquals(io.agentscope.core.message.MsgRole.TOOL, derived.getRole());
        assertEquals(
                "call-1",
                derived.getMetadata()
                        .get(io.agentscope.core.message.MessageMetadataKeys.SESSION_MESSAGE_ID));
    }

    @Test
    @DisplayName("a tool failure identity requires the error flag")
    void toolFailureRequiresErrorFlag() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SessionEventData.ToolResultData(
                                "call-1",
                                "search",
                                List.of(),
                                false,
                                new SessionEventData.ToolFailure("Boom", "boom", null),
                                null));
    }

    @Test
    @DisplayName("reasoning is first-class content of a settled assistant message")
    void reasoningIsFirstClassAssistantContent() {
        SessionLog log = new SessionLog();
        ThinkingBlock thinking = ThinkingBlock.builder().thinking("weighing options").build();

        log.append(
                TIME,
                new SessionEventData.AssistantMessageData(
                        0L, 0L, "a1", List.of(thinking, text("answer")), List.of(), null, false),
                SurfaceOp.append(),
                List.of());

        io.agentscope.core.message.Msg derived = log.deriveMessages().get(0);

        assertEquals(2, derived.getContent().size());
        assertTrue(derived.getContent().get(0) instanceof ThinkingBlock);
        assertNotNull(derived.getContent().get(0));
        assertEquals(
                "weighing options", ((ThinkingBlock) derived.getContent().get(0)).getThinking());
    }

    @Test
    @DisplayName("reading outside the log yields nothing rather than an error")
    void readOutsideLogYieldsNothing() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "hello"), SurfaceOp.append(), List.of());

        assertNotNull(log.eventAt(SessionSeq.Seq.of(0)));
        assertNull(log.eventAt(SessionSeq.Seq.of(1)));
    }

    @Test
    @DisplayName("projected events keep their recorded order in the log")
    void projectedEventsKeepRecordedOrder() {
        SessionLog log = new SessionLog();
        SessionEvent event = log.append(TIME, user("m1", "hello"), SurfaceOp.append(), List.of());

        assertSame(event, log.eventAt(SessionSeq.Seq.of(0)));
        assertEquals(List.of(event), log.events());
    }
}
