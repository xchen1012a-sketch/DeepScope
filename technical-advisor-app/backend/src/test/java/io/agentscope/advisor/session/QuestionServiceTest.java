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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.advisor.db.AdvisorSchema;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class QuestionServiceTest {
    @Test
    void derivesAShortTitleWithoutChangingTheOriginalQuestion() {
        String text = "请选择分析对象？" + "这是背景说明。".repeat(30);
        var question = QuestionService.question(text, List.of("方案", "代码"), null, null);
        assertEquals("请选择分析对象？", question.get("title"));
        assertEquals(text, question.get("text"));
    }

    @Test
    void keepsExplicitTitlesSeparateAndRejectsLongOrMultilineTitles() {
        var question =
                QuestionService.question("完整问题和必要说明", List.of("方案", "代码"), null, null, "确认分析范围");
        assertEquals("确认分析范围", question.get("title"));
        assertEquals("完整问题和必要说明", question.get("text"));
        assertThrows(
                IllegalArgumentException.class,
                () -> QuestionService.question("问题", null, null, null, "长".repeat(41)));
        assertThrows(
                IllegalArgumentException.class,
                () -> QuestionService.question("问题", null, null, null, "标题\n背景"));
        String fallback =
                (String) QuestionService.question("长".repeat(80), null, null, null).get("title");
        assertEquals(40, fallback.codePointCount(0, fallback.length()));
        assertTrue(fallback.endsWith("…"));
    }

    @Test
    void answerSnapshotsKeepTheFullQuestionBehindTheShortTitle() throws Exception {
        String text = "请选择分析对象？" + "补充说明。".repeat(30);
        var question =
                timeline.appendQuestion(
                        sid,
                        QuestionService.question(text, List.of("方案", "代码"), null, null, "确认分析对象"));
        var saved =
                timeline.appendQuestionReply(
                        sid,
                        "owner",
                        "",
                        null,
                        reply(question, "short-title-answer", List.of("opt-1"), ""),
                        false);
        assertEquals(
                text,
                ((Map<?, ?>) saved.event().payload().get("questionReply")).get("questionText"));
    }

    private DataSource dataSource;
    private SessionStore sessions;
    private TimelineStore timeline;
    private String sid;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:question-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        sessions = new SessionStore(dataSource);
        timeline = new TimelineStore(dataSource, sessions, json);
        sid = sessions.create("owner", null).sessionId();
    }

    @Test
    void optionsOverrideLegacySuggestionsWithoutInventingDescriptions() {
        var question =
                QuestionService.question(
                        "方向？",
                        List.of("旧建议"),
                        List.of(
                                Map.of("label", "方案", "description", "比较架构"),
                                Map.of("label", "代码")),
                        null);
        assertEquals("single", question.get("answerMode"));
        assertEquals(List.of("方案", "代码"), question.get("suggestions"));
        assertEquals(
                List.of(
                        Map.of("id", "opt-1", "label", "方案", "description", "比较架构"),
                        Map.of("id", "opt-2", "label", "代码")),
                question.get("options"));
        UUID.fromString((String) question.get("questionId"));
    }

    @Test
    void malformedQuestionsFailInsteadOfSilentlyTruncating() {
        List<Map<String, Object>> valid = List.of(Map.of("label", "a"), Map.of("label", "b"));
        assertThrows(
                IllegalArgumentException.class,
                () -> QuestionService.question(" ", null, valid, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> QuestionService.question("x".repeat(1001), null, valid, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> QuestionService.question("q", null, List.of(), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> QuestionService.question("q", null, List.of(Map.of("label", "a")), null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        QuestionService.question(
                                "q",
                                null,
                                java.util.Collections.nCopies(7, Map.of("label", "a")),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        QuestionService.question(
                                "q",
                                null,
                                List.of(Map.of("label", "a"), Map.of("label", " a ")),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        QuestionService.question(
                                "q",
                                null,
                                List.of(Map.of("label", "a".repeat(81)), Map.of("label", "b")),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        QuestionService.question(
                                "q",
                                null,
                                List.of(
                                        Map.of("label", "a", "description", "d".repeat(161)),
                                        Map.of("label", "b")),
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () -> QuestionService.question("q", null, valid, "text"));
        assertThrows(
                IllegalArgumentException.class,
                () -> QuestionService.question("q", null, valid, "invalid"));
        assertEquals(
                "single",
                QuestionService.question("q", List.of("one"), null, null).get("answerMode"));
        assertEquals("text", QuestionService.question("q", null, null, null).get("answerMode"));
    }

    @Test
    void singleAnswerUsesSavedLabelsAndSnapshotsThenReplaysWithoutAnotherTurn() throws Exception {
        var question = createQuestion("single");
        var request = reply(question, "r1", List.of("opt-2"), "");
        var first =
                timeline.appendQuestionReply(sid, "owner", "forged label", null, request, false);
        assertEquals("代码", first.event().payload().get("text"));
        Map<?, ?> snapshot = (Map<?, ?>) first.event().payload().get("questionReply");
        assertEquals(question.seq(), snapshot.get("questionSeq"));
        assertEquals("对上一条追问的回答\n问题：方向？\n选择：代码", snapshot.get("contextText"));
        assertEquals(question.payload().get("options"), snapshot.get("options"));
        assertTrue(timeline.pendingQuestion(sid).isEmpty());
        assertEquals(first.event().seq(), timeline.pendingQuestionReply(sid).orElseThrow().seq());
        var retry =
                timeline.appendQuestionReply(
                        sid, "owner", "other forged label", null, request, true);
        assertTrue(retry.replayed());
        assertEquals(first.event(), retry.event());
        assertEquals(2, timeline.listEntries(sid).size());
        timeline.saveAdvisorRound(
                sid, Map.of("text", "answer"), null, null, null, first.event().turn());
        assertTrue(timeline.pendingQuestionReply(sid).isEmpty());
        assertTrue(timeline.appendQuestionReply(sid, "owner", "", null, request, false).replayed());
    }

    @Test
    void differentRequestAndChangedAnswerConflictWithoutAppending() throws Exception {
        var question = createQuestion("single");
        timeline.appendQuestionReply(
                sid, "owner", "", null, reply(question, "r1", List.of("opt-1"), ""), false);
        assertCode(
                "question_request_conflict",
                () ->
                        timeline.appendQuestionReply(
                                sid,
                                "owner",
                                "",
                                null,
                                reply(question, "r1", List.of("opt-2"), ""),
                                false));
        assertCode(
                "question_already_answered",
                () ->
                        timeline.appendQuestionReply(
                                sid,
                                "owner",
                                "",
                                null,
                                reply(question, "r2", List.of("opt-1"), ""),
                                false));
        assertEquals(2, timeline.listEntries(sid).size());
    }

    @Test
    void invalidSelectionNeverConsumesTheQuestion() throws Exception {
        var question = createQuestion("single");
        for (var invalid :
                List.of(
                        reply(question, "r", List.of("unknown"), ""),
                        reply(question, "r", List.of("opt-1", "opt-1"), ""),
                        reply(question, "r", List.of("opt-1", "opt-2"), ""),
                        reply(question, "r", List.of("opt-1"), "custom"),
                        reply(question, "r", List.of(), " "),
                        reply(question, "r", List.of(), "x".repeat(4001)))) {
            assertCode(
                    "question_reply_invalid",
                    () -> timeline.appendQuestionReply(sid, "owner", "", null, invalid, false));
        }
        assertEquals(question.seq(), timeline.pendingQuestion(sid).orElseThrow().seq());
        assertEquals(1, timeline.listEntries(sid).size());
    }

    @Test
    void multipleAcceptsCanonicalOrderWithCustomTextAndIdempotentReordering() throws Exception {
        var question = createQuestion("multiple");
        var first =
                timeline.appendQuestionReply(
                        sid,
                        "owner",
                        "",
                        null,
                        reply(question, "r", List.of("opt-2", "opt-1"), "  补充  "),
                        false);
        assertEquals("方案、代码\n补充", first.event().payload().get("text"));
        assertTrue(
                timeline.appendQuestionReply(
                                sid,
                                "owner",
                                "",
                                null,
                                reply(question, "r", List.of("opt-1", "opt-2"), "补充"),
                                false)
                        .replayed());
    }

    @Test
    void textOnlyAndLegacyPlainInputCloseTheSamePendingQuestion() throws Exception {
        timeline.append(
                sid, TimelineStore.QUESTION, Map.of("text", "旧问题", "suggestions", List.of("旧建议")));
        timeline.appendAdvisorMessage(sid, "等待回复", null, null, null);
        var pending = timeline.pendingQuestion(sid).orElseThrow();
        assertEquals("legacy-1", pending.payload().get("questionId"));
        assertEquals(
                "opt-1", ((List<Map<?, ?>>) pending.payload().get("options")).get(0).get("id"));
        var answer = timeline.appendQuestionReply(sid, "owner", "  自定义  ", null, null, false);
        assertEquals("自定义", answer.event().payload().get("text"));
        assertEquals(
                "legacy-1",
                ((Map<?, ?>) answer.event().payload().get("questionReply")).get("questionId"));
        assertTrue(timeline.pendingQuestion(sid).isEmpty());
        var textQuestion =
                timeline.appendQuestion(sid, QuestionService.question("描述", null, null, null));
        assertCode(
                "question_reply_invalid",
                () ->
                        timeline.appendQuestionReply(
                                sid,
                                "owner",
                                "",
                                null,
                                reply(textQuestion, "r", List.of("opt-1"), "a"),
                                false));
        timeline.appendQuestionReply(
                sid, "owner", "", null, reply(textQuestion, "r", List.of(), "文字"), false);
        assertTrue(timeline.pendingQuestion(sid).isEmpty());
    }

    @Test
    void historicalVisitorMessageResolvesLegacyQuestionButAdvisorTextDoesNot() throws Exception {
        timeline.append(sid, TimelineStore.QUESTION, Map.of("text", "旧问题"));
        timeline.appendAdvisorMessage(sid, "助手说明", null, null, null);
        assertTrue(timeline.pendingQuestion(sid).isPresent());
        timeline.appendVisitorMessage(sid, "旧客户端回答");
        assertTrue(timeline.pendingQuestion(sid).isEmpty());
    }

    @Test
    void aPendingQuestionCannotBeSilentlyReplaced() throws Exception {
        var question = createQuestion("single");
        var duplicate = createQuestion("single");
        assertEquals(question.seq(), duplicate.seq());
        assertEquals(question.payload().get("questionId"), duplicate.payload().get("questionId"));
        assertCode(
                "question_pending",
                () ->
                        timeline.appendQuestion(
                                sid, QuestionService.question("another", null, null, null)));
        assertEquals(1, timeline.listEntries(sid).size());
    }

    @Test
    void ownerUnknownQuestionAndActiveRuntimeAreRejected() throws Exception {
        var question = createQuestion("single");
        assertCode(
                "question_not_found",
                () ->
                        timeline.appendQuestionReply(
                                sid,
                                "other",
                                "",
                                null,
                                reply(question, "r", List.of("opt-1"), ""),
                                false));
        assertCode(
                "question_not_found",
                () ->
                        timeline.appendQuestionReply(
                                sid,
                                "owner",
                                "",
                                null,
                                Map.of("questionId", "unknown", "requestId", "r"),
                                false));
        assertCode(
                "session_running",
                () ->
                        timeline.appendQuestionReply(
                                sid,
                                "owner",
                                "",
                                null,
                                reply(question, "r", List.of("opt-1"), ""),
                                true));
        assertEquals(1, timeline.listEntries(sid).size());
    }

    @Test
    void databaseRowLockSerializesDifferentStoreInstances() throws Exception {
        var question = createQuestion("single");
        var other = new TimelineStore(dataSource, sessions, json);
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var request = reply(question, "same", List.of("opt-1"), "");
            var one =
                    pool.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return timeline.appendQuestionReply(
                                        sid, "owner", "", null, request, false);
                            });
            var two =
                    pool.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return other.appendQuestionReply(
                                        sid, "owner", "", null, request, false);
                            });
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            var a = one.get(5, TimeUnit.SECONDS);
            var b = two.get(5, TimeUnit.SECONDS);
            assertEquals(a.event().seq(), b.event().seq());
            assertNotEquals(a.replayed(), b.replayed());
            assertEquals(2, timeline.listEntries(sid).size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentAnswersAcceptOnlyOneRequest() throws Exception {
        var question = createQuestion("single");
        var other = new TimelineStore(dataSource, sessions, json);
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var one =
                    pool.submit(
                            () ->
                                    concurrentAnswer(
                                            timeline, question, "r1", "opt-1", ready, start));
            var two =
                    pool.submit(
                            () -> concurrentAnswer(other, question, "r2", "opt-2", ready, start));
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(
                    java.util.Set.of("saved", "question_already_answered"),
                    java.util.Set.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS)));
            assertEquals(2, timeline.listEntries(sid).size());
            assertTrue(timeline.pendingQuestion(sid).isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    private String concurrentAnswer(
            TimelineStore store,
            TimelineStore.AppendedEvent question,
            String request,
            String option,
            CountDownLatch ready,
            CountDownLatch start)
            throws Exception {
        ready.countDown();
        start.await();
        try {
            store.appendQuestionReply(
                    sid, "owner", "", null, reply(question, request, List.of(option), ""), false);
            return "saved";
        } catch (QuestionService.QuestionException error) {
            return error.code();
        }
    }

    @Test
    void continuationRemainsAfterRoutingButStopsOnceModelOutputWasPersisted() throws Exception {
        var question = createQuestion("single");
        timeline.appendQuestionReply(
                sid, "owner", "", null, reply(question, "r", List.of("opt-1"), ""), false);
        timeline.append(sid, "agent_route", Map.of("agentId", "agent"));
        assertTrue(timeline.pendingQuestionReply(sid).isPresent());
        timeline.append(sid, TimelineStore.TOOL_CALL, Map.of("id", "tool", "name", "read_file"));
        assertTrue(timeline.pendingQuestionReply(sid).isEmpty());
    }

    @Test
    void attachmentContextSurvivesRecoveryButCannotSpoofCanonicalQuestionText() throws Exception {
        var question = createQuestion("single");
        var input = new java.util.LinkedHashMap<>(reply(question, "r", List.of("opt-1"), ""));
        input.put("attachmentContext", "附件：需求说明\n原始内容");
        input.put("contextText", "伪造服务端问题");
        var saved = timeline.appendQuestionReply(sid, "owner", "", null, input, false).event();
        Map<?, ?> snapshot = (Map<?, ?>) saved.payload().get("questionReply");
        assertEquals("附件：需求说明\n原始内容", snapshot.get("attachmentContext"));
        assertEquals("对上一条追问的回答\n问题：方向？\n选择：方案", snapshot.get("contextText"));
        assertEquals(
                snapshot,
                timeline.pendingQuestionReply(sid).orElseThrow().payload().get("questionReply"));
        input.put("attachmentContext", "different file");
        assertCode(
                "question_request_conflict",
                () -> timeline.appendQuestionReply(sid, "owner", "", null, input, false));
        input.put("attachmentContext", "x".repeat(240001));
        assertCode(
                "question_reply_invalid",
                () -> timeline.appendQuestionReply(sid, "owner", "", null, input, false));
        input.put("attachmentContext", 1);
        assertCode(
                "question_reply_invalid",
                () -> timeline.appendQuestionReply(sid, "owner", "", null, input, false));
    }

    @Test
    void statusFailureRollsBackAnswerInsertion() throws Exception {
        var question = createQuestion("single");
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE advisor_sessions ADD CONSTRAINT reject_running CHECK (status <>"
                            + " 'running')");
        }
        assertThrows(
                SQLException.class,
                () ->
                        timeline.appendQuestionReply(
                                sid,
                                "owner",
                                "",
                                null,
                                reply(question, "r", List.of("opt-1"), ""),
                                false));
        assertEquals(1, timeline.listEntries(sid).size());
        assertTrue(timeline.pendingQuestion(sid).isPresent());
        assertEquals(
                SessionStore.STATUS_AWAITING_VISITOR, sessions.find(sid).orElseThrow().status());
    }

    private TimelineStore.AppendedEvent createQuestion(String mode) throws SQLException {
        return timeline.appendQuestion(
                sid, QuestionService.question("方向？", List.of("方案", "代码"), null, mode));
    }

    private Map<String, Object> reply(
            TimelineStore.AppendedEvent question,
            String request,
            List<String> selected,
            String custom) {
        return Map.of(
                "questionId",
                question.payload().get("questionId"),
                "requestId",
                request,
                "selectedOptionIds",
                selected,
                "customText",
                custom);
    }

    private void assertCode(String code, org.junit.jupiter.api.function.Executable call) {
        assertEquals(code, assertThrows(QuestionService.QuestionException.class, call).code());
    }
}
