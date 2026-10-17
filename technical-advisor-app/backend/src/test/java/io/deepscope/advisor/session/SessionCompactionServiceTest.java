/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deepscope.advisor.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.core.agui.registry.AguiAgentRegistry;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** Guard branches of one-click compaction: conflicts, short-session skips, missing agents. */
class SessionCompactionServiceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path nativeRoot;

    @Test
    void compactsNativeHistoryWhenTheLegacyStateStoreIsEmpty() throws Exception {
        assertNativeCompaction(false);
    }

    @Test
    void failedNativeCompactionRetainsHistoryAndReleasesTheWriter() throws Exception {
        assertNativeCompaction(true);
    }

    private void assertNativeCompaction(boolean modelFails) throws Exception {
        var model = org.mockito.Mockito.mock(io.deepscope.core.model.Model.class);
        org.mockito.Mockito.when(model.getModelName()).thenReturn("native-test");
        org.mockito.Mockito.when(
                        model.stream(
                                org.mockito.ArgumentMatchers.anyList(),
                                org.mockito.ArgumentMatchers.anyList(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        modelFails
                                ? reactor.core.publisher.Flux.error(
                                        new IllegalStateException("model unavailable"))
                                : reactor.core.publisher.Flux.just(
                                        io.deepscope.core.model.ChatResponse.builder()
                                                .content(
                                                        java.util.List.of(
                                                                io.deepscope.core.message.TextBlock
                                                                        .builder()
                                                                        .text("native summary")
                                                                        .build()))
                                                .build()));
        var logs = new io.deepscope.core.session.InMemorySessionLogStore();
        var legacy = new io.deepscope.core.state.InMemoryAgentStateStore();
        var agent =
                io.deepscope.harness.agent.HarnessAgent.builder()
                        .agentId("native")
                        .name("Native")
                        .model(model)
                        .workspace(nativeRoot)
                        .sessionLogStore(logs)
                        .stateStore(legacy)
                        .build();
        try {
            var registry = new AguiAgentRegistry();
            registry.register("native", agent);
            service =
                    new SessionCompactionService(sessions, timeline, registry, new ObjectMapper());
            var session = sessions.create("alice", "existing conversation", "native");
            timeline.appendVisitorMessage(session.sessionId(), "hello");
            timeline.appendAdvisorMessage(session.sessionId(), "answer", null, "", "");
            sessions.updateStatus(session.sessionId(), SessionStore.STATUS_IDLE);
            var rc =
                    io.deepscope.core.agent.RuntimeContext.builder()
                            .userId("alice")
                            .sessionId(session.sessionId())
                            .build();
            try (var writer = logs.openWriter(agent.sessionKey(rc), "seed", null, rc)) {
                writer.checkpointNow(
                        io.deepscope.core.state.AgentState.builder()
                                .userId("alice")
                                .sessionId(session.sessionId())
                                .addMessage(
                                        io.deepscope.core.message.Msg.builder()
                                                .role(io.deepscope.core.message.MsgRole.USER)
                                                .textContent("hello")
                                                .build())
                                .addMessage(
                                        io.deepscope.core.message.Msg.builder()
                                                .role(io.deepscope.core.message.MsgRole.ASSISTANT)
                                                .textContent("answer")
                                                .build())
                                .build(),
                        "test_seed");
            }
            assertEquals(
                    true,
                    legacy.get(
                                    "alice",
                                    session.sessionId(),
                                    "agent_state",
                                    io.deepscope.core.state.AgentState.class)
                            .isEmpty());
            var result = service.compact(sessions.find(session.sessionId()).orElseThrow());
            assertEquals(
                    modelFails
                            ? SessionCompactionService.OUTCOME_FAILED
                            : SessionCompactionService.OUTCOME_COMPACTED,
                    result.outcome());
            var restored =
                    io.deepscope.core.session.log.MessageDerivation.history(
                            logs.readKernel(agent.sessionKey(rc), rc));
            assertEquals(modelFails ? 2 : 1, restored.size());
            org.junit.jupiter.api.Assertions.assertNull(logs.owner(agent.sessionKey(rc), rc));
            if (!modelFails)
                assertEquals(
                        io.deepscope.harness.agent.memory.compaction.ConversationCompactor
                                .SUMMARY_MSG_NAME,
                        restored.get(0).getName());
            if (!modelFails)
                org.junit.jupiter.api.Assertions.assertTrue(
                        ((io.deepscope.core.message.TextBlock) restored.get(0).getContent().get(0))
                                .getText()
                                .contains("session_history(agentId=\"native\""));
            assertEquals(
                    2,
                    timeline.listEntries(session.sessionId()).stream()
                            .filter(
                                    entry ->
                                            entry.kind().equals("user_message")
                                                    || entry.kind().equals("advisor_message"))
                            .count());
        } finally {
            agent.close();
        }
    }

    @Test
    void clearsEveryRoutedRuntimeBeforeDeletingItsIdentity() throws Exception {
        var registry = new AguiAgentRegistry();
        var first = org.mockito.Mockito.mock(io.deepscope.harness.agent.HarnessAgent.class);
        var second = org.mockito.Mockito.mock(io.deepscope.harness.agent.HarnessAgent.class);
        var one = org.mockito.Mockito.mock(io.deepscope.core.state.AgentStateStore.class);
        var two = org.mockito.Mockito.mock(io.deepscope.core.state.AgentStateStore.class);
        org.mockito.Mockito.when(first.getStateStore()).thenReturn(one);
        org.mockito.Mockito.when(second.getStateStore()).thenReturn(two);
        registry.register("first", first);
        registry.register("second", second);
        service = new SessionCompactionService(sessions, timeline, registry, new ObjectMapper());
        var session = sessions.create("alice", "free");
        timeline.append(session.sessionId(), "agent_route", java.util.Map.of("agentId", "first"));
        timeline.append(session.sessionId(), "agent_route", java.util.Map.of("agentId", "second"));
        service.clearSessionContext(session);
        org.mockito.Mockito.verify(one).delete("alice", session.sessionId());
        org.mockito.Mockito.verify(two).delete("alice", session.sessionId());
        org.mockito.Mockito.verify(first).clearStateCache("alice", session.sessionId());
        assertEquals(0, timeline.listEntries(session.sessionId()).size());
    }

    @Test
    void failedStateDeletionKeepsHistoryAvailableForRetry() throws Exception {
        var registry = new AguiAgentRegistry();
        var agent = org.mockito.Mockito.mock(io.deepscope.harness.agent.HarnessAgent.class);
        var store = org.mockito.Mockito.mock(io.deepscope.core.state.AgentStateStore.class);
        org.mockito.Mockito.when(agent.getStateStore()).thenReturn(store);
        registry.register("agent", agent);
        service = new SessionCompactionService(sessions, timeline, registry, new ObjectMapper());
        var session = sessions.create("alice", "free", "agent");
        timeline.appendAdvisorMessage(session.sessionId(), "history", null, "", "");
        org.mockito.Mockito.doThrow(new IllegalStateException("store unavailable"))
                .when(store)
                .delete("alice", session.sessionId());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> service.clearSessionContext(session));
        assertEquals(1, timeline.listEntries(session.sessionId()).size());
    }

    private SessionStore sessions;
    private TimelineStore timeline;
    private SessionCompactionService service;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:advisor-compact-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        sessions = new SessionStore(dataSource);
        ObjectMapper json = new ObjectMapper();
        timeline = new TimelineStore(dataSource, sessions, json);
        service = new SessionCompactionService(sessions, timeline, new AguiAgentRegistry(), json);
    }

    @Test
    void runningSessionIsRejectedAsConflict() throws Exception {
        SessionStore.SessionRow session = sessions.create("alice", "尽调");
        // A visitor message flips the session to running.
        timeline.appendVisitorMessage(session.sessionId(), "看看这个仓库");
        SessionStore.SessionRow running = sessions.find(session.sessionId()).orElseThrow();

        SessionCompactionService.CompactionResult result = service.compact(running);

        assertEquals(SessionCompactionService.OUTCOME_CONFLICT, result.outcome());
        assertEquals(SessionCompactionService.REASON_SESSION_RUNNING, result.reason());
    }

    @Test
    void shortSessionStillAttemptsCompaction() throws Exception {
        SessionStore.SessionRow session = sessions.create("alice", "短会话");
        timeline.appendVisitorMessage(session.sessionId(), "你好");
        timeline.appendAdvisorMessage(session.sessionId(), "你好", null, "", null);
        sessions.updateStatus(session.sessionId(), SessionStore.STATUS_IDLE);

        SessionCompactionService.CompactionResult result =
                service.compact(sessions.find(session.sessionId()).orElseThrow());

        assertEquals(SessionCompactionService.OUTCOME_FAILED, result.outcome());
        assertEquals(SessionCompactionService.REASON_UNAVAILABLE, result.reason());
    }

    @Test
    void compactsOnePersistedMessageAndSavesTheSummary() throws Exception {
        var registry = new AguiAgentRegistry();
        var agent = org.mockito.Mockito.mock(io.deepscope.harness.agent.HarnessAgent.class);
        var store = org.mockito.Mockito.mock(io.deepscope.core.state.AgentStateStore.class);
        var model = org.mockito.Mockito.mock(io.deepscope.core.model.Model.class);
        org.mockito.Mockito.when(agent.getStateStore()).thenReturn(store);
        org.mockito.Mockito.when(agent.getModel()).thenReturn(model);
        org.mockito.Mockito.when(model.getModelName()).thenReturn("test-summary-model");
        org.mockito.Mockito.when(agent.getName()).thenReturn("agent");
        org.mockito.Mockito.when(
                        model.stream(
                                org.mockito.ArgumentMatchers.anyList(),
                                org.mockito.ArgumentMatchers.anyList(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        reactor.core.publisher.Flux.just(
                                io.deepscope.core.model.ChatResponse.builder()
                                        .content(
                                                java.util.List.of(
                                                        io.deepscope.core.message.TextBlock
                                                                .builder()
                                                                .text("summary")
                                                                .build()))
                                        .build()));
        registry.register("agent", agent);
        service = new SessionCompactionService(sessions, timeline, registry, new ObjectMapper());
        var session = sessions.create("alice", "short", "agent");
        var state =
                io.deepscope.core.state.AgentState.builder()
                        .addMessage(
                                io.deepscope.core.message.Msg.builder()
                                        .role(io.deepscope.core.message.MsgRole.USER)
                                        .textContent("hello")
                                        .build())
                        .build();
        org.mockito.Mockito.when(
                        store.get(
                                "alice",
                                session.sessionId(),
                                "agent_state",
                                io.deepscope.core.state.AgentState.class))
                .thenReturn(java.util.Optional.of(state));

        var result = service.compact(session);

        assertEquals(SessionCompactionService.OUTCOME_COMPACTED, result.outcome());
        org.mockito.Mockito.verify(store).save("alice", session.sessionId(), "agent_state", state);
        assertEquals(
                "Here is a summary of the conversation to date:\n\nsummary",
                ((io.deepscope.core.message.TextBlock)
                                state.contextMutable().get(0).getContent().get(0))
                        .getText());
    }

    @Test
    void sessionWithoutARegisteredAgentFailsGracefully() throws Exception {
        SessionStore.SessionRow session = sessions.create("alice", "长会话");
        for (int i = 0; i < 3; i++) {
            timeline.appendVisitorMessage(session.sessionId(), "问题" + i);
            timeline.appendAdvisorMessage(session.sessionId(), "回答" + i, null, "", null);
        }
        sessions.updateStatus(session.sessionId(), SessionStore.STATUS_IDLE);

        SessionCompactionService.CompactionResult result =
                service.compact(sessions.find(session.sessionId()).orElseThrow());

        assertEquals(SessionCompactionService.OUTCOME_FAILED, result.outcome());
        assertEquals(SessionCompactionService.REASON_UNAVAILABLE, result.reason());
    }
}
