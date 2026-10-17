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
package io.deepscope.advisor.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.core.tool.mcp.McpClientBuilder;
import io.deepscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Opens a real MCP connection. Discovery closes it; a running agent keeps the one {@link #open} returns. */
public final class FrameworkMcpToolSource implements McpToolSource {

    @Override
    public List<ToolCatalog.DiscoveredTool> list(McpEndpointParser.McpEndpoint endpoint)
            throws Exception {
        McpClientWrapper client = open(endpoint);
        try {
            List<McpSchema.Tool> remote = client.listTools().block(Duration.ofSeconds(30));
            List<ToolCatalog.DiscoveredTool> discovered = new ArrayList<>();
            if (remote != null) {
                for (McpSchema.Tool tool : remote) {
                    Object schema = tool.inputSchema();
                    discovered.add(
                            new ToolCatalog.DiscoveredTool(
                                    tool.name(),
                                    tool.description() == null ? "" : tool.description(),
                                    schema == null
                                            ? "{}"
                                            : new ObjectMapper().writeValueAsString(schema)));
                }
            }
            return discovered;
        } finally {
            client.close();
        }
    }

    @Override
    public McpClientWrapper open(McpEndpointParser.McpEndpoint endpoint) {
        McpClientBuilder builder = McpClientBuilder.create(endpoint.name());
        switch (endpoint.transport()) {
            case "stdio" ->
                    builder.stdioTransport(endpoint.command(), endpoint.args(), endpoint.env());
            case "sse" -> {
                builder.sseTransport(endpoint.url());
                endpoint.headers().forEach(builder::header);
            }
            case "http" -> {
                builder.streamableHttpTransport(endpoint.url());
                endpoint.headers().forEach(builder::header);
            }
            default -> throw new IllegalArgumentException("Unknown MCP transport");
        }
        McpClientWrapper client = builder.buildAsync().block(Duration.ofSeconds(30));
        if (client == null) {
            throw new IllegalStateException("MCP connection was not established");
        }
        try {
            client.initialize().block(Duration.ofSeconds(30));
        } catch (RuntimeException error) {
            client.close();
            throw error;
        }
        return client;
    }
}
