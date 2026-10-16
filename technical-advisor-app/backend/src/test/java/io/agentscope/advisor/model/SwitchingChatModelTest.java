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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.advisor.db.AdvisorSchema;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import reactor.core.publisher.Flux;

/**
 * Verifies per-run Deep Thinking control in {@link SwitchingChatModel}: default OFF uses low
 * reasoning effort and strips thinking blocks; toggling ON uses high reasoning effort and preserves
 * thinking blocks.
 */
class SwitchingChatModelTest {

    private ModelCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:switching-model-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        catalog = new ModelCatalog(dataSource);
    }

    @Test
    void unknownModelCapacityStaysUnknownInsteadOfBecomingTheFallbackBudget() {
        var switching = new SwitchingChatModel(() -> Optional.of(windowModel(0)), null, catalog);
        assertEquals(0, switching.getContextWindowSize());
    }

    @Test
    void nextRunBudgetUsesTheNewCurrentModelInsteadOfTheLastResolvedModel() {
        AtomicReference<Model> current = new AtomicReference<>(windowModel(1_048_576));
        SwitchingChatModel switching =
                new SwitchingChatModel(() -> Optional.of(current.get()), null, catalog);
        switching.stream(List.of(), List.of(), null).blockLast();
        current.set(windowModel(128_000));
        assertEquals(128_000, switching.getContextWindowSize());
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

    @Test
    void deepSeekModeSwitchIsExplicitAndOverridesConflictingDefaults() {
        AtomicReference<GenerateOptions> captured = new AtomicReference<>();
        Model selected =
                new Model() {
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        captured.set(options);
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(TextBlock.builder().text("answer").build()))
                                        .build());
                    }

                    public String getModelName() {
                        return "deepseek-v4.1-flash";
                    }
                };
        var switching = new SwitchingChatModel(() -> Optional.of(selected), null, catalog);
        var defaults =
                GenerateOptions.builder()
                        .maxTokens(512)
                        .reasoningEffort("high")
                        .additionalBodyParam("thinking", java.util.Map.of("type", "enabled"))
                        .build();
        switching.forCurrentCall().stream(List.of(), List.of(), defaults).blockLast();
        assertEquals(
                java.util.Map.of("type", "disabled"),
                captured.get().getAdditionalBodyParams().get("thinking"));
        assertEquals(512, captured.get().getMaxTokens());
        var rc = RuntimeContext.builder().put(SwitchingChatModel.THINKING_KEY, true).build();
        switching.forCurrentCall().stream(List.of(), List.of(), null)
                .contextWrite(ctx -> ctx.put(AgentBase.RUNTIME_CONTEXT_KEY, rc))
                .blockLast();
        assertEquals(
                java.util.Map.of("type", "enabled"),
                captured.get().getAdditionalBodyParams().get("thinking"));
    }

    @Test
    void defaultRunDisablesDeepThinkingAndStripsThinkingBlocks() {
        AtomicReference<GenerateOptions> capturedOptions = new AtomicReference<>();
        Model stub =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        capturedOptions.set(options);
                        return Flux.just(
                                ChatResponse.builder()
                                        .id("r1")
                                        .content(
                                                List.of(
                                                        ThinkingBlock.builder()
                                                                .thinking("hidden thought")
                                                                .build(),
                                                        TextBlock.builder().text("answer").build()))
                                        .build());
                    }

                    @Override
                    public String getModelName() {
                        return "stub";
                    }
                };

        SwitchingChatModel switching = new SwitchingChatModel(Optional::empty, stub, catalog);
        List<ChatResponse> responses =
                switching.stream(List.of(), List.of(), null).collectList().block();

        assertEquals("low", capturedOptions.get().getReasoningEffort());
        assertEquals(1, responses.size());
        List<ContentBlock> blocks = responses.get(0).getContent();
        assertEquals(1, blocks.size());
        assertFalse(blocks.get(0) instanceof ThinkingBlock);
        assertEquals("answer", ((TextBlock) blocks.get(0)).getText());
    }

    @Test
    void reasoningEffortTierIsHonoredWhenDeepThinkingOnAndIgnoredWhenOff() {
        AtomicReference<GenerateOptions> capturedOptions = new AtomicReference<>();
        Model stub =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        capturedOptions.set(options);
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(TextBlock.builder().text("answer").build()))
                                        .build());
                    }

                    @Override
                    public String getModelName() {
                        return "stub";
                    }
                };
        SwitchingChatModel switching = new SwitchingChatModel(Optional::empty, stub, catalog);

        RuntimeContext xhigh =
                RuntimeContext.builder()
                        .put(SwitchingChatModel.THINKING_KEY, true)
                        .put(SwitchingChatModel.EFFORT_KEY, "xhigh")
                        .build();
        switching.stream(List.of(), List.of(), null)
                .contextWrite(ctx -> ctx.put(AgentBase.RUNTIME_CONTEXT_KEY, xhigh))
                .blockLast();
        assertEquals("xhigh", capturedOptions.get().getReasoningEffort());

        RuntimeContext unknownTier =
                RuntimeContext.builder()
                        .put(SwitchingChatModel.THINKING_KEY, true)
                        .put(SwitchingChatModel.EFFORT_KEY, "ultra")
                        .build();
        switching.stream(List.of(), List.of(), null)
                .contextWrite(ctx -> ctx.put(AgentBase.RUNTIME_CONTEXT_KEY, unknownTier))
                .blockLast();
        assertEquals("high", capturedOptions.get().getReasoningEffort());

        RuntimeContext thinkingOff =
                RuntimeContext.builder()
                        .put(SwitchingChatModel.THINKING_KEY, false)
                        .put(SwitchingChatModel.EFFORT_KEY, "max")
                        .build();
        switching.stream(List.of(), List.of(), null)
                .contextWrite(ctx -> ctx.put(AgentBase.RUNTIME_CONTEXT_KEY, thinkingOff))
                .blockLast();
        assertEquals("low", capturedOptions.get().getReasoningEffort());
    }

    @Test
    void deepThinkingEnabledPreservesThinkingBlocksAndUsesHighReasoningEffort() {
        AtomicReference<GenerateOptions> capturedOptions = new AtomicReference<>();
        Model stub =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        capturedOptions.set(options);
                        return Flux.just(
                                ChatResponse.builder()
                                        .id("r2")
                                        .content(
                                                List.of(
                                                        ThinkingBlock.builder()
                                                                .thinking("deep thought")
                                                                .build(),
                                                        TextBlock.builder().text("answer").build()))
                                        .build());
                    }

                    @Override
                    public String getModelName() {
                        return "stub";
                    }
                };

        SwitchingChatModel switching = new SwitchingChatModel(Optional::empty, stub, catalog);
        RuntimeContext rc =
                RuntimeContext.builder().put(SwitchingChatModel.THINKING_KEY, true).build();
        List<ChatResponse> responses =
                switching.stream(List.of(), List.of(), null)
                        .contextWrite(ctx -> ctx.put(AgentBase.RUNTIME_CONTEXT_KEY, rc))
                        .collectList()
                        .block();

        assertEquals("high", capturedOptions.get().getReasoningEffort());
        assertEquals(1, responses.size());
        List<ContentBlock> blocks = responses.get(0).getContent();
        assertEquals(2, blocks.size());
        assertTrue(blocks.get(0) instanceof ThinkingBlock);
        assertEquals("deep thought", ((ThinkingBlock) blocks.get(0)).getThinking());
    }
}
