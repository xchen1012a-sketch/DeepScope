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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.core.message.Msg;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.Model;
import io.deepscope.core.model.ToolSchema;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

/** Session API: per-owner isolation and the message/timeline contract used for refresh replay. */
class AdvisorSessionControllerTest {

    @Test
    void lateClientUploadAfterFallbackIsIdempotentAndPreservesTheNewRound() throws Exception {
        var json = new ObjectMapper();
        try (var persister = new AguiRoundPersister(timeline, sessions, 60_000)) {
            mvc =
                    MockMvcBuilders.standaloneSetup(
                                    new AdvisorSessionController(
                                            sessions,
                                            timeline,
                                            new SessionTitleService(
                                                    new NoReplyModel(), sessions, timeline, json),
                                            json,
                                            new io.deepscope.advisor.AdvisorProperties(
                                                    java.nio.file.Path.of(
                                                            System.getProperty("java.io.tmpdir"),
                                                            "advisor-late-save-test")),
                                            persister,
                                            null))
                            .addFilters(new ApiAuthenticationFilter(accounts, "/v1/admin"))
                            .build();
            String sid = createSession();
            timeline.appendVisitorMessage(sid, "first question");
            var context =
                    new io.deepscope.core.agui.adapter.strategy.AguiStreamContext(
                            sid,
                            "old",
                            io.deepscope.core.agui.adapter.AguiAdapterConfig.builder().build());
            persister.enrich(
                    null,
                    List.of(
                            new io.deepscope.core.agui.event.AguiEvent.RunStarted(sid, "old"),
                            new io.deepscope.core.agui.event.AguiEvent.TextMessageContent(
                                    sid, "old", "m1", "answer")),
                    context);
            persister.flush(sid, "old");
            var saved =
                    timeline.listEntries(sid).stream()
                            .filter(entry -> entry.kind().equals("advisor_message"))
                            .findFirst()
                            .orElseThrow();
            var body =
                    json.readValue(
                            saved.payload(),
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    java.util.Map<String, Object>>() {});
            body.put("role", "advisor");
            timeline.appendVisitorMessage(sid, "second question");
            var next =
                    new io.deepscope.core.agui.adapter.strategy.AguiStreamContext(
                            sid,
                            "new",
                            io.deepscope.core.agui.adapter.AguiAdapterConfig.builder().build());
            persister.enrich(
                    null,
                    List.of(
                            new io.deepscope.core.agui.event.AguiEvent.RunStarted(sid, "new"),
                            new io.deepscope.core.agui.event.AguiEvent.TextMessageContent(
                                    sid, "new", "m2", "still running")),
                    next);
            for (int retry = 0; retry < 2; retry++) {
                mvc.perform(
                                post("/api/advisor/sessions/" + sid + "/messages")
                                        .header("Authorization", bearer)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.seq").value(saved.seq()));
            }
            body.put("text", "conflicting answer");
            mvc.perform(
                            post("/api/advisor/sessions/" + sid + "/messages")
                                    .header("Authorization", bearer)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsString(body)))
                    .andExpect(status().isConflict());
            org.junit.jupiter.api.Assertions.assertTrue(persister.hasPending(sid));
            org.junit.jupiter.api.Assertions.assertEquals(
                    SessionStore.STATUS_RUNNING, sessions.find(sid).orElseThrow().status());
            org.junit.jupiter.api.Assertions.assertEquals(
                    1,
                    timeline.listEntries(sid).stream()
                            .filter(entry -> entry.kind().equals("advisor_message"))
                            .count());
        }
    }

    @Test
    void readingDetailDoesNotTerminateARoundBeforeTheClientCanPersistIt() throws Exception {
        var session =
                sessions.create(
                        accounts.authenticate(bearer.substring(7)).orElseThrow().userId(), "fresh");
        timeline.appendVisitorMessage(session.sessionId(), "question");
        mvc.perform(
                        get("/api/advisor/sessions/" + session.sessionId())
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.status").value("running"));
    }

    /** Model stub that never answers, so auto-naming stays silent during controller tests. */
    private static final class NoReplyModel implements Model {
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "no-reply";
        }
    }

