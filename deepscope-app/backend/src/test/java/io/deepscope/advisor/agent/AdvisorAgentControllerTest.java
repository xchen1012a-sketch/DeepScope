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

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.advisor.session.ReportActions;
import io.deepscope.advisor.session.SessionStore;
import io.deepscope.advisor.session.TimelineStore;
import io.deepscope.core.agui.registry.AguiAgentRegistry;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.Model;
import io.deepscope.core.model.ToolSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

/**
 * Agent management API: per-owner visibility, edit rights, live registration, and session
 * routing (lock mode returns the bound agent, free mode is a model choice with builtin fallback).
 */
class AdvisorAgentControllerTest {

    /** Model stub with a switchable reply, so route tests can steer the free-mode choice. */
    private static final class StubModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Supplier<Flux<ChatResponse>> replies = Flux::empty;
        private List<Msg> lastMessages = List.of();

        void repliesWith(String text) {
            replies =
                    () ->
                            Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(TextBlock.builder().text(text).build()))
                                            .build());
        }

        void fails() {
            replies = () -> Flux.error(new IllegalStateException("endpoint down"));
        }

        int calls() {
            return calls.get();
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            lastMessages = List.copyOf(messages);
            return replies.get();
        }

        @Override
        public String getModelName() {
            return "stub";
        }
    }

    @TempDir Path dataDir;

    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void waitForNativeMemoryWritesBeforeDeletingTheWorkspace() {
        assertTrue(
                io.deepscope.harness.agent.memory.MemoryBackgroundTasks.awaitQuiescence(
                        5, java.util.concurrent.TimeUnit.SECONDS),
                "native memory writes must finish before the temporary workspace is deleted");
    }

    private AccountStore accounts;
    private AgentStore agents;
    private AguiAgentRegistry registry;
    private AgentProvisioner provisioner;
    private io.deepscope.advisor.skill.SkillSync skillSync;
    private SessionStore sessions;
    private TimelineStore timeline;
    private io.deepscope.advisor.model.UsageGuard usageGuard;
    private StubModel model;
    private io.deepscope.spring.boot.agui.mvc.AguiMvcController runtime;
    private MockMvc mvc;
    private String adminBearer;
    private String carolBearer;
    private String daveBearer;
    private String carolUserId;
    private String rootAgentId;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:advisor-agents-"
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
        sessions = new SessionStore(dataSource);
        timeline = new TimelineStore(dataSource, sessions, json);
        usageGuard =
                new io.deepscope.advisor.model.UsageGuard(
                        new io.deepscope.advisor.model.ModelCatalog(dataSource), dataSource);
        registry = new AguiAgentRegistry();
        model = new StubModel();
        var catalog = new io.deepscope.advisor.tool.ToolCatalog(dataSource, json);
        var skillCatalog =
                new io.deepscope.advisor.skill.SkillCatalog(
                        dataSource, dataDir.resolve("skill-catalog"));
        skillSync = new io.deepscope.advisor.skill.SkillSync(skillCatalog);
        provisioner =
                new AgentProvisioner(
                        agents,
                        model,
                        new ReportActions(timeline, sessions),
                        registry,
                        catalog,
                        new io.deepscope.advisor.tool.McpToolSource() {
                            @Override
                            public java.util.List<
                                            io.deepscope.advisor.tool.ToolCatalog.DiscoveredTool>
                                    list(
                                            io.deepscope.advisor.tool.McpEndpointParser.McpEndpoint
                                                    endpoint) {
                                throw new IllegalStateException("this test does not open MCP");
                            }

                            @Override
                            public io.deepscope.core.tool.mcp.McpClientWrapper open(
                                    io.deepscope.advisor.tool.McpEndpointParser.McpEndpoint
                                            endpoint) {
                                throw new IllegalStateException("this test does not open MCP");
                            }
                        },
                        json,
                        skillSync,
                        new io.deepscope.advisor.knowledge.KnowledgeSearch(
                                new io.deepscope.advisor.knowledge.KnowledgeCatalog(dataSource),
                                java.util.Optional::empty,
                                new io.deepscope.advisor.knowledge.MemoryChunkIndex()),
                        timeline);
        provisioner.run(new DefaultApplicationArguments());
        runtime =
                org.mockito.Mockito.mock(io.deepscope.spring.boot.agui.mvc.AguiMvcController.class);
        org.mockito.Mockito.when(
                        runtime.withIdleThread(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        invocation -> {
                            invocation.<Runnable>getArgument(1).run();
                            return true;
                        });
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AdvisorAgentController(
                                        agents,
                                        provisioner,
                                        sessions,
                                        timeline,
                                        model,
                                        json,
                                        new io.deepscope.advisor.access.AccessGuard(
                                                new io.deepscope.advisor.access.AccessCatalog(
                                                        dataSource)),
                                        runtime))
                        .addFilters(new ApiAuthenticationFilter(accounts, "/v1/admin"))
                        .build();
        adminBearer = "Bearer " + accounts.login("root", "root-password").orElseThrow().token();
        carolBearer = "Bearer " + accounts.login("carol", "carol-password").orElseThrow().token();
        daveBearer = "Bearer " + accounts.login("dave", "dave-password").orElseThrow().token();
        carolUserId = accounts.login("carol", "carol-password").orElseThrow().account().userId();
        rootAgentId = createPersonalAgent(adminBearer);
    }

    @Test
    void longChatsWithinTokenBudgetDoNotCompactOnlyBecauseOfMessageCount() {
        var harness =
                (io.deepscope.harness.agent.HarnessAgent)
                        registry.getAgent(rootAgentId).orElseThrow();
        List<Msg> history =
                java.util.stream.IntStream.range(0, 51)
                        .mapToObj(
                                index ->
                                        Msg.builder()
                                                .role(io.deepscope.core.message.MsgRole.USER)
                                                .textContent("context detail ".repeat(250) + index)
                                                .build())
                        .toList();
        model.repliesWith("summary");
        int before = model.calls();
        var candidate =
                harness.getCompactionHook()
                        .prepareCandidate(
                                harness,
                                io.deepscope.core.agent.RuntimeContext.empty(),
                                new io.deepscope.core.middleware.ModelCallInput(
                                        history, List.of(), null, model),
                                1_000_000,
                                history)
                        .block();
        assertEquals(before, model.calls());
        assertEquals(history, candidate.history());
    }

    @Test
    void listShowsOwnPersonalAgentsOnly() throws Exception {
        createPersonalAgent(carolBearer);

        mvc.perform(get("/api/advisor/agents").header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].origin").value("personal"))
                .andExpect(jsonPath("$[0].ownerUserId").value(carolUserId));

        mvc.perform(get("/api/advisor/agents").header("Authorization", daveBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/advisor/agents").header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void createdAgentIsImmediatelyRegistered() throws Exception {
        String agentId = createPersonalAgent(carolBearer);

        mvc.perform(get("/api/advisor/agents/" + agentId).header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId))
                .andExpect(jsonPath("$.name").value("尽调小助手"))
                .andExpect(jsonPath("$.origin").value("personal"));

        assertTrue(
                registry.hasAgent(agentId),
                "a freshly created agent must be chatable without a restart");
    }

    @Test
    void detailHidesForeignPersonalAgents() throws Exception {
        String agentId = createPersonalAgent(carolBearer);

        mvc.perform(get("/api/advisor/agents/" + agentId).header("Authorization", daveBearer))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/advisor/agents/" + rootAgentId).header("Authorization", daveBearer))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/advisor/agents/missing").header("Authorization", carolBearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateEnforcesEditRights() throws Exception {
        String agentId = createPersonalAgent(carolBearer);

        mvc.perform(
                        patch("/api/advisor/agents/" + agentId)
                                .header("Authorization", daveBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"抢注\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(
                        patch("/api/advisor/agents/" + agentId)
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"新名字\",\"description\":\"新描述\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新名字"))
                .andExpect(jsonPath("$.description").value("新描述"));

        mvc.perform(
                        patch("/api/advisor/agents/" + rootAgentId)
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"篡改\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        patch("/api/advisor/agents/" + rootAgentId)
                                .header("Authorization", adminBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"description\":\"管理员修订\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("尽调小助手"))
                .andExpect(jsonPath("$.description").value("管理员修订"));

        mvc.perform(
                        patch("/api/advisor/agents/missing")
                                .header("Authorization", adminBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRejectsForeignPersonalAgents() throws Exception {
        String agentId = createPersonalAgent(carolBearer);

        mvc.perform(delete("/api/advisor/agents/" + agentId).header("Authorization", daveBearer))
                .andExpect(status().isNotFound());
        mvc.perform(
                        delete("/api/advisor/agents/" + rootAgentId)
                                .header("Authorization", carolBearer))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/advisor/agents/" + agentId).header("Authorization", adminBearer))
                .andExpect(status().isNoContent());

        assertTrue(agents.find(agentId).isEmpty(), "row should be gone");
        assertFalse(registry.hasAgent(agentId), "deleted agent must stop serving immediately");
        mvc.perform(delete("/api/advisor/agents/" + agentId).header("Authorization", adminBearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicAgentIsVisibleAndUsableByOthersButNotEditable() throws Exception {
        byte[] body =
                mvc.perform(
                                post("/api/advisor/agents")
                                        .header("Authorization", carolBearer)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"name\":\"公共助手\",\"description\":\"全员共用\",\"isPublic\":true}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.isPublic").value(true))
                        .andExpect(jsonPath("$.canEdit").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        String agentId =
                json.readTree(new String(body, StandardCharsets.UTF_8)).get("agentId").asText();

        // Dave can see it in list
        mvc.perform(get("/api/advisor/agents").header("Authorization", daveBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentId").value(agentId))
                .andExpect(jsonPath("$[0].isPublic").value(true))
                .andExpect(jsonPath("$[0].canEdit").value(false));

        // Dave can see it in available agents
        mvc.perform(get("/api/advisor/agents/available").header("Authorization", daveBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentId").value(agentId))
                .andExpect(jsonPath("$[0].isPublic").value(true));

        // Dave can see it in details, but canEdit is false and isOwner is false
        mvc.perform(get("/api/advisor/agents/" + agentId).header("Authorization", daveBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublic").value(true))
                .andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.isOwner").value(false));

        // Dave cannot edit or delete it
        mvc.perform(
                        patch("/api/advisor/agents/" + agentId)
                                .header("Authorization", daveBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"篡改公共\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/advisor/agents/" + agentId).header("Authorization", daveBearer))
                .andExpect(status().isForbidden());
    }

    @Test
    void lockedSessionRoutesToItsBoundAgentWithoutAModelCall() throws Exception {
        String agentId = createPersonalAgent(carolBearer);
        String sessionId = sessions.create(carolUserId, null, agentId).sessionId();

        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/route")
                                .header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId));

        assertTrue(model.calls() == 0, "lock mode must not call the model");
    }

    @Test
    void freeModeRoutesEachTurnAndKeepsTheSessionFree() throws Exception {
        String agentId = createPersonalAgent(carolBearer);
        String sessionId = sessions.create(carolUserId, null).sessionId();
        model.repliesWith(agentId);

        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/route")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"帮我做一次非 Java 仓库的尽调\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId));

        assertEquals(0, model.calls(), "one visible candidate needs no routing model call");
        assertEquals(
                null,
                sessions.find(sessionId).orElseThrow().agentId(),
                "free mode must remain free after routing");
        String secondAgent = createPersonalAgent(carolBearer);
        model.repliesWith(secondAgent);

        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/route")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"继续分析\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(secondAgent))
                .andExpect(jsonPath("$.reason").isNotEmpty());
        assertEquals(1, model.calls(), "multiple candidates still require per-turn selection");
        assertEquals(2, timeline.listEntries(sessionId).size());
        assertEquals("agent_route", timeline.listEntries(sessionId).get(0).kind());
    }

    @Test
    void freeModeFallsBackToTheFirstCandidate() throws Exception {
        String agentId = createPersonalAgent(carolBearer);
        String sessionId = sessions.create(carolUserId, null).sessionId();

        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/route")
                                .header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId));
        assertTrue(model.calls() == 0, "without a question there is nothing to choose from");

        String secondSession = sessions.create(carolUserId, null).sessionId();
        createPersonalAgent(carolBearer);
        model.fails();
        mvc.perform(
                        post("/api/advisor/sessions/" + secondSession + "/route")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"帮我看下这个方案\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId));

        assertEquals(
                null,
                sessions.find(secondSession).orElseThrow().agentId(),
                "fallback must not lock a free session");
        assertEquals(
                1, model.calls(), "multiple-candidate routing failure must still use the fallback");
    }

    @Test
    void freeModeWithoutVisibleAgentsUsesBasicChatAndRecoversWhenAnAgentAppears() throws Exception {
        String sid = sessions.create(carolUserId, null).sessionId();
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("basic"))
                .andExpect(jsonPath("$.agentId").value("basic-chat"))
                .andExpect(jsonPath("$.reasonCode").value("no_visible_agents"));
        assertEquals(0, model.calls());
        assertEquals(null, sessions.find(sid).orElseThrow().agentId());
        assertEquals(
                "basic",
                json.readTree(timeline.listEntries(sid).get(0).payload()).get("mode").asText());
        String id = createPersonalAgent(carolBearer);
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("agent"))
                .andExpect(jsonPath("$.agentId").value(id));
    }

    @Test
    void anUnavailableLockedAgentRequiresConsentBeforeBasicChat() throws Exception {
        String sid = sessions.create(carolUserId, null, "deleted-agent").sessionId();
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("unavailable"));
        assertEquals("deleted-agent", sessions.find(sid).orElseThrow().agentId());
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"allowBasic\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("basic"));
        assertEquals(null, sessions.find(sid).orElseThrow().agentId());
    }

    @Test
    void agentSwitchingCannotMutateTheBindingWhileTheRuntimeOwnsTheThread() throws Exception {
        String first = createPersonalAgent(carolBearer);
        String next = createPersonalAgent(carolBearer);
        String sid = sessions.create(carolUserId, null, first).sessionId();
        org.mockito.Mockito.when(
                        runtime.withIdleThread(
                                org.mockito.ArgumentMatchers.eq(sid),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("selectedAgentId", next))))
                .andExpect(status().isConflict());
        assertEquals(first, sessions.find(sid).orElseThrow().agentId());
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"selectedAgentId\":null}"))
                .andExpect(status().isConflict());
        assertEquals(first, sessions.find(sid).orElseThrow().agentId());
    }

    @Test
    void availableAgentPickerSearchesAndPagesOnlyVisibleAgents() throws Exception {
        for (int i = 1; i <= 4; i++) agents.create("Wheel " + i, "Server search", carolUserId);
        var hidden = agents.create("Wheel hidden", "", "other-owner");
        mvc.perform(
                        get("/api/advisor/agents/available")
                                .header("Authorization", carolBearer)
                                .param("page", "1")
                                .param("size", "3")
                                .param("q", "Wheel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.total").value(4));
        mvc.perform(
                        get("/api/advisor/agents/available")
                                .header("Authorization", carolBearer)
                                .param("page", "2")
                                .param("size", "3")
                                .param("q", "Wheel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(
                        get("/api/advisor/agents/available")
                                .header("Authorization", carolBearer)
                                .param("page", "1")
                                .param("size", "3")
                                .param("q", "Wheel 4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(
                        get("/api/advisor/agents/available")
                                .header("Authorization", carolBearer)
                                .param("agentId", hidden.agentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anExplicitChoiceCanSwitchAHealthyLockedSessionAndFreeMode() throws Exception {
        String first = createPersonalAgent(carolBearer);
        String second = createPersonalAgent(carolBearer);
        String sid = sessions.create(carolUserId, null, first).sessionId();
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(Map.of("selectedAgentId", second))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(second));
        assertEquals(second, sessions.find(sid).orElseThrow().agentId());
        assertEquals(0, model.calls());
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"selectedAgentId\":null}"))
                .andExpect(status().isOk());
        assertEquals(null, sessions.find(sid).orElseThrow().agentId());
    }

    @Test
    void anExplicitChoiceLocksAFreeSessionAndInvalidChoicesCannotReplaceIt() throws Exception {
        String chosen = createPersonalAgent(carolBearer);
        String sid = sessions.create(carolUserId, null).sessionId();
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(Map.of("selectedAgentId", chosen))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(chosen));
        assertEquals(chosen, sessions.find(sid).orElseThrow().agentId());
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("selectedAgentId", rootAgentId))))
                .andExpect(status().isNotFound());
        assertEquals(chosen, sessions.find(sid).orElseThrow().agentId());
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"selectedAgentId\":42}"))
                .andExpect(status().isBadRequest());
        assertEquals(chosen, sessions.find(sid).orElseThrow().agentId());
    }

    @Test
    void visitorCanReplaceAnUnavailableLockedAgentWithAVisibleOne() throws Exception {
        String sid = sessions.create(carolUserId, null, "deleted").sessionId();
        String replacement = createPersonalAgent(carolBearer);
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("selectedAgentId", rootAgentId))))
                .andExpect(status().isNotFound());
        assertEquals("deleted", sessions.find(sid).orElseThrow().agentId());
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("selectedAgentId", replacement))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("agent"))
                .andExpect(jsonPath("$.agentId").value(replacement));
        assertEquals(replacement, sessions.find(sid).orElseThrow().agentId());
    }

    @Test
    void basicChatHasNoToolsCarriesEarlierTextAndIsolatesUsers() throws Exception {
        new io.deepscope.advisor.AdvisorConfiguration()
                .basicChatRuntime(
                        new io.deepscope.advisor.AdvisorProperties(dataDir),
                        model,
                        sessions,
                        timeline,
                        json,
                        registry);
        var basic =
                (io.deepscope.harness.agent.HarnessAgent)
                        registry.getAgent("basic-chat").orElseThrow();
        assertTrue(basic.getToolkit().getToolSchemas().isEmpty());
        String sid = sessions.create(carolUserId, null).sessionId();
        timeline.appendVisitorMessage(sid, "prior-question");
        timeline.append(sid, "advisor_message", Map.of("text", "prior-answer"));
        timeline.appendVisitorMessage(sid, "current-question");
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", carolBearer))
                .andExpect(status().isOk());
        model.repliesWith("current-answer");
        var context =
                io.deepscope.core.agent.RuntimeContext.builder()
                        .userId(carolUserId)
                        .sessionId(sid)
                        .build();
        basic.streamEvents("current-question", context).collectList().block(Duration.ofSeconds(5));
        assertTrue(
                model.lastMessages.stream()
                        .map(Msg::getTextContent)
                        .collect(java.util.stream.Collectors.joining("\n"))
                        .contains("prior-answer"));
        basic.streamEvents("next-question", context).collectList().block(Duration.ofSeconds(5));
        assertTrue(
                model.lastMessages.stream()
                        .map(Msg::getTextContent)
                        .collect(java.util.stream.Collectors.joining("\n"))
                        .contains("current-answer"));
        var other =
                io.deepscope.core.agent.RuntimeContext.builder()
                        .userId("another-user")
                        .sessionId("other-session")
                        .build();
        basic.streamEvents("other-question", other).collectList().block(Duration.ofSeconds(5));
        assertFalse(
                model.lastMessages.stream()
                        .map(Msg::getTextContent)
                        .collect(java.util.stream.Collectors.joining("\n"))
                        .contains("prior-answer"));
        assertFalse(
                model.lastMessages.stream()
                        .map(Msg::getTextContent)
                        .collect(java.util.stream.Collectors.joining("\n"))
                        .contains("current-answer"));
    }

    @Test
    void basicRuntimeStillRejectsForeignSessionsAndLockedSessions() throws Exception {
        var resolver =
                new io.deepscope.advisor.AdvisorConfiguration()
                        .advisorRuntimeContextResolver(agents, sessions, skillSync, usageGuard);
        String sid = sessions.create(carolUserId, null).sessionId();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(
                AccountStore.Account.class.getName(),
                accounts.login("carol", "carol-password").orElseThrow().account());
        var input =
                new io.deepscope.core.agui.model.RunAgentInput(
                        sid, "run", null, null, null, null, null);
        var run =
                io.deepscope.core.agui.runtime.AguiRuntimeContextRequest
                        .<jakarta.servlet.http.HttpServletRequest>builder()
                        .input(input)
                        .pathAgentId("basic-chat")
                        .nativeRequest(request)
                        .build();
        assertEquals(carolUserId, resolver.resolve(run).getUserId());
        sessions.bindAgent(sid, rootAgentId);
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> resolver.resolve(run));
        sessions.bindAgent(sid, null);
        request.setAttribute(
                AccountStore.Account.class.getName(),
                accounts.login("dave", "dave-password").orElseThrow().account());
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> resolver.resolve(run));
    }

    @Test
    void administratorCannotRunAnotherOwnersAgentOrSelectItForChat() throws Exception {
        String foreignId = createPersonalAgent(carolBearer);
        var admin = accounts.login("root", "root-password").orElseThrow().account();
        String sid = sessions.create(admin.userId(), null).sessionId();
        var resolver =
                new io.deepscope.advisor.AdvisorConfiguration()
                        .advisorRuntimeContextResolver(agents, sessions, skillSync, usageGuard);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(AccountStore.Account.class.getName(), admin);
        var run =
                io.deepscope.core.agui.runtime.AguiRuntimeContextRequest
                        .<jakarta.servlet.http.HttpServletRequest>builder()
                        .input(
                                new io.deepscope.core.agui.model.RunAgentInput(
                                        sid, "run", null, null, null, null, null))
                        .pathAgentId(foreignId)
                        .nativeRequest(request)
                        .build();
        var error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> resolver.resolve(run));
        assertEquals(404, error.getStatusCode().value());
        mvc.perform(
                        get("/api/advisor/agents/available")
                                .param("agentId", foreignId)
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
        mvc.perform(
                        post("/api/advisor/sessions/" + sid + "/route")
                                .header("Authorization", adminBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("selectedAgentId", foreignId))))
                .andExpect(status().isNotFound());
    }

    @Test
    void routeHidesSessionsOfOtherVisitors() throws Exception {
        String sessionId = sessions.create(carolUserId, null).sessionId();

        mvc.perform(
                        post("/api/advisor/sessions/" + sessionId + "/route")
                                .header("Authorization", daveBearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void detailIncludesPermissionsAndRealWorkspaceSections() throws Exception {
        String agentId = createPersonalAgent(carolBearer);
        mvc.perform(get("/api/advisor/agents/" + agentId).header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.sections.subagents.length()").value(0))
                .andExpect(jsonPath("$.sections.tools.length()").value(greaterThan(0)));
    }

    @Test
    void copyingClonesWorkspaceButNotSessionStateAndHidesForeignSources() throws Exception {
        String source = createPersonalAgent(carolBearer);
        Path root = Path.of(agents.find(source).orElseThrow().workspaceDir());
        Files.createDirectories(root.resolve("knowledge"));
        Files.writeString(root.resolve("knowledge/real.md"), "真实配置");
        var result =
                mvc.perform(
                                post("/api/advisor/agents")
                                        .header("Authorization", carolBearer)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "name",
                                                                "副本",
                                                                "copyFromAgentId",
                                                                source))))
                        .andExpect(status().isCreated())
                        .andReturn();
        String copy =
                json.readTree(result.getResponse().getContentAsString()).get("agentId").asText();
        Path copied = Path.of(agents.find(copy).orElseThrow().workspaceDir());
        assertEquals("真实配置", Files.readString(copied.resolve("knowledge/real.md")));
        Files.writeString(copied.resolve("knowledge/real.md"), "独立改造");
        assertEquals("真实配置", Files.readString(root.resolve("knowledge/real.md")));
        mvc.perform(
                        post("/api/advisor/agents")
                                .header("Authorization", daveBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("name", "越权复制", "copyFromAgentId", source))))
                .andExpect(status().isNotFound());
    }

    @Test
    void workspaceFilesEnforcePermissionsValidationAndPathIsolation() throws Exception {
        String id = createPersonalAgent(carolBearer);
        String path = "/api/advisor/agents/" + id + "/files";
        mvc.perform(
                        post(path)
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "path",
                                                        "knowledge/test.md",
                                                        "content",
                                                        "事实"))))
                .andExpect(status().isCreated());
        mvc.perform(
                        get(path)
                                .param("path", "knowledge/test.md")
                                .header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("事实"));
        mvc.perform(
                        post(path)
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("path", "../escape.md", "content", "x"))))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post(path)
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "path",
                                                        "subagents/invalid.md",
                                                        "content",
                                                        "无定义头"))))
                .andExpect(status().isBadRequest());
        Path outside = dataDir.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.md"), "private");
        Path root = Path.of(agents.find(id).orElseThrow().workspaceDir());
        Files.createSymbolicLink(root.resolve("link"), outside);
        mvc.perform(get(path).param("path", "link/secret.md").header("Authorization", carolBearer))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path).param("path", "AGENTS.md").header("Authorization", daveBearer))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/advisor/agents/" + rootAgentId + "/files")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("path", "test.md", "content", "x"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void proposalApprovalWritesEditedDraftAndSameNameResolutionsStayFifo() throws Exception {
        String id = createPersonalAgent(carolBearer);
        String sid = sessions.create(carolUserId, null, id).sessionId();
        Map<String, Object> proposal =
                Map.of("name", "survey", "content", "---\ndescription: original\n---\n调查事实");
        int first = timeline.append(sid, TimelineStore.PROPOSAL, proposal).seq();
        int second = timeline.append(sid, TimelineStore.PROPOSAL, proposal).seq();
        resolve(sid, second, carolBearer, Map.of("approved", false))
                .andExpect(status().isConflict());
        String edited = "---\ndescription: edited\n---\n只报告真实事实";
        var result =
                resolve(sid, first, carolBearer, Map.of("approved", true, "content", edited))
                        .andExpect(status().isOk())
                        .andReturn();
        String file = json.readTree(result.getResponse().getContentAsString()).get("file").asText();
        assertEquals(
                edited,
                Files.readString(
                        Path.of(agents.find(id).orElseThrow().workspaceDir()).resolve(file)));
        resolve(sid, first, carolBearer, Map.of("approved", true)).andExpect(status().isConflict());
        resolve(sid, second, carolBearer, Map.of("approved", false)).andExpect(status().isOk());
        assertEquals(TimelineStore.PROPOSAL_RESOLVED, timeline.listEntries(sid).get(2).kind());
        assertTrue(timeline.listEntries(sid).get(2).payload().contains("true"));
        assertTrue(timeline.listEntries(sid).get(3).payload().contains("false"));
        assertEquals(SessionStore.STATUS_IDLE, sessions.find(sid).orElseThrow().status());
    }

    @Test
    void proposalResolutionEnforcesOwnerAdminFreeModeAndDraftValidation() throws Exception {
        String agentId = createPersonalAgent(carolBearer);
        String sid = sessions.create(carolUserId, null, agentId).sessionId();
        int seq =
                timeline.append(
                                sid,
                                TimelineStore.PROPOSAL,
                                Map.of("name", "survey", "content", "invalid"))
                        .seq();
        resolve(sid, seq, daveBearer, Map.of("approved", true)).andExpect(status().isNotFound());
        resolve(sid, seq, carolBearer, Map.of("approved", true)).andExpect(status().isBadRequest());
        String adminId = accounts.login("root", "root-password").orElseThrow().account().userId();
        String adminSid = sessions.create(adminId, null, rootAgentId).sessionId();
        int invalid =
                timeline.append(
                                adminSid,
                                TimelineStore.PROPOSAL,
                                Map.of("name", "survey", "content", "invalid"))
                        .seq();
        resolve(adminSid, invalid, adminBearer, Map.of("approved", true))
                .andExpect(status().isBadRequest());
        String free = sessions.create(carolUserId, null).sessionId();
        int freeSeq =
                timeline.append(
                                free,
                                TimelineStore.PROPOSAL,
                                Map.of("name", "survey", "content", "invalid"))
                        .seq();
        resolve(free, freeSeq, carolBearer, Map.of("approved", true))
                .andExpect(status().isConflict());
    }

    @Test
    void workspaceCatalogExcludesRuntimeMemoryAndCopyNeverClonesIt() throws Exception {
        String id = createPersonalAgent(carolBearer);
        Path root = Path.of(agents.find(id).orElseThrow().workspaceDir());
        Files.createDirectories(root.resolve("memory"));
        Files.writeString(root.resolve("memory/.consolidation_state"), "runtime");
        Files.createDirectories(root.resolve(carolUserId + "/.deepscope-runtime"));
        Files.writeString(root.resolve(carolUserId + "/MEMORY.md"), "private user memory");
        mvc.perform(get("/api/advisor/agents").header("Authorization", carolBearer))
                .andExpect(status().isOk());
        mvc.perform(get("/api/advisor/agents/" + id + "/tree").header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()").value(1));
        mvc.perform(
                        get("/api/advisor/agents/" + id + "/files")
                                .param("path", carolUserId + "/MEMORY.md")
                                .header("Authorization", carolBearer))
                .andExpect(status().isBadRequest());
        var result =
                mvc.perform(
                                post("/api/advisor/agents")
                                        .header("Authorization", carolBearer)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "name",
                                                                "clean copy",
                                                                "copyFromAgentId",
                                                                id))))
                        .andExpect(status().isCreated())
                        .andReturn();
        String copy =
                json.readTree(result.getResponse().getContentAsString()).get("agentId").asText();
        Path copyRoot = Path.of(agents.find(copy).orElseThrow().workspaceDir());
        assertFalse(Files.exists(copyRoot.resolve("memory")));
        assertFalse(Files.exists(copyRoot.resolve(carolUserId)));
    }

    @Test
    void runtimeIdentityStaysStableWhenDisplayNameChanges() throws Exception {
        String id = createPersonalAgent(carolBearer);
        var context =
                io.deepscope.core.agent.RuntimeContext.builder()
                        .userId(carolUserId)
                        .sessionId("stable-history")
                        .build();
        model.repliesWith("已收到");
        ((io.deepscope.harness.agent.HarnessAgent) registry.getAgent(id).orElseThrow())
                .streamEvents("history-before-rename", context)
                .collectList()
                .block();
        mvc.perform(
                        patch("/api/advisor/agents/" + id)
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"另一个显示名\"}"))
                .andExpect(status().isOk());
        ((io.deepscope.harness.agent.HarnessAgent) registry.getAgent(id).orElseThrow())
                .streamEvents("history-after-rename", context)
                .collectList()
                .block();
        assertTrue(
                model.lastMessages.stream()
                        .flatMap(msg -> msg.getContent().stream())
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .anyMatch(text -> text.getText().contains("history-before-rename")),
                "renaming must preserve the native session history");
    }

    @Test
    void personalEditsSurviveProvisioningAgain() throws Exception {
        mvc.perform(
                        patch("/api/advisor/agents/" + rootAgentId)
                                .header("Authorization", adminBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"description\":\"已保存的管理配置\"}"))
                .andExpect(status().isOk());
        provisioner.run(new DefaultApplicationArguments());
        mvc.perform(get("/api/advisor/agents/" + rootAgentId).header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("已保存的管理配置"));
    }

    @Test
    void runtimeRejectsForeignAgentsForeignSessionsAndLockedAgentMismatch() throws Exception {
        String id = createPersonalAgent(carolBearer);
        String ownSession = sessions.create(carolUserId, null, id).sessionId();
        var resolver =
                new io.deepscope.advisor.AdvisorConfiguration()
                        .advisorRuntimeContextResolver(agents, sessions, skillSync, usageGuard);
        var dave = accounts.login("dave", "dave-password").orElseThrow().account();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(AccountStore.Account.class.getName(), dave);
        var input =
                new io.deepscope.core.agui.model.RunAgentInput(
                        ownSession, "run", null, null, null, null, null);
        var run =
                io.deepscope.core.agui.runtime.AguiRuntimeContextRequest
                        .<jakarta.servlet.http.HttpServletRequest>builder()
                        .input(input)
                        .pathAgentId(id)
                        .nativeRequest(request)
                        .build();
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> resolver.resolve(run));
        request.setAttribute(
                AccountStore.Account.class.getName(),
                accounts.login("carol", "carol-password").orElseThrow().account());
        var wrongAgent =
                io.deepscope.core.agui.runtime.AguiRuntimeContextRequest
                        .<jakarta.servlet.http.HttpServletRequest>builder()
                        .input(input)
                        .pathAgentId(rootAgentId)
                        .nativeRequest(request)
                        .build();
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> resolver.resolve(wrongAgent));
        assertEquals(carolUserId, resolver.resolve(run).getUserId());
    }

    @Test
    void disablingAnAgentPersistsAndBlocksNewRunsUntilReenabled() throws Exception {
        String id = createPersonalAgent(carolBearer);
        mvc.perform(
                        patch("/api/advisor/agents/" + id)
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/advisor/agents/" + id).header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(
                        get("/api/advisor/agents/available")
                                .param("agentId", id)
                                .header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
        assertTrue(registry.getAgent(id).isEmpty());
        mvc.perform(
                        put("/api/advisor/agents/" + id + "/files")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"path\":\"AGENTS.md\",\"content\":\"Updated while"
                                                + " disabled\"}"))
                .andExpect(status().isOk());
        assertTrue(registry.getAgent(id).isEmpty());
        assertTrue(agents.list().stream().noneMatch(agent -> id.equals(agent.agentId())));
        String sid = sessions.create(carolUserId, null, id).sessionId();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(
                AccountStore.Account.class.getName(),
                accounts.login("carol", "carol-password").orElseThrow().account());
        var run =
                io.deepscope.core.agui.runtime.AguiRuntimeContextRequest
                        .<jakarta.servlet.http.HttpServletRequest>builder()
                        .input(
                                new io.deepscope.core.agui.model.RunAgentInput(
                                        sid, "run", null, null, null, null, null))
                        .pathAgentId(id)
                        .nativeRequest(request)
                        .build();
        var resolver =
                new io.deepscope.advisor.AdvisorConfiguration()
                        .advisorRuntimeContextResolver(agents, sessions, skillSync, usageGuard);
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> resolver.resolve(run));
        mvc.perform(
                        patch("/api/advisor/agents/" + id)
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":\"false\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        patch("/api/advisor/agents/" + id)
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        assertTrue(registry.getAgent(id).isPresent());
        assertEquals(carolUserId, resolver.resolve(run).getUserId());
    }

    @Test
    void subagentDraftNeedsAnEditableAgentANameAndAModelReply() throws Exception {
        model.repliesWith("描述：审查合同风险与无依据承诺\n正文：\n# 角色定位\n你是合同审查子智能体。");
        mvc.perform(
                        post("/api/advisor/agents/" + rootAgentId + "/subagents/draft")
                                .header("Authorization", adminBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"合同审查\",\"purpose\":\"审查采购合同风险\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.content")
                                .value(org.hamcrest.Matchers.containsString("description:")))
                .andExpect(
                        jsonPath("$.content")
                                .value(org.hamcrest.Matchers.containsString("合同审查子智能体")));

        mvc.perform(
                        post("/api/advisor/agents/" + rootAgentId + "/subagents/draft")
                                .header("Authorization", adminBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("missing_name"));

        model.fails();
        mvc.perform(
                        post("/api/advisor/agents/" + rootAgentId + "/subagents/draft")
                                .header("Authorization", adminBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"合同审查\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("draft_failed"));

        mvc.perform(
                        post("/api/advisor/agents/" + rootAgentId + "/subagents/draft")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"合同审查\"}"))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions resolve(
            String sid, int seq, String bearer, Map<String, Object> body) throws Exception {
        return mvc.perform(
                post("/api/advisor/sessions/" + sid + "/proposals/" + seq + "/resolve")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
    }

    private String createPersonalAgent(String bearer) throws Exception {
        byte[] body =
                mvc.perform(
                                post("/api/advisor/agents")
                                        .header("Authorization", bearer)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"name\":\"尽调小助手\",\"description\":\"非 Java"
                                                        + " 项目尽调\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        return json.readTree(new String(body, StandardCharsets.UTF_8)).get("agentId").asText();
    }
}
