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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.session.log.DurableSession;
import io.agentscope.core.session.log.MessageDerivation;
import io.agentscope.core.session.log.SessionEvent;
import io.agentscope.core.session.log.SessionEventData;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the source of restored model history.
 *
 * <p>Model history is derived from the log's recorded message events. The checkpoint remains the
 * source of <em>working state</em> (permissions, plan, tasks), but it is no longer the source of the
 * conversation: keeping two copies of the same history in one log invites them to disagree.
 */
class HistoryDerivedFromLogTest {

    @Test
    @DisplayName("a log with message events but no checkpoint still restores its history")
    void historySurvivesWithoutACheckpoint() {
        var store = new InMemorySessionLogStore();
        var key = SessionKeyFixture.key("stable", "s");
        var context = SessionKeyFixture.context();
        try (DurableSession recorder = store.openWriter(key, "run-1", "turn-1", context)) {
            recorder.captureState(
                    AgentState.builder()
                            .sessionId("s")
                            .userId("u")
                            .context(new ArrayList<>(List.of(new UserMessage("hello"))))
                            .build(),
                    "test");
        }

        List<Msg> restored = MessageDerivation.history(store.readKernel(key, context));

        assertEquals(1, restored.size(), "history must survive without a checkpoint");
        assertEquals("hello", restored.get(0).getTextContent());
    }

    @Test
    @DisplayName("restored history is the log's message history, not the checkpoint's copy")
    void restoredHistoryComesFromTheLog() {
        var store = new InMemorySessionLogStore();
        var key = SessionKeyFixture.key("stable", "s2");
        var context = SessionKeyFixture.context();
        try (DurableSession recorder = store.openWriter(key, "run-1", "turn-1", context)) {
            recorder.captureState(
                    AgentState.builder()
                            .sessionId("s2")
                            .userId("u")
                            .context(new ArrayList<>(List.of(new UserMessage("first"))))
                            .build(),
                    "test");
        }

        // The log's history, captured before a divergent checkpoint copy is written.
        List<Msg> fromLog = MessageDerivation.history(store.readKernel(key, context));
        // A checkpoint whose stored context is empty, while the log still holds the conversation:
        // restoring the checkpoint copy would silently drop the history.
        try (DurableSession writer = store.openWriter(key, "run-2", "turn-2", context)) {
            writer.append(
                    new SessionEventData.PayloadData(
                            "state/checkpoint",
                            Map.of(
                                    "projectorVersion",
                                    1,
                                    "stateJson",
                                    AgentState.builder()
                                            .sessionId("s2")
                                            .userId("u")
                                            .build()
                                            .toJson(),
                                    "reason",
                                    "test")));
        }
        List<Msg> restored = MessageDerivation.history(store.readKernel(key, context));

        assertEquals(
                fromLog.stream().map(Msg::getTextContent).toList(),
                restored.stream().map(Msg::getTextContent).toList(),
                "restored history must equal the log's derived message history");
    }

    @Test
    @DisplayName("story events use the unified vocabulary, so one log reads one way")
    void storyEventsUseTheUnifiedVocabulary() {
        var store = new InMemorySessionLogStore();
        var key = SessionKeyFixture.key("stable", "s3");
        var context = SessionKeyFixture.context();
        try (DurableSession recorder = store.openWriter(key, "run-1", "turn-1", context)) {
            recorder.captureState(
                    AgentState.builder()
                            .sessionId("s3")
                            .userId("u")
                            .context(new ArrayList<>(List.of(new UserMessage("hello"))))
                            .build(),
                    "test");
        }

        var types = new ArrayList<String>();
        for (SessionEvent event : store.readKernel(key, context).events()) {
            types.add(event.type());
        }

        assertTrue(
                types.contains("user/message"),
                "a user message must be recorded under the unified name, got " + types);
        assertFalse(
                types.contains("message/user"),
                "the superseded name must not appear beside the unified one: " + types);
    }

    /** The runtime context these tests share. */
    private static final class SessionKeyFixture {
        static SessionKey key(String agent, String session) {
            return new SessionKey("u", agent, session);
        }

        static io.agentscope.core.agent.RuntimeContext context() {
            return io.agentscope.core.agent.RuntimeContext.builder()
                    .userId("u")
                    .sessionId("s")
                    .build();
        }
    }
}
