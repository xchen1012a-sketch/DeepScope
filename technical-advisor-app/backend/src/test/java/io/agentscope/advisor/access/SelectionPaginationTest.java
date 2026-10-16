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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentscope.advisor.agent.AgentStore;
import io.agentscope.advisor.auth.AccountStore;
import io.agentscope.advisor.knowledge.KnowledgeCatalog;
import io.agentscope.advisor.skill.SkillCatalog;
import io.agentscope.advisor.tool.McpEndpointParser;
import io.agentscope.advisor.tool.ToolCatalog;
import io.agentscope.advisor.tool.ToolImport;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * Selectors page through the same routes as management lists, but stay scoped to the agent owner
 * and readable with {@code agent.manage} alone.
 */
@SpringBootTest
class SelectionPaginationTest {
    @TempDir static Path root;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("advisor.data-dir", root::toString);
        registry.add("agentscope.openai.api-key", () -> "test-only-key");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:selection-paging;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    MockMvc mvc;
    @Autowired WebApplicationContext context;
    @Autowired io.agentscope.advisor.auth.ApiAuthenticationFilter authentication;
    @Autowired AccountStore accounts;
    @Autowired AccessCatalog access;
    @Autowired AgentStore agents;
    @Autowired ToolCatalog tools;
    @Autowired SkillCatalog skills;
    @Autowired KnowledgeCatalog libraries;

