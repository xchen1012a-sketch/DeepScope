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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Product identity lives in one place: every agent runs inside DeepScope.
 *
 * <p>The workspace {@code AGENTS.md} is user-editable, so topping up only ever prepends the
 * identity line, never rewrites the body a user wrote, and writes nothing when the marker is
 * already present.
 */
final class AgentIdentity {

    /** Marker used for idempotence; keep it stable or existing files get topped up twice. */
    static final String MARKER = "身份：我是 DeepScope";

    static final String IDENTITY_LINE =
            "> 身份：我是 DeepScope。被问到身份时先这样回答，再按需要补充你的职责名称；不要自称“技术助理”“技术支持”或其它产品名。";

    /**
     * Agent system prompt. The app turns workspace context off, so {@code AGENTS.md} never reaches
     * the model; the identity therefore has to travel on the system prompt itself.
     */
    static final String SYSTEM_PREAMBLE =
            "你的产品身份是 DeepScope。被问到“你是谁”“你是什么”这类身份问题时，第一句必须回答“我是 DeepScope”，"
                    + "然后才可以补充自己的职责；任何时候都不得自称“技术助手”“技术助理”“技术支持”，"
                    + "也不得为产品使用其它名称。";

    static final String BASIC_CHAT_IDENTITY =
            "You are DeepScope. When asked who you are, answer that you are DeepScope first, then"
                    + " your duty if needed; never call yourself a \"technical assistant\" or"
                    + " another product name.";

    static final String PREVIEW_FALLBACK_BEHAVIOR =
            "你是 DeepScope 中的智能体。被问到身份时先回答“我是 DeepScope”，再按需要补充你的职责；"
                    + "不要自称“技术助理”或“技术支持”。请简明专业地回答问题。";

    /** Prompt constraint for model-drafted documents so they never rename the product. */
    static final String DRAFTING_RULE =
            "身份约定：这份定义属于 DeepScope 中的智能体，被问到身份时先回答“我是 DeepScope”，不要给产品另起名字，也不要自称技术助理或技术支持。";

    /** Upper bound for one agent's definition; the file is user-editable and goes straight in. */
    static final int MAX_BEHAVIOR_CHARS = 20_000;

    private AgentIdentity() {}

    /**
     * Composes the agent system prompt: the agent's own {@code AGENTS.md} first, the product
     * identity last. The workspace context middleware stays off in this app, so reading the
     * definition here is what makes {@code AGENTS.md} adjustable at runtime; keeping the identity
     * after it means a definition can never talk the agent out of being DeepScope.
     */
    static String agentPrompt(String behavior) {
        String body = behavior == null ? "" : behavior.strip();
        if (body.length() > MAX_BEHAVIOR_CHARS) {
            body = body.substring(0, MAX_BEHAVIOR_CHARS) + "\n\n（说明书过长，已截断）";
        }
        if (body.isBlank()) return SYSTEM_PREAMBLE;
        return body + "\n\n" + SYSTEM_PREAMBLE;
    }

    static boolean hasIdentity(String content) {
        return content != null && content.contains(MARKER);
    }

    static String withIdentity(String content) {
        if (hasIdentity(content)) return content;
        String body = content == null ? "" : content;
        if (body.isBlank()) return IDENTITY_LINE + "\n";
        return IDENTITY_LINE + "\n\n" + body;
    }

    /** Idempotently tops up one workspace definition; missing files are created. */
    static void topUp(Path agentsMarkdown) throws IOException {
        if (agentsMarkdown == null) return;
        String current =
                Files.exists(agentsMarkdown)
                        ? Files.readString(agentsMarkdown, StandardCharsets.UTF_8)
                        : null;
        if (hasIdentity(current)) return;
        Path parent = agentsMarkdown.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(agentsMarkdown, withIdentity(current), StandardCharsets.UTF_8);
    }
}
