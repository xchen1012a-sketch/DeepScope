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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.core.tool.mcp.McpClientWrapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * T5 verification: MCP update lifecycle covering added/changed/removed tools, network failure safety,
 * pending diff persistence across restarts, 409 conflict detection on remote definition drift,
 * preservation of toolId/permissions/agent bindings, and unapproved tool isolation.
 */
class McpUpdateServiceTest {
    @Test
    void removalConfirmationCannotDeleteAToolEditedAfterRefresh() throws Exception {
        catalog.approveMcpTool(server, new ToolCatalog.DiscoveredTool("gone", "", "{}"), "allow");
        var tool = catalog.toolsOfServer(server.serverId()).get(0);
        remoteTools.set(List.of());
        var diff = service.refresh(server);
        catalog.updatePermission(tool.toolId(), "deny");
        var error =
                assertThrows(
                        ToolProblem.class,
                        () ->
                                service.apply(
                                        server,
                                        new McpUpdateService.ApproveRequest(
                                                diff.fingerprint(),
                                                null,
                                                new McpUpdateService.AcceptedChanges(
                                                        List.of(),
                                                        List.of(),
                                                        List.of(
                                                                new McpUpdateService
                                                                        .AcceptedRemoved(
                                                                        tool.toolId(),
                                                                        "gone",
                                                                        true))))));
        assertEquals(409, error.status());
        assertTrue(catalog.findTool(tool.toolId()).isPresent());
    }

    private ToolCatalog catalog;
    private DataSource dataSource;
    private ObjectMapper advisorObjectMapper;
    private McpUpdateService service;
    private final AtomicReference<List<ToolCatalog.DiscoveredTool>> remoteTools =
            new AtomicReference<>(new ArrayList<>());
    private final AtomicBoolean remoteFails = new AtomicBoolean(false);

    private ToolCatalog.ServerRow server;

    @BeforeEach
    void setUp() throws Exception {
        advisorObjectMapper = new ObjectMapper();
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:mcp-update-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        catalog = new ToolCatalog(dataSource, advisorObjectMapper);

        McpToolSource mcp =
                new McpToolSource() {
                    @Override
                    public List<ToolCatalog.DiscoveredTool> list(
                            McpEndpointParser.McpEndpoint endpoint) throws Exception {
                        if (remoteFails.get()) {
                            throw new RuntimeException("Remote network unreachable");
                        }
                        return new ArrayList<>(remoteTools.get());
                    }

                    @Override
                    public McpClientWrapper open(McpEndpointParser.McpEndpoint endpoint) {
                        throw new UnsupportedOperationException("Not needed for discovery");
                    }
                };

        service = new McpUpdateService(catalog, mcp, advisorObjectMapper);

        server =
                catalog.insertServer(
                        new McpEndpointParser.McpEndpoint(
                                "test-mcp",
                                "http",
                                null,
                                List.of(),
                                java.util.Map.of(),
                                "http://localhost:8080/mcp",
                                java.util.Map.of()),
                        "owner-1",
                        "connected",
                        "");
    }

    @Test
    void legacyApprovalRollsBackEarlierPermissionChangesWhenAnotherChoiceIsInvalid()
            throws Exception {
        var live = new ToolCatalog.DiscoveredTool("existing", "", "{}");
        catalog.approveMcpTool(server, live, "allow");
        remoteTools.set(List.of(live, new ToolCatalog.DiscoveredTool("new", "", "{}")));
        assertThrows(
                Exception.class,
                () ->
                        service.apply(
                                server,
                                new McpUpdateService.ApproveRequest(
                                        null,
                                        List.of(
                                                java.util.Map.of(
                                                        "name", "existing", "permission", "deny"),
                                                java.util.Map.of(
                                                        "name", "new", "permission", "invalid")),
                                        null)));
        assertEquals("allow", catalog.toolsOfServer(server.serverId()).get(0).permission());
    }

