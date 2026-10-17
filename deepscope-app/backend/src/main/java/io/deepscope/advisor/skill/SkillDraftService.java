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
package io.deepscope.advisor.skill;

import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.MsgRole;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.model.Model;
import io.deepscope.harness.agent.context.ContextModelCalls;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the configured model to write a skill file from a name and a short brief.
 *
 * <p>An empty reply or a failed call returns a blank string. The caller does not store a made-up
 * skill in that case.
 */
public final class SkillDraftService {

    private static final Logger log = LoggerFactory.getLogger(SkillDraftService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    private final Model model;

    public SkillDraftService(Model model) {
        this.model = model;
    }

    public String draft(String name, String brief) {
        String title = name == null ? "" : name.strip();
        String intent = brief == null ? "" : brief.strip();
        if (intent.isBlank()) {
            return "";
        }
        try {
            if (title.isBlank()) {
                Named named = splitName(clean(ask(unnamedMessages(intent))));
                if (named == null) {
                    return "";
                }
                return file(named.name(), intent, named.body());
            }
            return file(title, intent, clean(ask(messages(title, intent))));
        } catch (RuntimeException error) {
            log.warn("Skill draft failed: {}", error.getMessage());
            return "";
        }
    }

    /** A short name for a brief. A failed call returns a blank string and does not invent one. */
    public String suggestName(String brief) {
        String intent = brief == null ? "" : brief.strip();
        if (intent.isBlank()) {
            return "";
        }
        try {
            return nameLine(clean(ask(suggestMessages(intent))));
        } catch (RuntimeException error) {
            log.warn("Skill name suggestion failed: {}", error.getMessage());
            return "";
        }
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

    static String file(String name, String brief, String body) {
        if (body.isBlank()) {
            return "";
        }
        String markdown =
                "---\nname: "
                        + quote(name)
                        + "\ndescription: "
                        + quote(brief)
                        + "\n---\n\n"
                        + body
                        + "\n";
        try {
            SkillPackage.Loaded loaded =
                    SkillPackage.load("skill.md", markdown.getBytes(StandardCharsets.UTF_8));
            if (loaded.name() == null
                    || loaded.name().isBlank()
                    || loaded.description() == null
                    || loaded.description().isBlank()) {
                return "";
            }
            return markdown;
        } catch (RuntimeException error) {
            log.warn("Skill draft was not a readable skill file: {}", error.getMessage());
            return "";
        }
    }

    static Named splitName(String text) {
        String stripped = text == null ? "" : text.strip();
        int breakAt = stripped.indexOf('\n');
        if (breakAt <= 0) {
            return null;
        }
        String title = nameLine(stripped.substring(0, breakAt));
        String body = stripped.substring(breakAt + 1).strip();
        if (title.isBlank() || body.isBlank()) {
            return null;
        }
        return new Named(title, body);
    }

    static String nameLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String line = text.strip().lines().findFirst().orElse("").strip();
        line = line.replaceFirst("^名称[:：]\\s*", "");
        line = line.replaceFirst("^#+\\s*", "");
        return line.replaceAll("^[\"'`]+|[\"'`]+$", "").strip();
    }

    private static List<Msg> unnamedMessages(String brief) {
        return List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "你负责撰写技能。第一行只写技能名称。空一行后写正文：什么情况下启用、按什么步骤做、做到什么程度可以停。"
                                                    + "不要前置信息，不要代码围栏，不要解释，不要编造不存在的工具或事实。")
                                        .build())
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("用途：" + brief).build())
                        .build());
    }

    private static List<Msg> suggestMessages(String brief) {
        return List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(
                                TextBlock.builder()
                                        .text("根据用途起一个简短的技能名称。只输出名称本身，不要解释，不要标点包裹。")
                                        .build())
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("用途：" + brief).build())
                        .build());
    }

    private static List<Msg> messages(String name, String brief) {
        return List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "你负责撰写技能正文。根据名称和用途写出：什么情况下启用、按什么步骤做、做到什么程度可以停。"
                                                        + "只输出正文，不要前置信息，不要代码围栏，不要解释，不要编造不存在的工具或事实。")
                                        .build())
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("名称：" + name + "\n用途：" + brief).build())
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

    record Named(String name, String body) {}
}
