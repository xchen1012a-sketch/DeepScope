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
package io.agentscope.advisor.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** A skill draft is the model's own text wrapped as a skill file. A failed call stores nothing. */
class SkillDraftServiceTest {

    @Test
    void wrapsTheModelTextAsASkillFile() {
        String body = "先看合同范围，再标出没有依据的承诺。";
        var model = new ReplyModel("```markdown\n" + body + "\n```");
        String markdown = new SkillDraftService(model).draft("合同审查", "审查合同风险时使用");
        SkillPackage.Loaded loaded =
                SkillPackage.load("skill.md", markdown.getBytes(StandardCharsets.UTF_8));
        assertEquals("合同审查", loaded.name());
        assertEquals("审查合同风险时使用", loaded.description());
        assertTrue(loaded.markdown().contains(body));
        assertEquals(1, model.calls.get());
    }

    @Test
    void aFailedCallStaysBlank() {
        var model = new ReplyModel(null);
        assertTrue(new SkillDraftService(model).draft("合同审查", "审查合同风险时使用").isBlank());
    }

    @Test
    void aMissingNameTakesTheFirstLineFromTheModel() {
        var model = new ReplyModel("合同审查\n\n先看合同范围，再标出没有依据的承诺。");
        String markdown = new SkillDraftService(model).draft("  ", "审查合同风险时使用");
        SkillPackage.Loaded loaded =
                SkillPackage.load("skill.md", markdown.getBytes(StandardCharsets.UTF_8));
        assertEquals("合同审查", loaded.name());
        assertTrue(loaded.markdown().contains("先看合同范围"));
    }

    @Test
    void suggestNameUsesTheModelLine() {
        var model = new ReplyModel("名称：合同审查");
        assertEquals("合同审查", new SkillDraftService(model).suggestName("审查合同风险时使用"));
    }

    @Test
    void aMissingBriefDoesNotCallTheModel() {
        var model = new ReplyModel("不应出现");
        assertEquals("", new SkillDraftService(model).draft("名称", " "));
        assertEquals("", new SkillDraftService(model).suggestName(" "));
        assertEquals(0, model.calls.get());
    }

    private static final class ReplyModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();
        private final String text;

        private ReplyModel(String text) {
            this.text = text;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            if (text == null) {
                return Flux.error(new IllegalStateException("endpoint down"));
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(text).build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return "stub";
        }
    }
}
