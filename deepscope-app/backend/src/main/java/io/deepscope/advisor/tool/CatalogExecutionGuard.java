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

/** Checks live catalog state immediately before a new external execution. */
public final class CatalogExecutionGuard {
    private final ToolCatalog catalog;

    public CatalogExecutionGuard(ToolCatalog catalog) {
        this.catalog = catalog;
    }

    public ToolCatalog.ToolRow check(String toolId) {
        try {
            var tool =
                    catalog.findTool(toolId)
                            .orElseThrow(
                                    () ->
                                            new ToolProblem(
                                                    404,
                                                    "tool_not_found",
                                                    "The tool is no longer available"));
            if (!tool.approved())
                throw new ToolProblem(403, "tool_unavailable", "The tool is unavailable");
            if (ToolCatalog.PERMISSION_DENY.equals(tool.permission()))
                throw new ToolProblem(403, "permission_denied", "This tool is forbidden");
            if (!catalog.executionEnabled(toolId))
                throw new ToolProblem(403, "tool_unavailable", "The tool is unavailable");
            return tool;
        } catch (java.sql.SQLException error) {
            throw new ToolProblem(
                    503, "catalog_unavailable", "Could not verify the tool's current permissions");
        }
    }

    public ToolCatalog.ToolRow check(String agentId, String toolId) {
        var tool = check(toolId);
        try {
            if (catalog.effectiveTools(agentId).stream()
                    .noneMatch(row -> row.toolId().equals(toolId)))
                throw new ToolProblem(403, "tool_unavailable", "This tool is no longer selected");
            return tool;
        } catch (java.sql.SQLException error) {
            throw new ToolProblem(503, "catalog_unavailable", "Could not verify the tool binding");
        }
    }
}
