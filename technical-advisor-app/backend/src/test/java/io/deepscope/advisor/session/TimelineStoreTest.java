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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.db.AdvisorSchema;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** Timeline persistence: monotonic seq, turn-per-visitor-message, status transitions, versions. */
class TimelineStoreTest {

    private TimelineStore timeline;
    private SessionStore sessions;
    private ObjectMapper json;
    private String sessionId;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:timeline-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        sessions = new SessionStore(dataSource);
        json = new ObjectMapper();
        timeline = new TimelineStore(dataSource, sessions, json);
        sessionId = sessions.create("user-1", null).sessionId();
    }

    @Test
    void oneRunIsSavedOnceAcrossRetriesAndIncludesArtifactsAtomically() throws Exception {
        timeline.appendVisitorMessage(sessionId, "question");
        Map<String, Object> payload =
                Map.of("text", "reply", "presentation", Map.of("runId", "run"));
        var tools = List.of(Map.of("id", "tool", "name", "read_file", "status", "done"));
        var first = timeline.saveAdvisorRound(sessionId, payload, tools, null, null, 1);
        var retry = timeline.saveAdvisorRound(sessionId, payload, tools, null, null, 1);
        assertEquals(first.seq(), retry.seq());
        assertEquals(
                List.of("user_message", "tool_call", "advisor_message"),
                timeline.listEntries(sessionId).stream()
                        .map(TimelineStore.TimelineEntry::kind)
                        .toList());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        timeline.saveAdvisorRound(
                                sessionId,
                                Map.of("text", "different", "presentation", Map.of("runId", "run")),
                                null,
                                null,
                                null,
                                1));
    }

    @Test
    void failedReplyInsertionRollsBackItsToolArtifacts() throws Exception {
        timeline.appendVisitorMessage(sessionId, "question");
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE advisor_events ADD CONSTRAINT reject_reply CHECK (type <>"
                            + " 'advisor_message')");
        }
        assertThrows(
                SQLException.class,
                () ->
                        timeline.saveAdvisorRound(
                                sessionId,
                                Map.of("text", "reply", "presentation", Map.of("runId", "run")),
                                List.of(Map.of("id", "tool")),
                                null,
                                null,
                                1));
        assertEquals(1, timeline.listEntries(sessionId).size());
    }

    @Test
    void separateStoreInstancesSerializeTheSameRunInTheDatabase() throws Exception {
        timeline.appendVisitorMessage(sessionId, "question");
        TimelineStore other = new TimelineStore(dataSource, sessions, json);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var payload =
                    Map.<String, Object>of(
                            "text", "reply", "presentation", Map.of("runId", "same"));
            var one =
                    pool.submit(
                            () ->
                                    timeline.saveAdvisorRound(
                                            sessionId, payload, null, null, null, 1));
            var two =
                    pool.submit(
                            () -> other.saveAdvisorRound(sessionId, payload, null, null, null, 1));
            assertEquals(
                    one.get(5, java.util.concurrent.TimeUnit.SECONDS).seq(),
                    two.get(5, java.util.concurrent.TimeUnit.SECONDS).seq());
            assertEquals(2, timeline.listEntries(sessionId).size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aLateRoundKeepsItsOriginalTurnAndDoesNotIdleTheNewTurn() throws Exception {
        timeline.appendVisitorMessage(sessionId, "first");
        timeline.appendVisitorMessage(sessionId, "second");
        var saved =
                timeline.saveAdvisorRound(
                        sessionId,
                        Map.of("text", "old reply", "presentation", Map.of("runId", "old")),
                        null,
                        null,
                        null,
                        1);
        assertEquals(1, saved.turn());
        assertEquals(SessionStore.STATUS_RUNNING, sessions.find(sessionId).orElseThrow().status());
    }

    @Test
    void visitorMessagesStartNewTurnsAndOtherEventsStayInCurrentTurn() throws SQLException {
        timeline.appendVisitorMessage(sessionId, "看看这个仓库");
        timeline.append(sessionId, TimelineStore.ROUTE, Map.of("mode", "general"));
        timeline.appendVisitorMessage(sessionId, "再看看需求");

        List<TimelineStore.TimelineEntry> entries = timeline.listEntries(sessionId);

        assertEquals(3, entries.size());
        assertEquals(1, entries.get(0).turn());
        assertEquals(1, entries.get(1).turn());
        assertEquals(2, entries.get(2).turn());
        assertEquals(1, entries.get(0).seq());
        assertEquals(2, entries.get(1).seq());
        assertEquals(3, entries.get(2).seq());
    }

    @Test
    void advisorMessageKeepsReasoningForReplayWhenPresent() throws Exception {
        timeline.appendVisitorMessage(sessionId, "问个问题");
        timeline.appendAdvisorMessage(sessionId, "这是回复", "这是思考过程", "general", "");
        timeline.appendAdvisorMessage(sessionId, "第二条回复", null, "general", "");

        List<TimelineStore.TimelineEntry> entries = timeline.listEntries(sessionId);

        Map<String, Object> withReasoning = json.readValue(entries.get(1).payload(), Map.class);
        assertTrue(withReasoning.containsKey("reasoning"), "reasoning must be persisted");
        assertEquals("这是思考过程", withReasoning.get("reasoning"));

        Map<String, Object> withoutReasoning = json.readValue(entries.get(2).payload(), Map.class);
        assertFalse(withoutReasoning.containsKey("reasoning"), "blank reasoning must be omitted");
    }

    @Test
    void toolCallsAndUsageStayOnTheVisitorTurnAheadOfTheReply() throws Exception {
        timeline.appendVisitorMessage(sessionId, "问");
        timeline.appendRunArtifacts(
                sessionId,
                java.util.List.of(
                        Map.of("id", "c1", "name", "read_file", "args", "{}", "status", "done")),
                java.util.List.of(
                        Map.of(
                                "stepId",
                                "s1",
                                "uncachedInput",
                                4,
                                "cacheRead",
                                9,
                                "cacheWrite",
                                0,
                                "output",
                                2)));
        timeline.appendAdvisorMessage(sessionId, "结论", "先读", "general", "");

        List<TimelineStore.TimelineEntry> entries = timeline.listEntries(sessionId);

        assertEquals(
                List.of("user_message", "tool_call", "usage", "advisor_message"),
                entries.stream().map(TimelineStore.TimelineEntry::kind).toList());
        assertEquals(1, entries.get(1).turn());
        Map<String, Object> usage = json.readValue(entries.get(2).payload(), Map.class);
        assertEquals("s1", usage.get("stepId"));
        assertEquals(9, usage.get("cacheRead"));
    }

    @Test
    void sessionStatusFollowsTheLifecycleOfEvents() throws SQLException {
        timeline.appendVisitorMessage(sessionId, "hi");
        assertEquals(SessionStore.STATUS_RUNNING, sessions.find(sessionId).orElseThrow().status());

        timeline.append(sessionId, TimelineStore.ROUTE, Map.of());
        assertEquals(SessionStore.STATUS_RUNNING, sessions.find(sessionId).orElseThrow().status());

        timeline.append(sessionId, TimelineStore.QUESTION, Map.of("text", "哪个仓库？"));
        assertEquals(
                SessionStore.STATUS_AWAITING_VISITOR,
                sessions.find(sessionId).orElseThrow().status());

        timeline.append(sessionId, TimelineStore.PROPOSAL, Map.of("name", "架构调查员"));
        assertEquals(
                SessionStore.STATUS_AWAITING_VISITOR,
                sessions.find(sessionId).orElseThrow().status(),
                "a subagent proposal parks the session like a question does");

        timeline.appendVisitorMessage(sessionId, "repo-a");
        assertEquals(SessionStore.STATUS_RUNNING, sessions.find(sessionId).orElseThrow().status());

        timeline.append(sessionId, TimelineStore.DELIVERABLE, Map.of());
        assertEquals(SessionStore.STATUS_IDLE, sessions.find(sessionId).orElseThrow().status());

        timeline.append(sessionId, TimelineStore.ERROR, Map.of("text", "boom"));
        assertEquals(SessionStore.STATUS_FAILED, sessions.find(sessionId).orElseThrow().status());
    }

    @Test
    void advisorReplyDoesNotClearPendingProposalStatus() throws SQLException {
        timeline.append(sessionId, TimelineStore.PROPOSAL, Map.of("name", "survey"));
        timeline.appendAdvisorMessage(sessionId, "请确认后继续", null, null, null);
        assertEquals(
                SessionStore.STATUS_AWAITING_VISITOR,
                sessions.find(sessionId).orElseThrow().status());
        timeline.append(
                sessionId,
                TimelineStore.PROPOSAL_RESOLVED,
                Map.of("name", "survey", "approved", false));
        timeline.appendAdvisorMessage(sessionId, "已拒绝", null, null, null);
        assertEquals(SessionStore.STATUS_IDLE, sessions.find(sessionId).orElseThrow().status());
    }

    @Test
    void deliverableVersionsIncrementAcrossFollowUpTurns() throws SQLException {
        assertEquals(1, timeline.saveDeliverable(sessionId, "v1", Map.of("title", "v1")));
        assertEquals(2, timeline.saveDeliverable(sessionId, "v2", Map.of("title", "v2")));

        List<TimelineStore.DeliverableRow> deliverables = timeline.listDeliverables(sessionId);
        assertEquals(2, deliverables.size());
        assertEquals(1, deliverables.get(0).version());
        assertEquals(2, deliverables.get(1).version());
    }

    @Test
    void clearEntriesRemovesAllEventsAndDeliverablesAndResetsStatus() throws SQLException {
        timeline.appendVisitorMessage(sessionId, "hello");
        timeline.appendAdvisorMessage(sessionId, "hi", null, "general", "");
        timeline.saveDeliverable(sessionId, "v1", Map.of("title", "doc"));
        assertEquals(2, timeline.listEntries(sessionId).size());
        assertEquals(1, timeline.listDeliverables(sessionId).size());

        timeline.clearEntries(sessionId);

        assertTrue(timeline.listEntries(sessionId).isEmpty());
        assertTrue(timeline.listDeliverables(sessionId).isEmpty());
        assertEquals(SessionStore.STATUS_IDLE, sessions.find(sessionId).orElseThrow().status());
    }
}
