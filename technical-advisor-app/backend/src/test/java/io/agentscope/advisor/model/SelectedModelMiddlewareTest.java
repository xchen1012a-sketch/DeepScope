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
package io.agentscope.advisor.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SelectedModelMiddlewareTest {
    @Test
    void aConcurrentSelectionDoesNotChangeTheInFlightModelOrUsageMetadata() {
        var selected = new AtomicReference<Model>(windowModel(1_048_576));
        var switching = new SwitchingChatModel(() -> Optional.of(selected.get()), null, null);
        var event =
                new SelectedModelMiddleware()
                        .onModelCall(
                                null,
                                null,
                                new ModelCallInput(List.of(), List.of(), null, switching),
                                input -> {
                                    selected.set(windowModel(128_000));
                                    assertEquals(1_048_576, input.model().getContextWindowSize());
                                    return Flux.just(new ModelCallEndEvent("reply", null));
                                })
                        .blockLast();
        assertEquals(
                1_048_576,
                event.getMetadata().get(SelectedModelMiddleware.CONTEXT_WINDOW_METADATA));
        assertEquals(
                "test-model-1048576",
                event.getMetadata().get(SelectedModelMiddleware.MODEL_NAME_METADATA));
        assertEquals(128_000, switching.getContextWindowSize());
    }

    @Test
    void runSelectionSurvivesDefaultChangesAcrossModelCalls() {
        var selected = new AtomicReference<Model>(windowModel(100));
        var switching = new SwitchingChatModel(() -> Optional.of(selected.get()), null, null);
        var context = io.agentscope.core.agent.RuntimeContext.builder().sessionId("a").build();
        var pinned = switching.forRun(context);
        selected.set(windowModel(200));
        assertEquals("test-model-100", switching.forRun(context).getModelName());
        assertEquals("test-model-100", pinned.getModelName());
        assertEquals(
                "test-model-200",
                switching
                        .forRun(
                                io.agentscope.core.agent.RuntimeContext.builder()
                                        .sessionId("b")
                                        .build())
                        .getModelName());
    }

    private static Model windowModel(int window) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }

            @Override
            public String getModelName() {
                return "test-model-" + window;
            }

            @Override
            public int getContextWindowSize() {
                return window;
            }
        };
    }
}
