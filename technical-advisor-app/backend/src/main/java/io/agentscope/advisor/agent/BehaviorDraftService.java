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
package io.agentscope.advisor.agent;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.context.ContextModelCalls;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the model to write a behavior document from the agent's name and description.
 *
 * <p>An empty model reply or a failed call returns a blank string. The caller keeps the existing
 * document in that case instead of storing a made-up one.
 */
public final class BehaviorDraftService {

    private static final Logger log = LoggerFactory.getLogger(BehaviorDraftService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final Model model;

    public BehaviorDraftService(Model model) {
        this.model = model;
    }

    public record Candidate(String name, String description, String behavior) {}

    public Candidate generateCandidate(String purpose, String currentName, String currentDesc) {
        String p = purpose == null ? "" : purpose.strip();
        String n = currentName == null ? "" : currentName.strip();
        String d = currentDesc == null ? "" : currentDesc.strip();

        String text =
                ContextModelCalls.auxiliary(model, candidateMessages(p, n, d))
                        .reduce(
                                new StringBuilder(),
                                (buffer, response) -> {
                                    List<ContentBlock> blocks = response.getContent();
                                    if (blocks != null) {
                                        for (ContentBlock block : blocks) {
                                            if (block instanceof TextBlock written
                                                    && written.getText() != null) {
                                                buffer.append(written.getText());
                                            }
                                        }
                                    }
                                    return buffer;
                                })
                        .map(StringBuilder::toString)
                        .block(TIMEOUT);

        if (text == null || text.isBlank()) {
            throw new IllegalStateException("empty_response");
        }
        return parseCandidate(text, n, d);
    }

    public String draft(String name, String description) {
        String brief = description == null ? "" : description.strip();
        String title = name == null ? "" : name.strip();
        if (title.isBlank() && brief.isBlank()) {
            return "";
        }
        try {
            String text =
                    ContextModelCalls.auxiliary(model, messages(title, brief))
                            .reduce(
                                    new StringBuilder(),
                                    (buffer, response) -> {
                                        List<ContentBlock> blocks = response.getContent();
                                        if (blocks != null) {
                                            for (ContentBlock block : blocks) {
                                                if (block instanceof TextBlock written
                                                        && written.getText() != null) {
                                                    buffer.append(written.getText());
                                                }
                                            }
                                        }
                                        return buffer;
                                    })
                            .map(StringBuilder::toString)
                            .block(TIMEOUT);
            return clean(text);
        } catch (RuntimeException error) {
            log.warn("Behavior draft failed: {}", error.getMessage());
            return "";
        }
    }

    private static List<Msg> messages(String name, String description) {
        return List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "你负责撰写智能体的行为文档。根据名称和简介写出它如何判断、何时自己做、何时派子智能体、"
                                                        + "结论如何交代依据。只输出文档正文，不要解释，不要编造不存在的事实或工具。\n"
                                                        + AgentIdentity.DRAFTING_RULE)
                                        .build())
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("名称：" + name + "\n简介：" + description)
                                        .build())
                        .build());
    }

    private static List<Msg> candidateMessages(
            String purpose, String currentName, String currentDescription) {
        return List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "你负责紧扣智能体的设定用途，为其撰写专业的工作要求与系统提示词。请明确定义智能体的角色定位、核心执行逻辑、输出规范及边界约束，严格围绕设定用途展开，不要编造不存在的事实或工具。\n"
                                                        + AgentIdentity.DRAFTING_RULE
                                                        + "\n"
                                                        + "请按以下格式输出：\n"
                                                        + "建议名称：<根据用途提炼的专业名称>\n"
                                                        + "建议简介：<概括核心职责的一句话简介>\n"
                                                        + "工作要求：<系统提示词正文，包含角色职责、执行准则与输出要求>\n")
                                        .build())
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "设定用途："
                                                        + purpose
                                                        + "\n当前名称："
                                                        + currentName
                                                        + "\n当前简介："
                                                        + currentDescription)
                                        .build())
                        .build());
    }

    static Candidate parseCandidate(String rawText, String currentName, String currentDesc) {
        String cleaned = clean(rawText);
        String candName = "";
        String candDesc = "";
        StringBuilder candBehavior = new StringBuilder();
        boolean inBehavior = false;

        for (String line : cleaned.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("建议名称：")
                    || trimmed.startsWith("建议名称:")
                    || trimmed.startsWith("名称：")
                    || trimmed.startsWith("名称:")) {
                candName = trimmed.replaceFirst("^(建议名称|名称)[：:]\\s*", "").trim();
            } else if (trimmed.startsWith("建议简介：")
                    || trimmed.startsWith("建议简介:")
                    || trimmed.startsWith("简介：")
                    || trimmed.startsWith("简介:")) {
                candDesc = trimmed.replaceFirst("^(建议简介|简介)[：:]\\s*", "").trim();
            } else if (trimmed.startsWith("工作要求：")
                    || trimmed.startsWith("工作要求:")
                    || trimmed.startsWith("行为规范：")
                    || trimmed.startsWith("行为规范:")
                    || trimmed.startsWith("行为要求：")
                    || trimmed.startsWith("行为要求:")
                    || trimmed.startsWith("行为：")
                    || trimmed.startsWith("行为:")) {
                inBehavior = true;
                String rest = trimmed.replaceFirst("^(工作要求|行为规范|行为要求|行为)[：:]\\s*", "").trim();
                if (!rest.isEmpty()) {
                    candBehavior.append(rest);
                }
            } else if (inBehavior) {
                if (!candBehavior.isEmpty()) {
                    candBehavior.append("\n");
                }
                candBehavior.append(line);
            }
        }

        if (candName.isEmpty()) {
            candName = currentName.isBlank() ? "智能体助手" : currentName;
        }
        if (candDesc.isEmpty()) {
            candDesc = currentDesc;
        }
        String behaviorStr = candBehavior.toString().trim();
        if (behaviorStr.isEmpty()) {
            behaviorStr = cleaned;
        }

        return new Candidate(candName, candDesc, behaviorStr);
    }

    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        if (stripped.startsWith("```")) {
            int firstBreak = stripped.indexOf('\n');
            int lastFence = stripped.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                stripped = stripped.substring(firstBreak + 1, lastFence).strip();
            }
        }
        return stripped;
    }
}
