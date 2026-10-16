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
 * Asks the configured model to write a subagent definition from its name and an optional purpose.
 *
 * <p>The declaration is composed here so the front matter is always valid: the harness derives the
 * name from the file name, so only {@code description} is written. An empty reply, a missing name
 * or a failed call returns a blank string, and the caller then keeps the editor as it is.
 */
public final class SubagentDraftService {

    private static final Logger log = LoggerFactory.getLogger(SubagentDraftService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String DESCRIPTION_PREFIX = "描述：";
    private static final String BODY_PREFIX = "正文：";

    private final Model model;

    public SubagentDraftService(Model model) {
        this.model = model;
    }

    public String draft(String name, String purpose) {
        String title = name == null ? "" : name.strip();
        if (title.isBlank()) {
            return "";
        }
        String intent = purpose == null ? "" : purpose.strip();
        try {
            String text = clean(ask(messages(title, intent)));
            if (text.isBlank()) {
                return "";
            }
            String description = described(text);
            if (description.isBlank()) {
                description = intent.isBlank() ? title : intent;
            }
            String body = body(text);
            return definition(description, body.isBlank() ? text : body);
        } catch (RuntimeException error) {
            log.warn("Subagent draft failed: {}", error.getMessage());
            return "";
        }
    }

    static String definition(String description, String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String summary =
                description == null || description.isBlank() ? "专项分析子智能体" : description.strip();
        return "---\ndescription: " + quote(summary) + "\n---\n\n" + body.strip() + "\n";
    }

    private static String described(String text) {
        for (String line : text.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.startsWith(DESCRIPTION_PREFIX)) {
                return trimmed.substring(DESCRIPTION_PREFIX.length()).strip();
            }
        }
        return "";
    }

    private static String body(String text) {
        List<String> lines = text.lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            if (!lines.get(index).strip().startsWith(BODY_PREFIX)) continue;
            StringBuilder rest = new StringBuilder();
            String first = lines.get(index).strip().substring(BODY_PREFIX.length()).strip();
            if (!first.isEmpty()) rest.append(first);
            for (int next = index + 1; next < lines.size(); next++) {
                if (!rest.isEmpty()) rest.append('\n');
                rest.append(lines.get(next));
            }
            return rest.toString().strip();
        }
        return "";
    }

    private String ask(List<Msg> messages) {
        String text =
                ContextModelCalls.auxiliary(model, messages)
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
        return text == null ? "" : text;
    }

    private static List<Msg> messages(String name, String purpose) {
        return List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "你负责撰写子智能体的定义文件正文，用于主智能体在运行时派发子任务。"
                                                        + "正文包含角色定位、工作流程与输出规范，写清何时自己做、何时上报，"
                                                        + "只输出定义正文，不要编造不存在的事实或工具。\n"
                                                        + AgentIdentity.DRAFTING_RULE
                                                        + "\n"
                                                        + "请严格按以下格式输出：\n"
                                                        + "描述：<一句话说明这个子智能体处理什么调查>\n"
                                                        + "正文：\n<定义正文 Markdown>")
                                        .build())
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "子智能体名称："
                                                        + name
                                                        + "\n用途说明："
                                                        + (purpose.isBlank()
                                                                ? "（未填写，请按名称推断）"
                                                                : purpose))
                                        .build())
                        .build());
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

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
