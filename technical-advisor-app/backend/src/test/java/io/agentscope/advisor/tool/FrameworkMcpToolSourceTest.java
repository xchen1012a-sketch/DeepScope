/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.advisor.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class FrameworkMcpToolSourceTest {
    @Test
    void discoverySerializesTheSdkSchemaAsJsonIncludingTypesAndRequiredFields() throws Exception {
        var client = mock(McpClientWrapper.class);
        var tool =
                new McpSchema.Tool(
                        "query-docs",
                        null,
                        "Query",
                        new McpSchema.JsonSchema(
                                "object",
                                Map.of("query", Map.of("type", "string")),
                                List.of("query"),
                                false,
                                null,
                                null),
                        null,
                        null,
                        null);
        when(client.listTools()).thenReturn(Mono.just(List.of(tool)));
        var source = mock(FrameworkMcpToolSource.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.doReturn(client).when(source).open(any());
        var result =
                source.list(
                        new McpEndpointParser.McpEndpoint(
                                "test", "stdio", "echo", List.of(), Map.of(), null, Map.of()));
        var schema = new ObjectMapper().readTree(result.get(0).inputSchema());
        assertEquals("object", schema.path("type").asText());
        assertEquals("string", schema.path("properties").path("query").path("type").asText());
        assertEquals("query", schema.path("required").get(0).asText());
    }
}
