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
package io.agentscope.advisor.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** Executes imported HTTP tools through the same bounded mapper used by tests. */
public final class HttpCatalogTool extends ToolBase {
    private final String method;
    private final String address;
    private final ToolHttpExecutor executor;
    private final Supplier<Map<String, Object>> credential;
    private final Runnable preflight;

    public HttpCatalogTool(
            String name,
            String description,
            Map<String, Object> inputSchema,
            String method,
            String urlTemplate,
            HttpClient client) {
        this(
                name,
                description,
                inputSchema,
                method,
                urlTemplate,
                new ToolHttpExecutor(new ObjectMapper(), client, Duration.ofSeconds(30)),
                () -> null,
                () -> {});
    }

    private HttpCatalogTool(
            String name,
            String description,
            Map<String, Object> inputSchema,
            String method,
            String address,
            ToolHttpExecutor executor,
            Supplier<Map<String, Object>> credential,
            Runnable preflight) {
        super(builder().name(name).description(description).inputSchema(inputSchema));
        this.method = method;
        this.address = address;
        this.executor = executor;
        this.credential = credential;
        this.preflight = preflight;
    }

    public static HttpCatalogTool create(
            ToolCatalog.ToolRow tool, ObjectMapper json, HttpClient client) {
        return create(
                tool,
                json,
                new ToolHttpExecutor(json, client, Duration.ofSeconds(30)),
                () -> null,
                () -> {});
    }

    /** Runtime wiring may supply the saved credential resolver and a live catalog guard. */
    public static HttpCatalogTool create(
            ToolCatalog.ToolRow tool,
            ObjectMapper json,
            ToolHttpExecutor executor,
            Supplier<Map<String, Object>> credential,
            Runnable preflight) {
        Map<String, Object> schema;
        try {
            schema =
                    json.readValue(tool.inputSchema(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception error) {
            throw new ToolProblem(422, "invalid_schema", "The saved tool input schema is invalid");
        }
        return new HttpCatalogTool(
                tool.name(),
                tool.description(),
                schema,
                tool.httpMethod(),
                tool.httpUrl(),
                executor,
                credential,
                preflight);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.defer(
                        () ->
                                executor.execute(
                                        method,
                                        address,
                                        getParameters(),
                                        param.getInput() == null ? Map.of() : param.getInput(),
                                        credential.get(),
                                        preflight))
                .map(
                        result -> {
                            String text =
                                    (result.httpStatus() == null ? "" : result.httpStatus() + " ")
                                            + result.result()
                                            + (result.truncated() ? "\n[truncated]" : "");
                            return result.errorCode() == null
                                    ? ToolResultBlock.text(text)
                                    : ToolResultBlock.error(text);
                        })
                .onErrorResume(
                        ToolProblem.class,
                        error -> Mono.just(ToolResultBlock.error(error.getMessage())));
    }
}
