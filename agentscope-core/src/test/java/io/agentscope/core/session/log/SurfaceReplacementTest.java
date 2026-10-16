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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the surface replacement rules of {@link SurfaceFold}.
 *
 * <p>A replacement shadows existing model-visible nodes without deleting them, so it must prove what
 * it hides and must not rewrite facts the model already acted on.
 */
class SurfaceReplacementTest {

    private static final long TIME = 1_700_000_000_000L;

    private static TextBlock text(String value) {
        return TextBlock.builder().text(value).build();
    }

    private static SessionEventData.UserMessageData user(String id, String value) {
        return SessionEventData.UserMessageData.of(id, List.of(text(value)));
    }

    private static SessionEventData.AssistantMessageData assistant(String id, String value) {
        return new SessionEventData.AssistantMessageData(
                0L, 0L, id, List.of(text(value)), List.of(), null, false);
    }

    private static ToolResultBlock toolBlock(String callId, String name, String output) {
        return ToolResultBlock.builder()
                .id(callId)
                .name(name)
                .output(text(output))
                .state(ToolResultState.SUCCESS)
                .build();
    }

    @Test
    @DisplayName("a replacement shadows a range and keeps the original in the log")
    void replacementShadowsWithoutDeleting() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "q1"), SurfaceOp.append(), List.of());
        log.append(TIME, assistant("a1", "a1"), SurfaceOp.append(), List.of());
        log.append(TIME, user("m2", "q2"), SurfaceOp.append(), List.of());

        log.append(
                TIME,
                user("summary", "condensed history"),
                SurfaceOp.replace(SessionSeq.Seq.of(0), SessionSeq.Seq.of(1)),
                List.of(SessionSeq.Seq.of(0), SessionSeq.Seq.of(1)));

        assertEquals(4, log.size(), "the shadowed events remain in the log");
        assertEquals(
                "[3, 2]",
                log.surfaceNodes().toString(),
                "the replacing node takes the shadowed range's place in surface order");
        assertEquals(2, log.deriveMessages().size());
        assertEquals("condensed history", log.deriveMessages().get(0).getTextContent());
        assertEquals("q2", log.deriveMessages().get(1).getTextContent());
    }

    @Test
    @DisplayName("a replacement advances both generations")
    void replacementAdvancesGenerations() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "q1"), SurfaceOp.append(), List.of());
        long replaceBefore = log.replaceGeneration();
        long contentBefore = log.contentGeneration();

        log.append(
                TIME,
                user("summary", "condensed"),
                SurfaceOp.replace(SessionSeq.Seq.of(0), SessionSeq.Seq.of(0)),
                List.of(SessionSeq.Seq.of(0)));

        assertEquals(replaceBefore + 1, log.replaceGeneration());
        assertEquals(contentBefore + 1, log.contentGeneration());
    }

    @Test
    @DisplayName("an append advances neither generation")
    void appendAdvancesNoGeneration() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "q1"), SurfaceOp.append(), List.of());

        assertEquals(0, log.replaceGeneration());
        assertEquals(0, log.contentGeneration());
    }

    @Test
    @DisplayName("both endpoints must exist on the current surface")
    void endpointsMustExistOnSurface() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "q1"), SurfaceOp.append(), List.of());

        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        log.append(
                                                TIME,
                                                user("s", "x"),
                                                SurfaceOp.replace(
                                                        SessionSeq.Seq.of(0), SessionSeq.Seq.of(9)),
                                                List.of(SessionSeq.Seq.of(0))))
                        .getMessage()
                        .contains("end seq 9 not found"));
    }

    @Test
    @DisplayName("endpoints must be in surface order, not numeric order")
    void endpointsMustBeInSurfaceOrder() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "q1"), SurfaceOp.append(), List.of());
        log.append(TIME, user("m2", "q2"), SurfaceOp.append(), List.of());

        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        log.append(
                                                TIME,
                                                user("s", "x"),
                                                SurfaceOp.replace(
                                                        SessionSeq.Seq.of(1), SessionSeq.Seq.of(0)),
                                                List.of(
                                                        SessionSeq.Seq.of(0),
                                                        SessionSeq.Seq.of(1))))
                        .getMessage()
                        .contains("is after end seq"));
    }

    @Test
    @DisplayName("a replacement must cite every node it shadows")
    void replacementMustCiteEveryShadowedNode() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "q1"), SurfaceOp.append(), List.of());
        log.append(TIME, user("m2", "q2"), SurfaceOp.append(), List.of());

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                log.append(
                                        TIME,
                                        user("s", "x"),
                                        SurfaceOp.replace(
                                                SessionSeq.Seq.of(0), SessionSeq.Seq.of(1)),
                                        List.of(SessionSeq.Seq.of(0))));

        assertTrue(
                failure.getMessage().contains("must include every shadowed"), failure.getMessage());
        assertEquals(2, log.size(), "a rejected replacement changes nothing");
        assertEquals(List.of(SessionSeq.Seq.of(0), SessionSeq.Seq.of(1)), log.surfaceNodes());
    }

    @Test
    @DisplayName("a replacement may not cite events at or after itself")
    void replacementEndpointsMustPrecedeTheReplacingEvent() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "q1"), SurfaceOp.append(), List.of());

        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        log.append(
                                                TIME,
                                                user("s", "x"),
                                                SurfaceOp.replace(
                                                        SessionSeq.Seq.of(0), SessionSeq.Seq.of(0)),
                                                List.of(
                                                        SessionSeq.Seq.of(0),
                                                        SessionSeq.Seq.of(1))))
                        .getMessage()
                        .contains("must reference earlier events"));
    }

    @Test
    @DisplayName("a tool result replacement may change only content")
    void toolResultReplacementMayChangeOnlyContent() {
        SessionLog log = new SessionLog();
        log.append(
                TIME,
                new SessionEventData.ToolResultData(
                        "call-1",
                        "search",
                        List.of(toolBlock("call-1", "search", "before")),
                        false,
                        null,
                        null),
                SurfaceOp.append(),
                List.of());

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                log.append(
                                        TIME,
                                        new SessionEventData.ToolResultData(
                                                "call-1",
                                                "search",
                                                List.of(toolBlock("call-1", "search", "after")),
                                                true,
                                                null,
                                                null),
                                        SurfaceOp.replace(
                                                SessionSeq.Seq.of(0), SessionSeq.Seq.of(0)),
                                        List.of(SessionSeq.Seq.of(0))));

        assertTrue(failure.getMessage().contains("may change only content"), failure.getMessage());
    }

    @Test
    @DisplayName("a tool result replacement with unchanged identity is accepted")
    void toolResultReplacementWithContentChangeIsAccepted() {
        SessionLog log = new SessionLog();
        log.append(
                TIME,
                new SessionEventData.ToolResultData(
                        "call-1",
                        "search",
                        List.of(toolBlock("call-1", "search", "before")),
                        false,
                        null,
                        null),
                SurfaceOp.append(),
                List.of());

        log.append(
                TIME,
                new SessionEventData.ToolResultData(
                        "call-1",
                        "search",
                        List.of(toolBlock("call-1", "search", "after")),
                        false,
                        null,
                        null),
                SurfaceOp.replace(SessionSeq.Seq.of(0), SessionSeq.Seq.of(0)),
                List.of(SessionSeq.Seq.of(0)));

        assertEquals(1, log.surfaceNodes().size());
        assertEquals("after", toolOutput(log.deriveMessages().get(0)));
    }

    /** Text carried by the first tool-result block of a derived tool message. */
    private static String toolOutput(io.agentscope.core.message.Msg message) {
        ToolResultBlock block = (ToolResultBlock) message.getContent().get(0);
        return ((TextBlock) block.getOutput().get(0)).getText();
    }

    @Test
    @DisplayName("a tool result replacement must cover exactly one node")
    void toolResultReplacementCoversOneNode() {
        SessionLog log = new SessionLog();
        log.append(
                TIME,
                new SessionEventData.ToolResultData(
                        "call-1",
                        "search",
                        List.of(toolBlock("call-1", "search", "a")),
                        false,
                        null,
                        null),
                SurfaceOp.append(),
                List.of());
        log.append(
                TIME,
                new SessionEventData.ToolResultData(
                        "call-2",
                        "search",
                        List.of(toolBlock("call-2", "search", "b")),
                        false,
                        null,
                        null),
                SurfaceOp.append(),
                List.of());

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                log.append(
                                        TIME,
                                        new SessionEventData.ToolResultData(
                                                "call-1",
                                                "search",
                                                List.of(toolBlock("call-1", "search", "c")),
                                                false,
                                                null,
                                                null),
                                        SurfaceOp.replace(
                                                SessionSeq.Seq.of(0), SessionSeq.Seq.of(1)),
                                        List.of(SessionSeq.Seq.of(0), SessionSeq.Seq.of(1))));

        assertTrue(failure.getMessage().contains("exactly one current node"), failure.getMessage());
    }

    @Test
    @DisplayName("the system prompt at node 0 may be rewritten only by a system message over it")
    void systemHeadIsProtected() {
        SessionLog log = new SessionLog();
        log.append(
                TIME,
                new SessionEventData.SystemMessageData(List.of(text("be helpful"))),
                SurfaceOp.append(),
                List.of());

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                log.append(
                                        TIME,
                                        user("m1", "hello"),
                                        SurfaceOp.replace(
                                                SessionSeq.Seq.of(0), SessionSeq.Seq.of(0)),
                                        List.of(SessionSeq.Seq.of(0))));

        assertTrue(
                failure.getMessage().contains("node 0 holds the system prompt"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a system message over exactly node 0 rewrites the prompt")
    void systemHeadRewriteIsAccepted() {
        SessionLog log = new SessionLog();
        log.append(
                TIME,
                new SessionEventData.SystemMessageData(List.of(text("be helpful"))),
                SurfaceOp.append(),
                List.of());

        log.append(
                TIME,
                new SessionEventData.SystemMessageData(List.of(text("be concise"))),
                SurfaceOp.replace(SessionSeq.Seq.of(0), SessionSeq.Seq.of(0)),
                List.of(SessionSeq.Seq.of(0)));

        assertEquals(1, log.surfaceNodes().size());
        assertEquals("be concise", log.deriveMessages().get(0).getTextContent());
    }

    @Test
    @DisplayName("a later system node carries no head protection")
    void laterSystemNodeIsNotProtected() {
        SessionLog log = new SessionLog();
        log.append(
                TIME,
                new SessionEventData.SystemMessageData(List.of(text("prompt"))),
                SurfaceOp.append(),
                List.of());
        log.append(TIME, user("m1", "hello"), SurfaceOp.append(), List.of());
        log.append(
                TIME,
                new SessionEventData.SystemMessageData(List.of(text("later note"))),
                SurfaceOp.append(),
                List.of());

        log.append(
                TIME,
                user("summary", "condensed"),
                SurfaceOp.replace(SessionSeq.Seq.of(1), SessionSeq.Seq.of(2)),
                List.of(SessionSeq.Seq.of(1), SessionSeq.Seq.of(2)));

        assertEquals(List.of(SessionSeq.Seq.of(0), SessionSeq.Seq.of(3)), log.surfaceNodes());
    }

    @Test
    @DisplayName("a log-only event cannot shadow a surface range")
    void logOnlyEventCannotCarryReplaceOperation() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SessionEvent.restore(
                                SessionSeq.Seq.of(1),
                                TIME,
                                new SessionEventData.PayloadData(
                                        SessionEventTypes.STEP_END, java.util.Map.of()),
                                SurfaceOp.replace(SessionSeq.Seq.of(0), SessionSeq.Seq.of(0)),
                                List.of(SessionSeq.Seq.of(0)),
                                false));
    }

    @Test
    @DisplayName("a replay rebuilds the surface without accumulating nodes")
    void replayRebuildsFromScratch() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "q1"), SurfaceOp.append(), List.of());
        log.append(
                TIME,
                user("summary", "condensed"),
                SurfaceOp.replace(SessionSeq.Seq.of(0), SessionSeq.Seq.of(0)),
                List.of(SessionSeq.Seq.of(0)));

        SurfaceFold fold = new SurfaceFold();
        fold.replay(log.events());
        fold.replay(log.events());

        assertEquals(List.of(SessionSeq.Seq.of(1)), fold.nodes());
        assertEquals(1, fold.replaceGeneration());
    }
}
