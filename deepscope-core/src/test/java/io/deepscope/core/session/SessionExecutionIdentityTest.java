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
package io.deepscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.ReActAgent;
import io.deepscope.core.agent.AgentRun;
import io.deepscope.core.agent.ExecutionIdentity;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.event.AgentEndEvent;
import io.deepscope.core.event.AgentEvent;
import io.deepscope.core.event.AgentResultEvent;
import io.deepscope.core.event.RequireExternalExecutionEvent;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.GenerateReason;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.message.ToolResultBlock;
import io.deepscope.core.message.ToolResultMessage;
import io.deepscope.core.message.ToolUseBlock;
import io.deepscope.core.message.UserMessage;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.Model;
import io.deepscope.core.model.ToolSchema;
import io.deepscope.core.session.log.DurableSession;
import io.deepscope.core.session.log.SessionEvent;
import io.deepscope.core.session.log.SessionExecutionData;
import io.deepscope.core.session.log.SessionLogException;
import io.deepscope.core.tool.Toolkit;
import io.deepscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SessionExecutionIdentityTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    private RuntimeContext context() {
        return RuntimeContext.builder().userId("u").sessionId("s").build();
    }

    private ReActAgent agent(boolean external) {
        AtomicInteger calls = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "identity-model";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        ContentBlock content =
                                external && calls.getAndIncrement() == 0
                                        ? ToolUseBlock.builder()
                                                .id("tool-1")
                                                .name("external")
                                                .input(Map.of())
                                                .build()
                                        : TextBlock.builder().text("done").build();
                        return Flux.just(
                                ChatResponse.builder()
                                        .id("identity-response")
                                        .content(List.of(content))
                                        .build());
                    }
                };
        var toolkit = new Toolkit();
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external")
                        .description("external")
                        .parameters(Map.of("type", "object", "properties", Map.of()))
                        .build());
        return ReActAgent.builder()
                .name("stable")
                .model(model)
                .toolkit(toolkit)
                .sessionLogStore(new InMemorySessionLogStore())
                .build();
    }

    @Test
    void handleLiveEventsAndNativeFactsShareExecutionIdentity() {
        var agent = agent(false);
        var context = context();
        var run = agent.prepareRun(List.of(new UserMessage("hello")), context);
        var events = run.stream().collectList().block(WAIT);
        assertFalse(events.isEmpty());
        ExecutionIdentity identity = events.get(0).getExecution();
        assertEquals(context.getRunId(), run.runId());
        assertEquals(run.runId(), identity.runId());
        assertEquals("s", identity.sessionId());
        assertEquals("stable", identity.agentId());
        assertTrue(events.stream().allMatch(e -> identity.equals(e.getExecution())));
        assertTrue(
                agent
                        .sessionLogStore()
                        .readKernel(agent.sessionKey(context), context)
                        .events()
                        .stream()
                        .allMatch(
                                e ->
                                        run.runId().equals(e.executionRunId())
                                                && identity.turnId().equals(e.turnId())));
        assertNotNull(context.getAgentState());
        assertNull(context.get(DurableSession.TURN_ID_KEY));
        assertEquals(AgentRun.Status.COMPLETED, run.status());
        assertThrows(
                SessionLogException.class,
                () ->
                        agent.prepareCall(List.of(new UserMessage("duplicate")), context).stream()
                                .blockLast(WAIT));
        var nextContext = RuntimeContext.builder(context).runId(null).build();
        var call = agent.prepareCall(List.of(new UserMessage("second")), nextContext);
        assertEquals(nextContext.getRunId(), call.runId());
        assertNotEquals(run.runId(), call.runId());
        call.stream().blockLast(WAIT);
        assertTrue(
                agent
                        .sessionLogStore()
                        .readKernel(agent.sessionKey(context), context)
                        .events()
                        .stream()
                        .anyMatch(
                                e ->
                                        e.type().equals("run/end")
                                                && e.executionRunId().equals(call.runId())));
    }

    @Test
    void committedAssistantOutputKeepsItsModelCallIdentity() {
        var agent = agent(false);
        var ctx = context();
        Msg live = agent.call(List.of(new UserMessage("hello")), ctx).block(WAIT);
        var facts = agent.sessionLogStore().readKernel(agent.sessionKey(ctx), ctx).events();
        var outputs =
                facts.stream()
                        .filter(
                                e ->
                                        e.type().equals("assistant/message")
                                                || e.type().equals("turn/output"))
                        .toList();
        assertFalse(outputs.isEmpty());
        // The model response identifies the call; the committed message must keep that identity.
        assertEquals("identity-response", live.getId());
        outputs.forEach(
                e -> {
                    if (e.data()
                            instanceof
                            io.deepscope.core.session.log.SessionEventData.AssistantMessageData
                                    assistant) {
                        assertEquals(live.getId(), assistant.messageId());
                    } else {
                        throw new AssertionError("unexpected output payload " + e.data());
                    }
                });
    }

    @Test
    void eachDirectSubscriptionAllocatesIndependentRunAndDefaultTurn() {
        var agent = agent(false);
        var ctx = context();
        var stream = agent.streamEvents(List.of(new UserMessage("hello")), ctx);
        var first = stream.collectList().block(WAIT).get(0).getExecution();
        var second = stream.collectList().block(WAIT).get(0).getExecution();
        assertNotEquals(first.runId(), second.runId());
        assertNotEquals(first.turnId(), second.turnId());
    }

    @Test
    void externalContinuationPreservesTurnButStartsNewRun() {
        var agent = agent(true);
        var ctx = context();
        ctx.put(DurableSession.TURN_ID_KEY, "logical-turn");
        var first = agent.prepareRun(List.of(new UserMessage("hello")), ctx);
        var events = first.stream().collectList().block(WAIT);
        assertEquals(AgentRun.Status.COMPLETED, first.status());
        assertTrue(
                events.stream()
                        .filter(AgentResultEvent.class::isInstance)
                        .map(AgentResultEvent.class::cast)
                        .anyMatch(
                                e ->
                                        e.getResult().getGenerateReason()
                                                == GenerateReason.TOOL_SUSPENDED));
        var second =
                agent.prepareRun(
                        List.of(
                                ToolResultMessage.builder()
                                        .result(
                                                ToolResultBlock.of(
                                                        "tool-1",
                                                        "external",
                                                        TextBlock.builder().text("ok").build()))
                                        .build()),
                        RuntimeContext.builder(ctx).runId(null).build());
        second.stream().blockLast(WAIT);
        assertNotEquals(first.runId(), second.runId());
        var facts = agent.sessionLogStore().readKernel(agent.sessionKey(ctx), ctx).events();
        for (String type : List.of("turn/start", "turn/resumed"))
            assertEquals(1, facts.stream().filter(e -> e.type().equals(type)).count(), type);
        // A closed turn records one unified closing event per outcome, each carrying its reason.
        for (SessionExecutionData.TurnEndReason reason :
                List.of(
                        SessionExecutionData.TurnEndReason.SUSPENDED,
                        SessionExecutionData.TurnEndReason.COMPLETED))
            assertEquals(
                    1,
                    facts.stream()
                            .filter(
                                    e ->
                                            e.type().equals("turn/end")
                                                    && e.data()
                                                            instanceof
                                                            SessionExecutionData.TurnEnd end
                                                    && end.reason() == reason)
                            .count(),
                    reason.name());
        assertEquals(
                List.of(),
                facts.stream()
                        .filter(e -> !"logical-turn".equals(e.turnId()))
                        .map(e -> e.type() + "=" + e.turnId())
                        .toList());
        assertThrows(
                RuntimeException.class,
                () ->
                        agent
                                .prepareCall(
                                        List.of(), RuntimeContext.builder(ctx).runId(null).build())
                                .stream()
                                .blockLast(WAIT));
    }

    @Test
    void forwardedIdentitySurvivesSerializationAndDoesNotPolluteParentRequests() {
        var child = new ExecutionIdentity("child", "child-session", "child-turn", "child-run");
        AgentEvent event = new AgentEndEvent("reply").withExecution(child);
        event.withExecution(new ExecutionIdentity("parent", "s", "t", "parent-run"));
        var decoded =
                JsonUtils.getJsonCodec()
                        .fromJson(JsonUtils.getJsonCodec().toJson(event), AgentEvent.class);
        assertEquals(child, decoded.getExecution());
        var store = new InMemorySessionLogStore();
        var parentKey = new SessionKey("u", "parent", "s");
        try (DurableSession recorder = store.openWriter(parentKey, "parent-run", "t", context())) {
            var request =
                    new RequireExternalExecutionEvent(
                                    "reply",
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("child-tool")
                                                    .name("external")
                                                    .input(Map.of())
                                                    .build()))
                            .withExecution(child);
            recorder.observe(request);
        }
        assertTrue(
                store.readKernel(parentKey, context()).events().stream()
                        .noneMatch((SessionEvent e) -> "interaction/requested".equals(e.type())),
                "another execution's interaction must not be recorded on this log");
    }
}
