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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.core.agui.adapter.AguiAdapterConfig;
import io.deepscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.deepscope.core.agui.adapter.strategy.AguiStreamContext;
import io.deepscope.core.agui.event.AguiEvent;
import io.deepscope.core.event.AgentResultEvent;
import io.deepscope.core.event.ModelCallStartEvent;
import io.deepscope.core.event.TextBlockDeltaEvent;
import io.deepscope.core.event.TextBlockEndEvent;
import io.deepscope.core.message.AssistantMessage;
import io.deepscope.core.message.TextBlock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * The server-side last resort: a round the frontend never posted must still land on the timeline
 * (text, reasoning, tool calls, usage, route) and close the session into the interrupted state.
 */
class AguiRoundPersisterTest {

    @Test
    void databaseFailureRetainsTheRoundUntilASuccessfulRetry() throws Exception {
        persister.enrich(
                null,
                List.of(new AguiEvent.TextMessageContent(sessionId, "run-1", "m1", "reply")),
                context);
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE advisor_events ADD CONSTRAINT reject_reply CHECK (type <>"
                            + " 'advisor_message')");
        }
        persister.flush(sessionId, "run-1");
        assertTrue(persister.hasPending(sessionId));
        assertEquals(0, timeline.listEntries(sessionId).size());
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE advisor_events DROP CONSTRAINT reject_reply");
        }
        persister.flush(sessionId, "run-1");
        org.junit.jupiter.api.Assertions.assertFalse(persister.hasPending(sessionId));
        assertEquals(1, timeline.listEntries(sessionId).size());
    }

    @Test
    void staleRecoveryCannotFlushAReplacementRound() throws Exception {
        persister.close();
        var now = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        persister = new AguiRoundPersister(timeline, sessions, 60_000, now::get);
        persister.enrich(
                null,
                List.of(new AguiEvent.TextMessageContent(sessionId, "run-1", "m1", "old")),
                context);
        now.addAndGet(2_000_000);
        persister.setActiveRunCheck(
                id -> {
                    var next =
                            new AguiStreamContext(
                                    sessionId, "run-2", AguiAdapterConfig.builder().build());
                    persister.enrich(
                            null,
                            List.of(
                                    new AguiEvent.RunStarted(sessionId, "run-2"),
                                    new AguiEvent.TextMessageContent(
                                            sessionId, "run-2", "m2", "new")),
                            next);
                    return false;
                });
        persister.flushStale(now.get());
        assertTrue(persister.hasPending(sessionId), "the replacement round must remain live");
        assertEquals(
                1,
                timeline.listEntries(sessionId).stream()
                        .filter(entry -> entry.kind().equals("advisor_message"))
                        .count());
    }

    @Test
    void backgroundRecoveryDoesNotInterruptAnActuallyActiveSession() throws Exception {
        sessions.updateStatus(sessionId, SessionStore.STATUS_RUNNING);
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE advisor_sessions SET updated_at = ? WHERE session_id ="
                                        + " ?")) {
            statement.setLong(1, System.currentTimeMillis() - 30 * 60 * 1000L);
            statement.setString(2, sessionId);
            statement.executeUpdate();
        }
        persister.setActiveRunCheck(id -> true);
        persister.reconcileStale(System.currentTimeMillis());
        assertEquals(SessionStore.STATUS_RUNNING, sessions.find(sessionId).orElseThrow().status());
        persister.setActiveRunCheck(id -> false);
        persister.reconcileStale(System.currentTimeMillis());
        assertEquals(
                SessionStore.STATUS_INTERRUPTED, sessions.find(sessionId).orElseThrow().status());
    }

    private DataSource dataSource;
    private SessionStore sessions;
    private TimelineStore timeline;
    private AguiRoundPersister persister;
    private String sessionId;
    private AguiStreamContext context;

    @BeforeEach
    void setUp() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:agui-persister-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        io.deepscope.advisor.db.AdvisorSchema.initialize(dataSource);
        sessions = new SessionStore(dataSource);
        ObjectMapper json = new ObjectMapper();
        timeline = new TimelineStore(dataSource, sessions, json);
        persister = new AguiRoundPersister(timeline, sessions, 0);
        sessionId = sessions.create("user-1", null).sessionId();
        // AG-UI 的 threadId 就是 advisor 的 sessionId：事件和落库必须指向同一个会话。
        context = new AguiStreamContext(sessionId, "run-1", AguiAdapterConfig.builder().build());
    }

    @AfterEach
    void tearDown() {
        persister.close();
    }

    @Test
    void abandonedRoundIsFlushedWithContentAndInterruptedStatus() throws Exception {
        timeline.appendVisitorMessage(sessionId, "看看这个仓库", null);

        persister.enrich(
                null,
                List.of(
                        new AguiEvent.ReasoningMessageContent(sessionId, "run-1", "m1", "先看构建脚本"),
                        new AguiEvent.TextMessageContent(sessionId, "run-1", "m2", "总体可信"),
                        new AguiEvent.ToolCallStart(sessionId, "run-1", "t1", "read_file"),
                        new AguiEvent.ToolCallArgs(
                                sessionId, "run-1", "t1", "{\"path\":\"AGENTS.md\"}"),
                        new AguiEvent.ToolCallResult(
                                sessionId, "run-1", "t1", "构建脚本内容", "tool", "m3", null, null),
                        routeEvent(),
                        usageEvent()),
                context);

        // 不发 RunFinished：直接同步调用 flush，避免与零延迟调度线程竞态；
        // RunFinished 只负责调度，内容由同一 flush 路径写出。
        persister.flush(sessionId);

        List<TimelineStore.TimelineEntry> entries = timeline.listEntries(sessionId);
        assertEquals(4, entries.size(), "visitor + tool_call + usage + advisor_message");
        assertEquals(TimelineStore.TOOL_CALL, entries.get(1).kind());
        assertEquals(TimelineStore.USAGE, entries.get(2).kind());
        assertEquals(TimelineStore.ADVISOR_MESSAGE, entries.get(3).kind());
        assertEquals("总体可信", text(entries.get(3).payload()));
        assertEquals("specialist", mode(entries.get(3).payload()));
        assertEquals(
                SessionStore.STATUS_INTERRUPTED, sessions.find(sessionId).orElseThrow().status());
    }

    @Test
    void abandonedRoundPreservesTextThinkingAndToolOrder() throws Exception {
        persister.enrich(
                null,
                List.of(
                        new AguiEvent.ReasoningMessageContent(sessionId, "run", "m1", "think A"),
                        new AguiEvent.TextMessageContent(sessionId, "run", "m1", "read files"),
                        new AguiEvent.ToolCallStart(sessionId, "run", "a", "read_file"),
                        new AguiEvent.ToolCallStart(sessionId, "run", "b", "search"),
                        new AguiEvent.ToolCallResult(
                                sessionId, "run", "b", "found", "tool", "b-result", null, null),
                        new AguiEvent.ToolCallResult(
                                sessionId, "run", "a", "read", "tool", "a-result", null, null),
                        new AguiEvent.TextMessageContent(sessionId, "run", "m1", "check results"),
                        new AguiEvent.ReasoningMessageContent(sessionId, "run", "m2", "think B"),
                        new AguiEvent.TextMessageContent(sessionId, "run", "m2", "answer")),
                context);
        persister.flush(sessionId);
        var entries = timeline.listEntries(sessionId);
        var payload = new ObjectMapper().readTree(entries.get(entries.size() - 1).payload());
        assertTrue(payload.has("parts"), "ordered parts must survive a disconnected client");
        assertEquals(1, payload.path("partsVersion").asInt());
        var parts = payload.get("parts");
        assertEquals(
                List.of("reasoning", "text", "tool", "tool", "text", "reasoning", "text"),
                java.util.stream.StreamSupport.stream(parts.spliterator(), false)
                        .map(part -> part.get("kind").asText())
                        .toList());
        assertEquals("a", parts.get(2).get("tool").get("id").asText());
        assertEquals("read", parts.get(2).get("tool").get("result").asText());
    }

    @Test
    void disconnectedUnfinishedToolsDoNotReplayAsStillRunning() throws Exception {
        persister.enrich(
                null,
                List.of(new AguiEvent.ToolCallStart(sessionId, "run", "a", "read_file")),
                context);
        persister.flush(sessionId);
        var entries = timeline.listEntries(sessionId);
        var payload = new ObjectMapper().readTree(entries.get(entries.size() - 1).payload());
        assertEquals("failed", payload.path("parts").get(0).path("tool").path("status").asText());
    }

    @Test
    void frontendRoundCancelsTheFallback() throws Exception {
        persister.enrich(
                null,
                List.of(
                        new AguiEvent.TextMessageContent(sessionId, "run-1", "m1", "前端会自己回传"),
                        new AguiEvent.RunFinished(sessionId, "run-1")),
                context);
        persister.markFlushed(sessionId);

        persister.flush(sessionId);

        // 只剩 create 时的空时间线：兜底没有重复写。
        assertTrue(timeline.listEntries(sessionId).isEmpty());
    }

    @Test
    void roundsWithoutContentAreNotWritten() throws Exception {
        persister.enrich(null, List.of(new AguiEvent.RunFinished(sessionId, "run-1")), context);

        persister.flush(sessionId);

        assertTrue(timeline.listEntries(sessionId).isEmpty());
        // 空轮不改状态：既不 interrupted 也不 failed，保持原样。
        assertEquals(SessionStore.STATUS_IDLE, sessions.find(sessionId).orElseThrow().status());
    }

    @Test
    void confirmsFinalTextUsingNativeCallIdentityRatherThanProviderMessageId() {
        persister.close();
        persister = new AguiRoundPersister(timeline, sessions, 60_000);
        var registry = new AgentEventConverterRegistry(List.of(), List.of(persister));
        persister.enrich(null, List.of(new AguiEvent.RunStarted(sessionId, "run-1")), context);
        registry.convert(new ModelCallStartEvent("call-before"), context);
        registry.convert(new TextBlockDeltaEvent("call-before", "b", "checking"), context);
        registry.convert(new TextBlockEndEvent("call-before", "b"), context);
        persister.enrich(
                null,
                List.of(new AguiEvent.ToolCallStart(sessionId, "run-1", "t", "read_file")),
                context);
        registry.convert(new ModelCallStartEvent("call-final"), context);
        registry.convert(new TextBlockDeltaEvent("call-final", "a", "answer "), context);
        registry.convert(new TextBlockEndEvent("call-final", "a"), context);
        registry.convert(new TextBlockDeltaEvent("call-final", "b", "complete"), context);
        registry.convert(new TextBlockEndEvent("call-final", "b"), context);
        var result =
                AssistantMessage.builder()
                        .id("provider-result-id")
                        .content(TextBlock.builder().text("answer complete").build())
                        .build();
        registry.convert(new AgentResultEvent(result), context);
        var events =
                persister.enrich(
                        null, List.of(new AguiEvent.RunFinished(sessionId, "run-1")), context);
        var presentation =
                events.stream()
                        .filter(
                                event ->
                                        event instanceof AguiEvent.Custom custom
                                                && "advisor_presentation".equals(custom.name()))
                        .map(event -> (AguiEvent.Custom) event)
                        .findFirst();
        assertTrue(
                presentation.isPresent(),
                "normal result must carry its confirmed display identity");
        var value = (Map<?, ?>) presentation.orElseThrow().value();
        assertEquals("completed", value.get("outcome"));
        assertEquals(List.of("call-final-a", "call-final-b"), value.get("finalMessageIds"));
    }

    @Test
    void timingEndsAtFirstFinalContentAndSurvivesDelayedFallback() throws Exception {
        persister.close();
        var now = new java.util.concurrent.atomic.AtomicLong(1000);
        persister = new AguiRoundPersister(timeline, sessions, 60_000, now::get);
        var registry = new AgentEventConverterRegistry(List.of(), List.of(persister));
        persister.enrich(null, List.of(new AguiEvent.RunStarted(sessionId, "run-1")), context);
        registry.convert(new ModelCallStartEvent("final-call"), context);
        now.set(83000);
        registry.convert(new TextBlockDeltaEvent("final-call", "a", "answer"), context);
        registry.convert(new TextBlockEndEvent("final-call", "a"), context);
        registry.convert(
                new AgentResultEvent(
                        AssistantMessage.builder()
                                .content(TextBlock.builder().text("answer").build())
                                .build()),
                context);
        now.set(95000);
        persister.enrich(null, List.of(new AguiEvent.RunFinished(sessionId, "run-1")), context);
        now.set(110000);
        persister.flush(sessionId);
        var payload = new ObjectMapper().readTree(timeline.listEntries(sessionId).get(0).payload());
        assertEquals(1, payload.path("presentationVersion").asInt());
        assertEquals(1000, payload.path("presentation").path("runStartedAt").asLong());
        assertEquals(83000, payload.path("presentation").path("finalStartedAt").asLong());
        assertEquals(95000, payload.path("presentation").path("runEndedAt").asLong());
    }

    @Test
    void interruptedOrRewrittenResultsNeverClaimFinalText() {
        for (var reason :
                List.of(
                        io.deepscope.core.message.GenerateReason.INTERRUPTED,
                        io.deepscope.core.message.GenerateReason.PERMISSION_ASKING,
                        io.deepscope.core.message.GenerateReason.MODEL_STOP)) {
            persister.close();
            persister = new AguiRoundPersister(timeline, sessions, 60_000);
            var registry = new AgentEventConverterRegistry(List.of(), List.of(persister));
            persister.enrich(null, List.of(new AguiEvent.RunStarted(sessionId, "run-1")), context);
            registry.convert(new ModelCallStartEvent("call"), context);
            registry.convert(new TextBlockDeltaEvent("call", "b", "original"), context);
            var result =
                    AssistantMessage.builder()
                            .generateReason(reason)
                            .content(TextBlock.builder().text("rewritten").build())
                            .build();
            registry.convert(new AgentResultEvent(result), context);
            persister.enrich(null, List.of(new AguiEvent.RunFinished(sessionId, "run-1")), context);
            var metadata = persister.presentation(sessionId, "run-1");
            assertEquals(List.of(), metadata.get("finalMessageIds"));
            assertTrue(!metadata.containsKey("finalStartedAt"));
        }
    }

    @Test
    void subagentResultCannotReplaceTheParentFinalIdentity() {
        persister.close();
        persister = new AguiRoundPersister(timeline, sessions, 60_000);
        var registry = new AgentEventConverterRegistry(List.of(), List.of(persister));
        persister.enrich(null, List.of(new AguiEvent.RunStarted(sessionId, "run-1")), context);
        registry.convert(new ModelCallStartEvent("parent"), context);
        registry.convert(new TextBlockDeltaEvent("parent", "a", "parent answer"), context);
        registry.convert(new TextBlockEndEvent("parent", "a"), context);
        registry.convert(
                new AgentResultEvent(
                        AssistantMessage.builder()
                                .content(TextBlock.builder().text("parent answer").build())
                                .build()),
                context);
        registry.convert(
                new AgentResultEvent(
                                AssistantMessage.builder()
                                        .content(TextBlock.builder().text("child answer").build())
                                        .build())
                        .withSource("parent/child"),
                context);
        persister.enrich(null, List.of(new AguiEvent.RunFinished(sessionId, "run-1")), context);
        assertEquals(
                List.of("parent-a"),
                persister.presentation(sessionId, "run-1").get("finalMessageIds"));
    }

    private AguiEvent.Custom routeEvent() {
        return new AguiEvent.Custom(
                sessionId,
                "run-1",
                "advisor_event",
                Map.of("kind", "route", "mode", "specialist", "skillName", "仓库尽调"),
                null,
                null);
    }

    private AguiEvent.Custom usageEvent() {
        return new AguiEvent.Custom(
                sessionId,
                "run-1",
                "token_usage",
                Map.of(
                        "delta",
                        Map.of(
                                "inputTokens", 1000,
                                "cachedTokens", 200,
                                "cacheCreationTokens", 100,
                                "outputTokens", 50),
                        "modelCall",
                        Map.of("replyId", "step-1")),
                null,
                null);
    }

    private static String text(String payload) {
        return payloadField(payload, "text");
    }

    private static String mode(String payload) {
        return payloadField(payload, "mode");
    }

    private static String payloadField(String payload, String key) {
        try {
            Object value =
                    new ObjectMapper()
                            .readValue(
                                    payload,
                                    new com.fasterxml.jackson.core.type.TypeReference<
                                            Map<String, Object>>() {})
                            .get(key);
            return value == null ? "" : value.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
