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
package io.deepscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.event.AgentEvent;
import io.deepscope.core.event.ToolResultEndEvent;
import io.deepscope.core.event.ToolResultStartEvent;
import io.deepscope.core.event.ToolResultTextDeltaEvent;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.ToolResultState;
import io.deepscope.core.message.ToolUseBlock;
import io.deepscope.core.middleware.ActingInput;
import io.deepscope.core.state.AgentState;
import io.deepscope.harness.agent.bus.BusEntry;
import io.deepscope.harness.agent.bus.MessageBus;
import io.deepscope.harness.agent.bus.WorkspaceMessageBus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AsyncToolMiddlewareTest {

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir;
    private MessageBus bus;
    private AgentState agentState;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        io.deepscope.harness.agent.filesystem.local.LocalFilesystem fs =
                new io.deepscope.harness.agent.filesystem.local.LocalFilesystem(tempDir, true, 10);
        bus = new WorkspaceMessageBus(fs, "/bus");
        agentState = AgentState.builder().build();
        agentState.setReplyId("reply-1");
        ctx = RuntimeContext.builder().sessionId("session-1").agentState(agentState).build();
    }

    @Test
    void toolCompletesBeforeTimeout_passesThrough() {
        AsyncToolMiddleware middleware = new AsyncToolMiddleware(bus, Duration.ofSeconds(5));

        ToolUseBlock tool = ToolUseBlock.builder().id("t1").name("fast_tool").build();
        ActingInput input = new ActingInput(List.of(tool));

        Flux<AgentEvent> downstream =
                Flux.just(
                        new ToolResultStartEvent("r1", "t1", "fast_tool"),
                        new ToolResultTextDeltaEvent("r1", "t1", "fast_tool", "result text"),
                        new ToolResultEndEvent("r1", "t1", "fast_tool", ToolResultState.SUCCESS));

        List<AgentEvent> events =
                middleware
                        .onActing(stubAgent(), ctx, input, next -> downstream)
                        .collectList()
                        .block();

        assertEquals(3, events.size());
        assertTrue(events.get(0) instanceof ToolResultStartEvent);
        assertTrue(events.get(1) instanceof ToolResultTextDeltaEvent);
        assertTrue(events.get(2) instanceof ToolResultEndEvent);

        ToolResultTextDeltaEvent delta = (ToolResultTextDeltaEvent) events.get(1);
        assertEquals("result text", delta.getDelta());

        List<BusEntry> inbox = bus.inboxDrain("session-1", 100).block();
        assertTrue(inbox.isEmpty());
    }

    @Test
    void toolExceedsTimeout_emitsPlaceholderAndOffloads() throws Exception {
        AsyncToolMiddleware middleware = new AsyncToolMiddleware(bus, Duration.ofMillis(200));

        ToolUseBlock tool = ToolUseBlock.builder().id("t1").name("slow_tool").build();
        ActingInput input = new ActingInput(List.of(tool));

        Flux<AgentEvent> slowDownstream =
                Flux.just((AgentEvent) new ToolResultStartEvent("r1", "t1", "slow_tool"))
                        .concatWith(
                                Mono.delay(Duration.ofSeconds(2))
                                        .thenMany(
                                                Flux.just(
                                                        new ToolResultTextDeltaEvent(
                                                                "r1",
                                                                "t1",
                                                                "slow_tool",
                                                                "slow result"),
                                                        new ToolResultEndEvent(
                                                                "r1",
                                                                "t1",
                                                                "slow_tool",
                                                                ToolResultState.SUCCESS))));

        List<AgentEvent> events =
                middleware
                        .onActing(stubAgent(), ctx, input, next -> slowDownstream)
                        .collectList()
                        .block();

        boolean hasPlaceholder =
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof ToolResultTextDeltaEvent d
                                                && d.getDelta().contains("running in background"));
        assertTrue(hasPlaceholder, "Should emit placeholder text delta");

        boolean hasEnd =
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof ToolResultEndEvent end
                                                && end.getState() == ToolResultState.SUCCESS);
        assertTrue(hasEnd, "Should emit end event");

        assertFalse(agentState.getContext().isEmpty(), "Placeholder should be written to context");

        Thread.sleep(3000);

        List<BusEntry> inbox = bus.inboxDrain("session-1", 100).block();
        assertFalse(inbox.isEmpty(), "Background result should be pushed to inbox");

        String hint = inbox.get(0).payload().get("hint").toString();
        assertTrue(hint.contains("slow result"), "Inbox should contain the actual result");
    }

    private static io.deepscope.core.agent.Agent stubAgent() {
        return new io.deepscope.core.agent.Agent() {
            @Override
            public String getAgentId() {
                return "test-agent-id";
            }

            @Override
            public String getName() {
                return "TestAgent";
            }

            @Override
            public void interrupt() {}

            @Override
            public void interrupt(Msg msg) {}

            @Override
            public io.deepscope.core.state.AgentState getAgentState() {
                return null;
            }

            @Override
            public Mono<Msg> call(List<Msg> msgs) {
                return Mono.empty();
            }

            @Override
            public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
                return Mono.empty();
            }

            @Override
            public Mono<Msg> call(List<Msg> msgs, com.fasterxml.jackson.databind.JsonNode schema) {
                return Mono.empty();
            }

            @Override
            public Flux<io.deepscope.core.agent.Event> stream(
                    List<Msg> msgs, io.deepscope.core.agent.StreamOptions options) {
                return Flux.empty();
            }

            @Override
            public Flux<io.deepscope.core.agent.Event> stream(
                    List<Msg> msgs, io.deepscope.core.agent.StreamOptions options, Class<?> m) {
                return Flux.empty();
            }

            @Override
            public Flux<io.deepscope.core.agent.Event> stream(
                    List<Msg> msgs,
                    io.deepscope.core.agent.StreamOptions options,
                    com.fasterxml.jackson.databind.JsonNode schema) {
                return Flux.empty();
            }

            @Override
            public Mono<Void> observe(Msg msg) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> observe(List<Msg> msgs) {
                return Mono.empty();
            }
        };
    }
}
