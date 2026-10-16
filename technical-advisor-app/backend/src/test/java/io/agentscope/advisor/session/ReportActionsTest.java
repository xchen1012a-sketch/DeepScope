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
package io.agentscope.advisor.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.advisor.db.AdvisorSchema;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Report-action tools: every call must persist the structured event into the session timeline
 * and answer the model with an acknowledgement. These tests run outside a streaming pipeline, so
 * no AG-UI emitter is present — persistence is the observable half of the protocol.
 */
class ReportActionsTest {

    private TimelineStore timeline;
    private SessionStore sessions;
    private ReportActions actions;
    private RuntimeContext context;
    private String sessionId;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:report-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        sessions = new SessionStore(dataSource);
        ObjectMapper json = new ObjectMapper();
        timeline = new TimelineStore(dataSource, sessions, json);
        actions = new ReportActions(timeline, sessions);
        sessionId = sessions.create("user-1", null).sessionId();
        context = RuntimeContext.builder().sessionId(sessionId).build();
    }

    @Test
    void questionStreamOnlyEmitsCommittedCanonicalQuestions() throws Exception {
        var emitted = new java.util.ArrayList<CustomEvent>();
        AgentEventEmitter emitter =
                event -> {
                    try {
                        var persisted = timeline.listEntries(sessionId);
                        assertEquals(1, persisted.size(), "event is emitted after commit");
                        emitted.add((CustomEvent) event);
                    } catch (SQLException error) {
                        throw new IllegalStateException(error);
                    }
                };
        for (int i = 0; i < 2; i++) {
            actions.reportQuestion(
                            "选择",
                            null,
                            List.of(Map.of("label", "a"), Map.of("label", "b")),
                            "multiple",
                            context)
                    .contextWrite(
                            reactor.util.context.Context.of(AgentEventEmitter.CONTEXT_KEY, emitter))
                    .block();
        }
        assertEquals(2, emitted.size());
        assertEquals(
                emitted.get(0).getValue().get("questionId"),
                emitted.get(1).getValue().get("questionId"));
        assertEquals(1, emitted.get(1).getValue().get("seq"));
        assertEquals("multiple", emitted.get(0).getValue().get("answerMode"));
        assertEquals("选择", emitted.get(0).getValue().get("title"));
    }

    @Test
    void persistsTheConciseTitleSeparatelyFromTheQuestion() throws Exception {
        actions.reportQuestion(
                        "请确认分析范围及必要约束。",
                        null,
                        List.of(Map.of("label", "架构"), Map.of("label", "性能")),
                        "single",
                        "确认分析范围",
                        context)
                .block();
        var payload = new ObjectMapper().readTree(timeline.listEntries(sessionId).get(0).payload());
        assertEquals("确认分析范围", payload.path("title").asText());
        assertEquals("请确认分析范围及必要约束。", payload.path("text").asText());
    }

    @Test
    void invalidOrUnpersistedQuestionDoesNotEmitSuccess() throws Exception {
        var emitted = new java.util.ArrayList<io.agentscope.core.event.AgentEvent>();
        AgentEventEmitter emitter = emitted::add;
        var subscriber = reactor.util.context.Context.of(AgentEventEmitter.CONTEXT_KEY, emitter);
        var invalid =
                actions.reportQuestion("选择", null, List.of(Map.of("label", "one")), null, context)
                        .contextWrite(subscriber)
                        .block();
        assertTrue(textOf(invalid).contains("参数错误"));
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE advisor_events ADD CONSTRAINT reject_question CHECK (type <>"
                            + " 'question')");
        }
        var failed = actions.reportQuestion("问题", null, context).contextWrite(subscriber).block();
        assertTrue(textOf(failed).contains("落库失败"));
        assertTrue(emitted.isEmpty());
        assertTrue(timeline.listEntries(sessionId).isEmpty());
    }

    @Test
    void legacySuggestionsBecomeAStableQuestionAndTrailingTextKeepsItPending() throws Exception {
        actions.reportQuestion("选择方向", List.of("方案", "代码"), context).block();
        var question =
                new ObjectMapper().readTree(timeline.listEntries(sessionId).get(0).payload());
        assertEquals(2, question.path("version").asInt());
        assertFalse(question.path("questionId").asText().isBlank());
        assertEquals("opt-1", question.path("options").get(0).path("id").asText());
        actions.reportQuestion("选择方向", List.of("方案", "代码"), context).block();
        assertEquals(1, timeline.listEntries(sessionId).size());
        timeline.saveAdvisorRound(sessionId, Map.of("text", "等待回复"), null, null, null, 1);
        assertEquals(
                SessionStore.STATUS_AWAITING_VISITOR,
                sessions.find(sessionId).orElseThrow().status());
    }

    @Test
    void routeReportLandsInTheTimeline() throws SQLException {
        ToolResultBlock result =
                actions.reportRoute("specialist", "仓库尽调", "访客给了仓库地址", null, context).block();

        assertTrue(result != null && textOf(result).contains("路由"));
        List<TimelineStore.TimelineEntry> entries = timeline.listEntries(sessionId);
        assertEquals(1, entries.size());
        assertEquals(TimelineStore.ROUTE, entries.get(0).kind());
        assertTrue(entries.get(0).payload().contains("specialist"));
    }

    @Test
    void legacyDeliverableKeepsTheAnswerWithoutCreatingAnArchiveStage() throws SQLException {
        ToolResultBlock result =
                actions.reportDeliverable(
                                "尽调报告",
                                "仓库尽调",
                                "specialist",
                                List.of(
                                        Map.of(
                                                "key",
                                                "risk",
                                                "title",
                                                "风险清单",
                                                "body",
                                                "需要补充单元测试。")),
                                List.of(Map.of("id", "F1", "fact", "无测试", "source", "pom.xml")),
                                null,
                                context)
                        .block();

        List<TimelineStore.DeliverableRow> deliverables = timeline.listDeliverables(sessionId);
        assertTrue(deliverables.isEmpty());
        var entries = timeline.listEntries(sessionId);
        assertEquals(1, entries.size());
        assertEquals(TimelineStore.ADVISOR_MESSAGE, entries.get(0).kind());
        assertTrue(entries.get(0).payload().contains("需要补充单元测试。"));
        assertTrue(entries.get(0).payload().contains("pom.xml"));
        assertFalse(textOf(result).contains("归档"));
    }

    @Test
    void factsReceiveInternalIdsWithoutAskingTheModelToNumberThem() throws Exception {
        var first =
                Map.<String, Object>of("fact", "无测试", "source", "pom.xml", "conclusion", "需补齐测试");
        ToolResultBlock result =
                actions.reportFacts("task-1", List.of(first, first), context).block();
        var payload = new ObjectMapper().readTree(timeline.listEntries(sessionId).get(0).payload());
        var facts = payload.path("facts");
        assertTrue(facts.get(0).path("id").asText().startsWith("fact-"));
        assertFalse(facts.get(0).path("id").asText().equals(facts.get(1).path("id").asText()));
        assertFalse(first.containsKey("id"), "the caller's map is never mutated");
        assertFalse(textOf(result).contains("编号"));
    }

    @Test
    void selfTaskFactsReceiveIdsWhileLegacyIdsRemainCompatible() throws Exception {
        actions.reportSelfTask(
                        "task-1",
                        "测试检查",
                        "已检查",
                        List.of(
                                Map.of("fact", "无测试", "source", "pom.xml"),
                                Map.of("id", "F12", "fact", "旧事实", "source", "README.md")),
                        context)
                .block();
        var facts =
                new ObjectMapper()
                        .readTree(timeline.listEntries(sessionId).get(0).payload())
                        .path("facts");
        assertTrue(facts.get(0).path("id").asText().startsWith("fact-"));
        assertEquals("F12", facts.get(1).path("id").asText());
    }

    @Test
    void proposalReportParksTheSessionAwaitingTheVisitor() throws SQLException {
        sessionId = sessions.create("user-1", null, "technical-advisor").sessionId();
        context = RuntimeContext.builder().sessionId(sessionId).build();
        ToolResultBlock result =
                actions.reportProposal("架构调查员", "调查非 Java 项目的构建与依赖", "现有子智能体覆盖不了", "定义内容", context)
                        .block();

        assertTrue(result != null && textOf(result).contains("提案"));
        List<TimelineStore.TimelineEntry> entries = timeline.listEntries(sessionId);
        assertEquals(1, entries.size());
        assertEquals(TimelineStore.PROPOSAL, entries.get(0).kind());
        assertTrue(entries.get(0).payload().contains("架构调查员"));
        assertEquals(
                SessionStore.STATUS_AWAITING_VISITOR,
                sessions.find(sessionId).orElseThrow().status(),
                "a pending proposal must park the session (F08)");
    }

    @Test
    void freeModeCannotSubmitSubagentProposals() throws SQLException {
        ToolResultBlock result =
                actions.reportProposal("survey", "调查", "能力缺口", "定义", context).block();
        assertTrue(result != null && textOf(result).contains("自由模式"));
        assertTrue(timeline.listEntries(sessionId).isEmpty());
    }

    @Test
    void reportsOutsideAKnownSessionStillAcknowledge() throws SQLException {
        RuntimeContext unknownSession = RuntimeContext.builder().sessionId("nope").build();
        ToolResultBlock result = actions.reportNote("说明", unknownSession).block();

        assertTrue(result != null && !textOf(result).isEmpty());
        assertTrue(timeline.listEntries(sessionId).isEmpty());
    }

    private static String textOf(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .collect(java.util.stream.Collectors.joining());
    }
}
