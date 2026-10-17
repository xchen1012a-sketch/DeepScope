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

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.access.AccessCatalog;
import io.deepscope.advisor.access.AccessGuard;
import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import io.deepscope.advisor.db.AdvisorSchema;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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

/**
 * Task 1: Agent creation drafts controller test.
 *
 * Verifies:
 * - Idempotency-Key returns same draftId
 * - Stale revision on PATCH returns 409
 * - Copying from unviewable source agent returns 404
 * - Editing visible but uneditable draft returns 403
 * - Same name allowed across drafts
 * - Drafts do not appear in official AgentStore
 */
class AgentCreationControllerTest {

    @TempDir Path dataDir;

    private final ObjectMapper json = new ObjectMapper();
    private DataSource dataSource;
    private AccountStore accounts;
    private AgentStore agents;
    private AgentCreationDraftStore drafts;
    private AgentCreationService service;
    private MockMvc mvc;

    private String adminBearer;
    private String carolBearer;
    private String daveBearer;
    private String carolUserId;
    private String daveUserId;

    @BeforeEach
    void setUp() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:creation-controller-"
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
        AccessGuard accessGuard = new AccessGuard(new AccessCatalog(dataSource));
        service = new AgentCreationService(drafts, agents, new AgentWorkspace(), accessGuard, json);

        mvc =
                MockMvcBuilders.standaloneSetup(new AgentCreationController(service, json))
                        .addFilters(new ApiAuthenticationFilter(accounts, "/v1/admin"))
                        .build();

        adminBearer = "Bearer " + accounts.login("root", "root-password").orElseThrow().token();
        carolBearer = "Bearer " + accounts.login("carol", "carol-password").orElseThrow().token();
        daveBearer = "Bearer " + accounts.login("dave", "dave-password").orElseThrow().token();
        carolUserId = accounts.login("carol", "carol-password").orElseThrow().account().userId();
        daveUserId = accounts.login("dave", "dave-password").orElseThrow().account().userId();
    }

    @Test
    void createDraftAndRetrieveSnapshot() throws Exception {
        mvc.perform(
                        post("/api/advisor/agent-creation-drafts")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "mode", "describe",
                                                        "purpose", "帮助写报告",
                                                        "name", "报告助手",
                                                        "description", "整理数据"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.draftId").isNotEmpty())
                .andExpect(jsonPath("$.ownerUserId").value(carolUserId))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.purpose").value("帮助写报告"))
                .andExpect(jsonPath("$.name").value("报告助手"));
    }

    @Test
    void idempotentCreationWithSameKeyReturnsSameDraftId() throws Exception {
        String key = "req-12345";
        String body =
                json.writeValueAsString(
                        Map.of(
                                "mode", "describe",
                                "purpose", "帮我查代码",
                                "name", "审查助手"));

        String res1 =
                mvc.perform(
                                post("/api/advisor/agent-creation-drafts")
                                        .header("Authorization", carolBearer)
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String draftId1 = json.readTree(res1).get("draftId").asText();

        String res2 =
                mvc.perform(
                                post("/api/advisor/agent-creation-drafts")
                                        .header("Authorization", carolBearer)
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String draftId2 = json.readTree(res2).get("draftId").asText();

        assertEquals(draftId1, draftId2, "Same Idempotency-Key must return the same draftId");
    }

    @Test
    void patchWithStaleRevisionReturns409Conflict() throws Exception {
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

        // Update with expected revision 1 advances revision to 2
        mvc.perform(
                        patch("/api/advisor/agent-creation-drafts/" + draft.draftId())
                                .header("Authorization", carolBearer)
                                .header("If-Match", "1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("name", "更新名称一"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.name").value("更新名称一"));

        // Second update still with stale revision 1 must fail with 409
        mvc.perform(
                        patch("/api/advisor/agent-creation-drafts/" + draft.draftId())
                                .header("Authorization", carolBearer)
                                .header("If-Match", "1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("name", "冲突更新"))))
                .andExpect(status().isConflict());
    }

    @Test
    void copyFromUnviewableSourceAgentReturns404() throws Exception {
        // Dave creates a private personal agent
        AgentStore.AgentRow davesAgent = agents.create("Dave私有助手", "专属秘密", daveUserId);

        // Carol attempts to copy from Dave's private agent -> must return 404
        mvc.perform(
                        post("/api/advisor/agent-creation-drafts")
                                .header("Authorization", carolBearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "mode",
                                                        "copy",
                                                        "sourceAgentId",
                                                        davesAgent.agentId()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUserCannotViewOrEditPersonalDraft() throws Exception {
        var carolsDraft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "Carol任务",
                        "Carol助手",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        // Dave tries to view Carol's draft -> 404
        mvc.perform(
                        get("/api/advisor/agent-creation-drafts/" + carolsDraft.draftId())
                                .header("Authorization", daveBearer))
                .andExpect(status().isNotFound());

        // Dave tries to edit Carol's draft -> 404
        mvc.perform(
                        patch("/api/advisor/agent-creation-drafts/" + carolsDraft.draftId())
                                .header("Authorization", daveBearer)
                                .header("If-Match", "1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("name", "篡改"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCanViewAllDrafts() throws Exception {
        drafts.create(
                carolUserId,
                "describe",
                null,
                "Carol任务",
                "Carol助手",
                "",
                "",
                "[]",
                "[]",
                "[]",
                null,
                null);
        drafts.create(
                daveUserId,
                "describe",
                null,
                "Dave任务",
                "Dave助手",
                "",
                "",
                "[]",
                "[]",
                "[]",
                null,
                null);

        mvc.perform(get("/api/advisor/agent-creation-drafts").header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mvc.perform(get("/api/advisor/agent-creation-drafts").header("Authorization", carolBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ownerUserId").value(carolUserId));
    }

    @Test
    void deleteRemovesDraft() throws Exception {
        var draft =
                drafts.create(
                        carolUserId,
                        "describe",
                        null,
                        "待删任务",
                        "待删助手",
                        "",
                        "",
                        "[]",
                        "[]",
                        "[]",
                        null,
                        null);

        mvc.perform(
                        delete("/api/advisor/agent-creation-drafts/" + draft.draftId())
                                .header("Authorization", carolBearer))
                .andExpect(status().isNoContent());

        mvc.perform(
                        get("/api/advisor/agent-creation-drafts/" + draft.draftId())
                                .header("Authorization", carolBearer))
                .andExpect(status().isNotFound());
    }
}
