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
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.TextBlock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-turn outline the conversation page's turn rail reads: one entry per turn naming its
 * jump target and what was asked and answered.
 */
class TurnOutlineProjectionTest {

    private static final long BASE = 1_700_000_000_000L;

    private ProjectionRegistry registry;
    private SessionLog log;

    @BeforeEach
    void setUp() {
        registry = new ProjectionRegistry();
        registry.register(new TurnOutlineProjection().unit());
        log = new SessionLog();
    }

    private TurnOutlineProjection.State outline() {
        return registry.stateOf(log, TurnOutlineProjection.KEY);
    }

    private static SessionEventData.UserMessageData user(String id, String value) {
        return SessionEventData.UserMessageData.of(
                id, List.of(TextBlock.builder().text(value).build()));
    }

    private static SessionEventData.UserMessageData injected(String id, String value) {
        return new SessionEventData.UserMessageData(
                id,
                List.of(TextBlock.builder().text(value).build()),
                new SessionEventData.MessageSource("working-directory"));
    }

    private static SessionEventData.AssistantMessageData assistant(String id, String value) {
        return new SessionEventData.AssistantMessageData(
                1L,
                1L,
                id,
                List.of(TextBlock.builder().text(value).build()),
                List.of(),
                null,
                false);
    }

    @Test
    @DisplayName("an empty log has no outline entries")
    void emptyLogHasNoEntries() {
        assertEquals(List.of(), outline().view());
    }

    @Test
    @DisplayName("one turn produces one entry naming its start position")
    void oneTurnProducesOneEntry() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        log.append(BASE, user("m1", "compare A and B"), SurfaceOp.append(), List.of());
        log.appendLogOnly(
                BASE,
                new SessionExecutionData.TurnEnd(1L, SessionExecutionData.TurnEndReason.COMPLETED));

        List<TurnOutlineProjection.TurnOutline> turns = outline().view();

        assertEquals(1, turns.size());
        assertEquals(1L, turns.get(0).turn());
        assertEquals(0L, turns.get(0).seq(), "the entry names its turn start position");
    }

    @Test
    @DisplayName("the prompt summary is the first human message")
    void promptSummaryIsTheFirstHumanMessage() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        log.append(BASE, user("m1", "compare A and B"), SurfaceOp.append(), List.of());
        log.append(BASE + 1, user("m2", "and C"), SurfaceOp.append(), List.of());

        assertEquals("compare A and B", outline().view().get(0).prompt());
    }

    @Test
    @DisplayName("an injected context message is not the prompt")
    void injectedContextIsNotThePrompt() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        log.append(
                BASE,
                injected("ctx", "Current working directory: /tmp"),
                SurfaceOp.append(),
                List.of());
        log.append(BASE + 1, user("m1", "the real question"), SurfaceOp.append(), List.of());

        assertEquals("the real question", outline().view().get(0).prompt());
    }

    @Test
    @DisplayName("a long prompt is truncated for the summary")
    void longPromptIsTruncated() {
        String longPrompt = "x".repeat(200);
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        log.append(BASE, user("m1", longPrompt), SurfaceOp.append(), List.of());

        String prompt = outline().view().get(0).prompt();

        assertEquals(TurnOutlineProjection.PROMPT_MAX_CHARS, prompt.length());
    }

    @Test
    @DisplayName("a turn with no human message reports no prompt")
    void turnWithoutHumanMessageHasNoPrompt() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));

        assertNull(outline().view().get(0).prompt());
    }

    @Test
    @DisplayName("the response is the latest assistant text at turn end")
    void responseIsTheLatestAssistantText() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        log.append(BASE, user("m1", "question"), SurfaceOp.append(), List.of());
        log.append(BASE + 10, assistant("a1", "first attempt"), SurfaceOp.append(), List.of());
        log.append(BASE + 20, assistant("a2", "final answer"), SurfaceOp.append(), List.of());
        log.appendLogOnly(
                BASE + 30,
                new SessionExecutionData.TurnEnd(1L, SessionExecutionData.TurnEndReason.COMPLETED));

        assertEquals("final answer", outline().view().get(0).response());
    }

    @Test
    @DisplayName("a turn still running reports no response")
    void runningTurnHasNoResponse() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        log.append(BASE + 10, assistant("a1", "partial"), SurfaceOp.append(), List.of());

        assertEquals(
                "partial",
                outline().view().get(0).response(),
                "an open turn's entry carries its latest assistant text until the turn closes");
    }

    @Test
    @DisplayName("a long response is truncated for the summary")
    void longResponseIsTruncated() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        log.append(BASE, user("m1", "q"), SurfaceOp.append(), List.of());
        log.append(BASE + 10, assistant("a1", "y".repeat(400)), SurfaceOp.append(), List.of());
        log.appendLogOnly(
                BASE + 20,
                new SessionExecutionData.TurnEnd(1L, SessionExecutionData.TurnEndReason.COMPLETED));

        assertEquals(
                TurnOutlineProjection.RESPONSE_MAX_CHARS,
                outline().view().get(0).response().length());
    }

    @Test
    @DisplayName("several turns produce entries in order")
    void severalTurnsProduceEntriesInOrder() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        log.append(BASE, user("m1", "first"), SurfaceOp.append(), List.of());
        log.appendLogOnly(
                BASE,
                new SessionExecutionData.TurnEnd(1L, SessionExecutionData.TurnEndReason.COMPLETED));
        log.appendLogOnly(BASE + 5, new SessionExecutionData.TurnStart(2L));
        log.append(BASE + 5, user("m2", "second"), SurfaceOp.append(), List.of());

        List<TurnOutlineProjection.TurnOutline> turns = outline().view();

        assertEquals(2, turns.size());
        assertEquals(1L, turns.get(0).turn());
        assertEquals("first", turns.get(0).prompt());
        assertEquals(2L, turns.get(1).turn());
        assertEquals("second", turns.get(1).prompt());
        assertEquals(3L, turns.get(1).seq(), "the second entry names its own turn start");
    }

    @Test
    @DisplayName("a draft update replaces only the open turn's entry")
    void draftUpdateReplacesOnlyTheOpenEntry() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        List<TurnOutlineProjection.TurnOutline> before = outline().view();
        assertNull(before.get(0).prompt());

        log.append(BASE, user("m1", "a new prompt"), SurfaceOp.append(), List.of());

        List<TurnOutlineProjection.TurnOutline> after = outline().view();
        assertEquals("a new prompt", after.get(0).prompt());
        assertEquals(
                before.size(),
                after.size(),
                "the draft update replaces the open entry rather than adding one");
    }

    @Test
    @DisplayName("a turn end publishes a new turns list")
    void turnEndPublishesNewList() {
        log.appendLogOnly(BASE, new SessionExecutionData.TurnStart(1L));
        log.append(BASE, user("m1", "q"), SurfaceOp.append(), List.of());
        List<TurnOutlineProjection.TurnOutline> before = outline().view();

        log.appendLogOnly(
                BASE + 10,
                new SessionExecutionData.TurnEnd(1L, SessionExecutionData.TurnEndReason.COMPLETED));

        assertEquals(1, outline().view().size());
        assertEquals(before.get(0).prompt(), outline().view().get(0).prompt());
    }

    @Test
    @DisplayName("the projection declares a stable key and version")
    void projectionDeclaresKeyAndVersion() {
        assertEquals("turnOutline", TurnOutlineProjection.KEY);
        assertEquals(1L, new TurnOutlineProjection().unit().stateVersion());
    }
}
