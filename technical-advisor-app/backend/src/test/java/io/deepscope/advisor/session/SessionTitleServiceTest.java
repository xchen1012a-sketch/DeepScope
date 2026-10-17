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
import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.Model;
import io.deepscope.core.model.ToolSchema;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import reactor.core.publisher.Flux;

/** Auto-naming: model-generated title after the first reply, truncation fallback, no overwrite. */
class SessionTitleServiceTest {

    /** Model stub that replies with the fixed title text and counts invocations. */
    private static final class StubModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();
        private final Supplier<Flux<ChatResponse>> replies;

        private StubModel(Supplier<Flux<ChatResponse>> replies) {
            this.replies = replies;
        }

        static StubModel of(String title) {
            return new StubModel(
                    () ->
                            Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text(title)
                                                                    .build()))
                                            .build()));
        }

        int calls() {
            return calls.get();
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            return replies.get();
        }

        @Override
        public String getModelName() {
            return "stub";
        }
    }

    private SessionStore sessions;
    private TimelineStore timeline;
    private String sessionId;
    private String ownerId;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:title-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        sessions = new SessionStore(dataSource);
        ObjectMapper json = new ObjectMapper();
        timeline = new TimelineStore(dataSource, sessions, json);
        ownerId = "user-1";
        sessionId = sessions.create(ownerId, null).sessionId();
    }

    private SessionTitleService service(Model model) {
        return new SessionTitleService(model, sessions, timeline, new ObjectMapper());
    }

    /** Polls until the session has a non-null title or the timeout passes. */
    private String awaitTitle(long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String title = null;
        while (System.currentTimeMillis() < deadline) {
            title = sessions.find(sessionId).orElseThrow().title();
            if (title != null && !title.isBlank()) {
                return title;
            }
            Thread.sleep(50);
        }
        return title;
    }

    @Test
    void untitledSessionGetsModelGeneratedTitleAfterFirstReply() throws Exception {
        StubModel model = StubModel.of("\"会话标题\"");
        timeline.appendVisitorMessage(sessionId, "帮我评审一个单体拆微服务的方案");
        timeline.appendAdvisorMessage(sessionId, "好的，我们开始", null, "general", "");
        service(model).nameIfNeeded(sessions.find(sessionId).orElseThrow());

        assertEquals("会话标题", awaitTitle(5000));
    }

    @Test
    void modelFailureFallsBackToQuestionExcerpt() throws Exception {
        StubModel failing =
                new StubModel(() -> Flux.error(new IllegalStateException("endpoint down")));
        timeline.appendVisitorMessage(sessionId, "帮我看一下这个数据库设计的问题出在哪里");
        timeline.appendAdvisorMessage(sessionId, "分析如下", null, "general", "");
        service(failing).nameIfNeeded(sessions.find(sessionId).orElseThrow());

        assertEquals("帮我看一下这个数据库设计的问题出在哪里", awaitTitle(5000));
        assertEquals(1, failing.calls());
    }

    @Test
    void secondReplyOfNamedSessionNeverCallsTheModelAgain() throws Exception {
        StubModel model = StubModel.of("首次生成的标题");
        SessionTitleService naming = service(model);

        timeline.appendVisitorMessage(sessionId, "第一个问题");
        timeline.appendAdvisorMessage(sessionId, "第一个回答", null, "general", "");
        naming.nameIfNeeded(sessions.find(sessionId).orElseThrow());
        assertEquals("首次生成的标题", awaitTitle(5000));
        int callsAfterFirst = model.calls();

        timeline.appendVisitorMessage(sessionId, "第二个问题");
        timeline.appendAdvisorMessage(sessionId, "第二个回答", null, "general", "");
        naming.nameIfNeeded(sessions.find(sessionId).orElseThrow());
        Thread.sleep(500);

        assertEquals(callsAfterFirst, model.calls());
        assertEquals("首次生成的标题", sessions.find(sessionId).orElseThrow().title());
    }

    @Test
    void longQuestionsAreTruncatedToTitleLimit() throws Exception {
        StubModel failing = new StubModel(() -> Flux.error(new IllegalStateException("down")));
        timeline.appendVisitorMessage(sessionId, "这个".repeat(50));
        timeline.appendAdvisorMessage(sessionId, "回答", null, "general", "");
        service(failing).nameIfNeeded(sessions.find(sessionId).orElseThrow());

        String title = awaitTitle(5000);
        assertTrue(title != null && title.length() <= 20);
    }
}
