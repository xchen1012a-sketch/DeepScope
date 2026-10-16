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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins request-envelope folding.
 *
 * <p>History is derived from the surface, so the envelope is what makes a request reconstructable:
 * without the latest snapshot a reader can see what the model said but not what it was asked.
 */
class RequestEnvelopeTest {

    private static final long TIME = 1_700_000_000_000L;

    private static SessionRequestData.RequestHeader header(
            List<String> tools, Map<String, Object> config) {
        return new SessionRequestData.RequestHeader(
                SessionRequestData.RequestHeaderReason.INITIAL, false, tools, config, Map.of());
    }

    @Test
    @DisplayName("a log with no header folds to nothing")
    void noHeaderFoldsToNothing() {
        SessionLog log = new SessionLog();

        assertNull(SessionRequestData.foldRequestHeader(log.events()));
        assertNull(SessionRequestData.foldRequestContext(log.events()));
    }

    @Test
    @DisplayName("the latest snapshot is the envelope the next request inherits")
    void latestSnapshotWins() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, header(List.of("search"), Map.of("model", "a")));
        log.appendLogOnly(TIME, header(List.of("search", "read_file"), Map.of("model", "b")));

        SessionRequestData.RequestHeader folded =
                SessionRequestData.foldRequestHeader(log.events());

        assertEquals(List.of("search", "read_file"), folded.toolNames());
        assertEquals("b", folded.config().get("model"));
    }

    @Test
    @DisplayName("a header snapshot is log-only and contributes no message")
    void headerIsLogOnly() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, header(List.of("search"), Map.of("model", "a")));

        assertEquals(1, log.size());
        assertEquals(List.of(), log.surfaceNodes());
        assertEquals(List.of(), log.deriveMessages());
    }

    @Test
    @DisplayName("the first header in an empty log is initial")
    void firstHeaderIsInitial() {
        SessionLog log = new SessionLog();

        assertEquals(
                SessionRequestData.RequestHeaderReason.INITIAL,
                SessionRequestData.reasonFor(log, header(List.of(), Map.of("model", "a"))));
    }

    @Test
    @DisplayName("a changed envelope is a change")
    void changedEnvelopeIsChange() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, header(List.of("search"), Map.of("model", "a")));

        assertEquals(
                SessionRequestData.RequestHeaderReason.CHANGE,
                SessionRequestData.reasonFor(
                        log, header(List.of("search", "read_file"), Map.of("model", "a"))));
    }

    @Test
    @DisplayName("an unchanged envelope that starts a series is a series")
    void unchangedEnvelopeIsSeries() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, header(List.of("search"), Map.of("model", "a")));

        assertEquals(
                SessionRequestData.RequestHeaderReason.SERIES,
                SessionRequestData.reasonFor(log, header(List.of("search"), Map.of("model", "a"))));
    }

    @Test
    @DisplayName("an explicit series start is a series even when the envelope changed")
    void explicitSeriesStartWins() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, header(List.of("search"), Map.of("model", "a")));

        SessionRequestData.RequestHeader changed =
                new SessionRequestData.RequestHeader(
                        SessionRequestData.RequestHeaderReason.INITIAL,
                        true,
                        List.of("search", "read_file"),
                        Map.of("model", "a"),
                        Map.of());

        assertEquals(
                SessionRequestData.RequestHeaderReason.SERIES,
                SessionRequestData.reasonFor(log, changed));
    }

    @Test
    @DisplayName("route metadata folds to the latest recorded context")
    void routeMetadataFoldsToLatest() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(
                TIME, new SessionRequestData.RequestContext("anthropic", "claude", 200_000L));
        log.appendLogOnly(TIME, new SessionRequestData.RequestContext("openai", "gpt", 128_000L));

        SessionRequestData.RequestContext folded =
                SessionRequestData.foldRequestContext(log.events());

        assertEquals("openai", folded.provider());
        assertEquals(128_000L, folded.contextWindow());
    }

    @Test
    @DisplayName("a route without an advertised window reports none")
    void routeWithoutWindowReportsNone() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, new SessionRequestData.RequestContext("local", "llama", null));

        assertNull(SessionRequestData.foldRequestContext(log.events()).contextWindow());
    }

    @Test
    @DisplayName("a non-positive context window is rejected")
    void nonPositiveWindowIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SessionRequestData.RequestContext("local", "llama", 0L));
    }

    @Test
    @DisplayName("envelope equality ignores the reason and the series marker")
    void envelopeEqualityComparesOnlyTheRequest() {
        SessionRequestData.RequestHeader first =
                new SessionRequestData.RequestHeader(
                        SessionRequestData.RequestHeaderReason.INITIAL,
                        false,
                        List.of("search"),
                        Map.of("model", "a"),
                        Map.of("maxTokens", 100));
        SessionRequestData.RequestHeader second =
                new SessionRequestData.RequestHeader(
                        SessionRequestData.RequestHeaderReason.SERIES,
                        true,
                        List.of("search"),
                        Map.of("model", "a"),
                        Map.of("maxTokens", 100));

        assertTrue(SessionRequestData.sameEnvelope(first, second));

        SessionRequestData.RequestHeader different =
                new SessionRequestData.RequestHeader(
                        SessionRequestData.RequestHeaderReason.CHANGE,
                        false,
                        List.of("search", "read_file"),
                        Map.of("model", "a"),
                        Map.of("maxTokens", 100));

        assertFalse(SessionRequestData.sameEnvelope(first, different));
    }

    @Test
    @DisplayName("a tool-less envelope reports no tools")
    void toolLessEnvelopeReportsNoTools() {
        SessionLog log = new SessionLog();
        log.appendLogOnly(TIME, header(List.of(), Map.of("model", "a")));

        assertEquals(List.of(), SessionRequestData.foldRequestHeader(log.events()).toolNames());
    }
}
