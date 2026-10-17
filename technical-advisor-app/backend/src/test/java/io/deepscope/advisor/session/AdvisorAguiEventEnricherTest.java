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
package io.deepscope.advisor.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.agui.adapter.AguiAdapterConfig;
import io.deepscope.core.agui.adapter.strategy.AguiStreamContext;
import io.deepscope.core.agui.event.AguiEvent;
import io.deepscope.core.event.ToolResultEndEvent;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.ToolResultState;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.Model;
import io.deepscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Application-layer AG-UI enrichment: the tool result state and the model's context window are the
 * two facts the conversation page needs and the protocol events do not carry.
 */
class AdvisorAguiEventEnricherTest {

    private static final AguiStreamContext CONTEXT =
            new AguiStreamContext("thread-1", "run-1", AguiAdapterConfig.defaultConfig());

    /** Model stub that only reports a context window. */
    private static final class WindowedModel implements Model {
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "windowed";
        }

        @Override
        public int getContextWindowSize() {
            return 8_000;
        }
    }

    private static AguiEvent.ToolCallResult toolResult() {
        return new AguiEvent.ToolCallResult(
                "thread-1", "run-1", "c1", "Error: boom", "tool", "r1:c1");
    }

    @Test
    void carriesTheToolResultStateOnTheResultEvent() {
        AdvisorAguiEventEnricher enricher = new AdvisorAguiEventEnricher(new WindowedModel());
        ToolResultEndEvent source =
                new ToolResultEndEvent("r1", "c1", "read_file", ToolResultState.ERROR);

        AguiEvent.ToolCallResult enriched =
                assertInstanceOf(
                        AguiEvent.ToolCallResult.class,
                        enricher.enrich(source, List.of(toolResult()), CONTEXT).get(0));

        assertEquals("c1", enriched.toolCallId());
        assertEquals("Error: boom", enriched.content());
        assertEquals(Map.of("state", "error"), enriched.rawEvent());
    }

    /** The page reads the state out of the serialized SSE line, so it has to survive encoding. */
    @Test
    void serializesTheStateOntoTheWireEvent() {
        AdvisorAguiEventEnricher enricher = new AdvisorAguiEventEnricher(new WindowedModel());
        AguiEvent.ToolCallResult enriched =
                assertInstanceOf(
                        AguiEvent.ToolCallResult.class,
                        enricher.enrich(
                                        new ToolResultEndEvent(
                                                "r1", "c1", "read_file", ToolResultState.ERROR),
                                        List.of(toolResult()),
                                        CONTEXT)
                                .get(0));

        String sse = new io.deepscope.core.agui.encoder.AguiEventEncoder().encode(enriched);

        assertTrue(sse.contains("\"type\":\"TOOL_CALL_RESULT\""), sse);
        assertTrue(sse.contains("\"state\":\"error\""), sse);
    }

    @Test
    void leavesAToolResultWithoutStateUntouched() {
        AdvisorAguiEventEnricher enricher = new AdvisorAguiEventEnricher(new WindowedModel());
        AguiEvent.ToolCallResult result = toolResult();

        assertSame(result, enricher.enrich(null, List.of(result), CONTEXT).get(0));
    }

    @Test
    void actualCallSnapshotWinsOverTheLatestSelectedModel() {
        var source = new io.deepscope.core.event.ModelCallEndEvent("reply", null);
        source.withMetadataEntry("advisor.contextWindow", 1_048_576);
        source.withMetadataEntry("advisor.modelName", "in-flight-model");
        var usage =
                new AguiEvent.Custom(
                        "thread-1",
                        "run-1",
                        "token_usage",
                        Map.of("delta", Map.of("inputTokens", 12)));
        var enriched =
                (AguiEvent.Custom)
                        new AdvisorAguiEventEnricher(new WindowedModel())
                                .enrich(source, List.of(usage), CONTEXT)
                                .get(0);
        var value = (Map<?, ?>) enriched.value();
        assertEquals(1_048_576, value.get("contextWindow"));
        assertEquals("in-flight-model", value.get("modelName"));
    }

    @Test
    void anUnknownCallSnapshotDoesNotBorrowAnotherModelsKnownWindow() {
        var source = new io.deepscope.core.event.ModelCallEndEvent("reply", null);
        source.withMetadataEntry("advisor.contextWindow", 0);
        source.withMetadataEntry("advisor.modelName", "unknown");
        var usage =
                new AguiEvent.Custom(
                        "thread-1",
                        "run-1",
                        "token_usage",
                        Map.of("delta", Map.of("inputTokens", 12)));
        var enriched =
                (AguiEvent.Custom)
                        new AdvisorAguiEventEnricher(new WindowedModel())
                                .enrich(source, List.of(usage), CONTEXT)
                                .get(0);
        var value = (Map<?, ?>) enriched.value();
        assertFalse(value.containsKey("contextWindow"));
    }

    @Test
    void addsTheContextWindowToTokenUsage() {
        AdvisorAguiEventEnricher enricher = new AdvisorAguiEventEnricher(new WindowedModel());
        AguiEvent.Custom usage =
                new AguiEvent.Custom(
                        "thread-1",
                        "run-1",
                        AdvisorAguiEventEnricher.TOKEN_USAGE_EVENT,
                        Map.of(
                                "delta",
                                Map.of("inputTokens", 12),
                                "modelCall",
                                Map.of("replyId", "s1")));

        AguiEvent.Custom enriched =
                assertInstanceOf(
                        AguiEvent.Custom.class,
                        enricher.enrich(null, List.of(usage), CONTEXT).get(0));

        Map<?, ?> value = assertInstanceOf(Map.class, enriched.value());
        assertEquals(8_000, value.get(AdvisorAguiEventEnricher.CONTEXT_WINDOW_FIELD));
        assertEquals("windowed", value.get(AdvisorAguiEventEnricher.MODEL_NAME_FIELD));
    }

    @Test
    void omitsTheWindowWhenTheModelDoesNotReportOne() {
        AdvisorAguiEventEnricher enricher =
                new AdvisorAguiEventEnricher(
                        new Model() {
                            @Override
                            public Flux<ChatResponse> stream(
                                    List<Msg> messages,
                                    List<ToolSchema> tools,
                                    GenerateOptions options) {
                                return Flux.empty();
                            }

                            @Override
                            public String getModelName() {
                                return "unknown";
                            }
                        });
        AguiEvent.Custom usage =
                new AguiEvent.Custom(
                        "thread-1",
                        "run-1",
                        AdvisorAguiEventEnricher.TOKEN_USAGE_EVENT,
                        Map.of("delta", Map.of()));

        Map<?, ?> value = assertInstanceOf(Map.class, usageOf(enricher, usage));
        assertNull(value.get(AdvisorAguiEventEnricher.CONTEXT_WINDOW_FIELD));
    }

    private static Object usageOf(AdvisorAguiEventEnricher enricher, AguiEvent.Custom usage) {
        AguiEvent.Custom enriched =
                assertInstanceOf(
                        AguiEvent.Custom.class,
                        enricher.enrich(null, List.of(usage), CONTEXT).get(0));
        return enriched.value();
    }
}
