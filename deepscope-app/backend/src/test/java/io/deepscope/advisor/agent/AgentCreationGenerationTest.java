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
package io.deepscope.advisor.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.access.AccessCatalog;
import io.deepscope.advisor.access.AccessGuard;
import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.Model;
import io.deepscope.core.model.ToolSchema;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

/**
 * Task 2: Asynchronous candidate generation, bounded execution, candidate isolation,
 * and atomic application with baseRevision verification.
 */
class AgentCreationGenerationTest {
    @Test
    void aLateWorkerFailureCannotOverwriteACanceledGeneration() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "audit",
                        "Audit",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);
        var operation = operations.create(draft.draftId(), "generation", "late-failure", "hash", 1);
        operations.updateState(
                operation.operationId(), "canceled", "canceled", null, null, null, null);
        operations.updateState(
                operation.operationId(),
                "failed",
                "error",
                null,
                null,
                "model_unavailable",
                "interrupted");
        assertEquals(
                "canceled", operations.findById(operation.operationId()).orElseThrow().state());
    }

    @TempDir Path dataDir;

    private static final class TestModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile String replyText = "建议名称：测试会议纪要助手\n建议简介：提炼会议决议与代办\n工作要求：严格归纳，标出代办";
        private volatile boolean shouldFail = false;
        private volatile java.util.concurrent.CountDownLatch holdLatch = null;

        void setReply(String text) {
            this.replyText = text;
            this.shouldFail = false;
        }

        void holdWith(java.util.concurrent.CountDownLatch latch) {
            this.holdLatch = latch;
        }

        void fail() {
            this.shouldFail = true;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            if (holdLatch != null) {
                try {
                    holdLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
            if (shouldFail) {
                return Flux.error(new IllegalStateException("model unavailable"));
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(replyText).build()))
                            .build());
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private DataSource dataSource;
    private AccountStore accounts;
    private AgentStore agents;
    private AgentCreationDraftStore drafts;
    private AgentCreationOperationStore operations;
    private AgentCreationService service;
    private BehaviorDraftService behaviorService;
    private TestModel model;
    private MockMvc mvc;

    private String carolBearer;
    private String carolUserId;

    @BeforeEach
    void setUp() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:generation-test-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        accounts = new AccountStore(dataSource, Clock.systemUTC(), Duration.ofHours(12));
        accounts.bootstrap("root", "root-password");
        accounts.createUser("carol", "carol-password");

        agents = new AgentStore(dataSource, dataDir.resolve("agents"));
        drafts = new AgentCreationDraftStore(dataSource);
        operations = new AgentCreationOperationStore(dataSource);
        model = new TestModel();
        behaviorService = new BehaviorDraftService(model);
        AccessGuard accessGuard = new AccessGuard(new AccessCatalog(dataSource));
        service =
                new AgentCreationService(
                        drafts,
                        operations,
                        agents,
                        new AgentWorkspace(),
                        behaviorService,
                        accessGuard,
                        json);

        mvc =
                MockMvcBuilders.standaloneSetup(new AgentCreationController(service, json))
                        .addFilters(new ApiAuthenticationFilter(accounts, "/v1/admin"))
                        .build();

        carolBearer = "Bearer " + accounts.login("carol", "carol-password").orElseThrow().token();
        carolUserId = accounts.login("carol", "carol-password").orElseThrow().account().userId();
    }

    @Test
    void submitGenerationReturns202AndPollsState() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "帮我提炼会议要点",
                        "初始名称",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        String postRes =
                mvc.perform(
                                post("/api/advisor/agent-creation-drafts/"
                                                + draft.draftId()
                                                + "/generations")
                                        .header("Authorization", carolBearer)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(Map.of("baseRevision", 1))))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.operationId").isNotEmpty())
                        .andExpect(jsonPath("$.operationType").value("generation"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String opId = json.readTree(postRes).get("operationId").asText();

        // Wait up to 2 seconds for worker to finish
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100);
            var op = operations.findById(opId).orElseThrow();
            if ("succeeded".equals(op.state())) break;
        }

        mvc.perform(
                        get("/api/advisor/agent-creation-drafts/"
                                        + draft.draftId()
                                        + "/generations/"
                                        + opId)
                                .header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("succeeded"))
                .andExpect(jsonPath("$.candidate").isNotEmpty());

        // Verifying draft itself was NOT overwritten
        var unchangedDraft = drafts.findById(draft.draftId()).orElseThrow();
        assertEquals("初始名称", unchangedDraft.name(), "Draft must not be overwritten automatically");
        assertEquals(1, unchangedDraft.revision());
    }

    @Test
    void duplicateSubmissionWithSameKeyDoesNotCallModelAgain() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "用途说明",
                        "",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        String key = "gen-key-repeat";
        String body = json.writeValueAsString(Map.of("baseRevision", 1));

        mvc.perform(
                        post("/api/advisor/agent-creation-drafts/"
                                        + draft.draftId()
                                        + "/generations")
                                .header("Authorization", carolBearer)
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isAccepted());

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (model.calls() == 0 && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(
                    java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertEquals(
                1, model.calls(), "the first accepted asynchronous request must reach the model");
        int callsAfterFirst = model.calls();

        mvc.perform(
                        post("/api/advisor/agent-creation-drafts/"
                                        + draft.draftId()
                                        + "/generations")
                                .header("Authorization", carolBearer)
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isAccepted());

        assertEquals(
                callsAfterFirst,
                model.calls(),
                "Duplicate generation request with same key must not call model again");
    }

    @Test
    void modelFailureRecordsFailedStateWithoutOverwritingDraft() throws Exception {
        model.fail();
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "写纪要",
                        "原名称",
                        "原简介",
                        "原要求",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        String postRes =
                mvc.perform(
                                post("/api/advisor/agent-creation-drafts/"
                                                + draft.draftId()
                                                + "/generations")
                                        .header("Authorization", carolBearer)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(Map.of("baseRevision", 1))))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String opId = json.readTree(postRes).get("operationId").asText();

        // Wait up to 2 seconds for worker to finish
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100);
            var op = operations.findById(opId).orElseThrow();
            if ("failed".equals(op.state())) break;
        }

        var op = operations.findById(opId).orElseThrow();
        assertEquals("failed", op.state());
        assertNotNull(op.errorCode());

        // Verifying draft is intact
        var intactDraft = drafts.findById(draft.draftId()).orElseThrow();
        assertEquals("原名称", intactDraft.name());
        assertEquals("原要求", intactDraft.behavior());
    }

    @Test
    void applyCandidateSucceedsWithMatchingBaseRevision() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "帮我写周报",
                        "旧周报助手",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        String postRes =
                mvc.perform(
                                post("/api/advisor/agent-creation-drafts/"
                                                + draft.draftId()
                                                + "/generations")
                                        .header("Authorization", carolBearer)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(Map.of("baseRevision", 1))))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String opId = json.readTree(postRes).get("operationId").asText();

        // Wait for candidate to be ready
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100);
            var op = operations.findById(opId).orElseThrow();
            if ("succeeded".equals(op.state())) break;
        }

        // Apply candidate with matching revision 1
        mvc.perform(
                        post("/api/advisor/agent-creation-drafts/"
                                        + draft.draftId()
                                        + "/generations/"
                                        + opId
                                        + "/apply")
                                .header("Authorization", carolBearer)
                                .header("If-Match", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.name").isNotEmpty())
                .andExpect(jsonPath("$.behavior").isNotEmpty());

        var updated = drafts.findById(draft.draftId()).orElseThrow();
        assertEquals(2, updated.revision());
    }

    @Test
    void applyCandidateFailsWith409WhenDraftRevisionHasAdvanced() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "帮我写周报",
                        "旧周报助手",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        String postRes =
                mvc.perform(
                                post("/api/advisor/agent-creation-drafts/"
                                                + draft.draftId()
                                                + "/generations")
                                        .header("Authorization", carolBearer)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(Map.of("baseRevision", 1))))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String opId = json.readTree(postRes).get("operationId").asText();

        // Wait for generation to succeed
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100);
            var op = operations.findById(opId).orElseThrow();
            if ("succeeded".equals(op.state())) break;
        }

        // Meanwhile user updates draft manually, advancing revision to 2
        drafts.update(
                draft.draftId(),
                1,
                new AgentCreationDraftStore.DraftUpdate(
                        "用户新用途", "用户手动改的名字", "", "", "[]", "[]", "[]"));

        // Applying the candidate based on stale revision 1 must fail with 409
        mvc.perform(
                        post("/api/advisor/agent-creation-drafts/"
                                        + draft.draftId()
                                        + "/generations/"
                                        + opId
                                        + "/apply")
                                .header("Authorization", carolBearer)
                                .header("If-Match", "1"))
                .andExpect(status().isConflict());

        // Draft retains user's manual edit
        var current = drafts.findById(draft.draftId()).orElseThrow();
        assertEquals("用户手动改的名字", current.name());
        assertEquals(2, current.revision());
    }

    @Test
    void markInterruptedOnStartupTurnsRunningOperationsIntoInterrupted() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "用途",
                        "名称",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        var op = operations.create(draft.draftId(), "generation", "test-key", "test-hash", 1);
        operations.updateState(
                op.operationId(), "running", "calling_model", null, null, null, null);

        // Simulate server restart: startup recovery
        operations.markInterruptedOnStartup();

        var recovered = operations.findById(op.operationId()).orElseThrow();
        assertEquals(
                "interrupted",
                recovered.state(),
                "Running operations must be marked interrupted on startup");
    }

    @Test
    void cancelGenerationMarksOperationCanceled() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "用途",
                        "原名称",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        var latch = new java.util.concurrent.CountDownLatch(1);
        model.holdWith(latch);

        String postRes =
                mvc.perform(
                                post("/api/advisor/agent-creation-drafts/"
                                                + draft.draftId()
                                                + "/generations")
                                        .header("Authorization", carolBearer)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(Map.of("baseRevision", 1))))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String opId = json.readTree(postRes).get("operationId").asText();

        // Cancel the generation
        mvc.perform(
                        post("/api/advisor/agent-creation-drafts/"
                                        + draft.draftId()
                                        + "/generations/"
                                        + opId
                                        + "/cancel")
                                .header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("canceled"));

        // Release latch
        latch.countDown();

        // Verify operation stays canceled
        var op = operations.findById(opId).orElseThrow();
        assertEquals("canceled", op.state());

        // Attempting to apply a canceled generation must fail with 400
        mvc.perform(
                        post("/api/advisor/agent-creation-drafts/"
                                        + draft.draftId()
                                        + "/generations/"
                                        + opId
                                        + "/apply")
                                .header("Authorization", carolBearer)
                                .header("If-Match", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void queueFullReturns429() throws Exception {
        var latch = new java.util.concurrent.CountDownLatch(1);
        model.holdWith(latch);

        try {
            // Fill 2 workers + 32 queued slots = 34 total
            for (int i = 0; i < 34; i++) {
                var draft =
                        drafts.create(
                                carolUserId,
                                "describe",
                                null,
                                "用途" + i,
                                "名称" + i,
                                "",
                                "",
                                "[]",
                                "[]",
                                "[]",
                                null,
                                null);
                mvc.perform(
                                post("/api/advisor/agent-creation-drafts/"
                                                + draft.draftId()
                                                + "/generations")
                                        .header("Authorization", carolBearer)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(Map.of("baseRevision", 1))))
                        .andExpect(status().isAccepted());
            }

            // 35th generation request must hit queue limit and return 429
            var overflowDraft =
                    drafts.create(
                            carolUserId,
                            "describe",
                            null,
                            "超额用途",
                            "超额名称",
                            "",
                            "",
                            "[]",
                            "[]",
                            "[]",
                            null,
                            null);
            mvc.perform(
                            post("/api/advisor/agent-creation-drafts/"
                                            + overflowDraft.draftId()
                                            + "/generations")
                                    .header("Authorization", carolBearer)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsString(Map.of("baseRevision", 1))))
                    .andExpect(status().isTooManyRequests());
        } finally {
            latch.countDown();
        }
    }
}
