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
import io.deepscope.advisor.AdvisorProperties;
import io.deepscope.advisor.model.SelectedModelMiddleware;
import io.deepscope.advisor.session.SessionStore;
import io.deepscope.advisor.session.TimelineStore;
import io.deepscope.core.agui.registry.AguiAgentRegistry;
import io.deepscope.core.model.Model;
import io.deepscope.core.state.JsonFileAgentStateStore;
import io.deepscope.core.tool.Toolkit;
import io.deepscope.harness.agent.HarnessAgent;
import io.deepscope.harness.agent.context.ContextBlock;
import io.deepscope.harness.agent.context.ContextRequest;
import io.deepscope.harness.agent.memory.compaction.CompactionConfig;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Tool-free conversation transport; never creates a managed agent definition. */
public final class BasicChatRuntime {
    public static final String AGENT_ID = "basic-chat";
    private final SessionStore sessions;
    private final TimelineStore timeline;
    private final ObjectMapper json;

    public BasicChatRuntime(
            AdvisorProperties properties,
            Model model,
            SessionStore sessions,
            TimelineStore timeline,
            ObjectMapper json,
            AguiAgentRegistry registry) {
        this(properties, model, sessions, timeline, json, registry, null);
    }

    public BasicChatRuntime(
            AdvisorProperties properties,
            Model model,
            SessionStore sessions,
            TimelineStore timeline,
            ObjectMapper json,
            AguiAgentRegistry registry,
            io.deepscope.advisor.command.ResourceCommandMiddleware commands) {
        this.sessions = sessions;
        this.timeline = timeline;
        this.json = json;
        var root = properties.dataDir().resolve(AGENT_ID);
        var builder =
                HarnessAgent.builder()
                        .agentId(AGENT_ID)
                        .name("Basic chat")
                        .sysPrompt(
                                AgentIdentity.BASIC_CHAT_IDENTITY
                                        + " Answer the user's question in their language. You have"
                                        + " no tools, knowledge libraries or subagents. Do not"
                                        + " claim to execute actions.")
                        .model(model)
                        .enablePendingToolRecovery(true)
                        .middleware(new SelectedModelMiddleware(model))
                        .toolkit(new Toolkit())
                        .enableMetaTool(false)
                        .enableTaskList(false)
                        .disableFilesystemTools()
                        .disableShellTool()
                        .disableWebTools()
                        .disableMemoryTools()
                        .disableMemoryHooks()
                        .disableWorkspaceContext()
                        .disableAtPathExpansion()
                        .disableSubagents()
                        .disableDynamicSubagents()
                        .disableDynamicSkills()
                        .disableDefaultWorkspaceSkills()
                        .skillsEnabled(false)
                        .disableToolsConfig()
                        .workspace(root.resolve("workspace"))
                        .stateStore(new JsonFileAgentStateStore(root.resolve("state")))
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(0)
                                        .triggerTokens(800_000)
                                        .build())
                        .contextSource(
                                "prior-conversation",
                                request ->
                                        Mono.fromCallable(() -> history(request))
                                                .subscribeOn(Schedulers.boundedElastic()));
        if (commands != null) builder.middleware(commands);
        var agent = builder.build();
        // Harness may add infrastructure tools (for example wait_async_results) at build time.
        // This transport exposes no actions, including those automatic registrations.
        agent.getToolkit()
                .getToolSchemas()
                .forEach(tool -> agent.getToolkit().removeTool(tool.getName()));
        registry.register(AGENT_ID, agent);
    }

    /** Earlier visible conversation remains data, separately budgeted from native basic history. */
    private List<ContextBlock> history(ContextRequest request) throws Exception {
        var session = sessions.find(request.sessionId());
        if (session.isEmpty() || !session.get().ownerUserId().equals(request.userId()))
            return List.of();
        var entries = timeline.listEntries(request.sessionId());
        int boundaryTurn = Integer.MAX_VALUE;
        for (var entry : entries) {
            if (entry.kind().equals("agent_route")
                    && "basic".equals(json.readTree(entry.payload()).path("mode").asText())) {
                boundaryTurn = entry.turn();
                break;
            }
        }
        // No route authorization means no history may be added via this transport.
        if (boundaryTurn == Integer.MAX_VALUE) return List.of();
        var messages = new ArrayList<Object>();
        for (var entry : entries) {
            if (entry.turn() >= boundaryTurn) break;
            if (!entry.kind().equals(TimelineStore.USER_MESSAGE)
                    && !entry.kind().equals(TimelineStore.ADVISOR_MESSAGE)) continue;
            String text = json.readTree(entry.payload()).path("text").asText();
            if (!text.isBlank())
                messages.add(
                        java.util.Map.of(
                                "role",
                                entry.kind().equals(TimelineStore.USER_MESSAGE)
                                        ? "user"
                                        : "assistant",
                                "content",
                                text));
        }
        return messages.isEmpty()
                ? List.of()
                : List.of(
                        ContextBlock.reference(
                                "prior-conversation", json.writeValueAsString(messages)));
    }
}
