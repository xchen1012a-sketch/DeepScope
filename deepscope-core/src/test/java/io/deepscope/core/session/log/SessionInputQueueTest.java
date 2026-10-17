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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.message.TextBlock;
import io.deepscope.core.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the input queue as one log's records rather than a second journal.
 *
 * <p>Accepted input is a recorded fact, so it belongs in the same append-only log as the work it
 * causes. Whether input has been applied is read from its position relative to the work, so no
 * separate "applied" marker or second sequence has to be kept in step with the first.
 */
class SessionInputQueueTest {

    private static final long TIME = 1_700_000_000_000L;

    private static UserMessage message(String text) {
        return new UserMessage(text);
    }

    @Test
    @DisplayName("accepted input is recorded and readable")
    void acceptedInputIsRecorded() {
        SessionLog log = new SessionLog();
        SessionInputQueue queue = new SessionInputQueue(log);

        queue.submit("req-1", List.of(message("compare A and B")), "1");

        var accepted = queue.pending();
        assertEquals(1, accepted.size());
        assertEquals("req-1", accepted.get(0).requestId());
        assertEquals("1", accepted.get(0).turnId());
    }

    @Test
    @DisplayName("re-submitting the same request id returns the recorded input unchanged")
    void resubmittingTheSameIdIsIdempotent() {
        SessionLog log = new SessionLog();
        SessionInputQueue queue = new SessionInputQueue(log);

        var first = queue.submit("req-1", List.of(message("hello")), "1");
        var second = queue.submit("req-1", List.of(message("hello")), "1");

        assertEquals(first, second);
        assertEquals(1, queue.pending().size(), "a retry must not record the input twice");
    }

    @Test
    @DisplayName("reusing a request id for different input is refused")
    void reusingAnIdForDifferentInputIsRefused() {
        SessionLog log = new SessionLog();
        SessionInputQueue queue = new SessionInputQueue(log);
        queue.submit("req-1", List.of(message("hello")), "1");

        assertThrows(
                IllegalArgumentException.class,
                () -> queue.submit("req-1", List.of(message("something else")), "1"));
    }

    @Test
    @DisplayName("input whose turn has started is no longer pending")
    void inputAppliedByItsTurnIsNotPending() {
        SessionLog log = new SessionLog();
        SessionInputQueue queue = new SessionInputQueue(log);
        queue.submit("req-1", List.of(message("hello")), "1");

        // The turn begins: the work the input caused now exists, so the input has been applied.
        new CallLog0(log).turnStart(1L);

        assertTrue(
                queue.pending().isEmpty(),
                "input applied by its turn must not stay pending, without a separate marker");
    }

    @Test
    @DisplayName("input stays pending until its turn begins")
    void inputStaysPendingUntilItsTurnBegins() {
        SessionLog log = new SessionLog();
        SessionInputQueue queue = new SessionInputQueue(log);
        queue.submit("req-1", List.of(message("first")), "1");
        queue.submit("req-2", List.of(message("second")), "2");

        new CallLog0(log).turnStart(1L);

        var pending = queue.pending();
        assertEquals(1, pending.size());
        assertEquals("req-2", pending.get(0).requestId());
    }

    @Test
    @DisplayName("guidance is recorded as input that does not start a turn")
    void guidanceDoesNotStartATurn() {
        SessionLog log = new SessionLog();
        SessionInputQueue queue = new SessionInputQueue(log);

        queue.steer("req-1", List.of(message("focus on cost")), "1");

        assertFalse(
                ((SessionInputQueue.Input) log.eventAt(SessionSeq.Seq.of(0)).data()).startsTurn(),
                "guidance adjusts work already running rather than starting its own");
    }

    @Test
    @DisplayName("a rejected request is reported rather than silently dropped")
    void rejectedRequestIsReported() {
        SessionLog log = new SessionLog();
        SessionInputQueue queue = new SessionInputQueue(log);
        queue.submit("req-1", List.of(message("hello")), "1");

        queue.reject("req-1", "session busy");

        assertEquals("session busy", queue.rejectionOf("req-1").orElseThrow());
    }

    @Test
    @DisplayName("input records are not model-visible")
    void inputRecordsAreNotModelVisible() {
        SessionLog log = new SessionLog();
        SessionInputQueue queue = new SessionInputQueue(log);

        queue.submit("req-1", List.of(message("hello")), "1");

        assertEquals(List.of(), log.surfaceNodes(), "queue bookkeeping must not enter the surface");
        assertEquals(List.of(), log.deriveMessages());
    }

    /** A minimal recorder that opens a turn, so the queue sees the work it caused. */
    private static final class CallLog0 {
        private final SessionLog log;

        private CallLog0(SessionLog log) {
            this.log = log;
        }

        private void turnStart(long turn) {
            log.appendLogOnly(TIME, new SessionExecutionData.TurnStart(turn));
        }
    }

    private static TextBlock text(String value) {
        return TextBlock.builder().text(value).build();
    }
}
