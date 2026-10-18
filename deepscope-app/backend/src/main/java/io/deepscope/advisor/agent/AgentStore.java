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
package io.deepscope.advisor.agent;

import java.nio.file.Path;
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
 * Agent definitions in PostgreSQL (stage 4). Nothing ships with the app: every agent is created
 * by a user afterwards. Every row carries its own workspace and state directory so each agent
 * runs — and persists its memory — in isolation.
 *
 * <p>Visibility follows TDS 3.4: personal rows are visible only to their owner, and admins see
 * everything.
 */
public final class AgentStore {

    public static final String ORIGIN_PERSONAL = "personal";

    /** One agent definition; the directory fields are stored paths, materialized on demand. */
    public record AgentRow(
            String agentId,
            String name,
            String description,
            String origin,
            String ownerUserId,
            String workspaceDir,
            String stateDir,
            long createdAt,
            long updatedAt,
            boolean enabled,
            boolean isPublic) {
        public AgentRow(
                String agentId,
                String name,
                String description,
                String origin,
                String ownerUserId,
                String workspaceDir,
                String stateDir,
                long createdAt,
                long updatedAt) {
            this(
                    agentId,
                    name,
                    description,
                    origin,
                    ownerUserId,
                    workspaceDir,
                    stateDir,
                    createdAt,
                    updatedAt,
                    true,
                    false);
        }

        public AgentRow(
                String agentId,
                String name,
                String description,
                String origin,
                String ownerUserId,
                String workspaceDir,
                String stateDir,
                long createdAt,
                long updatedAt,
                boolean enabled) {
            this(
                    agentId,
                    name,
                    description,
                    origin,
                    ownerUserId,
                    workspaceDir,
                    stateDir,
                    createdAt,
                    updatedAt,
                    enabled,
                    false);
        }
    }

    private static final String SELECT_COLUMNS =
            "agent_id, name, description, origin, owner_user_id, workspace_dir, state_dir,"
                    + " created_at, updated_at, enabled, is_public";

    private final DataSource dataSource;
    private final Path agentsRoot;

    public AgentStore(DataSource dataSource, Path agentsRoot) {
        this.dataSource = dataSource;
        this.agentsRoot = agentsRoot;
    }

    /**
     * Creates a personal agent owned by {@code ownerUserId}. Its workspace and state directory
     * live under {@code {agentsRoot}/{agentId}/} so every personal agent stays inside the app's
     * data dir.
     */
    public AgentRow create(String name, String description, String ownerUserId)
            throws SQLException {
        return create(name, description, ownerUserId, false);
    }

    public AgentRow create(String name, String description, String ownerUserId, boolean isPublic)
            throws SQLException {
        return createWithId(UUID.randomUUID().toString(), name, description, ownerUserId, isPublic);
    }

    public AgentRow createWithId(
            String agentId, String name, String description, String ownerUserId)
            throws SQLException {
        return createWithId(agentId, name, description, ownerUserId, false);
    }

