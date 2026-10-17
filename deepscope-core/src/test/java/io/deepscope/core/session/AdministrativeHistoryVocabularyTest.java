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
package io.deepscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.message.UserMessage;
import io.deepscope.core.session.log.DurableSession;
import io.deepscope.core.session.log.SessionEvent;
import io.deepscope.core.session.log.SessionExecutionData;
import io.deepscope.core.state.AgentState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the vocabulary an administrative state change records.
 *
 * <p>Administration is a second producer of story events beside the execution recorder. Both must
 * write one vocabulary, or a reader has to know which writer produced a record before it can
 * interpret it.
 */
class AdministrativeHistoryVocabularyTest {

    @Test
    @DisplayName("a turn closing records one event carrying why it ended")
    void turnClosingRecordsOneEventWithItsReason() {
        var store = new InMemorySessionLogStore();
        var key = new SessionKey("u", "agent", "turn-end");
        var context =
                io.deepscope.core.agent.RuntimeContext.builder()
                        .userId("u")
                        .sessionId("turn-end")
                        .build();
        var state = AgentState.builder().sessionId("turn-end").userId("u").build();
        try (DurableSession recorder = store.openWriter(key, "run-1", "turn-1", context)) {
            // finish() is reactive: it does nothing until subscribed.
            recorder.finish(state, "suspended").block(Duration.ofSeconds(10));
        }

        var turnEnds = new ArrayList<SessionEvent>();
        for (SessionEvent event : store.readKernel(key, context).events()) {
            if (event.type().equals("turn/end")) {
                turnEnds.add(event);
            }
        }

        assertFalse(
                turnEnds.isEmpty(),
                "a closed turn must record one turn/end event rather than a status-named event");
        var closing =
                assertInstanceOf(
                        SessionExecutionData.TurnEnd.class,
                        turnEnds.get(turnEnds.size() - 1).data());
        assertEquals(
                SessionExecutionData.TurnEndReason.SUSPENDED,
                closing.reason(),
                "the reason a person needs to see must travel with the closing event");
    }

    @Test
    @DisplayName("an administrative history change records unified message names")
    void administrativeChangeRecordsUnifiedNames() {
        var store = new InMemorySessionLogStore();
        var key = new SessionKey("u", "agent", "s");
        var context =
                io.deepscope.core.agent.RuntimeContext.builder().userId("u").sessionId("s").build();

        try (DurableSession writer = store.openWriter(key, "run-1", "turn-1", context)) {
            writer.captureState(
                    AgentState.builder()
                            .sessionId("s")
                            .userId("u")
                            .context(
                                    new ArrayList<>(
                                            List.of(new UserMessage("added administratively"))))
                            .build(),
                    "test");
        }

        var types = new ArrayList<String>();
        for (SessionEvent event : store.readKernel(key, context).events()) {
            types.add(event.type());
        }

        assertTrue(
                types.contains("user/message"),
                "administration must write the unified name, got " + types);
        assertFalse(
                types.contains("message/user"), "the superseded name must not appear: " + types);
    }
}
