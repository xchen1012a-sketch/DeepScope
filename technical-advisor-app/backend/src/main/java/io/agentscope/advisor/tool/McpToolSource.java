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

import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.util.List;

/**
 * Speaks MCP. {@link #list} is for discovery and closes the connection. {@link #open} leaves it
 * open so a running agent can call the approved tools.
 */
public interface McpToolSource {
    List<ToolCatalog.DiscoveredTool> list(McpEndpointParser.McpEndpoint endpoint) throws Exception;

    McpClientWrapper open(McpEndpointParser.McpEndpoint endpoint) throws Exception;
}
