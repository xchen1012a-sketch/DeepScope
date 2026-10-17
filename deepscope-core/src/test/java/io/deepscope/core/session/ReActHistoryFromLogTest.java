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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.ReActAgent;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.message.UserMessage;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.Model;
import io.deepscope.core.model.ToolSchema;
import io.deepscope.core.session.log.MessageDerivation;
import io.deepscope.core.session.log.SessionRequestData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * End-to-end pin on the history source: what the model receives must equal what the log derives.
 *
 * <p>This is the gate for the cutover. If these two disagree, the log is not the authority for model
 * history and every "replay the session" claim downstream is false.
 */
class ReActHistoryFromLogTest {

    private static RuntimeContext context() {
        return RuntimeContext.builder().userId("u").sessionId("s").build();
    }

    /** A model that thinks before answering, so reasoning content can be traced into the log. */
    private static final class ThinkingModel implements Model {
        public String getModelName() {
            return "fake-thinking";
        }

        public Flux<ChatResponse> stream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.of(
                                            io.deepscope.core.message.ThinkingBlock.builder()
                                                    .thinking("weighing the options")
                                                    .build(),
                                            TextBlock.builder().text("answer").build()))
                            .build());
        }
    }

    private static final class CaptureModel implements Model {
        List<List<Msg>> calls = new ArrayList<>();

        public String getModelName() {
            return "fake";
        }

        public Flux<ChatResponse> stream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(List.copyOf(msgs));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("answer").build()))
                            .build());
        }
    }

    @Test
    @DisplayName("the request envelope is a logged fact, so a request is reproducible")
    void requestEnvelopeIsLogged() {
        var store = new InMemorySessionLogStore();
        var agent =
                ReActAgent.builder()
                        .name("envelope")
                        .model(new CaptureModel())
                        .sessionLogStore(store)
                        .build();
        agent.call(List.of(new UserMessage("what tools do you have")), context())
                .block(Duration.ofSeconds(10));

        var log = store.readKernel(new SessionKey("u", "envelope", "s"), context());
        var headers = new ArrayList<io.deepscope.core.session.log.SessionEvent>();
        for (var e : log.events()) if (e.type().equals("request/header")) headers.add(e);

        assertFalse(
                headers.isEmpty(),
                "every request must record its envelope, or the request cannot be reconstructed");
        assertTrue(
                headers.stream()
                        .allMatch(
                                e ->
                                        e.data() instanceof SessionRequestData.RequestHeader header
                                                && header.reason() != null),
                "every envelope must say why it was written, because a reader cannot infer it");
        var first = (SessionRequestData.RequestHeader) headers.get(0).data();
        assertEquals(
                SessionRequestData.RequestHeaderReason.INITIAL,
                first.reason(),
                "the first envelope of a conversation is the initial one");
        var latest = (SessionRequestData.RequestHeader) headers.get(headers.size() - 1).data();
        assertTrue(
                latest.config().containsKey("model"),
                "the envelope must name the model actually used: " + latest.config().keySet());
        assertNotNull(latest.toolNames(), "the envelope must record the assembled tool names");
    }

    @Test
    @DisplayName("reasoning content reaches the logged history")
    void reasoningContentReachesTheLog() {
        var store = new InMemorySessionLogStore();
        var agent =
                ReActAgent.builder()
                        .name("thinker")
                        .model(new ThinkingModel())
                        .sessionLogStore(store)
                        .build();
        agent.call(List.of(new UserMessage("think about it")), context())
                .block(Duration.ofSeconds(10));

        var log = store.readKernel(new SessionKey("u", "thinker", "s"), context());
        boolean reasoningPresent =
                MessageDerivation.history(log).stream()
                        .anyMatch(
                                m ->
                                        m.getContent().stream()
                                                .anyMatch(
                                                        b ->
                                                                b
                                                                        instanceof
                                                                        io.deepscope.core.message
                                                                                .ThinkingBlock));

        assertTrue(
                reasoningPresent,
                "the logged history must carry reasoning content, or a refreshed conversation page"
                        + " cannot rebuild its reasoning rows");
    }

    @Test
    @DisplayName("a rebuilt agent sends exactly the history the log derives")
    void rebuiltAgentSendsLogDerivedHistory() {
        var store = new InMemorySessionLogStore();
        var first = new CaptureModel();
        var a = ReActAgent.builder().name("stable").model(first).sessionLogStore(store).build();
        a.call(List.of(new UserMessage("hello there")), context()).block(Duration.ofSeconds(10));

        var second = new CaptureModel();
        var b = ReActAgent.builder().name("stable").model(second).sessionLogStore(store).build();
        b.call(List.of(new UserMessage("continue")), context()).block(Duration.ofSeconds(10));

        var log = store.readKernel(new SessionKey("u", "stable", "s"), context());
        List<String> derived =
                MessageDerivation.history(log).stream().map(Msg::getTextContent).toList();
        List<String> sent = second.calls.get(0).stream().map(Msg::getTextContent).toList();

        assertTrue(
                sent.contains("hello there"),
                "the rebuilt agent must receive the earlier history: " + sent);
        // The call that produced the second "answer" logs it after the model returns, so the
        // request
        // equals the log's history up to the request point. Everything after that prefix is the
        // reply
        // this very call recorded — which is the point of the cutover: the reply is a logged fact
        // rather than a mutation of a working copy.
        assertEquals(
                sent,
                derived.subList(0, sent.size()),
                "the request must equal the log's derived history at the request point");
        assertTrue(
                derived.size() > sent.size(),
                "the call must record its own assistant reply into the log: " + derived);
    }
}
