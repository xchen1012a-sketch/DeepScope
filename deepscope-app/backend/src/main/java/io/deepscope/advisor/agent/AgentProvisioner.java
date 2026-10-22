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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.session.ReportActions;
import io.deepscope.advisor.session.ReportPresentationMiddleware;
import io.deepscope.advisor.skill.SkillSync;
import io.deepscope.advisor.tool.CatalogExecutionGuard;
import io.deepscope.advisor.tool.HttpCatalogTool;
import io.deepscope.advisor.tool.McpToolSource;
import io.deepscope.advisor.tool.ToolAuth;
import io.deepscope.advisor.tool.ToolCatalog;
import io.deepscope.advisor.tool.ToolCredentialStore;
import io.deepscope.advisor.tool.ToolHttpExecutor;
import io.deepscope.advisor.tool.ToolPermissions;
import io.deepscope.advisor.tool.ToolProblem;
import io.deepscope.core.agui.registry.AguiAgentRegistry;
import io.deepscope.core.model.Model;
import io.deepscope.core.state.JsonFileAgentStateStore;
import io.deepscope.core.tool.Toolkit;
import io.deepscope.core.tool.mcp.McpClientWrapper;
import io.deepscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Builds and registers a runtime {@link HarnessAgent} for every row in the agent table.
 *
 * <p>At startup every stored agent gets its workspace and state directory materialized and a
 * live instance in the AG-UI registry. The REST layer reuses {@link #register} and
 * {@link #unregister} so a newly created agent is immediately chatable and a deleted one stops
 * serving — no restart involved.
 */
public class AgentProvisioner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentProvisioner.class);

    private final AgentStore agents;
    private final Model model;
    private final ReportActions reportActions;
    private final AguiAgentRegistry registry;
    private final ToolCatalog catalog;
    private final McpToolSource mcp;
    private final ObjectMapper json;
    private final SkillSync skillSync;
    private final io.deepscope.advisor.knowledge.KnowledgeSearch knowledgeSearch;
    private final io.deepscope.advisor.session.TimelineStore timeline;
    private final Path sharedWorkspaceDir;
    private final ToolCredentialStore credentials;
    private final HttpClient http = HttpClient.newBuilder().build();
    private final Map<String, List<McpClientWrapper>> connections = new ConcurrentHashMap<>();
    private volatile io.deepscope.advisor.tool.ToolRuntimeCoordinator coordinator;
    private io.deepscope.advisor.command.ResourceCommandMiddleware commandMiddleware;
    private io.deepscope.advisor.task.TaskBoardActions taskBoardActions;

    public record PreparedAgent(HarnessAgent harness, List<McpClientWrapper> connections) {}

    public void setCoordinator(io.deepscope.advisor.tool.ToolRuntimeCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void setCommandMiddleware(
            io.deepscope.advisor.command.ResourceCommandMiddleware middleware) {
        this.commandMiddleware = middleware;
    }

    public void setTaskBoardActions(io.deepscope.advisor.task.TaskBoardActions taskBoardActions) {
        this.taskBoardActions = taskBoardActions;
    }

    public AgentProvisioner(
            AgentStore agents,
            Model model,
            ReportActions reportActions,
            AguiAgentRegistry registry,
            ToolCatalog catalog,
            McpToolSource mcp,
            ObjectMapper json,
            SkillSync skillSync,
            io.deepscope.advisor.knowledge.KnowledgeSearch knowledgeSearch,
            io.deepscope.advisor.session.TimelineStore timeline) {
        this(
                agents,
                model,
                reportActions,
                registry,
                catalog,
                mcp,
                json,
                skillSync,
                knowledgeSearch,
                timeline,
                null,
                null);
    }

    public AgentProvisioner(
            AgentStore agents,
            Model model,
            ReportActions reportActions,
            AguiAgentRegistry registry,
            ToolCatalog catalog,
            McpToolSource mcp,
            ObjectMapper json,
            SkillSync skillSync,
            io.deepscope.advisor.knowledge.KnowledgeSearch knowledgeSearch,
            io.deepscope.advisor.session.TimelineStore timeline,
            Path sharedWorkspaceDir) {
        this(
                agents,
                model,
                reportActions,
                registry,
                catalog,
                mcp,
                json,
                skillSync,
                knowledgeSearch,
                timeline,
                sharedWorkspaceDir,
                null);
    }

    public AgentProvisioner(
            AgentStore agents,
            Model model,
            ReportActions reportActions,
            AguiAgentRegistry registry,
            ToolCatalog catalog,
            McpToolSource mcp,
            ObjectMapper json,
            SkillSync skillSync,
            io.deepscope.advisor.knowledge.KnowledgeSearch knowledgeSearch,
            io.deepscope.advisor.session.TimelineStore timeline,
            Path sharedWorkspaceDir,
            ToolCredentialStore credentials) {
        this.agents = agents;
        this.model = model;
        this.reportActions = reportActions;
        this.registry = registry;
        this.catalog = catalog;
        this.mcp = mcp;
        this.json = json;
        this.skillSync = skillSync;
        this.knowledgeSearch = knowledgeSearch;
        this.timeline = timeline;
        this.sharedWorkspaceDir = sharedWorkspaceDir;
        this.credentials = credentials;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int registered = 0;
        for (AgentStore.AgentRow agent : agents.list()) {
            register(agent);
            registered++;
        }
        log.info("Provisioned {} advisor agent(s) into the AG-UI registry", registered);
    }

    /**
     * Builds a new runtime version for one agent row on disk without modifying existing connections
     * or active registry bindings.
     */
    public PreparedAgent prepare(AgentStore.AgentRow agent) throws IOException, SQLException {
        if (!agent.enabled()) throw new IllegalStateException("Agent is disabled");
        Path workspaceDir = Path.of(agent.workspaceDir());
        Path stateDir = Path.of(agent.stateDir());
        Files.createDirectories(workspaceDir);
        Files.createDirectories(stateDir);
        seedPersonalWorkspace(agent, workspaceDir);
        // 本应用关闭了工作区上下文，所以在这里把定义文件念给模型；身份规则由 agentPrompt 压在最后兜底。
        String agentPrompt = AgentIdentity.agentPrompt(readBehavior(workspaceDir));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(reportActions);
        if (taskBoardActions != null) {
            toolkit.registerTool(taskBoardActions);
        }
        List<ToolCatalog.ToolRow> selected =
                catalog.effectiveTools(agent.agentId()).stream()
                        .filter(
                                tool ->
                                        java.util.Objects.equals(
                                                agent.ownerUserId(), tool.ownerUserId()))
                        .toList();
        List<McpClientWrapper> opened = attachSelected(agent.agentId(), toolkit, selected);
        HarnessAgent harness;
        try {
            var builder =
                    HarnessAgent.builder()
                            .agentId(agent.agentId())
                            .name(agent.name())
                            .description(agent.description())
                            // 产品身份必须落在 sysPrompt 上：本应用关闭了工作区上下文，AGENTS.md 不进提示词。
                            .sysPrompt(agentPrompt)
                            .model(model)
                            .enablePendingToolRecovery(true)
                            .disableFilesystemTools()
                            .disableShellTool()
                            .disableWebTools()
                            .disableDynamicSkills()
                            .disableDefaultWorkspaceSkills()
                            .disableMemoryTools()
                            .disableMemoryHooks()
                            .disableToolsConfig()
                            .disableWorkspaceContext()
                            .disableAtPathExpansion()
                            .middleware(
                                    new io.deepscope.advisor.model.SelectedModelMiddleware(model))
                            .middleware(new ReportPresentationMiddleware())
                            .compaction(
                                    io.deepscope.harness.agent.memory.compaction.CompactionConfig
                                            .builder()
                                            .triggerMessages(0)
                                            .triggerTokens(800_000)
                                            .build())
                            .toolkit(toolkit)
                            .workspace(workspaceDir)
                            .filesystem(
                                    new io.deepscope.harness.agent.filesystem.spec
                                                    .LocalFilesystemSpec()
                                            .project(workspaceDir)
                                            .mode(
                                                    io.deepscope.harness.agent.workspace.LocalFsMode
                                                            .ROOTED))
                            .stateStore(new JsonFileAgentStateStore(stateDir))
                            .permissionContext(ToolPermissions.context(selected));
            if (commandMiddleware != null) builder.middleware(commandMiddleware);
            harness = builder.build();
            skillSync.sync(agent);
        } catch (Exception ex) {
            opened.forEach(McpClientWrapper::close);
            throw ex;
        }
        return new PreparedAgent(harness, opened);
    }

    /**
     * Publishes a successfully prepared agent into the AG-UI registry and swaps active connections,
     * returning the superseded connections for safe retirement.
     */
    public List<McpClientWrapper> commit(String agentId, PreparedAgent prepared) {
        registry.register(agentId, prepared.harness());
        List<McpClientWrapper> previous;
        if (prepared.connections() != null && !prepared.connections().isEmpty()) {
            previous = connections.put(agentId, prepared.connections());
        } else {
            previous = connections.remove(agentId);
        }
        log.info("Registered advisor agent '{}'", agentId);
        return previous != null ? previous : List.of();
    }

    /**
     * Materializes one agent row on disk and (re-)registers its runtime instance.
     */
    public HarnessAgent register(AgentStore.AgentRow agent) throws IOException, SQLException {
        PreparedAgent prepared = prepare(agent);
        List<McpClientWrapper> previous = commit(agent.agentId(), prepared);
        if (coordinator != null) {
            coordinator.retireOrClose(agent.agentId(), previous);
        } else {
            previous.forEach(McpClientWrapper::close);
        }
        return prepared.harness();
    }

    /** Reads actual tool schemas from the registered runtime, rather than a display-only catalog. */
    public List<Map<String, String>> tools(String agentId) {
        return registry.getAgent(agentId)
                .filter(HarnessAgent.class::isInstance)
                .map(HarnessAgent.class::cast)
                .map(
                        agent ->
                                agent.getToolkit().getToolSchemas().stream()
                                        .map(
                                                tool ->
                                                        Map.of(
                                                                "name",
                                                                tool.getName(),
                                                                "description",
                                                                tool.getDescription() == null
                                                                        ? ""
                                                                        : tool.getDescription()))
                                        .toList())
                .orElse(List.of());
    }

    /** Removes a runtime agent from the AG-UI registry; the row itself is deleted by the caller. */
    public void unregister(String agentId) {
        closeConnections(agentId);
        registry.unregister(agentId);
    }

    /** Mounts only the tools this agent selected. Declared tools and unapproved tools are absent. */
    private List<McpClientWrapper> attachSelected(
            String agentId, Toolkit toolkit, List<ToolCatalog.ToolRow> selected)
            throws SQLException {
        Map<String, List<String>> mcpNames = new LinkedHashMap<>();
        for (ToolCatalog.ToolRow tool : selected) {
            if (ToolCatalog.ORIGIN_HTTP.equals(tool.origin())) {
                Supplier<Map<String, Object>> credentialResolver =
                        () -> {
                            String credId = ToolAuth.credentialId(tool.configJson());
                            if (credId == null || credentials == null) return null;
                            // Never fall back to an anonymous request: a credential that cannot be
                            // read must fail the tool call with an actionable error instead of
                            // letting the remote service answer 401.
                            try {
                                return credentials.read(tool.ownerUserId(), credId);
                            } catch (ToolProblem error) {
                                throw error;
                            } catch (Exception error) {
                                throw new ToolProblem(
                                        422,
                                        "credential_unreadable",
                                        "The saved credential cannot be read; enter it again");
                            }
                        };
                Runnable preflight = () -> new CatalogExecutionGuard(catalog).check(tool.toolId());
                toolkit.registerAgentTool(
                        HttpCatalogTool.create(
                                tool,
                                json,
                                new ToolHttpExecutor(json, http, Duration.ofSeconds(30)),
                                credentialResolver,
                                preflight));
            } else if (ToolCatalog.ORIGIN_MCP.equals(tool.origin()) && tool.mcpServerId() != null) {
                mcpNames.computeIfAbsent(tool.mcpServerId(), ignored -> new ArrayList<>())
                        .add(tool.name());
            }
        }
        List<McpClientWrapper> opened = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : mcpNames.entrySet()) {
            ToolCatalog.ServerRow server = catalog.findServer(entry.getKey()).orElse(null);
            if (server == null
                    || !List.of("connected", "error").contains(server.status())
                    || selected.stream()
                            .filter(tool -> entry.getKey().equals(tool.mcpServerId()))
                            .anyMatch(
                                    tool ->
                                            !java.util.Objects.equals(
                                                    tool.ownerUserId(), server.ownerUserId()))) {
                continue;
            }
            try {
                McpClientWrapper client = mcp.open(catalog.endpointOf(server));
                toolkit.registration().mcpClient(client).enableTools(entry.getValue()).apply();
                for (var tool : selected) {
                    if (!entry.getKey().equals(tool.mcpServerId())) continue;
                    var registered = toolkit.getTool(tool.name());
                    if (registered != null)
                        toolkit.registerAgentTool(
                                new io.deepscope.advisor.tool.GuardedCatalogTool(
                                        registered,
                                        () -> {
                                            var current =
                                                    new CatalogExecutionGuard(catalog)
                                                            .check(agentId, tool.toolId());
                                            if ("ask".equals(current.permission())
                                                    && !"ask".equals(tool.permission()))
                                                throw new io.deepscope.advisor.tool.ToolProblem(
                                                        409,
                                                        "permission_changed",
                                                        "Tool permission changed; start a new turn"
                                                                + " for confirmation");
                                        },
                                        tool.permission()));
                }
                opened.add(client);
                catalog.markServer(server.serverId(), "connected", "");
            } catch (RuntimeException | java.io.IOException error) {
                catalog.markServer(server.serverId(), "error", error.getMessage());
            } catch (Exception error) {
                catalog.markServer(server.serverId(), "error", error.getMessage());
            }
        }
        if (knowledgeSearch != null && !knowledgeSearch.scope(agentId).isEmpty()) {
            toolkit.registerAgentTool(
                    new io.deepscope.advisor.knowledge.KnowledgeRetrievalTool(
                            agentId, knowledgeSearch, timeline));
        }
        return opened;
    }

    private void closeConnections(String agentId) {
        List<McpClientWrapper> previous = connections.remove(agentId);
        if (previous != null) {
            if (coordinator != null) {
                coordinator.retireOrClose(agentId, previous);
            } else {
                previous.forEach(McpClientWrapper::close);
            }
        }
    }

    /** Reads the agent definition that becomes the system prompt; a missing file is not fatal. */
    private static String readBehavior(Path workspaceDir) {
        Path agentsMarkdown = workspaceDir.resolve("AGENTS.md");
        if (!Files.isRegularFile(agentsMarkdown)) return "";
        try {
            return Files.readString(agentsMarkdown, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            log.warn(
                    "Agent definition is unreadable, falling back to identity only: {}",
                    unreadable.getMessage());
            return "";
        }
    }

    /** Writes the minimal behavior file of a personal agent, only when it does not exist yet. */
    private static void seedPersonalWorkspace(AgentStore.AgentRow agent, Path workspaceDir)
            throws IOException {
        Path agentsMarkdown = workspaceDir.resolve("AGENTS.md");
        if (Files.exists(agentsMarkdown)) {
            // 存量定义只补齐 DeepScope 产品身份，不改用户已经写好的正文（幂等）。
            AgentIdentity.topUp(agentsMarkdown);
            return;
        }
        String description = agent.description().isBlank() ? "" : agent.description() + "\n\n";
        Files.writeString(
                agentsMarkdown,
                AgentIdentity.withIdentity(
                        "# "
                                + agent.name()
                                + "\n\n"
                                + description
                                + "你是 DeepScope 中的智能体。收到输入后按问题复杂度高效响应：\n"
                                + "- 日常问候、身份咨询或无需查阅资料即可直接回答的简单问题：直接用正文快速回复，不要调用 report_* 或"
                                + " retrieve_knowledge 等工具。\n"
                                + "- 需要检索知识库或深入调查的技术问题：需要调用工具时尽量在同一轮并行发起（例如同时调用 report_route"
                                + " 与首次检索工具），减少多轮串行等待；关键判断通过对应 report_* 工具向访客汇报，最终回答直接输出正文，"
                                + "说明结论与来源，不生成内部引用编号，不增加交付或归档步骤。\n"),
                StandardCharsets.UTF_8);
    }
}
