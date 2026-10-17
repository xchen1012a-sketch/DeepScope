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

package io.deepscope.extensions.judge.jev.application;

import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.event.AgentEvent;
import io.deepscope.core.message.UserMessage;
import io.deepscope.core.middleware.AgentInput;
import io.deepscope.core.middleware.ModelCallInput;
import io.deepscope.extensions.judge.jev.example.JevModelRouterMiddleware;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Explicit application phase boundaries with an independent, sticky routing context per phase. */
public final class JevStageRouter {
    public record Phase(String name, RuntimeContext context) {}

    private final JevModelRouterMiddleware router;

    public JevStageRouter(JevModelRouterMiddleware router) {
        this.router = Objects.requireNonNull(router);
    }

    public Mono<Phase> begin(RuntimeContext parent, String stage, String goal) {
        if (stage == null || stage.isBlank() || goal == null || goal.isBlank())
            throw new IllegalArgumentException("stage and goal required");
        return Mono.defer(
                () -> {
                    var ctx =
                            parent == null
                                    ? RuntimeContext.empty()
                                    : RuntimeContext.builder(parent).build();
                    ctx.put("jev.stage", stage);
                    return router.onAgent(
                                    null,
                                    ctx,
                                    new AgentInput(
                                            List.of(
                                                    new UserMessage(
                                                            "Stage: "
                                                                    + stage
                                                                    + "; goal: "
                                                                    + goal))),
                                    i -> Flux.empty())
                            .then(Mono.just(new Phase(stage, ctx)));
                });
    }

    public Flux<AgentEvent> onModelCall(
            Phase phase, ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        return router.onModelCall(null, phase.context(), input, next);
    }
}
