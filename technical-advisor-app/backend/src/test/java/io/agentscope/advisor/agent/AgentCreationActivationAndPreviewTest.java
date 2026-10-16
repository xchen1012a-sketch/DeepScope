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
package io.agentscope.advisor.agent;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.advisor.access.AccessCatalog;
import io.agentscope.advisor.access.AccessGuard;
import io.agentscope.advisor.auth.AccountStore;
import io.agentscope.advisor.auth.ApiAuthenticationFilter;
import io.agentscope.advisor.db.AdvisorSchema;
import io.agentscope.advisor.session.ReportActions;
import io.agentscope.advisor.session.SessionStore;
import io.agentscope.advisor.session.TimelineStore;
import io.agentscope.advisor.skill.SkillCatalog;
import io.agentscope.advisor.skill.SkillSync;
import io.agentscope.advisor.tool.McpToolSource;
import io.agentscope.advisor.tool.ToolCatalog;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Consolidated Phase 1 tests: Checks, Idempotent Activation, Runtime Gates,
 * Failure Compensation, and Isolated Preview Trial.
 */
class AgentCreationActivationAndPreviewTest {

    @TempDir Path dataDir;

    private static final class TestModel implements Model {
        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("预览试聊回复：你好！").build()))
                            .build());
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private DataSource dataSource;
    private AccountStore accounts;
    private AgentStore agents;
    private AgentCreationDraftStore drafts;
    private AgentCreationOperationStore operations;
    private AgentPreviewStore previewStore;
    private AgentCreationService service;
    private AgentPreviewService previewService;
    private AgentProvisioner provisioner;
    private AguiAgentRegistry registry;
    private MockMvc mvc;

    private String carolBearer;
    private String carolUserId;
    private String daveBearer;
    private String daveUserId;

    @BeforeEach
    void setUp() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:activation-preview-test-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        accounts = new AccountStore(dataSource, Clock.systemUTC(), Duration.ofHours(12));
        accounts.bootstrap("root", "root-password");
        accounts.createUser("carol", "carol-password");
        accounts.createUser("dave", "dave-password");

        agents = new AgentStore(dataSource, dataDir.resolve("agents"));
        drafts = new AgentCreationDraftStore(dataSource);
        operations = new AgentCreationOperationStore(dataSource);
        previewStore = new AgentPreviewStore(dataSource);
        registry = new AguiAgentRegistry();
        TestModel model = new TestModel();
        BehaviorDraftService behaviorService = new BehaviorDraftService(model);
        AccessGuard accessGuard = new AccessGuard(new AccessCatalog(dataSource));
        AgentWorkspace workspace = new AgentWorkspace();

        SessionStore sessionStore = new SessionStore(dataSource);
        TimelineStore timelineStore = new TimelineStore(dataSource, sessionStore, json);
        ToolCatalog toolCatalog = new ToolCatalog(dataSource, json);
        McpToolSource mcpSource = new io.agentscope.advisor.tool.FrameworkMcpToolSource();
        SkillCatalog skillCatalog = new SkillCatalog(dataSource, dataDir.resolve("skills"));
        SkillSync skillSync = new SkillSync(skillCatalog);
        ReportActions reportActions = new ReportActions(timelineStore, sessionStore);

        provisioner =
                new AgentProvisioner(
                        agents,
                        model,
                        reportActions,
                        registry,
                        toolCatalog,
                        mcpSource,
                        json,
                        skillSync,
                        null,
                        timelineStore,
                        dataDir.resolve("shared"));

        previewService = new AgentPreviewService(previewStore, drafts, model, json);
        service =
                new AgentCreationService(
                        drafts,
                        operations,
                        agents,
                        workspace,
                        behaviorService,
                        accessGuard,
                        provisioner,
                        json);

        AgentCreationController controller =
                new AgentCreationController(service, previewService, json);
        mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .addFilters(new ApiAuthenticationFilter(accounts, "/v1/admin"))
                        .build();

        carolBearer = "Bearer " + accounts.login("carol", "carol-password").orElseThrow().token();
        carolUserId = accounts.login("carol", "carol-password").orElseThrow().account().userId();
        daveBearer = "Bearer " + accounts.login("dave", "dave-password").orElseThrow().token();
        daveUserId = accounts.login("dave", "dave-password").orElseThrow().account().userId();
    }

    @Test
    void activationKeepsSelectedCapabilitiesAndEndsTheDraft() throws Exception {
        var tools = new ToolCatalog(dataSource, json);
        var tool =
                tools.importTools(
                                carolUserId,
                                List.of(
                                        new io.agentscope.advisor.tool.ToolImport.ImportedTool(
                                                "selected",
                                                "audit",
                                                "{\"type\":\"object\"}",
                                                "http",
                                                "GET",
                                                "http://test.invalid")),
                                "allow")
                        .get(0);
        var skills = new SkillCatalog(dataSource, dataDir.resolve("skills"));
        var skill =
                skills.createDraft(
                        carolUserId,
                        "selected-skill",
                        "audit",
                        "---\nname: selected-skill\ndescription: audit\n---\nAudit");
        skills.publish(skill);
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "audit",
                        "Audit",
                        "",
                        "Answer briefly",
                        json.writeValueAsString(List.of(tool.toolId())),
                        json.writeValueAsString(List.of(skill.skillId())),
                        "[]",
                        null,
                        null);
        var account = accounts.login("carol", "carol-password").orElseThrow().account();
        var result = service.submitActivation(account, draft.draftId(), 1, "first");
        assertEquals(
                List.of(tool.toolId()), tools.selection(result.preallocatedAgentId()).toolIds());
        assertEquals(List.of(skill.skillId()), skills.selection(result.preallocatedAgentId()));
        assertTrue(
                java.nio.file.Files.exists(
                        Path.of(
                                        agents.find(result.preallocatedAgentId())
                                                .orElseThrow()
                                                .workspaceDir())
                                .resolve("skills/selected-skill/SKILL.md")));
        assertTrue(drafts.listVisible(carolUserId, false).isEmpty());
        var second = service.submitActivation(account, draft.draftId(), 1, "other-key");
        assertEquals(result.preallocatedAgentId(), second.preallocatedAgentId());
        assertEquals(1, agents.list().size());
    }

    @Test
    void twoDifferentActivationKeysMustResolveToTheSameOfficialAgent() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "audit",
                        "Audit",
                        "",
                        "Answer briefly",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);
        var account = accounts.login("carol", "carol-password").orElseThrow().account();
        var first = service.submitActivation(account, draft.draftId(), 1, "key-1");
        var second = service.submitActivation(account, draft.draftId(), 1, "key-2");
        assertEquals(first.preallocatedAgentId(), second.preallocatedAgentId());
    }

    @Test
    void deletingAPublishingDraftCannotRemoveItsOperation() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "audit",
                        "Audit",
                        "",
                        "Answer",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);
        var reservation =
                new AgentActivationStore(dataSource, json)
                        .reserve(draft.draftId(), 1, "reserved", "hash");
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> drafts.delete(draft.draftId()));
        assertTrue(operations.findById(reservation.operationId()).isPresent());
    }

    @Test
    void interruptedPublicationCanBeRetriedAfterStartupRecovery() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "audit",
                        "Audit",
                        "",
                        "Answer",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);
        var reserved =
                new AgentActivationStore(dataSource, json)
                        .reserve(draft.draftId(), 1, "crashed", "hash");
        // Re-initializing the schema represents the application's single-node startup boundary.
        AdvisorSchema.initialize(dataSource);
        var account = accounts.login("carol", "carol-password").orElseThrow().account();
        var result = service.submitActivation(account, draft.draftId(), 1, "after-restart");
        assertEquals("succeeded", result.state());
        assertEquals(reserved.agentId(), result.preallocatedAgentId());
    }

    @Test
    void concurrentActivationsFromSeparateServicesPublishOneAgent() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "audit",
                        "Concurrent",
                        "",
                        "Answer briefly",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);
        var account = accounts.login("carol", "carol-password").orElseThrow().account();
        var another =
                new AgentCreationService(
                        new AgentCreationDraftStore(dataSource),
                        new AgentCreationOperationStore(dataSource),
                        agents,
                        new AgentWorkspace(),
                        null,
                        new AccessGuard(new AccessCatalog(dataSource)),
                        provisioner,
                        json);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return service.submitActivation(
                                        account, draft.draftId(), 1, "first-concurrent");
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return another.submitActivation(
                                        account, draft.draftId(), 1, "second-concurrent");
                            });
            start.countDown();
            var one = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var two = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(one.preallocatedAgentId(), two.preallocatedAgentId());
            assertEquals(1, agents.list().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stagingAgentCannotBeSelectedUntilPublicationCompletes() throws Exception {
        try (var connection = dataSource.getConnection()) {
            agents.createWithId(connection, "staging-test", "Hidden", "", carolUserId, "staging");
        }
        assertTrue(agents.find("staging-test").isEmpty());
        assertTrue(agents.listVisible(carolUserId, true).isEmpty());
        assertEquals(
                0,
                new AgentOverviewStore(dataSource)
                        .page(carolUserId, true, true, null, 1, 10)
                        .total());
    }

    @Test
    void invalidCapabilitiesKeepTheDraftAndDoNotExposeAPartialAgent() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "audit",
                        "Invalid",
                        "",
                        "Answer briefly",
                        "[\"missing-tool\"]",
                        "[]",
                        "[]",
                        null,
                        null);
        var account = accounts.login("carol", "carol-password").orElseThrow().account();
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.submitActivation(account, draft.draftId(), 1, "invalid"));
        assertEquals("draft", drafts.activation(draft.draftId()).state());
        assertTrue(agents.list().isEmpty());
        assertEquals(1, drafts.listVisible(carolUserId, false).size());
    }

    @Test
    void checkDraftReturnsErrorsWhenNameOrBehaviorIsMissing() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "用途",
                        "",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        mvc.perform(
                        post("/api/advisor/agent-creation-drafts/" + draft.draftId() + "/checks")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("revision", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues", hasSize(2)))
                .andExpect(
                        jsonPath("$.issues[?(@.code == 'missing_name')].severity").value("error"))
                .andExpect(
                        jsonPath("$.issues[?(@.code == 'missing_behavior')].severity")
                                .value("error"));
    }

    @Test
    void activationBlockedWith422WhenDraftHasErrors() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "用途",
                        "有名字",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        // Behavior is empty -> validation error blocks activation with 422
        mvc.perform(
                        post("/api/advisor/agent-creation-drafts/"
                                        + draft.draftId()
                                        + "/activations")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("revision", 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.issues").isNotEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"用途", "", "   "})
    void activationSucceedsAndPreallocatedAgentIsMaterialized(String purpose) throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        purpose,
                        "测试正式助手",
                        "简介",
                        "工作要求：严格按规范回答",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        String postRes =
                mvc.perform(
                                post("/api/advisor/agent-creation-drafts/"
                                                + draft.draftId()
                                                + "/activations")
                                        .header("Authorization", carolBearer)
                                        .header("Idempotency-Key", "act-key-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(Map.of("revision", 1))))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.operationId").isNotEmpty())
                        .andExpect(jsonPath("$.state").value("succeeded"))
                        .andExpect(jsonPath("$.preallocatedAgentId").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String officialAgentId = json.readTree(postRes).get("preallocatedAgentId").asText();

        // Check official agent is created in AgentStore
        var officialAgent = agents.find(officialAgentId).orElseThrow();
        assertEquals("测试正式助手", officialAgent.name());
        assertEquals("简介", officialAgent.description());
        assertEquals(carolUserId, officialAgent.ownerUserId());

        // Check runtime registry contains the agent
        assertTrue(registry.getAgent(officialAgentId).isPresent());

        // Idempotent retry with same key returns identical agentId
        mvc.perform(
                        post("/api/advisor/agent-creation-drafts/"
                                        + draft.draftId()
                                        + "/activations")
                                .header("Authorization", carolBearer)
                                .header("Idempotency-Key", "act-key-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("revision", 1))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.preallocatedAgentId").value(officialAgentId));
    }

    @Test
    void pendingActivationIsBlockedByRuntimeGates() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "用途",
                        "待转正",
                        "",
                        "规范",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        String preallocatedId = "agent-pending-" + UUID.randomUUID();
        // Insert operation with state 'running'
        var op = operations.create(draft.draftId(), "activation", "key-gate", "hash-gate", 1);
        operations.updateState(
                op.operationId(), "running", "staging", null, preallocatedId, null, null);

        // Preallocated agent must be blocked by isActivationPending
        assertTrue(operations.isActivationPending(preallocatedId));

        // When operation succeeds, gate is released
        operations.updateState(
                op.operationId(), "succeeded", "completed", null, preallocatedId, null, null);
        assertFalse(operations.isActivationPending(preallocatedId));
    }

    @Test
    void previewSessionCreationAndRunIsIsolatedFromOfficialSession() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "测试用途",
                        "预览助手",
                        "预览简介",
                        "工作要求：快速应答",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        // 1. Create preview session
        String createRes =
                mvc.perform(
                                post("/api/advisor/agent-creation-drafts/"
                                                + draft.draftId()
                                                + "/previews")
                                        .header("Authorization", carolBearer)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(Map.of("revision", 1))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.previewId").isNotEmpty())
                        .andExpect(jsonPath("$.draftRevision").value(1))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String previewId = json.readTree(createRes).get("previewId").asText();

        // 2. Query preview session
        mvc.perform(
                        get("/api/advisor/agent-creation-drafts/"
                                        + draft.draftId()
                                        + "/previews/"
                                        + previewId)
                                .header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewId").value(previewId));

        // Dave cannot view Carol's preview (404/403)
        mvc.perform(
                        get("/api/advisor/agent-creation-drafts/"
                                        + draft.draftId()
                                        + "/previews/"
                                        + previewId)
                                .header("Authorization", daveBearer))
                .andExpect(status().isNotFound());

        // 3. Run a turn in preview session
        mvc.perform(
                        post("/api/advisor/agent-creation-drafts/"
                                        + draft.draftId()
                                        + "/previews/"
                                        + previewId
                                        + "/runs")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("input", "你好"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").isNotEmpty());

        // 4. Verify preview history is recorded in previewStore
        var preview = previewStore.findById(previewId).orElseThrow();
        assertTrue(preview.historyJson().contains("你好"));

        // 5. Delete preview session
        mvc.perform(
                        delete(
                                        "/api/advisor/agent-creation-drafts/"
                                                + draft.draftId()
                                                + "/previews/"
                                                + previewId)
                                .header("Authorization", carolBearer))
                .andExpect(status().isNoContent());

        assertTrue(previewStore.findById(previewId).isEmpty());
        // Draft is intact
        assertTrue(drafts.findById(draft.draftId()).isPresent());
    }
}
