/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deepscope.advisor.command;

import io.deepscope.advisor.skill.SkillCatalog;
import io.deepscope.advisor.tool.ToolCatalog;
import io.deepscope.core.agent.Agent;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.event.AgentEvent;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.MsgRole;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.middleware.AgentInput;
import io.deepscope.core.middleware.MiddlewareBase;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.regex.Pattern;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Resolves explicit resource commands at the authenticated chat boundary. */
public final class ResourceCommandMiddleware implements MiddlewareBase {
    private static final Pattern COMMAND = Pattern.compile("(?:^|\\s)/([a-zA-Z0-9_-]+)(?=\\s|$)");
    private final CommandCatalog commands;
    private final ToolCatalog tools;
    private final SkillCatalog skills;

    public ResourceCommandMiddleware(
            CommandCatalog commands, ToolCatalog tools, SkillCatalog skills) {
        this.commands = commands;
        this.tools = tools;
        this.skills = skills;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                        () -> {
                            try {
                                return next.apply(resolve(agent, context, input));
                            } catch (java.sql.SQLException error) {
                                return Flux.error(
                                        new IllegalStateException(
                                                "resource_catalog_unavailable", error));
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private AgentInput resolve(Agent agent, RuntimeContext context, AgentInput input)
            throws java.sql.SQLException {
        if (input == null || input.msgs() == null) return input;
        for (int index = input.msgs().size() - 1; index >= 0; index--) {
            var message = input.msgs().get(index);
            if (message.getRole() != MsgRole.USER) continue;
            String text = message.getTextContent();
            if (text == null) return input;
            var match = COMMAND.matcher(text);
            while (match.find()) {
                String code = match.group(1);
                String owner = context == null ? null : context.getUserId();
                var command = commands.findAvailableByCode(code, owner);
                if (command.isEmpty()) {
                    var stored = commands.findByCode(code);
                    if (code.startsWith("tool-")
                            || code.startsWith("mcp-")
                            || code.startsWith("skill-")
                            || stored.filter(
                                            row ->
                                                    ResourceCommandCatalog.resourceType(
                                                            row.targetType()))
                                    .isPresent())
                        throw new IllegalStateException("resource_command_unavailable");
                    continue;
                }
                var resource = command.get();
                if (!ResourceCommandCatalog.resourceType(resource.targetType())) continue;
                String name = selectedName(resource, owner, agent.getAgentId());
                String request =
                        (text.substring(0, match.start()) + text.substring(match.end())).strip();
                String instruction =
                        CommandCatalog.TARGET_SKILL.equals(resource.targetType())
                                ? "Use the published skill named "
                                        + name
                                        + " through the existing skill loader."
                                : "Use the registered tool named "
                                        + name
                                        + " through the normal tool calling flow. Keep all"
                                        + " permission and confirmation checks. Ask the user for"
                                        + " any missing required arguments.";
                var blocks = new ArrayList<ContentBlock>();
                blocks.add(
                        TextBlock.builder()
                                .text(instruction + "\nUser request: " + request)
                                .build());
                message.getContent().stream()
                        .filter(block -> !(block instanceof TextBlock))
                        .forEach(blocks::add);
                var messages = new ArrayList<>(input.msgs());
                messages.set(index, message.withContent(blocks));
                return new AgentInput(messages);
            }
            return input;
        }
        return input;
    }

    private String selectedName(CommandCatalog.CommandRow command, String owner, String agentId)
            throws java.sql.SQLException {
        if (CommandCatalog.TARGET_SKILL.equals(command.targetType())) {
            return skills.effective(agentId, owner).stream()
                    .filter(row -> row.skillId().equals(command.targetId()))
                    .map(SkillCatalog.SkillRow::name)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("resource_command_not_bound"));
        }
        var selected = tools.effectiveTools(agentId);
        String name =
                selected.stream()
                        .filter(
                                row ->
                                        row.toolId().equals(command.targetId())
                                                && row.ownerUserId().equals(owner))
                        .map(ToolCatalog.ToolRow::name)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("resource_command_not_bound"));
        if (selected.stream().filter(row -> name.equals(row.name())).count() > 1)
            throw new IllegalStateException("resource_command_ambiguous");
        return name;
    }
}