    @Test
    void confirmationCannotDeleteAnotherOwnersToolOrPartiallyApplyChoices() throws Exception {
        var victim =
                catalog.importTools(
                                "victim",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "victim",
                                                "",
                                                "{}",
                                                "http",
                                                "GET",
                                                "http://test.invalid")),
                                "allow")
                        .get(0);
        remoteTools.set(List.of(new ToolCatalog.DiscoveredTool("new-tool", "", "{}")));
        var diff = service.refresh(server);
        var error =
                assertThrows(
                        ToolProblem.class,
                        () ->
                                service.apply(
                                        server,
                                        new McpUpdateService.ApproveRequest(
                                                diff.fingerprint(),
                                                null,
                                                new McpUpdateService.AcceptedChanges(
                                                        List.of(
                                                                new McpUpdateService.AcceptedAdded(
                                                                        "new-tool", "ask")),
                                                        List.of(),
                                                        List.of(
                                                                new McpUpdateService
                                                                        .AcceptedRemoved(
                                                                        victim.toolId(),
                                                                        "victim",
                                                                        true))))));
        assertEquals(422, error.status());
        assertTrue(catalog.findTool(victim.toolId()).isPresent());
        assertTrue(catalog.toolsOfServer(server.serverId()).isEmpty());
        assertTrue(
                service.pendingDiff(catalog.findServer(server.serverId()).orElseThrow())
                        .isPresent());
    }

    @Test
    void refreshDetectsAddedChangedAndRemovedTools() throws Exception {
        // Initial catalog state: toolA (v1), toolB
        catalog.approveMcpTool(
                server,
                new ToolCatalog.DiscoveredTool(
                        "toolA", "Old description A", "{\"type\":\"object\"}"),
                "allow");
        catalog.approveMcpTool(
                server,
                new ToolCatalog.DiscoveredTool("toolB", "Description B", "{\"type\":\"string\"}"),
                "ask");

        // Remote now returns: toolA with updated schema/description (changed), toolC (added).
        // toolB is missing (removed).
        remoteTools.set(
                List.of(
                        new ToolCatalog.DiscoveredTool(
                                "toolA",
                                "New description A",
                                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"}}}"),
                        new ToolCatalog.DiscoveredTool(
                                "toolC", "Description C", "{\"type\":\"boolean\"}")));

        McpUpdateService.McpDiff diff = service.refresh(server);

        assertTrue(diff.hasChanges());
        assertNotNull(diff.fingerprint());

        // Check added
        assertEquals(1, diff.added().size());
        assertEquals("toolC", diff.added().get(0).name());

        // Check changed
        assertEquals(1, diff.changed().size());
        assertEquals("toolA", diff.changed().get(0).name());
        assertEquals("Old description A", diff.changed().get(0).oldDescription());
        assertEquals("New description A", diff.changed().get(0).newDescription());

        // Check removed
        assertEquals(1, diff.removed().size());
        assertEquals("toolB", diff.removed().get(0).name());
    }

    @Test
    void networkFailureDuringRefreshDoesNotClearTools() throws Exception {
        catalog.approveMcpTool(
                server, new ToolCatalog.DiscoveredTool("toolA", "Desc A", "{}"), "allow");
        catalog.approveMcpTool(
                server, new ToolCatalog.DiscoveredTool("toolB", "Desc B", "{}"), "ask");

        assertEquals(2, catalog.toolsOfServer(server.serverId()).size());

        // Trigger remote failure
        remoteFails.set(true);

        assertThrows(RuntimeException.class, () -> service.refresh(server));

        // Tools MUST remain intact
        List<ToolCatalog.ToolRow> existing = catalog.toolsOfServer(server.serverId());
        assertEquals(2, existing.size());

        // Server status marked as error
        ToolCatalog.ServerRow reloaded = catalog.findServer(server.serverId()).orElseThrow();
        assertEquals("error", reloaded.status());
    }

    @Test
    void pendingChangesPersistAndRecoverAcrossServiceInstances() throws Exception {
        catalog.approveMcpTool(
                server, new ToolCatalog.DiscoveredTool("toolA", "Old A", "{}"), "allow");

        remoteTools.set(
                List.of(
                        new ToolCatalog.DiscoveredTool("toolA", "New A", "{\"v\":2}"),
                        new ToolCatalog.DiscoveredTool("toolNew", "Desc New", "{}")));

        McpUpdateService.McpDiff diff = service.refresh(server);
        assertTrue(diff.hasChanges());

        // Reload server from DB
        ToolCatalog.ServerRow reloadedServer = catalog.findServer(server.serverId()).orElseThrow();
        assertFalse(reloadedServer.pendingJson().isBlank());
        assertFalse("[]".equals(reloadedServer.pendingJson()));

        // Create a new service instance (simulating app restart)
        McpUpdateService newServiceInstance =
                new McpUpdateService(
                        catalog,
                        new McpToolSource() {
                            @Override
                            public List<ToolCatalog.DiscoveredTool> list(
                                    McpEndpointParser.McpEndpoint endpoint) {
                                return List.of();
                            }

                            @Override
                            public McpClientWrapper open(McpEndpointParser.McpEndpoint endpoint) {
                                return null;
                            }
                        },
                        advisorObjectMapper);

        var recovered = newServiceInstance.pendingDiff(reloadedServer);
        assertTrue(recovered.isPresent());
        assertEquals(diff.fingerprint(), recovered.get().fingerprint());
        assertEquals(1, recovered.get().added().size());
        assertEquals("toolNew", recovered.get().added().get(0).name());
        assertEquals(1, recovered.get().changed().size());
        assertEquals("toolA", recovered.get().changed().get(0).name());
    }

    @Test
    void applyThrows409WhenRemoteChangesBeforeApproval() throws Exception {
        catalog.approveMcpTool(
                server, new ToolCatalog.DiscoveredTool("toolA", "Desc A", "{}"), "allow");

        remoteTools.set(
                List.of(
                        new ToolCatalog.DiscoveredTool("toolA", "Desc A", "{}"),
                        new ToolCatalog.DiscoveredTool("toolB", "Desc B", "{}")));

        McpUpdateService.McpDiff diff = service.refresh(server);
        String capturedFingerprint = diff.fingerprint();

        // Remote changes again before user approves!
        remoteTools.set(
                List.of(
                        new ToolCatalog.DiscoveredTool("toolA", "Drifted Description", "{}"),
                        new ToolCatalog.DiscoveredTool("toolB", "Desc B", "{}")));

        McpUpdateService.ApproveRequest request =
                new McpUpdateService.ApproveRequest(
                        capturedFingerprint,
                        null,
                        new McpUpdateService.AcceptedChanges(
                                List.of(new McpUpdateService.AcceptedAdded("toolB", "ask")),
                                List.of(),
                                List.of()));

        ToolProblem problem = assertThrows(ToolProblem.class, () -> service.apply(server, request));
        assertEquals(409, problem.status());
        assertEquals("mcp_changed", problem.code());

        // Verify no changes applied (toolB not added)
        List<ToolCatalog.ToolRow> tools = catalog.toolsOfServer(server.serverId());
        assertEquals(1, tools.size());
        assertEquals("toolA", tools.get(0).name());
    }

    @Test
    void applyPreservesToolIdPermissionAndAgentBindingsForChangedTools() throws Exception {
        ToolCatalog.ToolRow toolA =
                catalog.approveMcpTool(
                        server,
                        new ToolCatalog.DiscoveredTool("toolA", "Old A", "{\"ver\":1}"),
                        "allow");

        String originalToolId = toolA.toolId();

        // Bind an agent to toolA
        String agentId = "agent-" + UUID.randomUUID();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "INSERT INTO advisor_agent_tools (agent_id, tool_id) VALUES (?,"
                                        + " ?)")) {
            ps.setString(1, agentId);
            ps.setString(2, originalToolId);
            ps.executeUpdate();
        }

        // Remote updates toolA's description and schema
        remoteTools.set(
                List.of(new ToolCatalog.DiscoveredTool("toolA", "Updated A", "{\"ver\":2}")));

        McpUpdateService.McpDiff diff = service.refresh(server);
        assertEquals(1, diff.changed().size());

        McpUpdateService.ApproveRequest request =
                new McpUpdateService.ApproveRequest(
                        diff.fingerprint(),
                        null,
                        new McpUpdateService.AcceptedChanges(
                                List.of(),
                                List.of(
                                        new McpUpdateService.AcceptedChanged(
                                                originalToolId, "toolA", true)),
                                List.of()));

        service.apply(server, request);

        // Reload toolA
        ToolCatalog.ToolRow reloaded = catalog.findTool(originalToolId).orElseThrow();
        assertEquals(originalToolId, reloaded.toolId(), "toolId must be preserved");
        assertEquals("allow", reloaded.permission(), "permission must be preserved");
        assertEquals("Updated A", reloaded.description(), "description updated");
        assertEquals("{\"ver\":2}", reloaded.inputSchema(), "inputSchema updated");

        // Verify agent binding is still intact
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "SELECT COUNT(*) FROM advisor_agent_tools WHERE agent_id = ? AND"
                                        + " tool_id = ?")) {
            ps.setString(1, agentId);
            ps.setString(2, originalToolId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1), "agent binding must be preserved");
            }
        }
    }

    @Test
    void cancelledChangesDoNotApproveAddedToolsAndRemovedToolsCannotBeCalled() throws Exception {
        ToolCatalog.ToolRow toolA =
                catalog.approveMcpTool(
                        server, new ToolCatalog.DiscoveredTool("toolA", "Desc A", "{}"), "allow");
        ToolCatalog.ToolRow toolB =
                catalog.approveMcpTool(
                        server, new ToolCatalog.DiscoveredTool("toolB", "Desc B", "{}"), "ask");

        // Remote has: toolA, and new toolC. toolB is gone.
        remoteTools.set(
                List.of(
                        new ToolCatalog.DiscoveredTool("toolA", "Desc A", "{}"),
                        new ToolCatalog.DiscoveredTool("toolC", "Desc C", "{}")));

        McpUpdateService.McpDiff diff = service.refresh(server);
        assertEquals(1, diff.added().size());
        assertEquals("toolC", diff.added().get(0).name());
        assertEquals(1, diff.removed().size());
        assertEquals("toolB", diff.removed().get(0).name());

        // User approves removal of toolB, but DOES NOT accept toolC (added is empty)
        McpUpdateService.ApproveRequest request =
                new McpUpdateService.ApproveRequest(
                        diff.fingerprint(),
                        null,
                        new McpUpdateService.AcceptedChanges(
                                List.of(), // toolC cancelled / omitted
                                List.of(),
                                List.of(
                                        new McpUpdateService.AcceptedRemoved(
                                                toolB.toolId(), "toolB", true))));

        service.apply(server, request);

        // toolC was not added
        assertTrue(
                catalog.toolsOfServer(server.serverId()).stream()
                        .noneMatch(t -> "toolC".equals(t.name())));

        // toolB was removed and guard denies execution
        CatalogExecutionGuard guard = new CatalogExecutionGuard(catalog);
        ToolProblem error = assertThrows(ToolProblem.class, () -> guard.check(toolB.toolId()));
        assertEquals(404, error.status());
        assertEquals("tool_not_found", error.code());
    }

    @Test
    void wholeServerSubscriptionOnlyIncludesApprovedTools() throws Exception {
        // Agent subscribes to whole server
        String agentId = "agent-whole-server";
        catalog.replaceSelection(agentId, List.of(), List.of(server.serverId()));

        // Initial: only toolA approved
        catalog.approveMcpTool(
                server, new ToolCatalog.DiscoveredTool("toolA", "Desc A", "{}"), "allow");

        // Remote reports toolB as added
        remoteTools.set(
                List.of(
                        new ToolCatalog.DiscoveredTool("toolA", "Desc A", "{}"),
                        new ToolCatalog.DiscoveredTool("toolB", "Desc B", "{}")));

        service.refresh(server);

        // Effective tools of agent MUST only contain toolA, NOT unapproved/pending toolB
        List<ToolCatalog.ToolRow> effective = catalog.effectiveTools(agentId);
        assertEquals(1, effective.size());
        assertEquals("toolA", effective.get(0).name());
    }
}
