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
package io.agentscope.advisor.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/** Live command projections: resource tables remain the only source of ownership and status. */
final class ResourceCommandCatalog {
    private final DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();

    ResourceCommandCatalog(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    List<CommandCatalog.CommandRow> list(String owner) throws SQLException {
        if (owner == null || owner.isBlank()) return List.of();
        var commands = new ArrayList<CommandCatalog.CommandRow>();
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT t.* FROM advisor_tools t WHERE t.owner_user_id = ? AND"
                                    + " t.approved = 1 AND t.lifecycle_status = 'ready' AND"
                                    + " t.permission IN ('allow', 'ask') AND (t.origin = 'http' OR"
                                    + " (t.origin = 'mcp' AND EXISTS (SELECT 1 FROM"
                                    + " advisor_mcp_servers s WHERE s.server_id = t.mcp_server_id"
                                    + " AND s.owner_user_id = t.owner_user_id AND s.status IN"
                                    + " ('connected', 'error')))) ORDER BY t.name, t.tool_id")) {
            statement.setString(1, owner);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String origin = rows.getString("origin");
                    if (!validSchema(rows.getString("input_schema"))) continue;
                    if ("http".equals(origin)
                            && !validHttp(
                                    rows.getString("http_method"), rows.getString("http_url")))
                        continue;
                    String type =
                            "mcp".equals(origin)
                                    ? CommandCatalog.TARGET_MCP
                                    : CommandCatalog.TARGET_TOOL;
                    commands.add(
                            row(
                                    type,
                                    rows.getString("tool_id"),
                                    rows.getString("name"),
                                    rows.getString("description"),
                                    owner));
                }
            }
        }
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT skill_id, name, description, live_dir FROM advisor_skills"
                                        + " WHERE owner_user_id = ? AND status = 'published' AND"
                                        + " live_dir IS NOT NULL ORDER BY name, skill_id")) {
            statement.setString(1, owner);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!validSkill(rows.getString("live_dir"))) continue;
                    commands.add(
                            row(
                                    CommandCatalog.TARGET_SKILL,
                                    rows.getString("skill_id"),
                                    rows.getString("name"),
                                    rows.getString("description"),
                                    owner));
                }
            }
        }
        return commands;
    }

    private static CommandCatalog.CommandRow row(
            String type, String id, String name, String description, String owner) {
        return new CommandCatalog.CommandRow(
                type + ":" + id,
                type + "-" + id,
                name,
                description,
                CommandCatalog.ORIGIN_CUSTOM,
                owner,
                type,
                id,
                "{}",
                100,
                CommandCatalog.STATUS_ACTIVE,
                0,
                0);
    }

    static boolean resourceType(String type) {
        return List.of(
                        CommandCatalog.TARGET_TOOL,
                        CommandCatalog.TARGET_MCP,
                        CommandCatalog.TARGET_SKILL)
                .contains(type);
    }

    private boolean validSchema(String schema) {
        try {
            var value = json.readTree(schema);
            return value != null
                    && value.isObject()
                    && (!value.has("type") || "object".equals(value.path("type").asText()));
        } catch (Exception invalid) {
            return false;
        }
    }

    private static boolean validHttp(String method, String address) {
        if (method == null
                || !List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
                        .contains(method)) return false;
        try {
            var uri = URI.create(address.replaceAll("\\{[^{}]+}", "parameter"));
            return ("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static boolean validSkill(String directory) {
        try {
            Path file = Path.of(directory).resolve("SKILL.md");
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) > 0;
        } catch (Exception invalid) {
            return false;
        }
    }
}
