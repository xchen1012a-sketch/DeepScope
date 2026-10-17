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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the export form: a backend-neutral, normalized record stream that another tool can read
 * without knowing how the session was stored.
 */
class SessionExportTest {

    private static final long TIME = 1_700_000_000_000L;

    private static SessionEventData.UserMessageData user(String id, String value) {
        return SessionEventData.UserMessageData.of(
                id, List.of(TextBlock.builder().text(value).build()));
    }

    private static LocalSessionStore seeded(Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");
        try (LocalSessionStore.Handle handle = store.open("s1", LocalSessionStore.Access.WRITE)) {
            handle.appendLogOnly(TIME, new SessionExecutionData.TurnStart(1L));
            handle.append(TIME + 1, user("m1", "compare A and B"));
            handle.appendLogOnly(TIME + 2, new SessionExecutionData.StepStart(1L, 1L));
            handle.append(
                    TIME + 3,
                    SessionEventData.UserMessageData.of(
                            "m2", List.of(TextBlock.builder().text("condensed").build())),
                    SurfaceOp.replace(SessionSeq.Seq.of(1), SessionSeq.Seq.of(1)),
                    List.of(SessionSeq.Seq.of(1)));
            handle.flush();
        }
        return store;
    }

    @Test
    @DisplayName("the export starts with a header naming the format version")
    void exportStartsWithAHeader(@TempDir Path root) {
        String exported = SessionExport.of(new SessionQuery(seeded(root)), "s1");

        String firstLine = exported.lines().findFirst().orElseThrow();
        assertTrue(firstLine.contains("\"version\""), firstLine);
        assertTrue(firstLine.contains(String.valueOf(SessionFormatVersion.CURRENT)), firstLine);
    }

    @Test
    @DisplayName("one line is written per committed event")
    void oneLinePerEvent(@TempDir Path root) {
        String exported = SessionExport.of(new SessionQuery(seeded(root)), "s1");

        assertEquals(5, exported.lines().count(), "a header plus four committed events");
    }

    @Test
    @DisplayName("the export ends with exactly one newline")
    void exportEndsWithOneNewline(@TempDir Path root) {
        String exported = SessionExport.of(new SessionQuery(seeded(root)), "s1");

        assertTrue(exported.endsWith("\n"));
        assertTrue(!exported.endsWith("\n\n"), "a trailing blank line would read as a torn record");
    }

    @Test
    @DisplayName("shadowed events are exported, because the log keeps them")
    void shadowedEventsAreExported(@TempDir Path root) {
        String exported = SessionExport.of(new SessionQuery(seeded(root)), "s1");

        assertTrue(exported.contains("compare A and B"), "a shadowed node is still history");
        assertTrue(exported.contains("condensed"));
    }

    @Test
    @DisplayName("a replacement keeps its citations in the export")
    void replacementKeepsItsCitations(@TempDir Path root) {
        String exported = SessionExport.of(new SessionQuery(seeded(root)), "s1");

        assertTrue(exported.contains("\"surfaceOp\""), "the placement is part of the record");
        assertTrue(exported.contains("\"sourceEventSeqs\""), "so are the citations");
    }

    @Test
    @DisplayName("the exported bytes are UTF-8 and round-trip through the reader")
    void exportIsUtf8AndReadable(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s2");
        try (LocalSessionStore.Handle handle = store.open("s2", LocalSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m1", "星星 star"));
            handle.flush();
        }

        String exported = SessionExport.of(new SessionQuery(store), "s2");
        byte[] bytes = exported.getBytes(StandardCharsets.UTF_8);

        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("星星"));
        assertTrue(
                exported.contains("\u661F\u661F") || exported.contains("星星"),
                "a multi-byte character must survive the export");
    }

    @Test
    @DisplayName("an unknown session is refused")
    void unknownSessionIsRefused(@TempDir Path root) {
        SessionQuery query = new SessionQuery(seeded(root));

        assertThrows(
                LocalSessionStore.SessionNotFoundException.class,
                () -> SessionExport.of(query, "missing"));
    }

    @Test
    @DisplayName("a bounded export names the position it stopped at")
    void boundedExportNamesItsPosition(@TempDir Path root) {
        SessionExport.Bounded bounded =
                SessionExport.upTo(new SessionQuery(seeded(root)), "s1", SessionSeq.Seq.of(1));

        assertEquals(SessionSeq.Seq.of(1), bounded.throughSeq());
        assertEquals(3, bounded.jsonl().lines().count(), "header plus events zero and one");
    }

    @Test
    @DisplayName("a bound beyond the log is refused")
    void boundBeyondLogIsRefused(@TempDir Path root) {
        SessionQuery query = new SessionQuery(seeded(root));

        assertThrows(
                IllegalArgumentException.class,
                () -> SessionExport.upTo(query, "s1", SessionSeq.Seq.of(99)));
    }
}
