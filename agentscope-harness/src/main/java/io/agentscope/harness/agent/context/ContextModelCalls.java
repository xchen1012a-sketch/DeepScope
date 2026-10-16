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
package io.agentscope.harness.agent.context;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.log.DurableSession;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Budget-only auxiliary call boundary: never recursively compacts or injects task state. */
public final class ContextModelCalls {
    private ContextModelCalls() {}

    public static Flux<ChatResponse> auxiliary(Model model, List<Msg> messages) {
        return Flux.deferContextual(
                subscriber -> {
                    RuntimeContext rc =
                            subscriber.getOrDefault(
                                    io.agentscope.core.agent.AgentBase.RUNTIME_CONTEXT_KEY,
                                    RuntimeContext.empty());
                    String callId = UUID.randomUUID().toString();
                    return new HarnessContextBuilder(ContextPolicy.defaults(), null, null)
                            .prepare(
                                    null,
                                    RuntimeContext.empty(),
                                    new ModelCallInput(messages, List.of(), null, model),
                                    callId,
                                    ModelRequestPreparer.Purpose.SUMMARY)
                            .flatMapMany(
                                    input -> {
                                        DurableSession recorder = DurableSession.from(rc);
                                        Mono<Void> envelope =
                                                recorder == null
                                                        ? Mono.empty()
                                                        : recorder.prepareModel(
                                                                input,
                                                                callId,
                                                                "SUMMARY",
                                                                rc.getAgentState());
                                        return envelope.thenMany(
                                                input.model().stream(
                                                        input.messages(),
                                                        input.tools(),
                                                        input.options()));
                                    });
                });
    }
}
