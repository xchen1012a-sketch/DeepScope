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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the session title rules: deterministic fallback, terminal-safe normalization, and the point
 * where a reader may trust the value it sees.
 */
class SessionTitleTest {

    @Nested
    @DisplayName("normalization")
    class Normalization {

        @Test
        @DisplayName("whitespace collapses to one trimmed line")
        void whitespaceCollapses() {
            assertEquals(
                    "compare A and B", SessionTitle.fallback("  compare   A\n\tand B  ", 10, 100));
        }

        @Test
        @DisplayName("terminal escape sequences are removed")
        void escapeSequencesAreRemoved() {
            assertEquals("red text", SessionTitle.fallback("\u001B[31mred\u001B[0m text", 10, 100));
        }

        @Test
        @DisplayName("an unterminated escape sequence is removed")
        void unterminatedEscapeIsRemoved() {
            assertEquals("safe", SessionTitle.fallback("safe\u001B]0;title", 10, 100));
        }

        @Test
        @DisplayName("directional overrides are removed so a title cannot read deceptively")
        void directionalOverridesAreRemoved() {
            // Stripping the override leaves the stored reading order. The reference removes the
            // character rather than reordering text, so nothing is silently rewritten.
            assertEquals(
                    "invoicefdp.pdf", SessionTitle.fallback("invoice\u202Efdp\u200E.pdf", 10, 100));
        }

        @Test
        @DisplayName("control characters are removed")
        void controlCharactersAreRemoved() {
            assertEquals("clean", SessionTitle.fallback("cl\u0007ean", 10, 100));
        }

        @Test
        @DisplayName("the word cap keeps the leading words")
        void wordCapKeepsLeadingWords() {
            assertEquals("one two", SessionTitle.fallback("one two three four", 2, 100));
        }

        @Test
        @DisplayName("the byte cap never splits a code point")
        void byteCapNeverSplitsACodePoint() {
            // Three-byte characters: a two-character prefix fits, a partial third does not.
            String value = SessionTitle.fallback("星星星星", 10, 7);

            assertEquals("星星", value);
            assertTrue(value.getBytes(StandardCharsets.UTF_8).length <= 7);
        }

        @Test
        @DisplayName("an empty input yields an empty title")
        void emptyInputYieldsEmptyTitle() {
            assertEquals("", SessionTitle.fallback("   ", 10, 100));
        }

        @Test
        @DisplayName("a non-positive limit is rejected")
        void nonPositiveLimitIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> SessionTitle.fallback("x", 0, 100));
            assertThrows(IllegalArgumentException.class, () -> SessionTitle.fallback("x", 10, 0));
        }
    }

    @Nested
    @DisplayName("folding")
    class Folding {

        @Test
        @DisplayName("a log with no title event has no explicit title")
        void noTitleEventHasNoExplicitTitle() {
            assertNull(SessionTitle.explicitTitle(new SessionLog().events()));
        }

        @Test
        @DisplayName("the latest title event wins")
        void latestTitleEventWins() {
            SessionLog log = new SessionLog();
            log.appendLogOnly(
                    1L, new SessionTitle.TitleData("first", SessionTitle.Source.PROVIDER));
            log.appendLogOnly(
                    2L, new SessionTitle.TitleData("second", SessionTitle.Source.PROVIDER));

            assertEquals("second", SessionTitle.explicitTitle(log.events()));
        }

        @Test
        @DisplayName("a user-assigned title pins the session against automatic revision")
        void userTitlePinsTheSession() {
            SessionLog log = new SessionLog();
            log.appendLogOnly(1L, new SessionTitle.TitleData("mine", SessionTitle.Source.USER));

            assertTrue(SessionTitle.isPinned(log.events()));
        }

        @Test
        @DisplayName("a provider title does not pin the session")
        void providerTitleDoesNotPin() {
            SessionLog log = new SessionLog();
            log.appendLogOnly(1L, new SessionTitle.TitleData("auto", SessionTitle.Source.PROVIDER));

            assertFalse(SessionTitle.isPinned(log.events()));
        }

        @Test
        @DisplayName("a later provider title does not unpin a user title")
        void laterProviderTitleKeepsThePin() {
            SessionLog log = new SessionLog();
            log.appendLogOnly(1L, new SessionTitle.TitleData("mine", SessionTitle.Source.USER));
            log.appendLogOnly(2L, new SessionTitle.TitleData("auto", SessionTitle.Source.PROVIDER));

            assertTrue(SessionTitle.isPinned(log.events()));
            assertEquals("auto", SessionTitle.explicitTitle(log.events()));
        }

        @Test
        @DisplayName("a title event is log-only and contributes no message")
        void titleEventIsLogOnly() {
            SessionLog log = new SessionLog();
            log.appendLogOnly(
                    1L, new SessionTitle.TitleData("a title", SessionTitle.Source.PROVIDER));

            assertEquals(0, log.surfaceNodes().size());
            assertEquals(0, log.deriveMessages().size());
        }
    }

    @Nested
    @DisplayName("effective title")
    class EffectiveTitle {

        @Test
        @DisplayName("an explicit title wins over the fallback")
        void explicitTitleWins() {
            SessionLog log = new SessionLog();
            log.appendLogOnly(0L, new SessionExecutionData.TurnStart(1L));
            log.append(
                    1L,
                    SessionEventData.UserMessageData.of(
                            "m1", java.util.List.of(text("the first question"))),
                    SurfaceOp.append(),
                    java.util.List.of());
            log.appendLogOnly(2L, new SessionTitle.TitleData("chosen", SessionTitle.Source.USER));

            assertEquals("chosen", SessionTitle.effectiveTitle(log.events(), 8, 60));
        }

        @Test
        @DisplayName("with no title event the fallback names the first human message")
        void fallbackNamesTheFirstHumanMessage() {
            SessionLog log = new SessionLog();
            log.appendLogOnly(0L, new SessionExecutionData.TurnStart(1L));
            log.append(
                    1L,
                    SessionEventData.UserMessageData.of(
                            "m1", java.util.List.of(text("compare A and B carefully"))),
                    SurfaceOp.append(),
                    java.util.List.of());

            assertEquals("compare A and B", SessionTitle.effectiveTitle(log.events(), 4, 60));
        }

        @Test
        @DisplayName("an injected context message never names the session")
        void injectedContextNeverNames() {
            SessionLog log = new SessionLog();
            log.append(
                    0L,
                    new SessionEventData.UserMessageData(
                            "ctx",
                            java.util.List.of(text("Current working directory: /tmp")),
                            new SessionEventData.MessageSource("working-directory")),
                    SurfaceOp.append(),
                    java.util.List.of());
            log.append(
                    1L,
                    SessionEventData.UserMessageData.of(
                            "m1", java.util.List.of(text("the real question"))),
                    SurfaceOp.append(),
                    java.util.List.of());

            assertEquals("the real question", SessionTitle.effectiveTitle(log.events(), 8, 60));
        }

        @Test
        @DisplayName("a log with no human message and no title yields no title")
        void nothingToNameYieldsNoTitle() {
            assertNull(SessionTitle.effectiveTitle(new SessionLog().events(), 8, 60));
        }
    }

    private static io.deepscope.core.message.TextBlock text(String value) {
        return io.deepscope.core.message.TextBlock.builder().text(value).build();
    }
}
