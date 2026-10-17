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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins event relationship tracing: which node displaced a position, and what that node cited.
 *
 * <p>A replacement shadows rather than deletes, so a reader asking "what happened to the message I
 * saw at position 3" needs the chain of replacements and their citations, not just the current
 * surface.
 */
class SessionTraceTest {

    private static final long TIME = 1_700_000_000_000L;

    private static SessionEventData.UserMessageData user(String id, String value) {
        return SessionEventData.UserMessageData.of(
                id, List.of(TextBlock.builder().text(value).build()));
    }

    @Test
    @DisplayName("an event no replacement touched reports no displacement")
    void untouchedEventReportsNoDisplacement() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "original"), SurfaceOp.append(), List.of());

        SessionTrace trace = SessionTrace.of(log.events(), SessionSeq.Seq.of(0));

        assertTrue(trace.replacedBy().isEmpty());
        assertTrue(trace.replacedEventSeqs().isEmpty());
        assertTrue(trace.sourceEventSeqs().isEmpty());
    }

    @Test
    @DisplayName("a replaced event names the node that displaced it")
    void replacedEventNamesItsDisplacer() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "original"), SurfaceOp.append(), List.of());
        log.append(
                TIME + 1,
                user("m2", "condensed"),
                SurfaceOp.replace(SessionSeq.Seq.of(0), SessionSeq.Seq.of(0)),
                List.of(SessionSeq.Seq.of(0)));

        SessionTrace trace = SessionTrace.of(log.events(), SessionSeq.Seq.of(0));

        assertEquals(List.of(SessionSeq.Seq.of(1)), trace.replacedBy());
        assertEquals(List.of(SessionSeq.Seq.of(1)), trace.replacementChain());
    }

    @Test
    @DisplayName("a replacement reports what it cited and what it displaced")
    void replacementReportsCitationsAndRange() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "a"), SurfaceOp.append(), List.of());
        log.append(TIME + 1, user("m2", "b"), SurfaceOp.append(), List.of());
        log.append(
                TIME + 2,
                user("m3", "condensed"),
                SurfaceOp.replace(SessionSeq.Seq.of(0), SessionSeq.Seq.of(1)),
                List.of(SessionSeq.Seq.of(0), SessionSeq.Seq.of(1)));

        SessionTrace trace = SessionTrace.of(log.events(), SessionSeq.Seq.of(2));

        assertEquals(List.of(SessionSeq.Seq.of(0), SessionSeq.Seq.of(1)), trace.sourceEventSeqs());
        assertEquals(
                List.of(SessionSeq.Seq.of(0), SessionSeq.Seq.of(1)), trace.replacedEventSeqs());
        assertEquals(SessionSeq.Seq.of(0), trace.replacedRange().start());
        assertEquals(SessionSeq.Seq.of(1), trace.replacedRange().end());
    }

    @Test
    @DisplayName("a chain of replacements is followed to the current node")
    void chainIsFollowedToTheCurrentNode() {
        SessionLog log = new SessionLog();
        log.append(TIME, user("m1", "original"), SurfaceOp.append(), List.of());
        log.append(
                TIME + 1,
                user("m2", "first pass"),
                SurfaceOp.replace(SessionSeq.Seq.of(0), SessionSeq.Seq.of(0)),
                List.of(SessionSeq.Seq.of(0)));
        log.append(
                TIME + 2,
                user("m3", "second pass"),
                SurfaceOp.replace(SessionSeq.Seq.of(1), SessionSeq.Seq.of(1)),
                List.of(SessionSeq.Seq.of(1)));

        SessionTrace trace = SessionTrace.of(log.events(), SessionSeq.Seq.of(0));

        assertEquals(List.of(SessionSeq.Seq.of(1), SessionSeq.Seq.of(2)), trace.replacementChain());
        assertEquals(
                SessionSeq.Seq.of(2), trace.currentSeq(), "the chain ends at the visible node");
    }

    @Test
    @DisplayName("a log-only event reports no relationships")
    void logOnlyEventReportsNothing() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));

        SessionTrace trace = SessionTrace.of(log.events(), SessionSeq.Seq.of(0));

        assertTrue(trace.replacedBy().isEmpty());
        assertTrue(trace.replacedRange() == null);
    }

    @Test
    @DisplayName("an event that does not exist is refused")
    void unknownEventIsRefused() {
        SessionLog log = new SessionLog();

        assertThrows(
                IllegalArgumentException.class,
                () -> SessionTrace.of(log.events(), SessionSeq.Seq.of(7)));
    }
}
