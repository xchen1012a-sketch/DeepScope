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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.agent.AgentProvisioner;
import io.deepscope.advisor.agent.AgentStore;
import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.advisor.session.ReportActions;
import io.deepscope.advisor.skill.SkillCatalog;
import io.deepscope.advisor.skill.SkillSync;
import io.deepscope.core.agui.registry.AguiAgentRegistry;
import io.deepscope.core.model.Model;
import io.deepscope.core.tool.ToolCallParam;
import io.deepscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.Driver;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import reactor.core.publisher.Mono;

class GuardedCatalogToolTest {
    @TempDir Path root;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"deny", "delete", "unbind", "server-delete", "ask"})
    void anOldRuntimeCannotInvokeAMcpToolAfterRevocationEvenWithALiveConnection(String change)
            throws Exception {
        var source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:mcp-guard-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(source);
        var json = new ObjectMapper();
        var catalog = new ToolCatalog(source, json);
        var agents = new AgentStore(source, root.resolve("agents"));
        var server =
                catalog.insertServer(
                        new McpEndpointParser.McpEndpoint(
                                "fake",
                                "http",
                                null,
                                List.of(),
                                Map.of(),
                                "http://test.invalid",
                                Map.of()),
                        "owner",
                        "connected",
                        "");
        catalog.approveMcpTool(
                server,
                new ToolCatalog.DiscoveredTool("audit_call", "", "{\"type\":\"object\"}"),
                "allow");
        var row = catalog.toolsOfServer(server.serverId()).get(0);
        var agent = agents.create("Audit", "", "owner");
        catalog.replaceSelection(agent.agentId(), List.of(row.toolId()), List.of());
        var client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("fake");
        when(client.initialize()).thenReturn(Mono.empty());
        when(client.listTools())
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new McpSchema.Tool(
                                                "audit_call",
                                                null,
                                                "",
                                                new McpSchema.JsonSchema(
                                                        "object", Map.of(), List.of(), null, null,
                                                        null),
                                                null,
                                                null,
                                                null))));
        AtomicInteger invoked = new AtomicInteger();
        when(client.callTool(any(), any(), any()))
                .thenAnswer(
                        call -> {
                            invoked.incrementAndGet();
                            return Mono.just(
                                    new McpSchema.CallToolResult(
                                            List.of(new McpSchema.TextContent("ok")), false));
                        });
        McpToolSource mcp =
                new McpToolSource() {
                    public List<ToolCatalog.DiscoveredTool> list(
                            McpEndpointParser.McpEndpoint endpoint) {
                        return List.of();
                    }

                    public McpClientWrapper open(McpEndpointParser.McpEndpoint endpoint) {
                        return client;
                    }
                };
        var provisioner =
                new AgentProvisioner(
                        agents,
                        mock(Model.class),
                        new ReportActions(null, null),
                        new AguiAgentRegistry(),
                        catalog,
                        mcp,
                        json,
                        new SkillSync(new SkillCatalog(source, root.resolve("skills"))),
                        null,
                        null);
        var runtime = provisioner.register(agent);
        try {
            var tool = runtime.getToolkit().getTool("audit_call");
            assertNotNull(tool);
            assertEquals(
                    io.deepscope.core.permission.PermissionBehavior.ASK,
                    ((io.deepscope.core.tool.ToolBase) tool)
                            .checkPermissions(
                                    Map.of(),
                                    io.deepscope.core.permission.PermissionContextState.builder()
                                            .build())
                            .block()
                            .getBehavior());
            tool.callAsync(ToolCallParam.builder().input(Map.of()).build()).block();
            assertEquals(1, invoked.get());
            var pending = tool.callAsync(ToolCallParam.builder().input(Map.of()).build());
            switch (change) {
                case "deny" -> catalog.updatePermission(row.toolId(), "deny");
                case "ask" -> catalog.updatePermission(row.toolId(), "ask");
                case "delete" -> catalog.deleteTool(row.toolId());
                case "unbind" -> catalog.replaceSelection(agent.agentId(), List.of(), List.of());
                case "server-delete" -> catalog.deleteServer(server.serverId());
                default -> throw new AssertionError(change);
            }
            pending.onErrorResume(error -> Mono.empty()).block();
            assertEquals(1, invoked.get(), "a delayed subscription must check the current catalog");
        } finally {
            runtime.close();
        }
    }
}
