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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins how derived history is cached, invalidated, and extended by message projections.
 *
 * <p>Derived history is a pure function of the log, so the observable contract is: the same log
 * yields the same messages, appending leaves already-derived messages untouched, and an extension
 * that rewrites content must be interpretable or the log refuses the event.
 */
class DerivedHistoryTest {

    private static final long TIME = 1_700_000_000_000L;

    private static TextBlock text(String value) {
        return TextBlock.builder().text(value).build();
    }

    private static SessionEventData.UserMessageData user(String id, String value) {
        return SessionEventData.UserMessageData.of(id, List.of(text(value)));
    }

    @Test
    @DisplayName("deriving twice over an unchanged log returns the same message objects")
    void repeatedDerivationIsStable() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "hello"), SurfaceOp.append(), List.of());

        Msg first = log.deriveMessages().get(0);
        Msg second = log.deriveMessages().get(0);

        assertSame(first, second, "an unchanged log must not rebuild its messages");
    }

    @Test
    @DisplayName("appending a message keeps earlier derived messages identical")
    void appendKeepsEarlierMessagesIdentical() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "hello"), SurfaceOp.append(), List.of());
        Msg earlier = log.deriveMessages().get(0);

        log.append(
                TIME,
                new SessionEventData.AssistantMessageData(
                        0L, 0L, "a1", List.of(text("hi")), List.of(), null, false),
                SurfaceOp.append(),
                List.of());

        List<Msg> after = log.deriveMessages();
        assertEquals(2, after.size());
        assertSame(earlier, after.get(0), "an append must not disturb existing derived history");
    }

    @Test
    @DisplayName("a replacement changes the derived history it shadows")
    void replacementChangesDerivedHistory() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "original"), SurfaceOp.append(), List.of());
        assertEquals("original", log.deriveMessages().get(0).getTextContent());

        log.append(
                TIME,
                user("summary", "condensed"),
                SurfaceOp.replace(SessionSeq.Seq.of(0), SessionSeq.Seq.of(0)),
                List.of(SessionSeq.Seq.of(0)));

        assertEquals("condensed", log.deriveMessages().get(0).getTextContent());
    }

    @Test
    @DisplayName("deriving a single event applies the same rules as the full fold")
    void deriveEventMessageMatchesFold() {
        SessionLog log = new SessionLog();
        SessionEvent userEvent =
                log.append(TIME, user("m1", "hello"), SurfaceOp.append(), List.of());
        SessionEvent boundary = log.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
        SessionEvent system =
                log.append(
                        TIME,
                        new SessionEventData.SystemMessageData(List.of(text("be helpful"))),
                        SurfaceOp.append(),
                        List.of());

        assertSame(
                log.deriveMessages().get(0),
                log.deriveEventMessage(userEvent),
                "the per-event projection agrees with the folded history");
        assertNull(log.deriveEventMessage(boundary), "a log-only event derives no message");
        assertEquals("be helpful", log.deriveEventMessage(system).getTextContent());
    }

    @Test
    @DisplayName("a tool failure identity stays out of the model-facing content")
    void toolFailureIdentityStaysOutOfContent() {
        SessionLog log = new SessionLog();
        log.append(
                TIME,
                new SessionEventData.ToolResultData(
                        "call-1",
                        "create_order",
                        List.of(
                                io.agentscope.core.message.ToolResultBlock.builder()
                                        .id("call-1")
                                        .name("create_order")
                                        .output(text("could not reach the order service"))
                                        .state(io.agentscope.core.message.ToolResultState.ERROR)
                                        .build()),
                        true,
                        new SessionEventData.ToolFailure(
                                "OrderServiceUnavailable", "E_UNAVAILABLE", "upstream timeout"),
                        null),
                SurfaceOp.append(),
                List.of());

        Msg derived = log.deriveMessages().get(0);

        assertEquals(
                "could not reach the order service",
                ((io.agentscope.core.message.ToolResultBlock) derived.getContent().get(0))
                        .getOutput().stream()
                                .map(block -> ((TextBlock) block).getText())
                                .findFirst()
                                .orElseThrow());
        assertTrue(
                derived.getTextContent() == null
                        || !derived.getTextContent().contains("upstream timeout"),
                "the user-facing failure reason is not part of model content");
    }

    @Test
    @DisplayName("an extension projection rewrites derived content without rewriting the log")
    void extensionProjectionRewritesContent() {
        SessionMessageProjection redactor =
                new SessionMessageProjection() {
                    @Override
                    public String type() {
                        return "test/redact";
                    }

                    @Override
                    public Map<SessionSeq.Seq, Msg> project(SessionEvent event, Context context) {
                        SessionSeq.Seq target = SessionSeq.Seq.of(0);
                        Msg original = MessageDerivation.derive(context.events().get(0));
                        return Map.of(target, original.withContent(List.of(text("[redacted]"))));
                    }
                };

        SessionLog log = new SessionLog(List.of(redactor));
        log.append(TIME, user("m1", "secret"), SurfaceOp.append(), List.of());

        log.appendLogOnly(
                TIME, new SessionEventData.PayloadData("test/redact", Map.of("target", 0L)));

        assertEquals(
                "[redacted]",
                log.deriveMessages().get(0).getTextContent(),
                "the projection replaces derived content");
        assertEquals(
                "secret",
                ((SessionEventData.UserMessageData) log.eventAt(SessionSeq.Seq.of(0)).data())
                        .content().stream()
                                .map(block -> ((TextBlock) block).getText())
                                .findFirst()
                                .orElseThrow(),
                "the recorded content is unchanged");
        assertEquals(
                List.of(SessionSeq.Seq.of(0)),
                log.surfaceNodes(),
                "a projected log-only event never joins the surface");
    }

    @Test
    @DisplayName("a duplicated projection for one event type is rejected at construction")
    void duplicateProjectionIsRejected() {
        SessionMessageProjection first = projectionFor("test/dup");
        SessionMessageProjection second = projectionFor("test/dup");

        assertThrows(IllegalArgumentException.class, () -> new SessionLog(List.of(first, second)));
    }

    private static SessionMessageProjection projectionFor(String type) {
        return new SessionMessageProjection() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Map<SessionSeq.Seq, Msg> project(SessionEvent event, Context context) {
                return Map.of(
                        SessionSeq.Seq.of(0), MessageDerivation.derive(context.events().get(0)));
            }
        };
    }

    @Test
    @DisplayName("reasoning blocks survive into the derived message as content")
    void reasoningBlocksSurviveDerivation() {
        SessionLog log = new SessionLog();
        log.append(
                TIME,
                new SessionEventData.AssistantMessageData(
                        0L,
                        0L,
                        "a1",
                        List.of(ThinkingBlock.builder().thinking("step one").build(), text("done")),
                        List.of(),
                        null,
                        false),
                SurfaceOp.append(),
                List.of());

        Msg derived = log.deriveMessages().get(0);

        assertEquals(2, derived.getContent().size());
        assertTrue(derived.getContent().get(0) instanceof ThinkingBlock);
    }

    @Test
    @DisplayName("tool result derivation preserves recorded messageId across independent logs")
    void toolResultDerivationPreservesMessageId() {
        SessionEvent event =
                SessionEvent.surface(
                        SessionSeq.Seq.of(0),
                        TIME,
                        new SessionEventData.ToolResultData(
                                "msg-tool-1",
                                "search",
                                List.of(
                                        io.agentscope.core.message.ToolResultBlock.builder()
                                                .id("call-1")
                                                .name("search")
                                                .output(text("result"))
                                                .build()),
                                false,
                                null,
                                Map.of("messageId", "msg-tool-1")),
                        SurfaceOp.append(),
                        List.of());

        Msg first = MessageDerivation.derive(event);
        Msg second = MessageDerivation.derive(event);

        assertEquals("msg-tool-1", first.getId());
        assertEquals(first.getId(), second.getId());
    }

    @Test
    @DisplayName("resumed durable session does not re-append historical tool results on checkpoint")
    void resumedDurableSessionDoesNotReappendToolResults(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        NativeSessionLogStore store = new NativeSessionLogStore(new LocalSessionStore(tempDir));
        io.agentscope.core.state.AgentState turn1State =
                io.agentscope.core.state.AgentState.builder().build();
        turn1State
                .contextMutable()
                .add(
                        io.agentscope.core.message.UserMessage.builder()
                                .id("u1")
                                .content(List.of(text("hello")))
                                .build());
        turn1State
                .contextMutable()
                .add(
                        io.agentscope.core.message.AssistantMessage.builder()
                                .id("a1")
                                .content(
                                        List.of(
                                                io.agentscope.core.message.ToolUseBlock.builder()
                                                        .id("call-1")
                                                        .name("search")
                                                        .input(Map.of())
                                                        .build()))
                                .build());
        turn1State
                .contextMutable()
                .add(
                        io.agentscope.core.message.ToolResultMessage.builder()
                                .id("tr-1")
                                .result(
                                        io.agentscope.core.message.ToolResultBlock.builder()
                                                .id("call-1")
                                                .name("search")
                                                .output(text("found"))
                                                .build())
                                .build());
        turn1State
                .contextMutable()
                .add(
                        io.agentscope.core.message.AssistantMessage.builder()
                                .id("a2")
                                .content(List.of(text("done")))
                                .build());

        try (DurableSession turn1 = store.openWriter("s1", "run-1", "turn-1")) {
            turn1.checkpointNow(turn1State, "run_completed");
        }

        try (DurableSession turn2 = store.openWriter("s1", "run-2", "turn-2")) {
            io.agentscope.core.state.AgentState turn2State =
                    turn2.workingState(io.agentscope.core.state.AgentState.builder().build());
            turn2.checkpointNow(turn2State, "before_model");

            long toolResultEventCount =
                    turn2.kernel().events().stream()
                            .filter(e -> e.data() instanceof SessionEventData.ToolResultData)
                            .count();
            assertEquals(
                    1L,
                    toolResultEventCount,
                    "historical tool result must not be re-appended on subsequent turn checkpoint");
        }
    }

    @Test
    @DisplayName("history skips duplicate orphan tool result events for already-answered calls")
    void historySkipsDuplicateOrphanToolResults() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("u1", "question"), SurfaceOp.append(), List.of());
        log.append(
                TIME,
                new SessionEventData.AssistantMessageData(
                        1L,
                        1L,
                        "a1",
                        List.of(
                                io.agentscope.core.message.ToolUseBlock.builder()
                                        .id("call-1")
                                        .name("search")
                                        .input(Map.of())
                                        .build()),
                        List.of(),
                        null,
                        false),
                SurfaceOp.append(),
                List.of());
        io.agentscope.core.message.ToolResultBlock block =
                io.agentscope.core.message.ToolResultBlock.builder()
                        .id("call-1")
                        .name("search")
                        .output(text("ok"))
                        .build();
        log.append(
                TIME,
                new SessionEventData.ToolResultData(
                        "tr-original",
                        "search",
                        List.of(block),
                        false,
                        null,
                        Map.of("messageId", "tr-original")),
                SurfaceOp.append(),
                List.of());
        log.append(
                TIME,
                new SessionEventData.AssistantMessageData(
                        1L, 2L, "a2", List.of(text("final answer")), List.of(), null, false),
                SurfaceOp.append(),
                List.of());
        // Simulate a polluted log that re-appended the same tool result with a new messageId later
        log.append(
                TIME,
                new SessionEventData.ToolResultData(
                        "tr-duplicate",
                        "search",
                        List.of(block),
                        false,
                        null,
                        Map.of("messageId", "tr-duplicate")),
                SurfaceOp.append(),
                List.of());

        List<Msg> history = MessageDerivation.history(log);

        assertEquals(4, history.size());
        assertEquals(
                List.of("u1", "a1", "tr-original", "a2"),
                history.stream().map(Msg::getId).toList());
    }

    @Test
    @DisplayName("compaction summary replaces covered prefix across turns in workingState")
    void compactionSummaryReplacesCoveredPrefixAcrossTurns(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        NativeSessionLogStore store = new NativeSessionLogStore(new LocalSessionStore(tempDir));

        Msg u1 =
                io.agentscope.core.message.UserMessage.builder()
                        .id("u1")
                        .content(List.of(text("first question")))
                        .build();
        Msg a1 =
                io.agentscope.core.message.AssistantMessage.builder()
                        .id("a1")
                        .content(List.of(text("first answer")))
                        .build();
        Msg u2 =
                io.agentscope.core.message.UserMessage.builder()
                        .id("u2")
                        .content(List.of(text("second question")))
                        .build();
        Msg a2 =
                io.agentscope.core.message.AssistantMessage.builder()
                        .id("a2")
                        .content(List.of(text("second answer")))
                        .build();

        io.agentscope.core.state.AgentState turn1State =
                io.agentscope.core.state.AgentState.builder().build();
        turn1State.contextMutable().addAll(List.of(u1, a1, u2, a2));

        try (DurableSession turn1 = store.openWriter("s-compact", "run-1", "turn-1")) {
            turn1.checkpointNow(turn1State, "run_completed");
        }

        // Turn 2 compacts [u1, a1] into summary and keeps [u2, a2] plus new [u3, a3]
        Msg summary =
                io.agentscope.core.message.UserMessage.builder()
                        .id("__compaction_summary__:11111111-1111-1111-1111-111111111111")
                        .name("__compaction_summary__")
                        .content(List.of(text("<summary>u1 and a1 summary</summary>")))
                        .metadata(
                                Map.of(
                                        "context.summary_version",
                                        1,
                                        "context.covered_messages",
                                        List.of("u1", "a1")))
                        .build();
        Msg u3 =
                io.agentscope.core.message.UserMessage.builder()
                        .id("u3")
                        .content(List.of(text("third question")))
                        .build();
        Msg a3 =
                io.agentscope.core.message.AssistantMessage.builder()
                        .id("a3")
                        .content(List.of(text("third answer")))
                        .build();

        try (DurableSession turn2 = store.openWriter("s-compact", "run-2", "turn-2")) {
            io.agentscope.core.state.AgentState turn2State =
                    turn2.workingState(io.agentscope.core.state.AgentState.builder().build());
            assertEquals(4, turn2State.getContext().size());
            turn2State.contextMutable().clear();
            turn2State.contextMutable().addAll(List.of(summary, u2, a2, u3));
            turn2.checkpointNow(turn2State, "before_model");
            turn2State.contextMutable().add(a3);
            turn2.checkpointNow(turn2State, "run_completed");
        }

        // Turn 3 must resume with [summary, u2, a2, u3, a3], NOT resurrecting u1 or a1
        try (DurableSession turn3 = store.openWriter("s-compact", "run-3", "turn-3")) {
            io.agentscope.core.state.AgentState turn3State =
                    turn3.workingState(io.agentscope.core.state.AgentState.builder().build());
            assertEquals(
                    List.of(
                            "__compaction_summary__:11111111-1111-1111-1111-111111111111",
                            "u2",
                            "a2",
                            "u3",
                            "a3"),
                    turn3State.getContext().stream().map(Msg::getId).toList());
            assertEquals("__compaction_summary__", turn3State.getContext().get(0).getName());
            assertEquals(1L, turn3.kernel().replaceGeneration());
        }
    }
}
