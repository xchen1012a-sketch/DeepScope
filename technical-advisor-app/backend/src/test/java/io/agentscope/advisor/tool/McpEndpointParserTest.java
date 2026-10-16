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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A pasted command and a pasted config block both become a connection, not a tool. */
class McpEndpointParserTest {

    private final McpEndpointParser parser = new McpEndpointParser(new ObjectMapper());

    @Test
    void commandLineBecomesALocalProcess() {
        var endpoint = parser.parse("npx -y \"@scope/server\" /tmp").get(0);
        assertEquals("stdio", endpoint.transport());
        assertEquals("npx", endpoint.command());
        assertEquals(List.of("-y", "@scope/server", "/tmp"), endpoint.args());
    }

    @Test
    void configBlockCanDescribeSeveralServers() {
        var endpoints =
                parser.parse(
                        """
                        {"mcpServers": {
                          "files": {"command": "echo", "args": ["hi"], "env": {"TOKEN": "secret"}},
                          "remote": {"url": "https://mcp.example/sse", "transport": "sse", "headers": {"Authorization": "Bearer secret"}}
                        }}
                        """);
        assertEquals(2, endpoints.size());
        assertEquals("stdio", endpoints.get(0).transport());
        assertEquals("secret", endpoints.get(0).env().get("TOKEN"));
        assertEquals("sse", endpoints.get(1).transport());
        assertEquals("https://mcp.example/sse", endpoints.get(1).url());
    }

    @Test
    void aBareUrlIsAnHttpConnection() {
        var endpoint = parser.parse("{\"url\": \"https://mcp.example/mcp\"}").get(0);
        assertEquals("http", endpoint.transport());
    }
}
