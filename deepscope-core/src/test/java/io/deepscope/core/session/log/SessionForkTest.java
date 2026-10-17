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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.message.TextBlock;
import io.deepscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins fork seed construction: the boundary between inherited history and child-owned work.
 *
 * <p>A fork copies a prefix, marks the cut, and closes only the tail that the prefix left open. The
 * source is never modified, and a reader can always tell which events the child actually produced.
 */
class SessionForkTest {

    private static final long TIME = 1_700_000_000_000L;

    private static TextBlock text(String value) {
        return TextBlock.builder().text(value).build();
    }

    private static SessionEventData.UserMessageData user(String id, String value) {
        return SessionEventData.UserMessageData.of(id, List.of(text(value)));
    }

    private static SessionLog balancedSource() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
        log.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));
        log.append(TIME, user("m1", "compare A and B"), SurfaceOp.append(), List.of());
        log.appendLogOnly(TIME, new SessionExecutionData.StepEnd(1L, 1L));
        log.appendLogOnly(
                TIME,
                new SessionExecutionData.TurnEnd(1L, SessionExecutionData.TurnEndReason.COMPLETED));
        return log;
    }

    @Test
    @DisplayName("an explicit boundary copies that inclusive prefix")
    void explicitBoundaryCopiesInclusivePrefix() {
        SessionLog source = balancedSource();

        SessionFork.Seed seed = SessionFork.buildForkSeed(source.events(), SessionSeq.Seq.of(2));

        assertEquals(3, seed.inheritedEventCount(), "the prefix is inclusive of the boundary");
        assertEquals(
                source.events().subList(0, 3),
                seed.events().subList(0, 3),
                "the copied prefix preserves the source events");
    }

    @Test
    @DisplayName("the default boundary is the last source event")
    void defaultBoundaryIsTheLastEvent() {
        SessionLog source = balancedSource();

        SessionFork.Seed seed = SessionFork.buildForkSeed(source.events());

        assertEquals(source.size(), seed.inheritedEventCount());
    }

    @Test
    @DisplayName("a seed marks the inherited cut with a tagged end-seed event")
    void seedMarksTheCut() {
        SessionLog source = balancedSource();

        SessionFork.Seed seed = SessionFork.buildForkSeed(source.events(), SessionSeq.Seq.of(2));

        SessionEvent marker = seed.events().get((int) seed.inheritedEventCount());
        assertTrue(marker.data() instanceof SessionRequestData.EndSeed);
        assertTrue(((SessionRequestData.EndSeed) marker.data()).inherited());
        assertTrue(seed.isSeeded());
    }

    @Test
    @DisplayName("the inherited count excludes the marker and any closers")
    void inheritedCountExcludesMarkerAndClosers() {
        SessionLog source = new SessionLog();
        source.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
        source.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));

        SessionFork.Seed seed = SessionFork.buildForkSeed(source.events());

        assertEquals(2, seed.inheritedEventCount());
        assertTrue(
                seed.events().size() > seed.inheritedEventCount(),
                "the seed carries child-owned events after the cut");
    }

    @Test
    @DisplayName("a cut inside an open turn closes it with a fork reason")
    void cutInsideOpenTurnClosesItAsForked() {
        SessionLog source = new SessionLog();
        source.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
        source.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));

        SessionFork.Seed seed = SessionFork.buildForkSeed(source.events());

        SessionEvent closer = seed.events().get(seed.events().size() - 1);
        SessionExecutionData.TurnEnd end = (SessionExecutionData.TurnEnd) closer.data();
        assertEquals(SessionExecutionData.TurnEndReason.FORKED, end.reason());
    }

    @Test
    @DisplayName("a cut inside an open step closes the step before the turn")
    void cutInsideOpenStepClosesStepFirst() {
        SessionLog source = new SessionLog();
        source.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
        source.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));

        SessionFork.Seed seed = SessionFork.buildForkSeed(source.events());

        List<SessionEvent> childOwned =
                seed.events().subList((int) seed.inheritedEventCount(), seed.events().size());
        assertEquals(3, childOwned.size(), "marker, step end, turn end");
        assertTrue(childOwned.get(0).data() instanceof SessionRequestData.EndSeed);
        assertTrue(childOwned.get(1).data() instanceof SessionExecutionData.StepEnd);
        assertTrue(childOwned.get(2).data() instanceof SessionExecutionData.TurnEnd);
    }

    @Test
    @DisplayName("a balanced cut adds a marker and no closers")
    void balancedCutAddsOnlyTheMarker() {
        SessionLog source = balancedSource();

        SessionFork.Seed seed = SessionFork.buildForkSeed(source.events(), SessionSeq.Seq.of(4));

        assertEquals(6, seed.events().size(), "five copied events plus the marker");
        assertEquals(5, seed.inheritedEventCount());
    }

    @Test
    @DisplayName("forking never modifies the source log")
    void forkingNeverModifiesTheSource() {
        SessionLog source = balancedSource();
        List<SessionEvent> before = source.events();

        SessionFork.buildForkSeed(source.events(), SessionSeq.Seq.of(2));

        assertEquals(before, source.events());
        assertEquals(5, source.size());
    }

    @Test
    @DisplayName("a boundary beyond the log is rejected")
    void boundaryBeyondLogIsRejected() {
        SessionLog source = balancedSource();

        assertThrows(
                IllegalArgumentException.class,
                () -> SessionFork.buildForkSeed(source.events(), SessionSeq.Seq.of(99)));
    }

    @Test
    @DisplayName("an unanswered tool request in the inherited prefix becomes a fork result")
    void unansweredRequestInPrefixBecomesForkResult() {
        SessionLog source = new SessionLog();
        source.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
        source.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));
        source.append(
                TIME,
                new SessionEventData.AssistantMessageData(
                        1L,
                        1L,
                        "a1",
                        List.of(
                                ToolUseBlock.builder()
                                        .id("call-1")
                                        .name("create_order")
                                        .input(Map.of())
                                        .build()),
                        List.of(),
                        null,
                        false),
                SurfaceOp.append(),
                List.of());

        SessionFork.Seed seed = SessionFork.buildForkSeed(source.events());

        SessionEventData.ToolResultData result =
                (SessionEventData.ToolResultData)
                        seed.events().get((int) seed.inheritedEventCount() + 1).data();
        assertEquals("call-1", result.callId());
        assertTrue(result.isError());
        assertEquals(TurnRepair.TOOL_NOT_STARTED, result.error().code());
    }

    @Test
    @DisplayName("a child session built from a seed accepts its own appends")
    void childSessionBuiltFromSeedAcceptsAppends() {
        SessionLog source = balancedSource();
        SessionFork.Seed seed = SessionFork.buildForkSeed(source.events(), SessionSeq.Seq.of(2));

        SessionLog child = new SessionLog();
        child.appendAll(seed.events());
        SessionEvent appended =
                child.append(TIME, user("m2", "another route"), SurfaceOp.append(), List.of());

        assertEquals(seed.events().size(), appended.seq().value());
        assertEquals(
                List.of(SessionSeq.Seq.of(2), SessionSeq.Seq.of(6)),
                child.surfaceNodes(),
                "the child's surface continues the inherited one");
        assertEquals(2, child.deriveMessages().size());
    }

    @Test
    @DisplayName("a child surface inherits the prefix's messages")
    void childInheritsPrefixMessages() {
        SessionLog source = balancedSource();
        SessionFork.Seed seed = SessionFork.buildForkSeed(source.events());

        SessionLog child = new SessionLog();
        child.appendAll(seed.events());

        assertEquals(
                source.deriveMessages().stream()
                        .map(io.deepscope.core.message.Msg::getTextContent)
                        .toList(),
                child.deriveMessages().stream()
                        .map(io.deepscope.core.message.Msg::getTextContent)
                        .toList());
    }

    @Test
    @DisplayName("a fork reports the source session it branched from")
    void forkReportsItsParent() {
        SessionLog source = balancedSource();

        SessionFork.Seed seed =
                SessionFork.buildForkSeed(source.events(), SessionSeq.Seq.of(2), "parent-1");

        assertEquals("parent-1", seed.parentSession());
    }

    @Test
    @DisplayName("a fork without a parent reports none")
    void forkWithoutParentReportsNone() {
        SessionLog source = balancedSource();

        SessionFork.Seed seed = SessionFork.buildForkSeed(source.events(), SessionSeq.Seq.of(2));

        org.junit.jupiter.api.Assertions.assertNull(seed.parentSession());
    }
}
