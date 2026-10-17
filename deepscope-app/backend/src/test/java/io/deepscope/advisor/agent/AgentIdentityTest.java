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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The product identity is DeepScope; topping up never rewrites what a user already wrote. */
class AgentIdentityTest {
    @TempDir Path root;

    @Test
    void prependsTheIdentityWithoutTouchingTheUserBody() {
        String body = "# 代码测试智能体\n\n你只处理与代码测试相关的问题。\n";

        String toppedUp = AgentIdentity.withIdentity(body);

        assertTrue(toppedUp.startsWith(AgentIdentity.IDENTITY_LINE));
        assertTrue(toppedUp.endsWith(body));
        assertTrue(AgentIdentity.hasIdentity(toppedUp));
    }

    @Test
    void toppingUpTwiceChangesNothing() {
        String once = AgentIdentity.withIdentity("# 智能体\n正文\n");

        assertEquals(once, AgentIdentity.withIdentity(once));
    }

    @Test
    void blankContentStillGetsTheIdentity() {
        assertTrue(AgentIdentity.hasIdentity(AgentIdentity.withIdentity("")));
        assertTrue(AgentIdentity.hasIdentity(AgentIdentity.withIdentity("   \n")));
    }

    @Test
    void topUpWritesOnlyWhenTheMarkerIsMissing() throws Exception {
        Path file = root.resolve("AGENTS.md");
        String custom = "# 我自己的智能体\n\n这是我的正文。\n";
        Files.writeString(file, custom, StandardCharsets.UTF_8);

        AgentIdentity.topUp(file);
        String afterFirst = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(afterFirst.startsWith(AgentIdentity.IDENTITY_LINE));
        assertTrue(afterFirst.contains("这是我的正文。"));

        // 第二次补齐幂等：内容与 mtime 都不再变化
        long modified = Files.getLastModifiedTime(file).toMillis();
        AgentIdentity.topUp(file);
        assertEquals(afterFirst, Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(modified, Files.getLastModifiedTime(file).toMillis());
    }

    @Test
    void topUpCreatesTheFileWhenItIsMissing() throws Exception {
        Path file = root.resolve("nested").resolve("AGENTS.md");

        AgentIdentity.topUp(file);

        assertTrue(Files.exists(file));
        assertTrue(AgentIdentity.hasIdentity(Files.readString(file, StandardCharsets.UTF_8)));
    }

    @Test
    void agentPromptFeedsTheDefinitionAndKeepsTheIdentityLast() {
        String behavior = "# 合同审查助手\n你只做合同审查，不要越界。\n";

        String prompt = AgentIdentity.agentPrompt(behavior);

        assertTrue(prompt.startsWith(behavior.strip()));
        assertTrue(prompt.endsWith(AgentIdentity.SYSTEM_PREAMBLE));
        assertTrue(prompt.indexOf(AgentIdentity.SYSTEM_PREAMBLE) > prompt.indexOf("你只做合同审查"));
        // 说明书里即使写了别的名字，也压不掉最后那条身份规则
        assertTrue(AgentIdentity.agentPrompt("你是技术助手。").endsWith(AgentIdentity.SYSTEM_PREAMBLE));
    }

    @Test
    void agentPromptWithoutADefinitionIsJustTheIdentity() {
        assertEquals(AgentIdentity.SYSTEM_PREAMBLE, AgentIdentity.agentPrompt(null));
        assertEquals(AgentIdentity.SYSTEM_PREAMBLE, AgentIdentity.agentPrompt("   \n"));
    }

    @Test
    void agentPromptTruncatesAnOverlongDefinition() {
        String huge = "长".repeat(AgentIdentity.MAX_BEHAVIOR_CHARS + 500);

        String prompt = AgentIdentity.agentPrompt(huge);

        assertTrue(prompt.length() < huge.length());
        assertTrue(prompt.contains("已截断"));
        assertTrue(prompt.endsWith(AgentIdentity.SYSTEM_PREAMBLE));
    }

    @Test
    void identityLinesNameTheProductAndForbidTheOldAliases() {
        assertTrue(AgentIdentity.IDENTITY_LINE.contains("DeepScope"));
        assertTrue(AgentIdentity.BASIC_CHAT_IDENTITY.contains("DeepScope"));
        assertTrue(AgentIdentity.PREVIEW_FALLBACK_BEHAVIOR.contains("DeepScope"));
        assertFalse(AgentIdentity.PREVIEW_FALLBACK_BEHAVIOR.contains("技术支持助手"));
    }

    @Test
    void systemPreambleContainsAntiHallucinationAndSummaryRules() {
        String preamble = AgentIdentity.SYSTEM_PREAMBLE;
        assertTrue(preamble.contains("简单问题直接回答"));
        assertTrue(preamble.contains("严禁调用任何外部工具或检索工具"));
        assertTrue(preamble.contains("总结意图就近原则"));
        assertTrue(preamble.contains("默认就近总结上一条助手回答"));
        assertTrue(preamble.contains("回答开头必须先用一句话明确声明本次总结的范围"));
    }
}