    private AccountStore accounts;
    private MockMvc mvc;
    private String bearer;
    private DataSource dataSource;
    private SessionStore sessions;
    private TimelineStore timeline;

    @BeforeEach
    void setUp() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:advisor-sessions-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        accounts = new AccountStore(dataSource, Clock.systemUTC(), Duration.ofHours(12));
        accounts.bootstrap("alice", "alice-password");
        accounts.bootstrap("bob", "bob-password");
        sessions = new SessionStore(dataSource);
        ObjectMapper json = new ObjectMapper();
        timeline = new TimelineStore(dataSource, sessions, json);
        SessionTitleService titles =
                new SessionTitleService(new NoReplyModel(), sessions, timeline, json);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AdvisorSessionController(sessions, timeline, titles, json))
                        .addFilters(new ApiAuthenticationFilter(accounts, "/v1/admin"))
                        .build();
        bearer = "Bearer " + accounts.login("alice", "alice-password").orElseThrow().token();
    }

    @Test
    void structuredQuestionAnswerUsesCanonicalTextAndRestoresContinuation() throws Exception {
        String sid = createSession();
        var question =
                timeline.appendQuestion(
                        sid, QuestionService.question("选择方向", List.of("方案", "代码"), null, null));
        timeline.saveAdvisorRound(sid, java.util.Map.of("text", "等待回答"), null, null, null, 1);
        mvc.perform(get("/api/advisor/sessions/" + sid).header("Authorization", bearer))
                .andExpect(
                        jsonPath("$.pendingQuestion.questionId")
                                .value(question.payload().get("questionId")))
                .andExpect(jsonPath("$.pendingQuestion.seq").value(question.seq()))
                .andExpect(jsonPath("$.session.status").value("awaiting_visitor"));
        var reply =
                java.util.Map.of(
                        "questionId",
                        question.payload().get("questionId"),
                        "requestId",
                        "r1",
                        "selectedOptionIds",
                        List.of("opt-2"));
        var body = java.util.Map.of("role", "visitor", "text", "伪造选项", "questionReply", reply);
        String url = "/api/advisor/sessions/" + sid + "/messages";
        mvc.perform(
                        post(url)
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(new ObjectMapper().writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payload.text").value("代码"))
                .andExpect(jsonPath("$.payload.questionReply.questionText").value("选择方向"))
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(
                        post(url)
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(new ObjectMapper().writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(get("/api/advisor/sessions/" + sid).header("Authorization", bearer))
                .andExpect(jsonPath("$.pendingQuestion").isEmpty())
                .andExpect(jsonPath("$.pendingQuestionReply.payload.text").value("代码"));
    }

    @Test
    void cardAnswerCanOmitTextAndAdvisorCannotAnswerQuestion() throws Exception {
        String sid = createSession();
        var question =
                timeline.appendQuestion(sid, QuestionService.question("输入描述", null, null, null));
        var reply =
                java.util.Map.of(
                        "questionId",
                        question.payload().get("questionId"),
                        "requestId",
                        "r1",
                        "customText",
                        "自定义回答");
        String url = "/api/advisor/sessions/" + sid + "/messages";
        mvc.perform(
                        post(url)
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        new ObjectMapper()
                                                .writeValueAsString(
                                                        java.util.Map.of(
                                                                "role",
                                                                "advisor",
                                                                "text",
                                                                "bad",
                                                                "questionReply",
                                                                reply))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("question_reply_invalid"));
        mvc.perform(
                        post(url)
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        new ObjectMapper()
                                                .writeValueAsString(
                                                        java.util.Map.of(
                                                                "role",
                                                                "system",
                                                                "text",
                                                                "bad",
                                                                "questionReply",
                                                                reply))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("question_reply_invalid"));
        mvc.perform(
                        post(url)
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        new ObjectMapper()
                                                .writeValueAsString(
                                                        java.util.Map.of(
                                                                "role",
                                                                "visitor",
                                                                "questionReply",
                                                                reply))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payload.text").value("自定义回答"));
    }

    @Test
    void plainComposerAnswersPendingQuestionAndUnrelatedOwnerCannotReadIt() throws Exception {
        String sid = createSession();
        timeline.append(sid, TimelineStore.QUESTION, java.util.Map.of("text", "旧问题"));
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"visitor\",\"text\":\"普通输入\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payload.questionReply.questionId").value("legacy-1"));
        String bob = "Bearer " + accounts.login("bob", "bob-password").orElseThrow().token();
        mvc.perform(get("/api/advisor/sessions/" + sid).header("Authorization", bob))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/messages")
                                .header("Authorization", bob)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"role\":\"visitor\",\"questionReply\":{\"questionId\":\"legacy-1\",\"requestId\":\"r\",\"customText\":\"bad\"}}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createsListsAndRenamesSessionsForTheOwner() throws Exception {
        String sessionId = createSession();
        mvc.perform(get("/api/advisor/sessions/" + sessionId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.status").value("idle"));

        mvc.perform(get("/api/advisor/sessions").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sessionId").value(sessionId));

        mvc.perform(
                        patch("/api/advisor/sessions/" + sessionId)
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"仓库尽调\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("仓库尽调"));
    }

    @Test
    void otherVisitorsCannotSeeRenameOrDeleteSomeoneElsesSession() throws Exception {
        String sessionId = createSession();
        String bob = "Bearer " + accounts.login("bob", "bob-password").orElseThrow().token();

        mvc.perform(get("/api/advisor/sessions/" + sessionId).header("Authorization", bob))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/advisor/sessions").header("Authorization", bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(
                        patch("/api/advisor/sessions/" + sessionId)
                                .header("Authorization", bob)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"抢注\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/advisor/sessions/" + sessionId).header("Authorization", bob))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/advisor/sessions/" + sessionId).header("Authorization", bearer))
                .andExpect(status().isOk());
    }

    @Test
    void detailDoesNotChangeEvenAStaleSessionsLifecycle() throws Exception {
        String sessionId = createSession();
        // 一条访客消息把状态推到 running，模拟客户端中断后无人收口的一轮。
        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"visitor\",\"text\":\"看看这个仓库\"}"))
                .andExpect(status().isCreated());
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE advisor_sessions SET updated_at = ? WHERE session_id"
                                        + " = ?")) {
            statement.setLong(1, System.currentTimeMillis() - 20 * 60 * 1000L);
            statement.setString(2, sessionId);
            statement.executeUpdate();
        }
        // Recovery belongs to the guarded background reconciler; GET remains read-only.
        mvc.perform(get("/api/advisor/sessions/" + sessionId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.status").value("running"));
    }

    @Test
    void messagesAndTimelineReplayPerTurn() throws Exception {
        String sessionId = createSession();
        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"visitor\",\"text\":\"看看这个仓库\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn").value(1))
                .andExpect(jsonPath("$.kind").value("user_message"));
        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"role\":\"advisor\",\"text\":\"总体可信\",\"mode\":\"specialist\","
                                            + "\"domainName\":\"仓库尽调\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn").value(1))
                .andExpect(jsonPath("$.kind").value("advisor_message"));
        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"visitor\",\"text\":\"第二个问题\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn").value(2));

        mvc.perform(get("/api/advisor/sessions/" + sessionId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].payload.text").value("看看这个仓库"))
                .andExpect(jsonPath("$.entries[2].turn").value(2));
    }

    @Test
    void recordsAnAdvisorRoundThatHasNoFinalText() throws Exception {
        String sessionId = createSession();
        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"visitor\",\"text\":\"看看这个仓库\"}"))
                .andExpect(status().isCreated());
        // 只有思考与工具调用、没有最终正文的一轮也要落库
        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"role\":\"advisor\",\"text\":\"\",\"reasoning\":\"先看构建脚本\","
                                            + "\"toolCalls\":[{\"id\":\"c1\",\"name\":\"read_file\",\"status\":\"done\"}],"
                                            + "\"usage\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("advisor_message"));

        mvc.perform(get("/api/advisor/sessions/" + sessionId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[1].kind").value("tool_call"))
                .andExpect(jsonPath("$.entries[2].kind").value("advisor_message"))
                .andExpect(jsonPath("$.entries[2].payload.text").value(""));
    }

    @Test
    void orderedPartsSurviveTheMessageAndDetailApis() throws Exception {
        String sid = createSession();
        String body =
                """
                {"role":"advisor","text":"beforeafter","partsVersion":1,"parts":[
                  {"id":"run:part:1","runId":"run","kind":"text","status":"done","text":"before"},
                  {"id":"run:part:2","runId":"run","kind":"tool","status":"done","tool":{"id":"a","name":"read_file","status":"done"}},
                  {"id":"run:part:3","runId":"run","kind":"text","status":"done","text":"after"}]}
                """;
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payload.partsVersion").value(1))
                .andExpect(jsonPath("$.payload.parts[1].tool.id").value("a"));
        mvc.perform(get("/api/advisor/sessions/" + sid).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].payload.parts[2].text").value("after"));
    }

    @Test
    void normalUploadUsesObservedServerTimingInsteadOfClientProvidedTiming() throws Exception {
        var json = new ObjectMapper();
        var now = new java.util.concurrent.atomic.AtomicLong(1000);
        try (var persister = new AguiRoundPersister(timeline, sessions, 60_000, now::get)) {
            mvc =
                    MockMvcBuilders.standaloneSetup(
                                    new AdvisorSessionController(
                                            sessions,
                                            timeline,
                                            new SessionTitleService(
                                                    new NoReplyModel(), sessions, timeline, json),
                                            json,
                                            new io.deepscope.advisor.AdvisorProperties(
                                                    java.nio.file.Path.of(
                                                            System.getProperty("java.io.tmpdir"),
                                                            "advisor-presentation-test")),
                                            persister,
                                            null))
                            .addFilters(new ApiAuthenticationFilter(accounts, "/v1/admin"))
                            .build();
            String sid = createSession();
            var context =
                    new io.deepscope.core.agui.adapter.strategy.AguiStreamContext(
                            sid,
                            "run",
                            io.deepscope.core.agui.adapter.AguiAdapterConfig.builder().build());
            var registry =
                    new io.deepscope.core.agui.adapter.strategy.AgentEventConverterRegistry(
                            List.of(), List.of(persister));
            persister.enrich(
                    null,
                    List.of(new io.deepscope.core.agui.event.AguiEvent.RunStarted(sid, "run")),
                    context);
            registry.convert(new io.deepscope.core.event.ModelCallStartEvent("call"), context);
            now.set(83000);
            registry.convert(
                    new io.deepscope.core.event.TextBlockDeltaEvent("call", "body", "answer"),
                    context);
            registry.convert(
                    new io.deepscope.core.event.TextBlockEndEvent("call", "body"), context);
            registry.convert(
                    new io.deepscope.core.event.AgentResultEvent(
                            io.deepscope.core.message.AssistantMessage.builder()
                                    .content(
                                            io.deepscope.core.message.TextBlock.builder()
                                                    .text("answer")
                                                    .build())
                                    .build()),
                    context);
            now.set(95000);
            persister.enrich(
                    null,
                    List.of(new io.deepscope.core.agui.event.AguiEvent.RunFinished(sid, "run")),
                    context);
            String body =
                    """
                    {"role":"advisor","text":"answer","partsVersion":1,"parts":[
                    {"id":"run:part:1","runId":"run","kind":"text","status":"done","messageId":"call-body","text":"answer"}],
                    "presentationVersion":1,"presentation":{"runId":"run","runStartedAt":1,"finalStartedAt":2,
                    "runEndedAt":3,"finalMessageIds":["call-body"],"outcome":"completed"}}
                    """;
            mvc.perform(
                            post("/api/advisor/sessions/" + sid + "/messages")
                                    .header("Authorization", bearer)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.payload.presentation.runStartedAt").value(1000))
                    .andExpect(jsonPath("$.payload.presentation.finalStartedAt").value(83000))
                    .andExpect(jsonPath("$.payload.presentation.runEndedAt").value(95000));
            org.junit.jupiter.api.Assertions.assertNull(persister.presentation(sid, "run"));
        }
    }

    @Test
    void savesPresentationForRefreshAndRejectsInvalidReferences() throws Exception {
        String sid = createSession();
        String body =
                """
                {"role":"advisor","text":"answer","partsVersion":1,"parts":[
                  {"id":"run:part:1","runId":"run","kind":"text","status":"done","messageId":"final","text":"answer"}],
                 "presentationVersion":1,"presentation":{"runId":"run","runStartedAt":1000,
                   "finalStartedAt":83000,"runEndedAt":95000,"finalMessageIds":["final"],"outcome":"completed"}}
                """;
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payload.presentation.finalStartedAt").value(83000));
        mvc.perform(get("/api/advisor/sessions/" + sid).header("Authorization", bearer))
                .andExpect(jsonPath("$.entries[0].payload.presentation.runStartedAt").value(1000));
        for (String bad :
                List.of(
                        body.replace("[\"final\"]", "[\"missing\"]"),
                        body.replace("83000", "999"),
                        body.replace("\"presentationVersion\":1", "\"presentationVersion\":2"))) {
            mvc.perform(
                            post("/api/advisor/sessions/" + sid + "/messages")
                                    .header("Authorization", bearer)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(bad))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/advisor/sessions/" + sid).header("Authorization", bearer))
                .andExpect(jsonPath("$.entries.length()").value(1));
    }

    @Test
    void rejectsMalformedPartsBeforeWritingAnyTimelineRecords() throws Exception {
        String sid = createSession();
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"role\":\"advisor\",\"text\":\"reply\",\"partsVersion\":1,\"parts\":[{\"id\":\"bad\",\"runId\":\"run\",\"kind\":\"text\",\"text\":\"reply\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/advisor/sessions/" + sid).header("Authorization", bearer))
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void stillRejectsRoundsWithNothingAtAll() throws Exception {
        String sessionId = createSession();
        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"role\":\"advisor\",\"text\":\"\",\"toolCalls\":[],\"usage\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"visitor\",\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordsSubagentStepsInArrivalOrder() throws Exception {
        String sessionId = createSession();
        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"visitor\",\"text\":\"看看这个仓库\"}"))
                .andExpect(status().isCreated());
        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"role\":\"advisor\",\"text\":\"结论\",\"subagentRuns\":["
                                            + "{\"subtaskId\":\"s1\",\"events\":["
                                            + "{\"kind\":\"thinking\",\"text\":\"先看构建脚本\"},"
                                            + "{\"kind\":\"tool_call\",\"toolCallId\":\"c9\",\"name\":\"read_file\",\"status\":\"done\"}]}]}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/advisor/sessions/" + sessionId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[1].kind").value("subagent_step"))
                .andExpect(jsonPath("$.entries[1].payload.subtaskId").value("s1"))
                .andExpect(jsonPath("$.entries[1].payload.order").value(1))
                .andExpect(jsonPath("$.entries[1].payload.kind").value("thinking"))
                .andExpect(jsonPath("$.entries[2].payload.order").value(2))
                .andExpect(jsonPath("$.entries[2].payload.toolCallId").value("c9"));
    }

    @Test
    void unauthenticatedAccessIsRejected() throws Exception {
        mvc.perform(get("/api/advisor/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/agui/run/technical-advisor")).andExpect(status().isUnauthorized());
    }

    @Test
    void compactingSomeoneElsesSessionIsNotFound() throws Exception {
        String sessionId = createSession();
        String bob = "Bearer " + accounts.login("bob", "bob-password").orElseThrow().token();
        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + ":compact")
                                .header("Authorization", bob))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadsMarkdownZipAndImageAttachments() throws Exception {
        org.springframework.mock.web.MockMultipartFile mdFile =
                new org.springframework.mock.web.MockMultipartFile(
                        "file",
                        "architecture.md",
                        "text/markdown",
                        "# 核心架构\n采用事件驱动架构。".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .multipart("/api/advisor/sessions/attachments")
                                .file(mdFile)
                                .header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("document"))
                .andExpect(jsonPath("$.name").value("architecture.md"))
                .andExpect(
                        jsonPath("$.extractedText")
                                .value(org.hamcrest.Matchers.containsString("采用事件驱动架构")));

        java.io.ByteArrayOutputStream zipOut = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(zipOut)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("src/Main.java"));
            zos.write("public class Main {}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        org.springframework.mock.web.MockMultipartFile zipFile =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "repo.zip", "application/zip", zipOut.toByteArray());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .multipart("/api/advisor/sessions/attachments")
                                .file(zipFile)
                                .header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("archive"))
                .andExpect(
                        jsonPath("$.extractedText")
                                .value(org.hamcrest.Matchers.containsString("src/Main.java")));

        byte[] pngBytes = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        org.springframework.mock.web.MockMultipartFile imageFile =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "diagram.png", "image/png", pngBytes);
        byte[] uploadResp =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .multipart("/api/advisor/sessions/attachments")
                                        .file(imageFile)
                                        .header("Authorization", bearer))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.kind").value("image"))
                        .andExpect(jsonPath("$.mimeType").value("image/png"))
                        .andExpect(
                                jsonPath("$.dataUrl")
                                        .value(
                                                org.hamcrest.Matchers.startsWith(
                                                        "data:image/png;base64,")))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();

        String attachmentId =
                new ObjectMapper()
                        .readTree(new String(uploadResp, java.nio.charset.StandardCharsets.UTF_8))
                        .get("id")
                        .asText();
        mvc.perform(
                        get("/api/advisor/sessions/attachments/" + attachmentId + "/content")
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .contentType("image/png"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .bytes(pngBytes));
    }

    @Test
    void paginatesTimelineAndSubagentsPerSession() throws Exception {
        String sessionId = createSession();
        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"visitor\",\"text\":\"第一问\"}"))
                .andExpect(status().isCreated());
        timeline.append(
                sessionId,
                TimelineStore.DISPATCH,
                java.util.Map.of(
                        "subtaskId",
                        "sub-1",
                        "name",
                        "code-explorer",
                        "spec",
                        java.util.Map.of("task", "读取 Pom")));
        timeline.append(
                sessionId,
                TimelineStore.SUBAGENT_SETTLED,
                java.util.Map.of("subagentId", "sub-1", "status", "done", "summary", "已梳理完依赖"));
        timeline.append(
                sessionId,
                TimelineStore.DISPATCH,
                java.util.Map.of(
                        "subtaskId",
                        "sub-2",
                        "name",
                        "doc-reader",
                        "spec",
                        java.util.Map.of("task", "读取 README")));
        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/messages")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"advisor\",\"text\":\"第一答\"}"))
                .andExpect(status().isCreated());

        mvc.perform(
                        get("/api/advisor/sessions/" + sessionId + "/timeline?page=1&size=2")
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("advisor_message"))
                .andExpect(jsonPath("$.items[0].anchor").value("flow-5"));

        mvc.perform(
                        get("/api/advisor/sessions/" + sessionId + "/timeline?page=3&size=2")
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(3))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("user_message"))
                .andExpect(jsonPath("$.items[0].anchor").value("flow-1"));

        mvc.perform(
                        get("/api/advisor/sessions/" + sessionId + "/subagents?page=1&size=1")
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("sub-2"))
                .andExpect(jsonPath("$.items[0].name").value("doc-reader"));

        mvc.perform(
                        get("/api/advisor/sessions/" + sessionId + "/subagents?page=2&size=1")
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("sub-1"))
                .andExpect(jsonPath("$.items[0].status").value("done"));
    }

    private String createSession() throws Exception {
        byte[] body =
                mvc.perform(post("/api/advisor/sessions").header("Authorization", bearer))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        return new ObjectMapper()
                .readTree(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                .get("sessionId")
                .asText();
    }
}
