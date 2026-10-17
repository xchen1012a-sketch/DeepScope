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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.message.Msg;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.Model;
import io.deepscope.core.model.ToolSchema;
import io.deepscope.harness.agent.subagent.AgentSpecLoader;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** A drafted subagent definition must be a file the harness loader accepts; failures stay blank. */
class SubagentDraftServiceTest {

    @Test
    void writesAFrontMatterDefinitionTheLoaderAccepts() {
        var model = new ReplyModel("描述：审查合同风险与无依据承诺\n正文：\n# 角色定位\n你是合同审查子智能体。");
        String markdown = new SubagentDraftService(model).draft("合同审查", "审查采购合同风险");

        var declaration = AgentSpecLoader.parse(markdown, "contract-review", null);
        assertNotNull(declaration);
        assertEquals("contract-review", declaration.getName());
        assertEquals("审查合同风险与无依据承诺", declaration.getDescription());
        assertTrue(markdown.contains("# 角色定位"));
        assertEquals(1, model.calls.get());
    }

    @Test
    void descriptionFallsBackToThePurposeAndTheWholeReplyBecomesTheBody() {
        var model = new ReplyModel("# 角色定位\n只报告核实过的事实。");
        String markdown = new SubagentDraftService(model).draft("尽调助手", "整理尽调材料");

        var declaration = AgentSpecLoader.parse(markdown, "diligence", null);
        assertNotNull(declaration);
        assertEquals("整理尽调材料", declaration.getDescription());
        assertTrue(markdown.contains("只报告核实过的事实"));
    }

    @Test
    void aDescriptionWithColonsStillParses() {
        var model = new ReplyModel("描述：范围：采购与外包合同\n正文：\n# 角色定位\n核对条款。");
        String markdown = new SubagentDraftService(model).draft("合同审查", "");

        var declaration = AgentSpecLoader.parse(markdown, "contracts", null);
        assertNotNull(declaration);
        assertEquals("范围：采购与外包合同", declaration.getDescription());
    }

    @Test
    void aBlankNameNeverCallsTheModel() {
        var model = new ReplyModel("不应出现");
        assertEquals("", new SubagentDraftService(model).draft("  ", "用途"));
        assertEquals(0, model.calls.get());
    }

    @Test
    void aFailedOrEmptyCallStaysBlank() {
        assertTrue(new SubagentDraftService(new ReplyModel(null)).draft("合同审查", "用途").isBlank());
        assertTrue(new SubagentDraftService(new ReplyModel("   ")).draft("合同审查", "用途").isBlank());
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
