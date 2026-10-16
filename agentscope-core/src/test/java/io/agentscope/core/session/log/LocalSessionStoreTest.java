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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the local session store's observable contract.
 *
 * <p>A session has one writer; a committed prefix is never rewritten; reads never expose a partial
 * append; and flushing is the durability barrier.
 */
class LocalSessionStoreTest {

    private static final long TIME = 1_700_000_000_000L;

    private static SessionEventData.UserMessageData user(String id, String value) {
        return SessionEventData.UserMessageData.of(
                id, List.of(TextBlock.builder().text(value).build()));
    }

    @Test
    @DisplayName("a created session is listable and openable at its committed state")
    void createdSessionIsListableAndOpenable(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");

        assertTrue(store.list().contains("s1"));

        try (LocalSessionStore.Handle handle = store.open("s1", LocalSessionStore.Access.WRITE)) {
            assertEquals(0, handle.log().size());
        }
    }

    @Test
    @DisplayName("appends survive closing and reopening the store")
    void appendsSurviveReopen(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        try (LocalSessionStore.Handle handle = store.open("s1", LocalSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m1", "hello"));
            handle.append(TIME, user("m2", "again"));
            handle.flush();
        }

        LocalSessionStore reopened = new LocalSessionStore(root);
        try (LocalSessionStore.Handle handle = reopened.open("s1", LocalSessionStore.Access.READ)) {
            assertEquals(2, handle.log().size());
            assertEquals(
                    List.of("hello", "again"),
                    handle.log().deriveMessages().stream()
                            .map(io.agentscope.core.message.Msg::getTextContent)
                            .toList());
        }
    }

    @Test
    @DisplayName("projection figures survive a persist and reopen round trip")
    void projectionFiguresSurviveReopen(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        try (LocalSessionStore.Handle handle = store.open("s1", LocalSessionStore.Access.WRITE)) {
            handle.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
            handle.appendLogOnly(TIME, new SessionExecutionData.StepStart(1L, 1L));
            handle.append(
                    TIME + 400,
                    new SessionEventData.AssistantMessageData(
                            1L,
                            1L,
                            "a1",
                            List.of(TextBlock.builder().text("answer").build()),
                            List.of(
                                    new AssistantStreamRecord.TextChunks(
                                            TIME + 100, 0, List.of(), List.of("hello"))),
                            new SessionEventData.Usage(0L, 40L, 0L, 0L, null),
                            false));
            handle.appendLogOnly(
                    TIME + 400, new SessionExecutionData.ToolCall(1L, 1L, "c1", "search", "{}"));
            handle.append(
                    TIME + 700,
                    new SessionEventData.ToolResultData(
                            "c1",
                            "search",
                            List.of(
                                    io.agentscope.core.message.ToolResultBlock.builder()
                                            .id("c1")
                                            .name("search")
                                            .output(TextBlock.builder().text("ok").build())
                                            .state(
                                                    io.agentscope.core.message.ToolResultState
                                                            .SUCCESS)
                                            .build()),
                            false,
                            null,
                            null));
            handle.appendLogOnly(TIME + 700, new SessionExecutionData.StepEnd(1L, 1L));
            handle.flush();
        }

        ProjectionRegistry registry = new ProjectionRegistry();
        registry.register(new SessionStatsProjection().unit());
        SessionStatsProjection.SessionStats reopenedTotals;
        try (LocalSessionStore.Handle handle =
                new LocalSessionStore(root).open("s1", LocalSessionStore.Access.READ)) {
            SessionStatsProjection.State state =
                    registry.stateOf(handle.log(), SessionStatsProjection.KEY);
            reopenedTotals = state.totals();
        }

        assertEquals(1, reopenedTotals.steps());
        assertEquals(1, reopenedTotals.turns());
        assertEquals(400L, reopenedTotals.llmMs(), "model wall time survives the round trip");
        assertEquals(100L, reopenedTotals.ttftMs(), "first-token latency survives the round trip");
        assertEquals(300L, reopenedTotals.decodeMs());
        assertEquals(40L, reopenedTotals.decodeTokens());
        assertEquals(300L, reopenedTotals.toolMs(), "tool pairing survives the round trip");
    }

    @Test
    @DisplayName("the kernel's derived state survives a store reopen")
    void kernelDerivedStateSurvivesReopen(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        try (LocalSessionStore.Handle handle = store.open("s1", LocalSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m1", "first"));
            handle.append(
                    TIME + 1,
                    new SessionEventData.AssistantMessageData(
                            0L,
                            0L,
                            "a1",
                            List.of(TextBlock.builder().text("reply").build()),
                            List.of(),
                            null,
                            false));
            handle.flush();
        }

        try (LocalSessionStore.Handle reopened =
                new LocalSessionStore(root).open("s1", LocalSessionStore.Access.READ)) {
            assertEquals(
                    2,
                    reopened.log().deriveMessages().size(),
                    "a reopened kernel log must derive the history it recorded");
        }
    }

    @Test
    @DisplayName("a second writer for the same session is refused")
    void secondWriterIsRefused(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        try (LocalSessionStore.Handle first = store.open("s1", LocalSessionStore.Access.WRITE)) {
            assertThrows(
                    LocalSessionStore.SessionAlreadyOwnedException.class,
                    () -> store.open("s1", LocalSessionStore.Access.WRITE));
            assertTrue(first.log().size() == 0);
        }
    }

    @Test
    @DisplayName("creating a session that already exists is refused")
    void creatingAnExistingSessionIsRefused(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");

        assertThrows(
                LocalSessionStore.SessionAlreadyExistsException.class, () -> store.create("s1"));
    }

    @Test
    @DisplayName("an unknown session cannot be opened")
    void unknownSessionCannotBeOpened(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);

        assertThrows(
                LocalSessionStore.SessionNotFoundException.class,
                () -> store.open("missing", LocalSessionStore.Access.WRITE));
    }

    @Test
    @DisplayName("a closed handle refuses further operations")
    void closedHandleRefusesOperations(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        LocalSessionStore.Handle handle = store.open("s1", LocalSessionStore.Access.WRITE);
        handle.close();

        assertThrows(LocalSessionStore.SessionHandleClosedException.class, () -> handle.log());
        assertThrows(LocalSessionStore.SessionHandleClosedException.class, handle::flush);
    }

    @Test
    @DisplayName("closing is idempotent")
    void closingIsIdempotent(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        LocalSessionStore.Handle handle = store.open("s1", LocalSessionStore.Access.WRITE);

        handle.close();
        handle.close();
    }

    @Test
    @DisplayName("closing releases write ownership")
    void closingReleasesOwnership(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        store.open("s1", LocalSessionStore.Access.WRITE).close();

        try (LocalSessionStore.Handle again = store.open("s1", LocalSessionStore.Access.WRITE)) {
            assertEquals(0, again.log().size());
        }
    }

    @Test
    @DisplayName("listing is empty before any session exists")
    void listingIsEmptyInitially(@TempDir Path root) {
        assertEquals(List.of(), new LocalSessionStore(root).list());
    }

    @Test
    @DisplayName("two sessions stay independent")
    void sessionsStayIndependent(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        store.create("s2");
        try (LocalSessionStore.Handle first = store.open("s1", LocalSessionStore.Access.WRITE);
                LocalSessionStore.Handle second =
                        store.open("s2", LocalSessionStore.Access.WRITE)) {
            first.append(TIME, user("m1", "one"));
            second.append(TIME, user("m2", "two"));
            first.flush();
            second.flush();

            assertEquals("one", first.log().deriveMessages().get(0).getTextContent());
            assertEquals("two", second.log().deriveMessages().get(0).getTextContent());
        }
    }

    @Test
    @DisplayName("a session id that escapes the root is refused")
    void escapingSessionIdIsRefused(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);

        assertThrows(IllegalArgumentException.class, () -> store.create("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.create("a/b"));
    }

    @Test
    @DisplayName("a rejected append leaves the stored log unchanged")
    void rejectedAppendLeavesLogUnchanged(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        try (LocalSessionStore.Handle handle = store.open("s1", LocalSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m1", "hello"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> handle.append(9L, TIME, user("m2", "bad")));
            assertEquals(1, handle.log().size());
        }
    }

    @Test
    @DisplayName("a torn final line is discarded on read")
    void tornFinalLineIsDiscarded(@TempDir Path root) throws IOException {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        try (LocalSessionStore.Handle handle = store.open("s1", LocalSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m1", "hello"));
            handle.flush();
        }
        Path logFile = store.logPath("s1");
        Files.writeString(logFile, Files.readString(logFile) + "{\"partial\"");

        LocalSessionStore reopened = new LocalSessionStore(root);
        try (LocalSessionStore.Handle handle = reopened.open("s1", LocalSessionStore.Access.READ)) {
            assertEquals(1, handle.log().size(), "only the committed record survives");
        }
    }

    @Test
    @DisplayName("a version marker is recorded so a foreign log can be refused")
    void versionMarkerIsRecorded(@TempDir Path root) throws IOException {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        try (LocalSessionStore.Handle handle = store.open("s1", LocalSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m1", "hello"));
            handle.flush();
        }

        String first = Files.readAllLines(store.logPath("s1")).get(0);

        assertTrue(first.contains("\"version\""), "the log records its format version: " + first);
        assertEquals(
                SessionFormatVersion.CURRENT,
                new LocalSessionStore(root).stat("s1").version(),
                "a current log reports the current version");
    }

    @Test
    @DisplayName("stat reports the committed event count without reading events")
    void statReportsEventCount(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        try (LocalSessionStore.Handle handle = store.open("s1", LocalSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m1", "one"));
            handle.append(TIME, user("m2", "two"));
            handle.flush();
        }

        assertEquals(2, store.stat("s1").eventCount());
        assertEquals("s1", store.stat("s1").sessionId());
    }

    @Test
    @DisplayName("a log written by an unknown future version is refused rather than misread")
    void futureVersionIsRefused(@TempDir Path root) throws IOException {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        try (LocalSessionStore.Handle handle = store.open("s1", LocalSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m1", "hello"));
            handle.flush();
        }
        Path logFile = store.logPath("s1");
        Files.writeString(
                logFile, Files.readString(logFile).replace("\"version\":1", "\"version\":99"));

        LocalSessionStore reopened = new LocalSessionStore(root);
        LocalSessionStore.FormatUnsupportedException failure =
                assertThrows(
                        LocalSessionStore.FormatUnsupportedException.class,
                        () -> reopened.open("s1", LocalSessionStore.Access.READ));
        assertTrue(failure.getMessage().contains("99"), failure.getMessage());
    }

    @Test
    @DisplayName("list only reports readable sessions")
    void listReportsOnlyReadableSessions(@TempDir Path root) throws IOException {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("good");
        store.create("bad");
        try (LocalSessionStore.Handle good = store.open("good", LocalSessionStore.Access.WRITE);
                LocalSessionStore.Handle bad = store.open("bad", LocalSessionStore.Access.WRITE)) {
            good.append(TIME, user("m1", "hello"));
            bad.append(TIME, user("m1", "hello"));
            good.flush();
            bad.flush();
        }
        Files.writeString(
                store.logPath("bad"),
                Files.readString(store.logPath("bad")).replace("\"version\":1", "\"version\":99"));

        List<String> listed = new LocalSessionStore(root).list();

        assertTrue(listed.contains("good"));
        assertFalse(listed.contains("bad"), "an unreadable session is omitted from list");
    }
}
