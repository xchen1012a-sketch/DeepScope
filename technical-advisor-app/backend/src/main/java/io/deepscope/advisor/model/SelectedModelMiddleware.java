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
package io.deepscope.advisor.model;

import io.deepscope.core.agent.Agent;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.event.AgentEvent;
import io.deepscope.core.event.ModelCallEndEvent;
import io.deepscope.core.middleware.MiddlewareBase;
import io.deepscope.core.middleware.ModelCallInput;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Pins the current selected model at the model-call boundary. */
public final class SelectedModelMiddleware implements MiddlewareBase {
    private final io.deepscope.core.model.Model model;

    public SelectedModelMiddleware() {
        this(null);
    }

    public SelectedModelMiddleware(io.deepscope.core.model.Model model) {
        this.model = model;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            io.deepscope.core.middleware.AgentInput input,
            Function<io.deepscope.core.middleware.AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    if (model instanceof SwitchingChatModel switching) switching.forRun(context);
                    return next.apply(input);
                });
    }

    public static final String CONTEXT_WINDOW_METADATA = "advisor.contextWindow";
    public static final String MODEL_NAME_METADATA = "advisor.modelName";

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    var model =
                            input.model() instanceof SwitchingChatModel switching
                                    ? switching.forRun(ctx)
                                    : input.model();
                    int window = model.getContextWindowSize();
                    String name = model.getModelName();
                    var pinned =
                            new ModelCallInput(
                                    input.messages(), input.tools(), input.options(), model);
                    return next.apply(pinned)
                            .map(
                                    event -> {
                                        if (event instanceof ModelCallEndEvent) {
                                            event.withMetadataEntry(
                                                    CONTEXT_WINDOW_METADATA, window);
                                            event.withMetadataEntry(MODEL_NAME_METADATA, name);
                                        }
                                        return event;
                                    });
                });
    }
}
