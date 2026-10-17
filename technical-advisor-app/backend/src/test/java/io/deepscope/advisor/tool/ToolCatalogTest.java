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
package io.deepscope.advisor.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.db.AdvisorSchema;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** Catalog visibility, approval, and per-agent selection. */
class ToolCatalogTest {

    private ToolCatalog catalog;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:tools-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        catalog = new ToolCatalog(dataSource, new ObjectMapper());
    }

    @Test
    void freshCatalogShipsNoTools() throws Exception {
        assertEquals(0, catalog.listTools("carol", false).size());
    }

    @Test
    void liveExecutionRejectsUnavailableLifecycleAndDisabledMcpServers() throws Exception {
        var tool =
                catalog.importTools(
                                "carol",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "weather",
                                                "Weather",
                                                "{}",
                                                "http",
                                                "GET",
                                                "https://example.com/weather")),
                                "ask")
                        .get(0);
        var guard = new CatalogExecutionGuard(catalog);
        assertEquals(tool.toolId(), guard.check(tool.toolId()).toolId());
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE advisor_tools SET lifecycle_status = 'apply_failed'");
        }
        assertThrows(ToolProblem.class, () -> guard.check(tool.toolId()));
        var server =
                catalog.insertServer(
                        new McpEndpointParser.McpEndpoint(
                                "mcp",
                                "stdio",
                                "echo",
                                List.of(),
                                java.util.Map.of(),
                                null,
                                java.util.Map.of()),
                        "carol",
                        "connected",
                        "");
        var mcpTool =
                catalog.approveMcpTool(
                        server, new ToolCatalog.DiscoveredTool("lookup", "Lookup", "{}"), "allow");
        assertEquals(mcpTool.toolId(), guard.check(mcpTool.toolId()).toolId());
        catalog.markServer(server.serverId(), "disabled", "");
        assertThrows(ToolProblem.class, () -> guard.check(mcpTool.toolId()));
    }

    @Test
    void foreignToolsStayHiddenAndSelectionDoesNotIncludeThem() throws Exception {
        var imported =
                catalog.importTools(
                        "carol",
                        List.of(
                                new ToolImport.ImportedTool(
                                        "lookup",
                                        "Look up",
                                        "{}",
                                        "http",
                                        "GET",
                                        "https://example.com/lookup")),
                        "ask");
        assertEquals(0, catalog.listTools("dave", false).size());
        assertEquals(0, catalog.listTools("dave", true).size());
        assertEquals(0, catalog.pageTools("dave", true, null, null, 1, 20).total());
        assertFalse(ToolCatalog.canView("carol", "http", "dave", true));
        assertFalse(ToolCatalog.canEdit("carol", "http", "dave", true));

        var server =
                catalog.insertServer(
                        new McpEndpointParser.McpEndpoint(
                                "files",
                                "stdio",
                                "echo",
                                List.of(),
                                java.util.Map.of(),
                                null,
                                java.util.Map.of()),
                        "carol",
                        "connected",
                        "");
        catalog.approveMcpTool(
                server, new ToolCatalog.DiscoveredTool("list_dir", "List", "{}"), "ask");
        assertTrue(catalog.listServers("dave", true).isEmpty());
        assertEquals(0, catalog.pageServers("dave", true, null, null, 1, 20).total());
        catalog.replaceSelection(
                "agent-1", List.of(imported.get(0).toolId()), List.of(server.serverId()));

        var effective = catalog.effectiveTools("agent-1");
        assertEquals(2, effective.size());
        assertTrue(effective.stream().noneMatch(tool -> "declared".equals(tool.origin())));
    }

    @Test
    void aSubscribedServerPicksUpNewlyApprovedTools() throws Exception {
        var server =
                catalog.insertServer(
                        new McpEndpointParser.McpEndpoint(
                                "files",
                                "stdio",
                                "echo",
                                List.of(),
                                java.util.Map.of(),
                                null,
                                java.util.Map.of()),
                        "carol",
                        "connected",
                        "");
        catalog.replaceSelection("agent-1", List.of(), List.of(server.serverId()));
        assertEquals(0, catalog.effectiveTools("agent-1").size());
        catalog.approveMcpTool(
                server, new ToolCatalog.DiscoveredTool("list_dir", "List", "{}"), "deny");
        assertEquals(1, catalog.effectiveTools("agent-1").size());
        assertEquals("deny", catalog.effectiveTools("agent-1").get(0).permission());
    }

    @Test
    void aFailedBatchRollsBackPreviouslyInsertedTools() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE advisor_tools ADD CONSTRAINT reject_name CHECK (name <>"
                            + " 'reject')");
        }
        assertThrows(
                java.sql.SQLException.class,
                () ->
                        catalog.importTools(
                                "owner",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "valid",
                                                "",
                                                "{}",
                                                "http",
                                                "GET",
                                                "https://example.test"),
                                        new ToolImport.ImportedTool(
                                                "reject",
                                                "",
                                                "{}",
                                                "http",
                                                "GET",
                                                "https://example.test")),
                                "ask"));
        assertTrue(catalog.listTools("owner", false).isEmpty());
    }

    @Test
    void testImpactedAgentIdsAndAtomicDeleteToolWithRevision() throws Exception {
        var imported =
                catalog.importTools(
                        "carol",
                        List.of(
                                new ToolImport.ImportedTool(
                                        "search",
                                        "Search query",
                                        "{}",
                                        "http",
                                        "GET",
                                        "https://example.com/search")),
                        "allow");
        String toolId = imported.get(0).toolId();
        long rev = imported.get(0).revision();

        // Bind tool to agent-1 and agent-2
        catalog.replaceSelection("agent-1", List.of(toolId), List.of());
        catalog.replaceSelection("agent-2", List.of(toolId), List.of());

        List<String> impacted = catalog.impactedAgentIdsForTool(toolId);
        assertEquals(2, impacted.size());
        assertTrue(impacted.contains("agent-1"));
        assertTrue(impacted.contains("agent-2"));

        // Delete with wrong revision -> false, no changes
        assertFalse(catalog.deleteTool(toolId, rev + 99));
        assertEquals(2, catalog.impactedAgentIdsForTool(toolId).size());
        assertTrue(catalog.findTool(toolId).isPresent());

        // Delete with correct revision -> true, tool and bindings removed atomically
        assertTrue(catalog.deleteTool(toolId, rev));
        assertTrue(catalog.findTool(toolId).isEmpty());
        assertTrue(catalog.impactedAgentIdsForTool(toolId).isEmpty());
    }

    @Test
    void testImpactedAgentIdsAndAtomicDeleteServerWithRevision() throws Exception {
        var server =
                catalog.insertServer(
                        new McpEndpointParser.McpEndpoint(
                                "mcp-files",
                                "stdio",
                                "echo",
                                List.of(),
                                java.util.Map.of(),
                                null,
                                java.util.Map.of()),
                        "carol",
                        "connected",
                        "");
        var tool =
                catalog.approveMcpTool(
                        server, new ToolCatalog.DiscoveredTool("read_file", "Read", "{}"), "allow");

        // agent-1 selects tool directly, agent-2 subscribes to whole server
        catalog.replaceSelection("agent-1", List.of(tool.toolId()), List.of());
        catalog.replaceSelection("agent-2", List.of(), List.of(server.serverId()));

        List<String> impacted = catalog.impactedAgentIdsForServer(server.serverId());
        assertEquals(2, impacted.size());
        assertTrue(impacted.contains("agent-1"));
        assertTrue(impacted.contains("agent-2"));

        // Delete with wrong revision -> false
        assertFalse(catalog.deleteServer(server.serverId(), server.revision() + 99));
        assertTrue(catalog.findServer(server.serverId()).isPresent());
        assertEquals(1, catalog.toolsOfServer(server.serverId()).size());

        // Delete with correct revision -> true, server and tools and bindings removed
        assertTrue(catalog.deleteServer(server.serverId(), server.revision()));
        assertTrue(catalog.findServer(server.serverId()).isEmpty());
        assertTrue(catalog.toolsOfServer(server.serverId()).isEmpty());
        assertTrue(catalog.impactedAgentIdsForServer(server.serverId()).isEmpty());
    }
}
