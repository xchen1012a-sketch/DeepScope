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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.message.TextBlock;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins text search over stored sessions.
 *
 * <p>Search covers the text a reader would recognise: what the user asked, what the model answered,
 * and what tools returned. It never reads a query as syntax, because the query is data a person
 * typed.
 */
class SessionSearchTest {

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
            handle.append(TIME + 1, user("m1", "compare Deployment Cost and Reliability"));
            handle.append(
                    TIME + 2,
                    new SessionEventData.AssistantMessageData(
                            1L,
                            1L,
                            "a1",
                            List.of(TextBlock.builder().text("Deployment cost is lower").build()),
                            List.of(),
                            null,
                            false));
            handle.flush();
        }
        store.create("s2");
        try (LocalSessionStore.Handle handle = store.open("s2", LocalSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m2", "unrelated topic"));
            handle.flush();
        }
        return store;
    }

    @Test
    @DisplayName("a literal match reports the session and its matching position")
    void literalMatchReportsSessionAndPosition(@TempDir Path root) {
        SessionSearch search = new SessionSearch(new SessionQuery(seeded(root)));

        List<SessionSearch.Hit> hits = search.search("Deployment");

        // "Deployment" appears in the prompt and in the answer, so both surface nodes match.
        assertEquals(2, hits.size());
        assertEquals("s1", hits.get(0).sessionId());
        assertEquals(SessionSeq.Seq.of(1), hits.get(0).seq());
        assertEquals(SessionSeq.Seq.of(2), hits.get(1).seq());
    }

    @Test
    @DisplayName("matching is case-insensitive")
    void matchingIsCaseInsensitive(@TempDir Path root) {
        SessionSearch search = new SessionSearch(new SessionQuery(seeded(root)));

        assertEquals(2, search.search("deployment").size());
        assertEquals(2, search.search("DEPLOYMENT").size());
    }

    @Test
    @DisplayName("assistant text is searchable")
    void assistantTextIsSearchable(@TempDir Path root) {
        SessionSearch search = new SessionSearch(new SessionQuery(seeded(root)));

        List<SessionSearch.Hit> hits = search.search("lower");

        assertEquals(1, hits.size());
        assertEquals(SessionSeq.Seq.of(2), hits.get(0).seq());
    }

    @Test
    @DisplayName("whitespace in the query is flexible, so a spaced phrase still matches")
    void whitespaceIsFlexible(@TempDir Path root) {
        SessionSearch search = new SessionSearch(new SessionQuery(seeded(root)));

        // A phrase present in one message only: the answer drops the conjunction.
        assertEquals(1, search.search("Cost   and").size(), "a spaced phrase still matches");
    }

    @Test
    @DisplayName("a query is literal data, not search syntax")
    void queryIsLiteralData(@TempDir Path root) {
        SessionSearch search = new SessionSearch(new SessionQuery(seeded(root)));

        assertEquals(0, search.search("Deployment OR unrelated").size());
        assertEquals(0, search.search("\"Deployment\"").size(), "quotes are part of the text");
    }

    @Test
    @DisplayName("no match reports nothing")
    void noMatchReportsNothing(@TempDir Path root) {
        SessionSearch search = new SessionSearch(new SessionQuery(seeded(root)));

        assertEquals(List.of(), search.search("nonexistent phrase"));
    }

    @Test
    @DisplayName("an empty query matches nothing rather than everything")
    void emptyQueryMatchesNothing(@TempDir Path root) {
        SessionSearch search = new SessionSearch(new SessionQuery(seeded(root)));

        assertEquals(List.of(), search.search("   "));
    }

    @Test
    @DisplayName("matches span sessions and are grouped in session order")
    void matchesSpanSessions(@TempDir Path root) {
        LocalSessionStore store = seeded(root);
        try (LocalSessionStore.Handle handle = store.open("s2", LocalSessionStore.Access.WRITE)) {
            handle.append(TIME + 5, user("m3", "deployment again"));
            handle.flush();
        }
        SessionSearch search = new SessionSearch(new SessionQuery(store));

        List<SessionSearch.Hit> hits = search.search("deployment");

        // s1 matches twice (prompt and answer) because the answer repeats the word.
        assertEquals(3, hits.size());
        assertEquals("s1", hits.get(0).sessionId());
        assertEquals("s1", hits.get(1).sessionId());
        assertEquals("s2", hits.get(2).sessionId());
    }

    @Test
    @DisplayName("a hit carries a bounded snippet of the matched text")
    void hitCarriesASnippet(@TempDir Path root) {
        SessionSearch search = new SessionSearch(new SessionQuery(seeded(root)));

        String snippet = search.search("Deployment").get(0).snippet();

        assertTrue(snippet.toLowerCase().contains("deployment"), snippet);
        assertTrue(
                snippet.length() <= SessionSearch.SNIPPET_MAX_CHARS,
                "a snippet is bounded so one long message cannot flood a result list");
    }

    @Test
    @DisplayName("a long message yields a bounded snippet around the match")
    void longMessageYieldsBoundedSnippet(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s3");
        try (LocalSessionStore.Handle handle = store.open("s3", LocalSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m1", "x".repeat(400) + " needle " + "y".repeat(400)));
            handle.flush();
        }
        SessionSearch search = new SessionSearch(new SessionQuery(store));

        String snippet = search.search("needle").get(0).snippet();

        assertTrue(snippet.contains("needle"));
        assertTrue(snippet.length() <= SessionSearch.SNIPPET_MAX_CHARS);
    }

    @Test
    @DisplayName("a query longer than the snippet budget is still matched")
    void longQueryIsStillMatched(@TempDir Path root) {
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s4");
        String longText = "alpha beta gamma delta epsilon zeta eta theta";
        try (LocalSessionStore.Handle handle = store.open("s4", LocalSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m1", longText));
            handle.flush();
        }
        SessionSearch search = new SessionSearch(new SessionQuery(store));

        assertEquals(1, search.search(longText).size());
    }

    @Test
    @DisplayName("a limit bounds the reported hits")
    void limitBoundsHits(@TempDir Path root) {
        SessionSearch search = new SessionSearch(new SessionQuery(seeded(root)));

        assertEquals(1, search.search("deployment", 1).size());
    }
}
