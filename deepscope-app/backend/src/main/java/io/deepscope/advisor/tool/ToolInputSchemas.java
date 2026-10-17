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
package io.deepscope.advisor.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/** Reads real MCP metadata for legacy SDK toString records; never executes a tool or edits it. */
final class ToolInputSchemas {
    private ToolInputSchemas() {}

    static String resolve(
            ToolCatalog.ToolRow tool, ToolCatalog catalog, McpToolSource mcp, ObjectMapper json) {
        String saved = tool.inputSchema();
        if (saved == null || !saved.startsWith("JsonSchema[")) return saved;
        if (!ToolCatalog.ORIGIN_MCP.equals(tool.origin()) || catalog == null || mcp == null)
            throw invalidSchema();
        try {
            var server =
                    catalog.findServer(tool.mcpServerId())
                            .filter(
                                    row ->
                                            Objects.equals(row.ownerUserId(), tool.ownerUserId())
                                                    && java.util.List.of("connected", "error")
                                                            .contains(row.status()))
                            .orElseThrow(ToolInputSchemas::invalidSchema);
            var matches =
                    mcp.list(catalog.endpointOf(server)).stream()
                            .filter(row -> tool.name().equals(row.name()))
                            .toList();
            if (matches.size() != 1) throw invalidSchema();
            String schema = matches.get(0).inputSchema();
            if (schema == null || schema.length() > 32_768) throw invalidSchema();
            var node = json.readTree(schema);
            if (node == null
                    || !node.isObject()
                    || node.has("type") && !"object".equals(node.path("type").asText()))
                throw invalidSchema();
            return schema;
        } catch (ToolProblem error) {
            throw error;
        } catch (Exception error) {
            throw new ToolProblem(
                    502,
                    "schema_refresh_failed",
                    "Could not read this MCP tool's current parameter schema");
        }
    }

    private static ToolProblem invalidSchema() {
        return new ToolProblem(
                422,
                "invalid_schema",
                "The tool parameter schema is invalid; synchronize the MCP server");
    }
}