    public AgentRow createWithId(
            String agentId, String name, String description, String ownerUserId, boolean isPublic)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return createWithId(
                    connection, agentId, name, description, ownerUserId, "active", isPublic);
        }
    }

    AgentRow createWithId(
            Connection connection,
            String agentId,
            String name,
            String description,
            String ownerUserId,
            String lifecycle)
            throws SQLException {
        return createWithId(connection, agentId, name, description, ownerUserId, lifecycle, false);
    }

    AgentRow createWithId(
            Connection connection,
            String agentId,
            String name,
            String description,
            String ownerUserId,
            String lifecycle,
            boolean isPublic)
            throws SQLException {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("Agent ID must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Agent name must not be blank");
        }
        String resolvedName = name.strip();
        String resolvedDescription = description == null ? "" : description.strip();
        String workspaceDir = agentsRoot.resolve(agentId).resolve("workspace").toString();
        String stateDir = agentsRoot.resolve(agentId).resolve("state").toString();
        long now = System.currentTimeMillis();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO advisor_agents (agent_id, name, description, origin,"
                            + " owner_user_id, workspace_dir, state_dir, created_at, updated_at,"
                            + " lifecycle, is_public) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, agentId);
            statement.setString(2, resolvedName);
            statement.setString(3, resolvedDescription);
            statement.setString(4, ORIGIN_PERSONAL);
            statement.setString(5, ownerUserId);
            statement.setString(6, workspaceDir);
            statement.setString(7, stateDir);
            statement.setLong(8, now);
            statement.setLong(9, now);
            statement.setString(10, lifecycle);
            statement.setInt(11, isPublic ? 1 : 0);
            statement.executeUpdate();
        }
        return new AgentRow(
                agentId,
                resolvedName,
                resolvedDescription,
                ORIGIN_PERSONAL,
                ownerUserId,
                workspaceDir,
                stateDir,
                now,
                now,
                true,
                isPublic);
    }

    /** All rows, used by the provisioner at startup. */
    public List<AgentRow> list() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT "
                                        + SELECT_COLUMNS
                                        + " FROM advisor_agents WHERE lifecycle = 'active' AND"
                                        + " enabled = 1 ORDER BY created_at");
                ResultSet rows = statement.executeQuery()) {
            List<AgentRow> agents = new ArrayList<>();
            while (rows.next()) {
                agents.add(fromCurrentRow(rows));
            }
            return agents;
        }
    }

    /** The caller's personal agents; admins see everything (TDS 3.4). */
    public List<AgentRow> listVisible(String userId, boolean isAdmin) throws SQLException {
        return listVisible(userId, isAdmin, null);
    }

    public List<AgentRow> listVisible(String userId, boolean isAdmin, String query)
            throws SQLException {
        return listVisible(userId, isAdmin, query, false);
    }

    public List<AgentRow> listAvailable(String userId, String query) throws SQLException {
        return listVisible(userId, false, query, true);
    }

    private List<AgentRow> listVisible(
            String userId, boolean isAdmin, String query, boolean enabledOnly) throws SQLException {
        String sql =
                "SELECT "
                        + SELECT_COLUMNS
                        + " FROM advisor_agents"
                        + (isAdmin
                                ? " WHERE lifecycle = 'active'"
                                : " WHERE lifecycle = 'active' AND (is_public = 1 OR owner_user_id"
                                        + " IS NULL OR owner_user_id = ?)")
                        + (enabledOnly ? " AND enabled = 1" : "")
                        + " ORDER BY created_at";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        io.deepscope.advisor.db.ListSearch.prepare(
                                connection,
                                sql,
                                query,
                                List.of("name", "description"),
                                isAdmin ? new Object[0] : new Object[] {userId});
                ResultSet rows = statement.executeQuery()) {
            List<AgentRow> agents = new ArrayList<>();
            while (rows.next()) agents.add(fromCurrentRow(rows));
            return agents;
        }
    }

    public io.deepscope.advisor.db.PageResult<AgentRow> pageVisible(
            String userId, boolean allOwners, String query, int page, int size)
            throws SQLException {
        return pageVisible(userId, allOwners, query, page, size, false);
    }

    public io.deepscope.advisor.db.PageResult<AgentRow> pageAvailable(
            String userId, String query, int page, int size) throws SQLException {
        return pageVisible(userId, false, query, page, size, true);
    }

    private io.deepscope.advisor.db.PageResult<AgentRow> pageVisible(
            String userId, boolean allOwners, String query, int page, int size, boolean enabledOnly)
            throws SQLException {
        var sql =
                new io.deepscope.advisor.db.JdbcPage.Query(
                                "SELECT " + SELECT_COLUMNS + " FROM advisor_agents",
                                "created_at, agent_id")
                        .where("lifecycle = 'active'")
                        .where(
                                allOwners
                                        ? "1 = 1"
                                        : "(is_public = 1 OR owner_user_id IS NULL OR owner_user_id"
                                                + " = ?)",
                                allOwners ? new Object[0] : new Object[] {userId})
                        .search(query, List.of("name", "description"));
        if (enabledOnly) sql.where("enabled = 1");
        try (var connection = dataSource.getConnection()) {
            return io.deepscope.advisor.db.JdbcPage.fetch(
                    connection,
                    sql,
                    page,
                    size,
                    statement -> {
                        List<AgentRow> result = new ArrayList<>();
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) result.add(fromCurrentRow(rows));
                        }
                        return result;
                    });
        }
    }

    public Optional<AgentRow> find(String agentId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT "
                                        + SELECT_COLUMNS
                                        + " FROM advisor_agents WHERE agent_id"
                                        + " = ? AND lifecycle = 'active'")) {
            statement.setString(1, agentId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(fromCurrentRow(rows)) : Optional.empty();
            }
        }
    }

    /** Updates the editable display fields of an agent. */
    public boolean update(String agentId, String name, String description) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_agents SET name = ?, description = ?, updated_at ="
                                        + " ? WHERE agent_id = ?")) {
            statement.setString(1, name);
            statement.setString(2, description);
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, agentId);
            return statement.executeUpdate() > 0;
        }
    }

    public boolean delete(String agentId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM advisor_agents WHERE agent_id = ?")) {
            statement.setString(1, agentId);
            return statement.executeUpdate() > 0;
        }
    }

    public boolean update(String agentId, String name, String description, boolean enabled)
            throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE advisor_agents SET name = ?, description = ?, enabled = ?,"
                                        + " updated_at = ? WHERE agent_id = ? AND lifecycle ="
                                        + " 'active'")) {
            statement.setString(1, name);
            statement.setString(2, description);
            statement.setInt(3, enabled ? 1 : 0);
            statement.setLong(4, System.currentTimeMillis());
            statement.setString(5, agentId);
            return statement.executeUpdate() > 0;
        }
    }

    public boolean update(
            String agentId, String name, String description, boolean enabled, boolean isPublic)
            throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE advisor_agents SET name = ?, description = ?, enabled = ?,"
                                        + " is_public = ?, updated_at = ? WHERE agent_id = ? AND"
                                        + " lifecycle = 'active'")) {
            statement.setString(1, name);
            statement.setString(2, description);
            statement.setInt(3, enabled ? 1 : 0);
            statement.setInt(4, isPublic ? 1 : 0);
            statement.setLong(5, System.currentTimeMillis());
            statement.setString(6, agentId);
            return statement.executeUpdate() > 0;
        }
    }

    /** Edit rights: personal rows belong to their owner — admins may edit anything (TDS 3.4). */
    public static boolean canView(AgentRow agent, String userId, boolean isAdmin) {
        return isAdmin
                || agent.isPublic()
                || agent.ownerUserId() == null
                || userId.equals(agent.ownerUserId());
    }

    public static boolean canEdit(AgentRow agent, String userId, boolean isAdmin) {
        return isAdmin || (agent.ownerUserId() != null && agent.ownerUserId().equals(userId));
    }

    private static AgentRow fromCurrentRow(ResultSet rows) throws SQLException {
        return new AgentRow(
                rows.getString("agent_id"),
                rows.getString("name"),
                rows.getString("description"),
                rows.getString("origin"),
                rows.getString("owner_user_id"),
                rows.getString("workspace_dir"),
                rows.getString("state_dir"),
                rows.getLong("created_at"),
                rows.getLong("updated_at"),
                rows.getInt("enabled") == 1,
                rows.getInt("is_public") == 1);
    }
}
