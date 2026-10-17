/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.deepscope.extensions.judge.jev;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.deepscope.core.ReActAgent;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.message.ToolResultBlock;
import io.deepscope.core.message.ToolUseBlock;
import io.deepscope.core.message.UserMessage;
import io.deepscope.core.model.ChatModelBase;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.ToolSchema;
import io.deepscope.core.permission.PermissionBehavior;
import io.deepscope.core.permission.PermissionContextState;
import io.deepscope.core.permission.PermissionMode;
import io.deepscope.core.permission.PermissionRule;
import io.deepscope.core.tool.ToolBase;
import io.deepscope.core.tool.ToolCallParam;
import io.deepscope.core.tool.Toolkit;
import io.deepscope.extensions.judge.jev.example.JevAutoModeMiddleware;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class JevPermissionIntegrationTest {
    @Test
    void jevAllowCannotOverridePermissionDenyAndAllowedToolRunsOnce() {
        for (boolean deny : List.of(true, false)) {
            AtomicInteger executed = new AtomicInteger(), calls = new AtomicInteger();
            var toolkit = new Toolkit();
            toolkit.registerAgentTool(
                    new ToolBase(
                            "refund",
                            "refund",
                            Map.of("type", "object", "properties", Map.of()),
                            false,
                            true,
                            false,
                            null,
                            false,
                            false) {
                        @Override
                        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                            executed.incrementAndGet();
                            return Mono.just(ToolResultBlock.text("done"));
                        }
                    });
            var model =
                    new ChatModelBase() {
                        @Override
                        public String getModelName() {
                            return "fixture";
                        }

                        @Override
                        protected Flux<ChatResponse> doStream(
                                List<Msg> messages,
                                List<ToolSchema> tools,
                                GenerateOptions options) {
                            ContentBlock content =
                                    calls.getAndIncrement() == 0
                                            ? new ToolUseBlock("refund-id", "refund", Map.of())
                                            : TextBlock.builder().text("done").build();
                            return Flux.just(
                                    ChatResponse.builder().content(List.of(content)).build());
                        }
                    };
            var permissions = PermissionContextState.builder().mode(PermissionMode.BYPASS);
            if (deny)
                permissions.addDenyRule(
                        "refund",
                        new PermissionRule("refund", null, PermissionBehavior.DENY, "test"));
            var guard =
                    JevAutoModeMiddleware.builder(
                                    r ->
                                            Mono.just(
                                                    new SystemOneResult(
                                                            "fixture",
                                                            Map.of("tool_0", new NoulAnswer(0.99)),
                                                            null)))
                            .guardedTool("refund")
                            .execution(
                                    new JevExecution.Options(
                                            JevExecution.Mode.ENFORCE,
                                            Duration.ofSeconds(1),
                                            "v1",
                                            (c, r) -> {}))
                            .build();
            var agent =
                    ReActAgent.builder()
                            .name("test")
                            .model(model)
                            .toolkit(toolkit)
                            .permissionContext(permissions.build())
                            .middleware(guard)
                            .build();
            agent.streamEvents(List.of(new UserMessage("refund")))
                    .collectList()
                    .block(Duration.ofSeconds(5));
            assertEquals(deny ? 0 : 1, executed.get());
        }
    }
}
