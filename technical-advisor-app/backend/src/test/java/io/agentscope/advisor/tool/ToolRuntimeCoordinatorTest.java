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
package io.agentscope.advisor.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.advisor.agent.AgentProvisioner;
import io.agentscope.advisor.agent.AgentStore;
import io.agentscope.advisor.db.AdvisorSchema;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class ToolRuntimeCoordinatorTest {

    @Test
    void anOldRunCannotReleaseTheCurrentRunsLease() {
        ToolRuntimeCoordinator coordinator =
                new ToolRuntimeCoordinator(null, null, null, null, null);
        coordinator.claimRun("session", "old");
        coordinator.acquireLease("session", "agent", "old");
        coordinator.releaseLease("session", "old");
        coordinator.claimRun("session", "new");
        coordinator.acquireLease("session", "agent", "new");
        coordinator.releaseLease("session", "old");
        assertEquals(1, coordinator.activeLeaseCount("agent"));
        coordinator.releaseLease("session", "new");
        assertEquals(0, coordinator.activeLeaseCount("agent"));
    }

    @Test
    void lateInitializationCannotOverwriteANewerRunsLease() {
        ToolRuntimeCoordinator coordinator =
                new ToolRuntimeCoordinator(null, null, null, null, null);
        coordinator.claimRun("session", "old");
        coordinator.claimRun("session", "new");
        coordinator.acquireLease("session", "agent", "new");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> coordinator.acquireLease("session", "agent", "old"));
        coordinator.releaseLease("session", "old");
        assertEquals(1, coordinator.activeLeaseCount("agent"));
    }

    private DataSource dataSource;
    private ToolCatalog catalog;
    private AgentStore agents;
    private AguiAgentRegistry registry;
    private AgentProvisioner provisioner;
    private ToolRuntimeCoordinator coordinator;
    private final ObjectMapper json = new ObjectMapper();

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:coord_test_"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        catalog = new ToolCatalog(dataSource, json);
        agents = new AgentStore(dataSource, tempDir.resolve("agents"));
        registry = new AguiAgentRegistry();
        provisioner = mock(AgentProvisioner.class);
        coordinator = new ToolRuntimeCoordinator(catalog, agents, provisioner, dataSource, json);
    }

    @Test
    void testOnlyRebuildsImpactedAgents() throws Exception {
        // Create 2 agents
        AgentStore.AgentRow agent1 = agents.create("Agent One", "First agent", "user-1");
        AgentStore.AgentRow agent2 = agents.create("Agent Two", "Second agent", "user-1");

        // Import tool 1 and tool 2
        var imported1 =
                catalog.importTools(
                        "user-1",
                        List.of(
                                new ToolImport.ImportedTool(
                                        "WeatherTool",
                                        "Get weather",
                                        "{}",
                                        "http",
                                        "GET",
                                        "http://localhost/weather")),
                        "allow");
        var imported2 =
                catalog.importTools(
                        "user-1",
                        List.of(
                                new ToolImport.ImportedTool(
                                        "StockTool",
                                        "Get stock",
                                        "{}",
                                        "http",
                                        "GET",
                                        "http://localhost/stock")),
                        "allow");

        String tool1Id = imported1.get(0).toolId();
        String tool2Id = imported2.get(0).toolId();

        // Bind agent 1 -> tool 1, agent 2 -> tool 2
        catalog.replaceSelection(agent1.agentId(), List.of(tool1Id), List.of());
        catalog.replaceSelection(agent2.agentId(), List.of(tool2Id), List.of());

        // Mock provisioner prepare & commit for agent 1
        HarnessAgent harness1 = mock(HarnessAgent.class);
        AgentProvisioner.PreparedAgent prep1 =
                new AgentProvisioner.PreparedAgent(harness1, List.of());
        when(provisioner.prepare(agent1)).thenReturn(prep1);
        when(provisioner.commit(agent1.agentId(), prep1)).thenReturn(List.of());

        // Update tool 1 only
        ToolRuntimeCoordinator.ApplyResult result =
                coordinator.applyChanges("user-1", "req-1", List.of(tool1Id), List.of());

        assertEquals("applied", result.status());
        assertEquals(List.of(agent1.agentId()), result.impactedAgentIds());

        // Verify only agent 1 was prepared and committed
        verify(provisioner, times(1)).prepare(agent1);
        verify(provisioner, times(1)).commit(agent1.agentId(), prep1);
        verify(provisioner, never()).prepare(agent2);
    }

    @Test
    void testSaveAndApplySeparatedAndBuildFailurePreservesOldVersion() throws Exception {
        AgentStore.AgentRow agent1 = agents.create("Agent Fail", "Failing build", "user-1");

        var imported =
                catalog.importTools(
                        "user-1",
                        List.of(
                                new ToolImport.ImportedTool(
                                        "BrokenTool",
                                        "Broken",
                                        "{}",
                                        "http",
                                        "GET",
                                        "http://localhost/broken")),
                        "allow");
        String toolBrokenId = imported.get(0).toolId();
        catalog.replaceSelection(agent1.agentId(), List.of(toolBrokenId), List.of());

        // Simulate build failure during prepare
        when(provisioner.prepare(agent1)).thenThrow(new IOException("Connection to MCP failed"));

        ToolRuntimeCoordinator.ApplyResult result =
                coordinator.applyChanges("user-1", "req-fail", List.of(toolBrokenId), List.of());

        assertEquals("apply_failed", result.status());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("配置已保存，运行更新失败"));

        // Commit was never called for failing agent
        verify(provisioner, never())
                .commit(
                        org.mockito.ArgumentMatchers.eq(agent1.agentId()),
                        org.mockito.ArgumentMatchers.any());

        // Now fix the issue and retryApply
        HarnessAgent harnessFixed = mock(HarnessAgent.class);
        AgentProvisioner.PreparedAgent prepFixed =
                new AgentProvisioner.PreparedAgent(harnessFixed, List.of());
        org.mockito.Mockito.doReturn(prepFixed).when(provisioner).prepare(agent1);
        org.mockito.Mockito.doReturn(List.of())
                .when(provisioner)
                .commit(agent1.agentId(), prepFixed);

        ToolRuntimeCoordinator.ApplyResult retryResult =
                coordinator.retryApply("user-1", "req-fail");

        assertEquals("applied", retryResult.status());
        verify(provisioner, times(1)).commit(agent1.agentId(), prepFixed);
    }

    @Test
    void testConcurrentRoundsUseFixedVersionAndDoNotCloseOldConnectionsPrematurely()
            throws Exception {
        AgentStore.AgentRow agent1 = agents.create("Agent MCP", "MCP agent", "user-1");

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
                        "user-1",
                        "connected",
                        "");
        var mcpTool =
                catalog.approveMcpTool(
                        server, new ToolCatalog.DiscoveredTool("list_dir", "List", "{}"), "allow");

        catalog.replaceSelection(agent1.agentId(), List.of(mcpTool.toolId()), List.of());

        McpClientWrapper oldConn = mock(McpClientWrapper.class);
        McpClientWrapper newConn = mock(McpClientWrapper.class);

        // Round 1 starts on session-1
        coordinator.acquireLease("session-1", agent1.agentId());
        assertEquals(1, coordinator.activeLeaseCount(agent1.agentId()));

        // Tool update triggers rebuild
        HarnessAgent newHarness = mock(HarnessAgent.class);
        AgentProvisioner.PreparedAgent prepNew =
                new AgentProvisioner.PreparedAgent(newHarness, List.of(newConn));
        when(provisioner.prepare(agent1)).thenReturn(prepNew);
        // Committing returns old connections
        when(provisioner.commit(agent1.agentId(), prepNew)).thenReturn(List.of(oldConn));

        coordinator.applyChanges("user-1", "req-mcp", List.of(mcpTool.toolId()), List.of());

        // Old connection must NOT be closed because session-1 is still running!
        verify(oldConn, never()).close();

        // Round 2 starts on session-2
        coordinator.acquireLease("session-2", agent1.agentId());
        assertEquals(2, coordinator.activeLeaseCount(agent1.agentId()));

        // Round 1 completes
        coordinator.releaseLease("session-1");
        // Still has 1 lease (session-2)
        assertEquals(1, coordinator.activeLeaseCount(agent1.agentId()));

        // Round 2 completes
        coordinator.releaseLease("session-2");
        assertEquals(0, coordinator.activeLeaseCount(agent1.agentId()));

        // Now that all leases dropped to 0, old connection is closed!
        verify(oldConn, times(1)).close();
        verify(newConn, never()).close();
    }

    @Test
    void testLeaseDropsToZeroOnNormalFinishCancelOrException() {
        String sessionId = "sess-err";
        String agentId = "agent-x";

        coordinator.acquireLease(sessionId, agentId);
        assertEquals(1, coordinator.activeLeaseCount(agentId));

        // Simulate finally block in runner:
        coordinator.releaseLease(sessionId);
        assertEquals(0, coordinator.activeLeaseCount(agentId));
        assertFalse(coordinator.hasActiveLeases(agentId));
    }

    @Test
    void testDeletedOrDenyToolBlocksOldContextFromNextExecution() throws SQLException {
        var imported =
                catalog.importTools(
                        "user-1",
                        List.of(
                                new ToolImport.ImportedTool(
                                        "GuardedTool",
                                        "desc",
                                        "{}",
                                        "http",
                                        "GET",
                                        "http://localhost")),
                        "allow");
        String toolId = imported.get(0).toolId();

        CatalogExecutionGuard guard = new CatalogExecutionGuard(catalog);
        assertNotNull(guard.check(toolId));

        // Change permission to deny
        catalog.updatePermission(toolId, "deny");
        ToolProblem errDeny = assertThrows(ToolProblem.class, () -> guard.check(toolId));
        assertEquals("permission_denied", errDeny.code());

        // Delete tool
        catalog.deleteTool(toolId, null);
        ToolProblem errDeleted = assertThrows(ToolProblem.class, () -> guard.check(toolId));
        assertEquals("tool_not_found", errDeleted.code());
    }

    @Test
    void testRetryApplyIdempotentAndNoDuplicateConnections() throws Exception {
        AgentStore.AgentRow agent = agents.create("Agent Idem", "desc", "user-1");
        var imported =
                catalog.importTools(
                        "user-1",
                        List.of(
                                new ToolImport.ImportedTool(
                                        "ToolI", "desc", "{}", "http", "GET", "http://localhost")),
                        "allow");
        String toolId = imported.get(0).toolId();
        catalog.replaceSelection(agent.agentId(), List.of(toolId), List.of());

        HarnessAgent harness = mock(HarnessAgent.class);
        AgentProvisioner.PreparedAgent prep =
                new AgentProvisioner.PreparedAgent(harness, List.of());
        when(provisioner.prepare(agent)).thenReturn(prep);
        when(provisioner.commit(agent.agentId(), prep)).thenReturn(List.of());

        // First apply
        ToolRuntimeCoordinator.ApplyResult res1 =
                coordinator.applyChanges("user-1", "req-idem", List.of(toolId), List.of());
        assertEquals("applied", res1.status());

        // Retry apply
        ToolRuntimeCoordinator.ApplyResult res2 = coordinator.retryApply("user-1", "req-idem");
        assertEquals("applied", res2.status());

        // Prepared/commit called only once!
        verify(provisioner, times(1)).prepare(agent);
        verify(provisioner, times(1)).commit(agent.agentId(), prep);
    }
}
