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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the read-only query surface: listing, reading, and filtering stored sessions without
 * replaying them through a writer.
 */
class SessionQueryTest {

    private static final long TIME = 1_700_000_000_000L;

    private static SessionEventData.UserMessageData user(String id, String value) {
        return SessionEventData.UserMessageData.of(
                id, List.of(TextBlock.builder().text(value).build()));
    }

    /** A store holding one session with a title and one step, plus an empty second session. */
    private static LocalSessionStore seeded(Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("first");
        try (LocalSessionStore.Handle handle =
                store.open("first", LocalSessionStore.Access.WRITE)) {
            handle.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
            handle.append(TIME + 1, user("m1", "compare A and B"));
            handle.appendLogOnly(TIME + 1, new SessionExecutionData.StepStart(1L, 1L));
            handle.appendLogOnly(TIME + 2, new SessionExecutionData.StepEnd(1L, 1L));
            SessionTitle.appendTo(handle, TIME + 3, "chosen title", SessionTitle.Source.USER);
            handle.flush();
        }
        store.create("second");
        try (LocalSessionStore.Handle handle =
                store.open("second", LocalSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m2", "the first question"));
            handle.flush();
        }
        return store;
    }

    @Test
    @DisplayName("listing names every stored session in order")
    void listingNamesEverySession(@TempDir Path root) {
        SessionQuery query = new SessionQuery(seeded(root));

        assertEquals(List.of("first", "second"), query.listSessionIds());
    }

    @Test
    @DisplayName("summaries carry the size, title, and stats of each session")
    void summariesCarryDerivedFacts(@TempDir Path root) {
        SessionQuery query = new SessionQuery(seeded(root));

        SessionQuery.SessionSummary summary = query.summary("first");

        assertEquals("first", summary.sessionId());
        assertEquals(5, summary.eventCount());
        assertEquals("chosen title", summary.title(), "the recorded title wins over the fallback");
        assertEquals(1, summary.stats().steps());
        assertEquals(1, summary.stats().turns());
    }

    @Test
    @DisplayName("a session with no recorded title falls back to its first human message")
    void titleFallsBackToTheFirstHumanMessage(@TempDir Path root) {
        SessionQuery query = new SessionQuery(seeded(root));

        assertEquals(
                "the first question",
                query.summary("second").title() != null ? query.summary("second").title() : null);
    }

    @Test
    @DisplayName("reading a session returns its committed events")
    void readingReturnsCommittedEvents(@TempDir Path root) {
        SessionQuery query = new SessionQuery(seeded(root));

        List<SessionEvent> events = query.readEvents("first");

        assertEquals(5, events.size());
        assertEquals(SessionEventTypes.TURN_START, events.get(0).type());
    }

    @Test
    @DisplayName("reading a session does not claim write ownership")
    void readingDoesNotClaimOwnership(@TempDir Path root) {
        LocalSessionStore store = seeded(root);
        SessionQuery query = new SessionQuery(store);

        query.readEvents("first");

        try (LocalSessionStore.Handle handle =
                store.open("first", LocalSessionStore.Access.WRITE)) {
            assertEquals(5, handle.log().size());
        }
    }

    @Test
    @DisplayName("the derived message history is available without a writer")
    void derivedHistoryIsAvailable(@TempDir Path root) {
        SessionQuery query = new SessionQuery(seeded(root));

        assertEquals(1, query.derivedMessages("first").size());
        assertEquals("compare A and B", query.derivedMessages("first").get(0).getTextContent());
    }

    @Test
    @DisplayName("filtering by title keeps only matching sessions")
    void filteringByTitleKeepsMatches(@TempDir Path root) {
        SessionQuery query = new SessionQuery(seeded(root));

        assertEquals(
                List.of("first"),
                query.findSessions(summary -> "chosen title".equals(summary.title())).stream()
                        .map(SessionQuery.SessionSummary::sessionId)
                        .toList());
    }

    @Test
    @DisplayName("events can be filtered by type")
    void eventsCanBeFilteredByType(@TempDir Path root) {
        SessionQuery query = new SessionQuery(seeded(root));

        List<SessionEvent> stepEvents =
                query.findEvents("first", event -> SessionEventTypes.STEP_END.equals(event.type()));

        assertEquals(1, stepEvents.size());
    }

    @Test
    @DisplayName("an unknown session is refused rather than reported empty")
    void unknownSessionIsRefused(@TempDir Path root) {
        SessionQuery query = new SessionQuery(seeded(root));

        assertThrows(
                LocalSessionStore.SessionNotFoundException.class,
                () -> query.readEvents("missing"));
    }

    @Test
    @DisplayName("an unreadable session is omitted from summaries instead of failing the sweep")
    void unreadableSessionIsOmitted(@TempDir Path root) throws IOException {
        LocalSessionStore store = seeded(root);
        Files.writeString(
                store.logPath("second"),
                Files.readString(store.logPath("second"))
                        .replace("\"version\":1", "\"version\":99"));
        SessionQuery query = new SessionQuery(store);

        List<String> ids = query.listSessionIds();
        assertFalse(ids.contains("second"), "an unreadable log is not listed");
        assertEquals(List.of("first"), ids);
    }

    @Test
    @DisplayName("summaries never expose a write handle")
    void summariesLeaveTheStoreWritable(@TempDir Path root) {
        LocalSessionStore store = seeded(root);
        SessionQuery query = new SessionQuery(store);
        query.summary("first");
        query.summary("second");

        try (LocalSessionStore.Handle ignored =
                store.open("first", LocalSessionStore.Access.WRITE)) {
            assertTrue(true, "ownership is free after querying");
        }
    }

    @Test
    @DisplayName("stat exposure reports the stored version")
    void statExposureReportsVersion(@TempDir Path root) {
        SessionQuery query = new SessionQuery(seeded(root));

        assertEquals(SessionFormatVersion.CURRENT, query.stat("first").version());
    }
}
