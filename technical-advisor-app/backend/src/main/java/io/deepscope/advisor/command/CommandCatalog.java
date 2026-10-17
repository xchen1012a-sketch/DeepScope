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
package io.deepscope.advisor.command;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Registry of controlled slash commands, managing builtin and user-created commands.
 */
public final class CommandCatalog {

    public static final String TARGET_ACTION = "action";
    public static final String TARGET_SKILL = "skill";
    public static final String TARGET_TOOL = "tool";
    public static final String TARGET_MCP = "mcp";
    public static final String TARGET_PROMPT = "prompt";

    public static final String ORIGIN_BUILTIN = "builtin";
    public static final String ORIGIN_CUSTOM = "custom";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    public record CommandRow(
            String commandId,
            String code,
            String name,
            String description,
            String origin,
            String ownerUserId,
            String targetType,
            String targetId,
            String paramsJson,
            int sortOrder,
            String status,
            long createdAt,
            long updatedAt) {}

    private final DataSource dataSource;
    private final ResourceCommandCatalog resources;

    public CommandCatalog(DataSource dataSource) {
        this.dataSource = dataSource;
        this.resources = new ResourceCommandCatalog(dataSource);
    }

    public void ensureBuiltin() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_commands SET command_id = 'cmd-builtin-compact',"
                                        + " code = 'compact' WHERE code = 'compress' AND origin ="
                                        + " 'builtin'")) {
            statement.executeUpdate();
        }
        ensureBuiltinCommand(
                "cmd-builtin-compact",
                "compact",
                "立即压缩上下文",
                "自动提炼长对话要点，释放上下文空间",
                TARGET_ACTION,
                "compact",
                "{}",
                10);
        ensureBuiltinCommand(
                "cmd-builtin-clear",
                "clear",
                "清空会话上下文",
                "重置当前会话历史，开始全新对话",
                TARGET_ACTION,
                "clear",
                "{}",
                20);
    }

    private void ensureBuiltinCommand(
            String id,
            String code,
            String name,
            String description,
            String targetType,
            String targetId,
            String paramsJson,
            int sortOrder)
            throws SQLException {
        if (findByCode(code).isPresent()) return;
        long now = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO advisor_commands (command_id, code, name, description,"
                                    + " origin, owner_user_id, target_type, target_id, params_json,"
                                    + " sort_order, status, created_at, updated_at) VALUES (?, ?,"
                                    + " ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, code);
            statement.setString(3, name);
            statement.setString(4, description);
            statement.setString(5, ORIGIN_BUILTIN);
            statement.setString(6, targetType);
            statement.setString(7, targetId);
            statement.setString(8, paramsJson);
            statement.setInt(9, sortOrder);
            statement.setString(10, STATUS_ACTIVE);
            statement.setLong(11, now);
            statement.setLong(12, now);
            statement.executeUpdate();
        }
    }

    public List<CommandRow> listAvailable(String userId) throws SQLException {
        List<CommandRow> liveResources = resources.list(userId);
        String sql =
                userId == null || userId.isBlank()
                        ? "SELECT * FROM advisor_commands WHERE status = 'active' AND origin ="
                                + " 'builtin' ORDER BY sort_order ASC, code ASC"
                        : "SELECT * FROM advisor_commands WHERE status = 'active' AND (origin ="
                                + " 'builtin' OR owner_user_id = ?) ORDER BY sort_order ASC, code"
                                + " ASC";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (userId != null && !userId.isBlank()) {
                statement.setString(1, userId);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<CommandRow> list = new ArrayList<>();
                while (rows.next()) {
                    CommandRow command = mapRow(rows);
                    if (!ResourceCommandCatalog.resourceType(command.targetType())
                            || liveResources.stream()
                                    .anyMatch(
                                            resource ->
                                                    resource.targetType()
                                                                    .equals(command.targetType())
                                                            && resource.targetId()
                                                                    .equals(command.targetId()))) {
                        list.add(command);
                    }
                }
                for (CommandRow resource : liveResources) {
                    if (list.stream().noneMatch(command -> command.code().equals(resource.code())))
                        list.add(resource);
                }
                return list;
            }
        }
    }

    /** Execution must use the same live availability and owner checks as discovery. */
    public Optional<CommandRow> findAvailableByCode(String code, String userId)
            throws SQLException {
        return listAvailable(userId).stream()
                .filter(command -> command.code().equals(code))
                .findFirst();
    }

    public Optional<CommandRow> findByCode(String code) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT * FROM advisor_commands WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapRow(rows)) : Optional.empty();
            }
        }
    }

    public Optional<CommandRow> find(String commandId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT * FROM advisor_commands WHERE command_id = ?")) {
            statement.setString(1, commandId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapRow(rows)) : Optional.empty();
            }
        }
    }

    public CommandRow createCustom(
            String ownerUserId,
            String code,
            String name,
            String description,
            String targetType,
            String targetId,
            String paramsJson,
            int sortOrder)
            throws SQLException {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO advisor_commands (command_id, code, name, description,"
                                    + " origin, owner_user_id, target_type, target_id, params_json,"
                                    + " sort_order, status, created_at, updated_at) VALUES (?, ?,"
                                    + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, code.trim());
            statement.setString(3, name.trim());
            statement.setString(4, description == null ? "" : description.trim());
            statement.setString(5, ORIGIN_CUSTOM);
            statement.setString(6, ownerUserId);
            statement.setString(7, targetType);
            statement.setString(8, targetId);
            statement.setString(9, paramsJson == null || paramsJson.isBlank() ? "{}" : paramsJson);
            statement.setInt(10, sortOrder);
            statement.setString(11, STATUS_ACTIVE);
            statement.setLong(12, now);
            statement.setLong(13, now);
            statement.executeUpdate();
        }
        return find(id).orElseThrow();
    }

    public boolean deleteCustom(String commandId, String ownerUserId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM advisor_commands WHERE command_id = ? AND"
                                        + " owner_user_id = ? AND origin = 'custom'")) {
            statement.setString(1, commandId);
            statement.setString(2, ownerUserId);
            return statement.executeUpdate() > 0;
        }
    }

    private static CommandRow mapRow(ResultSet rs) throws SQLException {
        return new CommandRow(
                rs.getString("command_id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("origin"),
                rs.getString("owner_user_id"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("params_json"),
                rs.getInt("sort_order"),
                rs.getString("status"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