    @BeforeEach
    void setupMvc() {
        mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                                context)
                        .addFilters(authentication)
                        .build();
    }

    private String accountWith(String permission) throws Exception {
        String name = "selection-" + UUID.randomUUID();
        accounts.createUser(name, "test-only-password");
        var login = accounts.login(name, "test-only-password").orElseThrow();
        var role = access.createRole(name, name, 1, "");
        access.replacePermissions(role.roleId(), List.of(permission));
        access.replaceGrants(login.account().userId(), List.of(role.roleId()));
        return "Bearer " + login.token();
    }

    private AccountStore.Account accountOf(String token) throws Exception {
        return accounts.authenticate(token.substring(7)).orElseThrow();
    }

    @Test
    void toolSelectionPagingStaysScopedToTheAgentOwnerAndReadOnly() throws Exception {
        String manager = accountWith(AccessPoints.AGENT_MANAGE);
        String outsider = accountWith(AccessPoints.CHAT);
        var managerAccount = accountOf(manager);
        var outsiderAccount = accountOf(outsider);
        var owned = agents.create("Own", "", managerAccount.userId());
        var foreign = agents.create("Foreign", "", outsiderAccount.userId());
        tools.importTools(
                managerAccount.userId(),
                List.of(
                        new ToolImport.ImportedTool(
                                "mine", "", "{}", "http", "GET", "http://test.invalid/mine")),
                "ask");
        tools.importTools(
                outsiderAccount.userId(),
                List.of(
                        new ToolImport.ImportedTool(
                                "foreign", "", "{}", "http", "GET", "http://test.invalid/foreign")),
                "ask");

        mvc.perform(
                        get("/api/advisor/tools")
                                .param("purpose", "selection")
                                .param("agentId", owned.agentId())
                                .param("page", "1")
                                .param("size", "10")
                                .header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("mine"))
                .andExpect(jsonPath("$.items[0].canEdit").value(false));

        // Tool catalogs stay private even when an agent manager can edit another owner's agent.
        mvc.perform(
                        get("/api/advisor/tools")
                                .param("purpose", "selection")
                                .param("agentId", foreign.agentId())
                                .param("page", "1")
                                .param("size", "10")
                                .header("Authorization", manager))
                .andExpect(status().isNotFound());

        mvc.perform(
                        get("/api/advisor/tools")
                                .param("purpose", "selection")
                                .param("agentId", "missing-agent")
                                .param("page", "1")
                                .param("size", "10")
                                .header("Authorization", manager))
                .andExpect(status().isNotFound());
    }

    @Test
    void toolSelectionPagingFiltersByOriginAndRejectsUnknownValues() throws Exception {
        String manager = accountWith(AccessPoints.AGENT_MANAGE);
        var managerAccount = accountOf(manager);
        var owned = agents.create("Own", "", managerAccount.userId());
        tools.importTools(
                managerAccount.userId(),
                List.of(
                        new ToolImport.ImportedTool(
                                "http-one", "", "{}", "http", "GET", "http://test.invalid/one"),
                        new ToolImport.ImportedTool(
                                "http-two", "", "{}", "http", "GET", "http://test.invalid/two"),
                        new ToolImport.ImportedTool(
                                "http-three",
                                "",
                                "{}",
                                "http",
                                "GET",
                                "http://test.invalid/three")),
                "ask");

        mvc.perform(
                        get("/api/advisor/tools")
                                .param("purpose", "selection")
                                .param("agentId", owned.agentId())
                                .param("origin", "http,declared")
                                .param("page", "2")
                                .param("size", "2")
                                .header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items.length()").value(1));

        mvc.perform(
                        get("/api/advisor/tools")
                                .param("purpose", "selection")
                                .param("agentId", owned.agentId())
                                .param("origin", "unknown")
                                .param("page", "1")
                                .param("size", "10")
                                .header("Authorization", manager))
                .andExpect(status().isBadRequest());

        mvc.perform(
                        get("/api/advisor/tools")
                                .param("purpose", "selection")
                                .param("agentId", owned.agentId())
                                .param("page", "1")
                                .param("size", "101")
                                .header("Authorization", manager))
                .andExpect(status().isBadRequest());
    }

    @Test
    void selectionPagingRequiresAgentManagement() throws Exception {
        String chatOnly = accountWith(AccessPoints.CHAT);
        String toolManager = accountWith(AccessPoints.TOOL_MANAGE);
        for (String token : List.of(chatOnly, toolManager)) {
            mvc.perform(
                            get("/api/advisor/tools")
                                    .param("purpose", "selection")
                                    .param("page", "1")
                                    .param("size", "10")
                                    .header("Authorization", token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void skillSelectionPagingOnlyReturnsPublishedSkillsOfTheOwner() throws Exception {
        String manager = accountWith(AccessPoints.AGENT_MANAGE);
        String outsider = accountWith(AccessPoints.CHAT);
        var managerAccount = accountOf(manager);
        var outsiderAccount = accountOf(outsider);
        var owned = agents.create("Own", "", managerAccount.userId());
        skills.publish(
                skills.createDraft(managerAccount.userId(), "Live skill", "usable", "# live"));
        skills.createDraft(managerAccount.userId(), "Draft skill", "not yet", "# draft");
        skills.publish(
                skills.createDraft(
                        outsiderAccount.userId(), "Foreign skill", "other", "# foreign"));

        mvc.perform(
                        get("/api/advisor/skills")
                                .param("purpose", "selection")
                                .param("agentId", owned.agentId())
                                .param("page", "1")
                                .param("size", "10")
                                .header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Live skill"))
                .andExpect(jsonPath("$.items[0].status").value("published"))
                .andExpect(jsonPath("$.items[0].canEdit").value(false));
    }

    @Test
    void knowledgeSelectionPagingUsesTheAgentOwner() throws Exception {
        String manager = accountWith(AccessPoints.AGENT_MANAGE);
        String outsider = accountWith(AccessPoints.CHAT);
        var managerAccount = accountOf(manager);
        var outsiderAccount = accountOf(outsider);
        var owned = agents.create("Own", "", managerAccount.userId());
        libraries.create(managerAccount.userId(), "Mine one", "target");
        libraries.create(managerAccount.userId(), "Mine two", "target");
        libraries.create(outsiderAccount.userId(), "Theirs", "target");

        mvc.perform(
                        get("/api/advisor/knowledge")
                                .param("purpose", "selection")
                                .param("agentId", owned.agentId())
                                .param("page", "1")
                                .param("size", "10")
                                .header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].canEdit").value(false));
    }

    @Test
    void mcpSelectionPagingCarriesToolSummaries() throws Exception {
        String manager = accountWith(AccessPoints.AGENT_MANAGE);
        var managerAccount = accountOf(manager);
        var owned = agents.create("Own", "", managerAccount.userId());
        var server =
                tools.insertServer(
                        new McpEndpointParser.McpEndpoint(
                                "calc-service",
                                "stdio",
                                "npx",
                                List.of("calc-mcp"),
                                java.util.Map.of(),
                                null,
                                java.util.Map.of()),
                        managerAccount.userId(),
                        "connected",
                        "");
        var calc =
                tools.approveMcpTool(
                        server,
                        new ToolCatalog.DiscoveredTool("calc", "adds numbers", "{}"),
                        "ask");

        mvc.perform(
                        get("/api/advisor/mcp-servers")
                                .param("purpose", "selection")
                                .param("agentId", owned.agentId())
                                .param("page", "1")
                                .param("size", "10")
                                .header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("calc-service"))
                .andExpect(jsonPath("$.items[0].toolSummaries.length()").value(1))
                .andExpect(jsonPath("$.items[0].toolSummaries[0].toolId").value(calc.toolId()))
                .andExpect(jsonPath("$.items[0].toolSummaries[0].name").value("calc"))
                .andExpect(jsonPath("$.items[0].toolSummaries[0].approved").value(true))
                .andExpect(jsonPath("$.items[0].toolSummaries[0].permission").value("ask"));
    }

    @Test
    void managementPagesIgnoreSelectionOnlyParameters() throws Exception {
        String manager = accountWith(AccessPoints.TOOL_MANAGE);
        var managerAccount = accountOf(manager);
        tools.insertServer(
                new McpEndpointParser.McpEndpoint(
                        "plain-service",
                        "stdio",
                        "npx",
                        List.of("plain-mcp"),
                        java.util.Map.of(),
                        null,
                        java.util.Map.of()),
                managerAccount.userId(),
                "connected",
                "");

        mvc.perform(
                        get("/api/advisor/mcp-servers")
                                .param("page", "1")
                                .header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("plain-service"))
                .andExpect(jsonPath("$.items[0].toolSummaries").doesNotExist());
    }
}
