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

package io.deepscope.core.tracing;

import io.deepscope.core.agent.AgentBase;
import io.deepscope.core.formatter.AbstractBaseFormatter;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.ToolResultBlock;
import io.deepscope.core.model.ChatModelBase;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.ToolSchema;
import io.deepscope.core.tool.ToolCallParam;
import io.deepscope.core.tool.Toolkit;
import java.util.List;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * @deprecated since 2.0.0. Use {@link OtelTracingMiddleware} instead.
 */
@Deprecated(forRemoval = true, since = "2.0.0")
public interface Tracer {

    default Mono<Msg> callAgent(
            AgentBase instance, List<Msg> inputMessages, Supplier<Mono<Msg>> agentCall) {
        return agentCall.get();
    }

    default Flux<ChatResponse> callModel(
            ChatModelBase instance,
            List<Msg> inputMessages,
            List<ToolSchema> toolSchemas,
            GenerateOptions options,
            Supplier<Flux<ChatResponse>> modelCall) {
        return modelCall.get();
    }

    default Mono<ToolResultBlock> callTool(
            Toolkit toolkit,
            ToolCallParam toolCallParam,
            Supplier<Mono<ToolResultBlock>> toolKitCall) {
        return toolKitCall.get();
    }

    default <TReq, TResp, TParams> List<TReq> callFormat(
            AbstractBaseFormatter<TReq, TResp, TParams> formatter,
            List<Msg> msgs,
            Supplier<List<TReq>> formatCall) {
        return formatCall.get();
    }

    default <TResp> TResp runWithContext(ContextView reactorCtx, Supplier<TResp> inner) {
        return inner.get();
    }

    default void shutdown() {}
}
