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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** A behavior draft is the model's own text. A failed call does not invent a document. */
class BehaviorDraftServiceTest {

    @Test
    void usesTheModelTextAndStripsAFence() {
        String body = "# 尽调\n\n先判断要不要调查。";
        var model = new ReplyModel("```markdown\n" + body + "\n```");
        assertEquals(body, new BehaviorDraftService(model).draft("尽调", "非 Java 项目"));
        assertEquals(1, model.calls.get());
    }

    @Test
    void aFailedCallStaysBlank() {
        var model = new ReplyModel(null);
        assertTrue(new BehaviorDraftService(model).draft("尽调", "简介").isBlank());
    }

    @Test
    void nothingToAnalyzeDoesNotCallTheModel() {
        var model = new ReplyModel("不应出现");
        assertEquals("", new BehaviorDraftService(model).draft("  ", ""));
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
