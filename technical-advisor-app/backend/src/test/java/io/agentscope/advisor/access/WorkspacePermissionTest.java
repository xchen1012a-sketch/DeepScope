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
package io.agentscope.advisor.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentscope.advisor.auth.AccountStore;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
class WorkspacePermissionTest {
    @TempDir static Path root;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("advisor.data-dir", root::toString);
        registry.add("agentscope.openai.api-key", () -> "test-only-key");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:workspace-permissions;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    MockMvc mvc;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired io.agentscope.advisor.auth.ApiAuthenticationFilter authentication;

    @org.junit.jupiter.api.BeforeEach
    void setupMvc() {
        mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                                context)
                        .addFilters(authentication)
                        .build();
    }

    @Autowired AccountStore accounts;
    @Autowired AccessCatalog access;
    @Autowired io.agentscope.advisor.agent.AgentStore agents;
    @Autowired io.agentscope.advisor.knowledge.KnowledgeCatalog libraries;
    @Autowired io.agentscope.advisor.tool.ToolCatalog tools;

    private String accountWith(String permission) throws Exception {
        String name = "audit-" + UUID.randomUUID();
        accounts.createUser(name, "test-only-password");
        var login = accounts.login(name, "test-only-password").orElseThrow();
        var role = access.createRole(name, name, 1, "");
        access.replacePermissions(role.roleId(), List.of(permission));
        access.replaceGrants(login.account().userId(), List.of(role.roleId()));
        return "Bearer " + login.token();
    }

    @Test
    void administratorIdentityCannotOverrideAnExplicitlyRestrictedRole() throws Exception {
        String name = "restricted-admin-" + UUID.randomUUID();
        accounts.bootstrap(name, "test-only-password");
        var login = accounts.login(name, "test-only-password").orElseThrow();
        var role = access.createRole(name, name, 1, "");
        access.replacePermissions(role.roleId(), List.of(AccessPoints.CHAT));
        access.replaceGrants(login.account().userId(), List.of(role.roleId()));
        org.junit.jupiter.api.Assertions.assertTrue(login.account().isAdmin());
        mvc.perform(
                        post("/api/advisor/agents")
                                .header("Authorization", "Bearer " + login.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"blocked\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void readonlyOwnerCannotWriteAndSelectionCannotLeakAForeignOwnersTools() throws Exception {
        String viewer = accountWith(AccessPoints.KNOWLEDGE_VIEW);
        var viewerAccount = accounts.authenticate(viewer.substring(7)).orElseThrow();
        var library = libraries.create(viewerAccount.userId(), "Read only", "");
        mvc.perform(
                        get("/api/advisor/knowledge/" + library.libraryId())
                                .header("Authorization", viewer))
                .andExpect(status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.canEdit")
                                .value(false));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                        "/api/advisor/knowledge/" + library.libraryId())
                                .header("Authorization", viewer))
                .andExpect(status().isForbidden());
        String manager = accountWith(AccessPoints.AGENT_MANAGE);
        var user = accounts.authenticate(manager.substring(7)).orElseThrow();
        var owned = agents.create("Own", "", user.userId());
        var foreign = agents.create("Foreign", "", viewerAccount.userId());
        tools.importTools(
                user.userId(),
                List.of(
                        new io.agentscope.advisor.tool.ToolImport.ImportedTool(
                                "mine", "", "{}", "http", "GET", "http://test.invalid")),
                "ask");
        tools.importTools(
                viewerAccount.userId(),
                List.of(
                        new io.agentscope.advisor.tool.ToolImport.ImportedTool(
                                "foreign", "", "{}", "http", "GET", "http://test.invalid")),
                "ask");
        mvc.perform(
                        get("/api/advisor/tools")
                                .param("purpose", "selection")
                                .param("agentId", owned.agentId())
                                .header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.length()")
                                .value(1))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$[0].name")
                                .value("mine"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$[0].canEdit")
                                .value(false));
        mvc.perform(
                        get("/api/advisor/tools")
                                .param("purpose", "selection")
                                .param("agentId", foreign.agentId())
                                .header("Authorization", viewer))
                .andExpect(status().isForbidden());
    }

    @Test
    void chatOnlyCannotWriteWorkspaceResourcesButCanListAvailableAgents() throws Exception {
        String token = accountWith(AccessPoints.CHAT);
        for (String route :
                List.of("skills", "tools/import", "knowledge", "agents", "agent-creation-drafts")) {
            mvc.perform(
                            post("/api/advisor/" + route)
                                    .header("Authorization", token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"name\":\"audit\",\"description\":\"audit\",\"content\":\"audit\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/advisor/agents").header("Authorization", token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/advisor/agents/available").header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void agentManagerCanReadSelectionWithoutResourceManagement() throws Exception {
        String token = accountWith(AccessPoints.AGENT_MANAGE);
        for (String route : List.of("skills", "tools", "mcp-servers", "knowledge")) {
            mvc.perform(get("/api/advisor/" + route).header("Authorization", token))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/api/advisor/" + route)
                                    .param("purpose", "selection")
                                    .header("Authorization", token))
                    .andExpect(status().isOk());
        }
        mvc.perform(
                        get("/api/advisor/tools")
                                .param("purpose", "selection")
                                .param("agentId", "foreign")
                                .header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void granularAgentAndKnowledgeEditorsCanEditOwnedResourcesWithoutDeleteRights()
            throws Exception {
        String token = accountWith(AccessPoints.AGENT_VIEW);
        var account = accounts.authenticate(token.substring(7)).orElseThrow();
        access.replacePermissions(
                access.roleIds(account.userId()).get(0),
                List.of("agent.view", "agent.edit", "knowledge.view", "knowledge.edit"));
        var agent = agents.create("Owned", "", account.userId());
        var library = libraries.create(account.userId(), "Owned library", "");
        mvc.perform(get("/api/advisor/agents/" + agent.agentId()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.canEdit")
                                .value(true));
        mvc.perform(
                        get("/api/advisor/knowledge/" + library.libraryId())
                                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.canEdit")
                                .value(true));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                        "/api/advisor/agents/" + agent.agentId())
                                .header("Authorization", token))
                .andExpect(status().isForbidden());
    }
}
