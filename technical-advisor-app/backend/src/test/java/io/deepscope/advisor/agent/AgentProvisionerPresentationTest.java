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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.session.ReportActions;
import io.deepscope.advisor.session.ReportPresentationMiddleware;
import io.deepscope.advisor.skill.SkillSync;
import io.deepscope.advisor.tool.ToolCatalog;
import io.deepscope.core.agui.registry.AguiAgentRegistry;
import io.deepscope.core.model.Model;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentProvisionerPresentationTest {

    @Test
    void rebuildingAnAgentCannotReconnectOrReenableADisabledMcpServer() throws Exception {
        var source =
                new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                        new org.h2.Driver(),
                        "jdbc:h2:mem:disabled-mcp-"
                                + java.util.UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        io.deepscope.advisor.db.AdvisorSchema.initialize(source);
        var catalog = new ToolCatalog(source, new ObjectMapper());
        var server =
                catalog.insertServer(
                        new io.deepscope.advisor.tool.McpEndpointParser.McpEndpoint(
                                "private",
                                "stdio",
                                "echo",
                                java.util.List.of(),
                                java.util.Map.of(),
                                null,
                                java.util.Map.of()),
                        "user-1",
                        "disabled",
                        "");
        catalog.approveMcpTool(
                server, new ToolCatalog.DiscoveredTool("lookup", "Lookup", "{}"), "allow");
        catalog.replaceSelection(
                row().agentId(), java.util.List.of(), java.util.List.of(server.serverId()));
        var mcp = mock(io.deepscope.advisor.tool.McpToolSource.class);
        var provisioner =
                new AgentProvisioner(
                        null,
                        mock(Model.class),
                        new ReportActions(null, null),
                        new AguiAgentRegistry(),
                        catalog,
                        mcp,
                        new ObjectMapper(),
                        mock(SkillSync.class),
                        null,
                        null);
        var prepared = provisioner.prepare(row());
        try {
            org.mockito.Mockito.verifyNoInteractions(mcp);
            assertEquals("disabled", catalog.findServer(server.serverId()).orElseThrow().status());
        } finally {
            prepared.harness().close();
        }
    }

    @TempDir Path root;

    @Test
    void newWorkspaceGuidesDirectAnswersAndRuntimeAppliesTheSameConvention() throws Exception {
        var row = row();
        var prepared = provisioner().prepare(row);
        try {
            String behavior = Files.readString(Path.of(row.workspaceDir()).resolve("AGENTS.md"));
            assertFalse(behavior.contains("report_deliverable"));
            assertTrue(behavior.contains("最终回答直接输出正文"));
            assertTrue(
                    prepared.harness().getDelegate().getMiddlewares().stream()
                            .anyMatch(ReportPresentationMiddleware.class::isInstance));
            assertTrue(
                    prepared.harness().getToolkit().getTool("report_deliverable") != null,
                    "legacy pending calls remain resolvable");
            assertCompactionThreshold(prepared.harness());
        } finally {
            prepared.harness().close();
        }
    }

    @Test
    void basicRuntimeUsesTheSameAutomaticCompactionThreshold() {
        var registry = new AguiAgentRegistry();
        new BasicChatRuntime(
                new io.deepscope.advisor.AdvisorProperties(root),
                mock(Model.class),
                null,
                null,
                new ObjectMapper(),
                registry);
        var harness =
                (io.deepscope.harness.agent.HarnessAgent)
                        registry.getAgent(BasicChatRuntime.AGENT_ID).orElseThrow();
        try {
            assertCompactionThreshold(harness);
        } finally {
            harness.close();
        }
    }

    private void assertCompactionThreshold(io.deepscope.harness.agent.HarnessAgent harness) {
        var config =
                (io.deepscope.harness.agent.memory.compaction.CompactionConfig)
                        org.springframework.test.util.ReflectionTestUtils.getField(
                                harness.getCompactionHook(), "config");
        assertEquals(800_000, config.getTriggerTokens());
        assertEquals(0, config.getTriggerMessages());
    }

    @Test
    void customizedWorkspaceKeepsItsBodyAndGainsTheProductIdentity() throws Exception {
        var row = row();
        Path workspace = Path.of(row.workspaceDir());
        Files.createDirectories(workspace);
        String custom = "# 安全测试\n只做只读审查。\n旧版内部报告要求 F1 编号并调用 report_deliverable。\n";
        Files.writeString(workspace.resolve("AGENTS.md"), custom);
        var prepared = provisioner().prepare(row);
        try {
            // 产品身份幂等补齐，用户写的正文一字不改
            String afterPrepare = Files.readString(workspace.resolve("AGENTS.md"));
            assertTrue(afterPrepare.startsWith(AgentIdentity.IDENTITY_LINE));
            assertTrue(afterPrepare.endsWith(custom));
            assertTrue(
                    prepared.harness().getDelegate().getMiddlewares().stream()
                            .anyMatch(ReportPresentationMiddleware.class::isInstance));
        } finally {
            prepared.harness().close();
        }
    }

    @Test
    void preparedAgentFeedsItsDefinitionAndKeepsTheProductIdentityLast() throws Exception {
        var row = row();
        Path workspace = Path.of(row.workspaceDir());
        Files.createDirectories(workspace);
        String custom = "# 合同审查助手\n你只做合同审查，不要越界。\n";
        Files.writeString(workspace.resolve("AGENTS.md"), custom);

        var prepared = provisioner().prepare(row);
        try {
            String sysPrompt = prepared.harness().getDelegate().getSysPrompt();
            assertNotNull(sysPrompt);
            // A：说明书正文真的进提示词，说明书可以动态调整
            assertTrue(sysPrompt.contains("你只做合同审查，不要越界。"));
            // 身份规则压在最后兜底，说明书压不掉产品身份
            assertTrue(sysPrompt.endsWith(AgentIdentity.SYSTEM_PREAMBLE));
            assertTrue(
                    sysPrompt.indexOf("你只做合同审查")
                            < sysPrompt.indexOf(AgentIdentity.SYSTEM_PREAMBLE));
            assertTrue(sysPrompt.contains("不得自称“技术助手”“技术助理”“技术支持”"));
        } finally {
            prepared.harness().close();
        }
    }

    private AgentStore.AgentRow row() {
        return new AgentStore.AgentRow(
                "presentation-agent",
                "测试",
                "只读检查",
                AgentStore.ORIGIN_PERSONAL,
                "user-1",
                root.resolve("workspace").toString(),
                root.resolve("state").toString(),
                0,
                0);
    }

    private AgentProvisioner provisioner() {
        return new AgentProvisioner(
                null,
                mock(Model.class),
                new ReportActions(null, null),
                new AguiAgentRegistry(),
                mock(ToolCatalog.class),
                null,
                new ObjectMapper(),
                mock(SkillSync.class),
                null,
                null);
    }
}
