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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.message.ThinkingBlock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the stored representation of an event: it must round-trip losslessly.
 *
 * <p>A reader that cannot recover exactly what was recorded would silently change model history, so
 * content blocks use the message model's own polymorphic form rather than a parallel one.
 */
class EventCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("a user message round-trips its content blocks")
    void userMessageRoundTrips() {
        SessionEvent event =
                SessionEvent.surface(
                        SessionSeq.Seq.of(0),
                        1_700_000_000_000L,
                        SessionEventData.UserMessageData.of(
                                "m1", List.of(TextBlock.builder().text("hello").build())),
                        SurfaceOp.append(),
                        List.of());

        SessionEvent restored = roundTrip(event);

        SessionEventData.UserMessageData data =
                assertInstanceOf(SessionEventData.UserMessageData.class, restored.data());
        assertEquals("m1", data.messageId());
        assertEquals(1, data.content().size());
        assertEquals("hello", ((TextBlock) data.content().get(0)).getText());
    }

    @Test
    @DisplayName("reasoning survives the round trip as a thinking block")
    void reasoningSurvivesRoundTrip() {
        SessionEvent event =
                SessionEvent.surface(
                        SessionSeq.Seq.of(0),
                        1_700_000_000_000L,
                        new SessionEventData.AssistantMessageData(
                                1L,
                                2L,
                                "a1",
                                List.of(
                                        ThinkingBlock.builder().thinking("weighing").build(),
                                        TextBlock.builder().text("answer").build()),
                                List.of(
                                        new AssistantStreamRecord.ReasoningChunks(
                                                1_700_000_000_000L,
                                                0,
                                                List.of(),
                                                List.of("weighing"))),
                                new SessionEventData.Usage(10L, 20L, 5L, 0L, 3L),
                                false),
                        SurfaceOp.append(),
                        List.of());

        SessionEvent restored = roundTrip(event);

        SessionEventData.AssistantMessageData data =
                assertInstanceOf(SessionEventData.AssistantMessageData.class, restored.data());
        assertEquals(2, data.content().size());
        assertInstanceOf(ThinkingBlock.class, data.content().get(0));
        assertEquals("weighing", ((ThinkingBlock) data.content().get(0)).getThinking());
        assertEquals(1, data.stream().size());
        assertInstanceOf(AssistantStreamRecord.ReasoningChunks.class, data.stream().get(0));
        assertEquals(10L, data.usage().inputTokens());
        assertEquals(3L, data.usage().reasoningTokens());
        assertEquals(1L, data.turn());
        assertEquals(2L, data.step());
    }

    @Test
    @DisplayName("a replacement records its operation and shadowed sources")
    void replacementRoundTrips() {
        SessionEvent event =
                SessionEvent.surface(
                        SessionSeq.Seq.of(3),
                        1_700_000_000_000L,
                        SessionEventData.UserMessageData.of(
                                "summary", List.of(TextBlock.builder().text("condensed").build())),
                        SurfaceOp.replace(SessionSeq.Seq.of(0), SessionSeq.Seq.of(1)),
                        List.of(SessionSeq.Seq.of(0), SessionSeq.Seq.of(1)));

        SessionEvent restored = roundTrip(event);

        SurfaceOp.Replace replace = assertInstanceOf(SurfaceOp.Replace.class, restored.surfaceOp());
        assertEquals(0, replace.start().value());
        assertEquals(1, replace.end().value());
        assertEquals(
                List.of(SessionSeq.Seq.of(0), SessionSeq.Seq.of(1)), restored.sourceEventSeqs());
    }

    @Test
    @DisplayName("a log-only lifecycle event round-trips its reason")
    void lifecycleRoundTrips() {
        SessionEvent event =
                SessionEvent.logOnly(
                        SessionSeq.Seq.of(0),
                        1_700_000_000_000L,
                        new SessionExecutionData.TurnEnd(
                                2L, SessionExecutionData.TurnEndReason.INTERRUPTED));

        SessionEvent restored = roundTrip(event);

        SessionExecutionData.TurnEnd end =
                assertInstanceOf(SessionExecutionData.TurnEnd.class, restored.data());
        assertEquals(2L, end.turn());
        assertEquals(SessionExecutionData.TurnEndReason.INTERRUPTED, end.reason());
    }

    @Test
    @DisplayName("an extension record keeps its opaque payload")
    void extensionRecordRoundTrips() {
        SessionEvent event =
                SessionEvent.logOnly(
                        SessionSeq.Seq.of(0),
                        1_700_000_000_000L,
                        new SessionEventData.PayloadData(
                                "acme/review_note", java.util.Map.of("note", "ok")));

        SessionEvent restored = roundTrip(event);

        SessionEventData.PayloadData data =
                assertInstanceOf(SessionEventData.PayloadData.class, restored.data());
        assertEquals("acme/review_note", data.type());
        assertEquals("ok", data.fields().get("note"));
    }

    @Test
    @DisplayName("an unrecognized required record makes the log refuse to fold")
    void unknownRequiredRecordIsRefused() {
        SessionLog log = new SessionLog();
        SessionEvent unknown =
                SessionEvent.logOnly(
                        SessionSeq.Seq.of(0),
                        1_700_000_000_000L,
                        new SessionEventData.PayloadData("acme/unknown", java.util.Map.of()));

        log.append(unknown);

        assertEquals(1, log.size(), "a log-only extension record is stored like any other event");
        assertTrue(
                !log.events().get(0).ignorable(),
                "an unmarked extension record is required, so an unaware reader must refuse");
    }

    @Test
    @DisplayName("a settled attempt keeps its embedded stream")
    void settledAttemptKeepsItsStream() {
        SessionEvent event =
                SessionEvent.logOnly(
                        SessionSeq.Seq.of(0),
                        1_700_000_000_000L,
                        new SessionExecutionData.AssistantAttempt(
                                1L,
                                1L,
                                java.util.List.of(
                                        new AssistantStreamRecord.TextChunks(
                                                1_700_000_000_100L,
                                                0,
                                                java.util.List.of(50L),
                                                java.util.List.of("", "hello")))));

        SessionEvent restored = roundTrip(event);

        SessionExecutionData.AssistantAttempt attempt =
                assertInstanceOf(SessionExecutionData.AssistantAttempt.class, restored.data());
        assertEquals(
                1,
                attempt.stream().size(),
                "a lost stream would silently drop token accounting and first-token timing");
        AssistantStreamRecord.TextChunks run =
                assertInstanceOf(AssistantStreamRecord.TextChunks.class, attempt.stream().get(0));
        assertEquals(java.util.List.of("", "hello"), run.texts());
        assertEquals(1_700_000_000_100L, run.time0());
    }

    @Test
    @DisplayName("a replacement's cited source events survive a persist and reopen")
    void replacementSourcesSurviveReopen() throws java.io.IOException {
        java.nio.file.Path root =
                java.nio.file.Files.createTempDirectory("codec-replacement-sources");
        LocalSessionStore store = new LocalSessionStore(root);
        store.create("s1");

        // Record one node, then a replacement that cites it. Both go through the handle, which is
        // what persists them.
        try (LocalSessionStore.Handle handle = store.open("s1", LocalSessionStore.Access.WRITE)) {
            handle.append(
                    1_700_000_000_000L,
                    SessionEventData.UserMessageData.of(
                            "m1", List.of(TextBlock.builder().text("original").build())));
            handle.append(
                    1_700_000_000_100L,
                    SessionEventData.UserMessageData.of(
                            "m2", List.of(TextBlock.builder().text("condensed").build())),
                    SurfaceOp.replace(SessionSeq.Seq.of(0), SessionSeq.Seq.of(0)),
                    List.of(SessionSeq.Seq.of(0)));
            handle.flush();
        }

        try (LocalSessionStore.Handle handle =
                new LocalSessionStore(root).open("s1", LocalSessionStore.Access.READ)) {
            SessionEvent replacement = handle.log().eventAt(SessionSeq.Seq.of(1));
            assertInstanceOf(SurfaceOp.Replace.class, replacement.surfaceOp());
            assertEquals(
                    List.of(SessionSeq.Seq.of(0)),
                    replacement.sourceEventSeqs(),
                    "a replacement that lost its citations could not be traced back");
        }
    }

    @Test
    @DisplayName("a request header round-trips a reason written in the recording protocol's form")
    void requestHeaderRoundTripsProtocolReason() {
        SessionEvent event =
                SessionEvent.logOnly(
                        SessionSeq.Seq.of(0),
                        1_700_000_000_000L,
                        new SessionRequestData.RequestHeader(
                                SessionRequestData.RequestHeaderReason.INITIAL,
                                false,
                                List.of("search"),
                                java.util.Map.of("model", "fake"),
                                java.util.Map.of()));

        // The recording protocol names a reason in lower case; the stored form must accept it, or a
        // header written by the recorder cannot be read back.
        java.util.Map<String, Object> encoded = SessionEventCodec.encode(event, mapper);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> fields = (java.util.Map<String, Object>) encoded.get("data");
        fields.put("reason", "initial");

        SessionEvent restored = SessionEventCodec.decode(encoded, mapper, 0);

        SessionRequestData.RequestHeader header =
                assertInstanceOf(SessionRequestData.RequestHeader.class, restored.data());
        assertEquals(SessionRequestData.RequestHeaderReason.INITIAL, header.reason());
    }

    @Test
    @DisplayName("a title revision survives the round trip as its own payload type")
    void titleRevisionKeepsItsType() {
        SessionEvent event =
                SessionEvent.logOnly(
                        SessionSeq.Seq.of(0),
                        1_700_000_000_000L,
                        new SessionTitle.TitleData("chosen title", SessionTitle.Source.USER));

        SessionEvent restored = roundTrip(event);

        SessionTitle.TitleData data =
                assertInstanceOf(
                        SessionTitle.TitleData.class,
                        restored.data(),
                        "a title read back as an opaque payload would silently lose the session"
                                + " name");
        assertEquals("chosen title", data.title());
        assertEquals(SessionTitle.Source.USER, data.source());
    }

    private SessionEvent roundTrip(SessionEvent event) {
        java.util.Map<String, Object> encoded = SessionEventCodec.encode(event, mapper);
        return SessionEventCodec.decode(encoded, mapper, (int) event.seq().value());
    }
}
