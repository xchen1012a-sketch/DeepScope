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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a pasted command or a pasted config block into connection endpoints.
 *
 * <p>The text is how to reach a service. It is not a tool description and it is not program
 * source.
 */
public final class McpEndpointParser {

    private final ObjectMapper json;

    public McpEndpointParser(ObjectMapper json) {
        this.json = json;
    }

    public List<McpEndpoint> parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("MCP connection text is empty");
        }
        String trimmed = text.strip();
        if (trimmed.startsWith("{")) {
            return parseJson(trimmed);
        }
        List<String> tokens = tokenize(trimmed);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("MCP command is empty");
        }
        return List.of(
                new McpEndpoint(
                        tokens.get(0),
                        "stdio",
                        tokens.get(0),
                        tokens.subList(1, tokens.size()),
                        Map.of(),
                        null,
                        Map.of()));
    }

    private List<McpEndpoint> parseJson(String text) {
        JsonNode root;
        try {
            root = json.readTree(text);
        } catch (Exception error) {
            throw new IllegalArgumentException("MCP config is not valid JSON", error);
        }
        JsonNode servers = root.get("mcpServers");
        if (servers != null && servers.isObject()) {
            List<McpEndpoint> endpoints = new ArrayList<>();
            Iterator<String> names = servers.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                endpoints.add(one(name, servers.get(name)));
            }
            if (endpoints.isEmpty()) {
                throw new IllegalArgumentException("MCP config has no servers");
            }
            return endpoints;
        }
        String name = text(root, "name");
        return List.of(one(name == null ? "mcp" : name, root));
    }

    private McpEndpoint one(String name, JsonNode node) {
        String command = text(node, "command");
        if (command != null) {
            return new McpEndpoint(
                    name,
                    "stdio",
                    command,
                    stringList(node.get("args")),
                    map(node.get("env")),
                    null,
                    Map.of());
        }
        String url = text(node, "url");
        if (url == null) {
            throw new IllegalArgumentException("MCP config needs a command or a url");
        }
        String transport = text(node, "transport");
        String kind = "sse".equalsIgnoreCase(transport) ? "sse" : "http";
        return new McpEndpoint(
                name, kind, null, List.of(), Map.of(), url, map(node.get("headers")));
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static Map<String, String> map(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        node.fieldNames().forEachRemaining(field -> values.put(field, node.get(field).asText()));
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (!quoted && Character.isWhitespace(ch)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** How to reach one MCP service. Secrets stay on this object and are not copied onto tools. */
    public record McpEndpoint(
            String name,
            String transport,
            String command,
            List<String> args,
            Map<String, String> env,
            String url,
            Map<String, String> headers) {

        public McpEndpoint {
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            name = name == null || name.isBlank() ? "mcp" : name.strip();
            transport = transport.toLowerCase(Locale.ROOT);
        }
    }
}
