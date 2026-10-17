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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The single tool catalog plus the MCP services those tools may belong to.
 *
 * <p>Nothing ships with the app: rows are created by imports, HTTP declarations, or MCP
 * discovery, and belong to their creator. An agent selection names tools, or a whole MCP
 * service. Selecting a service does not approve its tools; approval is a separate gate.
 */
public final class ToolCatalog {

    public static final String ORIGIN_DECLARED = "declared";
    public static final String ORIGIN_HTTP = "http";
    public static final String ORIGIN_MCP = "mcp";
    public static final String PERMISSION_ALLOW = "allow";
    public static final String PERMISSION_ASK = "ask";
    public static final String PERMISSION_DENY = "deny";

    private final DataSource dataSource;
    private final ObjectMapper json;

    public ToolCatalog(DataSource dataSource, ObjectMapper json) {
        this.dataSource = dataSource;
        this.json = json;
    }

    DataSource dataSource() {
        return dataSource;
    }

    public List<ToolRow> listTools(String userId, boolean admin) throws SQLException {
        return listTools(userId, admin, null);
    }

    public List<ToolRow> listTools(String userId, boolean admin, String query) throws SQLException {
        String sql =
                "SELECT "
                        + TOOL_COLUMNS
                        + " FROM advisor_tools WHERE owner_user_id = ?"
                        + " ORDER BY origin, name";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        io.deepscope.advisor.db.ListSearch.prepare(
                                connection,
                                sql,
                                query,
                                List.of("name", "description", "http_url"),
                                userId)) {
            return tools(statement);
        }
    }

    public io.deepscope.advisor.db.PageResult<ToolRow> pageTools(
            String userId, boolean admin, String query, String permission, int page, int size)
            throws SQLException {
        return pageTools(userId, admin, query, permission, List.of(), page, size);
    }

    public io.deepscope.advisor.db.PageResult<ToolRow> pageTools(
            String userId,
            boolean admin,
            String query,
            String permission,
            List<String> origins,
            int page,
            int size)
            throws SQLException {
        var sql =
                new io.deepscope.advisor.db.JdbcPage.Query(
                                "SELECT " + TOOL_COLUMNS + " FROM advisor_tools",
                                "origin, name, tool_id")
                        .where("owner_user_id = ?", userId)
                        .equal("permission", permission)
                        .search(query, List.of("name", "description", "http_url"));
        if (origins != null && !origins.isEmpty()) {
            sql.where(
                    "origin IN ("
                            + origins.stream()
                                    .map(origin -> "?")
                                    .collect(java.util.stream.Collectors.joining(", "))
                            + ")",
                    origins.toArray());
        }
        try (var connection = dataSource.getConnection()) {
            return io.deepscope.advisor.db.JdbcPage.fetch(connection, sql, page, size, this::tools);
        }
    }

    public Optional<ToolRow> findTool(String toolId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT "
                                        + TOOL_COLUMNS
                                        + " FROM advisor_tools WHERE tool_id = ?")) {
            statement.setString(1, toolId);
            List<ToolRow> rows = tools(statement);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        }
    }

    /** A saved resource can remain visible in management while external execution is disabled. */
    public boolean executionEnabled(String toolId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT 1 FROM advisor_tools t WHERE t.tool_id = ? AND"
                                        + " t.lifecycle_status = 'ready' AND (t.origin <> 'mcp' OR"
                                        + " EXISTS (SELECT 1 FROM advisor_mcp_servers s WHERE"
                                        + " s.server_id = t.mcp_server_id AND s.owner_user_id ="
                                        + " t.owner_user_id AND s.status IN ('connected',"
                                        + " 'error')))")) {
            statement.setString(1, toolId);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    List<ToolRow> listTools(Connection connection, String userId, boolean admin)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT " + TOOL_COLUMNS + " FROM advisor_tools WHERE owner_user_id=?")) {
            statement.setString(1, userId);
            return tools(statement);
        }
    }

    ToolRow findTool(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT " + TOOL_COLUMNS + " FROM advisor_tools WHERE tool_id=?")) {
            statement.setString(1, id);
            var found = tools(statement);
            return found.isEmpty() ? null : found.get(0);
        }
    }

    public List<ToolRow> importTools(
            String ownerUserId, List<ToolImport.ImportedTool> imported, String permission)
            throws SQLException {
        return importTools(ownerUserId, imported, permission, null);
    }

    /**
     * Imports in one transaction. A prepared credential joins the same transaction, so a rejected
     * batch never leaves a stored secret pointing at a tool that was never created.
     */
    public List<ToolRow> importTools(
            String ownerUserId,
            List<ToolImport.ImportedTool> imported,
            String permission,
            ToolCredentialStore.Pending credential)
            throws SQLException {
        String level = permissionOf(permission);
        List<String> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ToolCredentialStore.insert(connection, credential);
                for (ToolImport.ImportedTool tool : imported)
                    ids.add(insertTool(connection, tool, ownerUserId, level, tool.mcpServerId()));
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
        List<ToolRow> created = new ArrayList<>();
        for (String id : ids) created.add(findTool(id).orElseThrow());
        return created;
    }

    /**
     * Applies an edited definition. {@code expectedRevision} is the version the editor loaded; a
     * mismatch means someone else saved first, so nothing is written and the caller reports a
     * conflict.
     */
    public boolean updateDefinition(
            String id, ToolImport.ImportedTool definition, String permission, long expectedRevision)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return updateDefinition(connection, id, definition, permission, expectedRevision);
        }
    }

    boolean updateDefinition(
            Connection connection,
            String id,
            ToolImport.ImportedTool definition,
            String permission,
            long expectedRevision)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "UPDATE advisor_tools SET name=?, description=?, input_schema=?,"
                                + " origin=?, http_method=?, http_url=?, mcp_server_id=?,"
                                + " permission=?, config_json=?, updated_at=?, revision ="
                                + " revision + 1 WHERE tool_id=? AND revision=?")) {
            statement.setString(1, definition.name());
            statement.setString(2, definition.description());
            statement.setString(3, definition.inputSchema());
            statement.setString(4, definition.origin());
            statement.setString(5, definition.httpMethod());
            statement.setString(6, definition.httpUrl());
            statement.setString(7, definition.mcpServerId());
            statement.setString(8, permissionOf(permission));
            statement.setString(
                    9, definition.configJson() == null ? "{}" : definition.configJson());
            statement.setLong(10, System.currentTimeMillis());
            statement.setString(11, id);
            statement.setLong(12, expectedRevision);
            return statement.executeUpdate() > 0;
        }
    }

    public boolean updatePermission(String toolId, String permission) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_tools SET permission = ?, updated_at = ?,"
                                        + " revision = revision + 1 WHERE tool_id = ?")) {
            statement.setString(1, permissionOf(permission));
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, toolId);
            return statement.executeUpdate() > 0;
        }
    }

    public boolean deleteTool(String toolId) throws SQLException {
        return deleteTool(toolId, null);
    }

    public boolean deleteTool(String toolId, Long expectedRevision) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (expectedRevision != null) {
                    try (PreparedStatement check =
                            connection.prepareStatement(
                                    "SELECT revision FROM advisor_tools WHERE tool_id = ?")) {
                        check.setString(1, toolId);
                        try (ResultSet rs = check.executeQuery()) {
                            if (!rs.next() || rs.getLong(1) != expectedRevision) {
                                connection.rollback();
                                return false;
                            }
                        }
                    }
                }
                boolean deleted = deleteTool(connection, toolId);
                connection.commit();
                return deleted;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    boolean deleteTool(Connection connection, String toolId) throws SQLException {
        try (PreparedStatement selection =
                connection.prepareStatement("DELETE FROM advisor_agent_tools WHERE tool_id = ?")) {
            selection.setString(1, toolId);
            selection.executeUpdate();
        }
        try (PreparedStatement statement =
                connection.prepareStatement("DELETE FROM advisor_tools WHERE tool_id = ?")) {
            statement.setString(1, toolId);
            return statement.executeUpdate() > 0;
        }
    }

    public ServerRow insertServer(
            McpEndpointParser.McpEndpoint endpoint,
            String ownerUserId,
            String status,
            String message)
            throws SQLException {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO advisor_mcp_servers (server_id, name, transport,"
                                    + " command, args_json, env_json, url, headers_json, status,"
                                    + " status_message, owner_user_id, created_at, updated_at)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, endpoint.name());
            statement.setString(3, endpoint.transport());
            statement.setString(4, endpoint.command());
            statement.setString(5, write(endpoint.args()));
            statement.setString(6, write(endpoint.env()));
            statement.setString(7, endpoint.url());
            statement.setString(8, write(endpoint.headers()));
            statement.setString(9, status);
            statement.setString(10, message == null ? "" : message);
            statement.setString(11, ownerUserId);
            statement.setLong(12, now);
            statement.setLong(13, now);
            statement.executeUpdate();
        }
        return findServer(id).orElseThrow();
    }

    public void markServer(String serverId, String status, String message) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_mcp_servers SET status = ?, status_message = ?,"
                                        + " updated_at = ? WHERE server_id = ?")) {
            statement.setString(1, status);
            statement.setString(2, message == null ? "" : message);
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, serverId);
            statement.executeUpdate();
        }
    }

    public boolean updateServer(
            String serverId, McpEndpointParser.McpEndpoint endpoint, long expectedRevision)
            throws SQLException {
        long now = System.currentTimeMillis();
        String sql =
                "UPDATE advisor_mcp_servers SET name = ?, transport = ?, command = ?, args_json ="
                        + " ?, env_json = ?, url = ?, headers_json = ?, status = 'connected',"
                        + " status_message = '', updated_at = ?, revision = revision + 1 WHERE"
                        + " server_id = ? AND revision = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, endpoint.name());
            statement.setString(2, endpoint.transport());
            statement.setString(3, endpoint.command());
            statement.setString(4, write(endpoint.args()));
            statement.setString(5, write(endpoint.env()));
            statement.setString(6, endpoint.url());
            statement.setString(7, write(endpoint.headers()));
            statement.setLong(8, now);
            statement.setString(9, serverId);
            statement.setLong(10, expectedRevision);
            return statement.executeUpdate() > 0;
        }
    }

    public List<ServerRow> listServers(String userId, boolean admin) throws SQLException {
        return listServers(userId, admin, null);
    }

    public List<ServerRow> listServers(String userId, boolean admin, String query)
            throws SQLException {
        boolean searching = query != null && !query.isBlank();
        String sql =
                "SELECT "
                        + SERVER_COLUMNS
                        + " FROM advisor_mcp_servers WHERE (owner_user_id = ?"
                        + ")"
                        + (searching
                                ? " AND (LOWER(name) LIKE ? ESCAPE '!' OR"
                                        + " LOWER(COALESCE(command,'')) LIKE ? ESCAPE '!' OR"
                                        + " LOWER(COALESCE(url,'')) LIKE ? ESCAPE '!' OR EXISTS"
                                        + " (SELECT 1 FROM advisor_tools t WHERE"
                                        + " t.mcp_server_id=advisor_mcp_servers.server_id AND"
                                        + " LOWER(t.name) LIKE ? ESCAPE '!'))"
                                : "")
                        + " ORDER BY name";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            if (searching) {
                String pattern = io.deepscope.advisor.db.ListSearch.pattern(query);
                for (int i = 2; i <= 5; i++) statement.setString(i, pattern);
            }
            return servers(statement);
        }
    }

    public io.deepscope.advisor.db.PageResult<ServerRow> pageServers(
            String userId, boolean admin, String query, String status, int page, int size)
            throws SQLException {
        var sql =
                new io.deepscope.advisor.db.JdbcPage.Query(
                                "SELECT " + SERVER_COLUMNS + " FROM advisor_mcp_servers",
                                "name, server_id")
                        .where("owner_user_id = ?", userId)
                        .equal("status", status);
        if (query != null && !query.isBlank()) {
            String pattern = io.deepscope.advisor.db.ListSearch.pattern(query);
            sql.where(
                    "LOWER(name) LIKE ? ESCAPE '!' OR LOWER(COALESCE(command,'')) LIKE ? ESCAPE '!'"
                        + " OR LOWER(COALESCE(url,'')) LIKE ? ESCAPE '!' OR EXISTS (SELECT 1 FROM"
                        + " advisor_tools t WHERE t.mcp_server_id=advisor_mcp_servers.server_id AND"
                        + " LOWER(t.name) LIKE ? ESCAPE '!')",
                    pattern,
                    pattern,
                    pattern,
                    pattern);
        }
        try (var connection = dataSource.getConnection()) {
            return io.deepscope.advisor.db.JdbcPage.fetch(
                    connection, sql, page, size, this::servers);
        }
    }

    public Optional<ServerRow> findServer(String serverId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT "
                                        + SERVER_COLUMNS
                                        + " FROM advisor_mcp_servers WHERE server_id = ?")) {
            statement.setString(1, serverId);
            List<ServerRow> rows = servers(statement);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        }
    }

    public boolean deleteServer(String serverId) throws SQLException {
        return deleteServer(serverId, null);
    }

    public boolean deleteServer(String serverId, Long expectedRevision) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (expectedRevision != null) {
                    try (PreparedStatement check =
                            connection.prepareStatement(
                                    "SELECT revision FROM advisor_mcp_servers WHERE server_id ="
                                            + " ?")) {
                        check.setString(1, serverId);
                        try (ResultSet rs = check.executeQuery()) {
                            if (!rs.next() || rs.getLong(1) != expectedRevision) {
                                connection.rollback();
                                return false;
                            }
                        }
                    }
                }
                try (PreparedStatement tools =
                        connection.prepareStatement(
                                "DELETE FROM advisor_agent_tools WHERE tool_id IN (SELECT tool_id"
                                        + " FROM advisor_tools WHERE mcp_server_id = ?)")) {
                    tools.setString(1, serverId);
                    tools.executeUpdate();
                }
                try (PreparedStatement tools =
                        connection.prepareStatement(
                                "DELETE FROM advisor_tools WHERE mcp_server_id = ?")) {
                    tools.setString(1, serverId);
                    tools.executeUpdate();
                }
                try (PreparedStatement servers =
                        connection.prepareStatement(
                                "DELETE FROM advisor_agent_servers WHERE server_id = ?")) {
                    servers.setString(1, serverId);
                    servers.executeUpdate();
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM advisor_mcp_servers WHERE server_id = ?")) {
                    statement.setString(1, serverId);
                    boolean deleted = statement.executeUpdate() > 0;
                    connection.commit();
                    return deleted;
                }
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    public List<ToolRow> toolsOfServer(String serverId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT "
                                        + TOOL_COLUMNS
                                        + " FROM advisor_tools WHERE mcp_server_id = ? ORDER BY"
                                        + " name")) {
            statement.setString(1, serverId);
            return tools(statement);
        }
    }

    public ToolRow approveMcpTool(ServerRow server, DiscoveredTool discovered, String permission)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return approveMcpTool(connection, server, discovered, permission);
        }
    }

    ToolRow approveMcpTool(
            Connection connection, ServerRow server, DiscoveredTool discovered, String permission)
            throws SQLException {
        Optional<ToolRow> existing =
                findByServerAndName(connection, server.serverId(), discovered.name());
        if (existing.isPresent()) {
            try (var statement =
                    connection.prepareStatement(
                            "UPDATE advisor_tools SET permission=?, updated_at=?,"
                                    + " revision=revision+1 WHERE tool_id=?")) {
                statement.setString(1, permissionOf(permission));
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, existing.get().toolId());
                statement.executeUpdate();
            }
            return findTool(connection, existing.get().toolId());
        }
        String id =
                insertTool(
                        connection,
                        new ToolImport.ImportedTool(
                                discovered.name(),
                                discovered.description(),
                                discovered.inputSchema(),
                                ORIGIN_MCP,
                                null,
                                null),
                        server.ownerUserId(),
                        permissionOf(permission),
                        server.serverId());
        return findTool(connection, id);
    }

    public boolean updateServerPendingJson(String serverId, String pendingJson)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return updateServerPendingJson(connection, serverId, pendingJson);
        }
    }

    boolean updateServerPendingJson(Connection connection, String serverId, String pendingJson)
            throws SQLException {
        long now = System.currentTimeMillis();
        String sql =
                "UPDATE advisor_mcp_servers SET pending_json = ?, updated_at = ? WHERE server_id ="
                        + " ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pendingJson == null ? "[]" : pendingJson);
            statement.setLong(2, now);
            statement.setString(3, serverId);
            return statement.executeUpdate() > 0;
        }
    }

    public int impactedAgentCountForTool(String toolId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(DISTINCT agent_id) FROM advisor_agent_tools WHERE"
                                        + " tool_id = ?")) {
            statement.setString(1, toolId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public List<String> impactedAgentIdsForTool(String toolId) throws SQLException {
        List<String> agentIds = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT DISTINCT agent_id FROM advisor_agent_tools WHERE tool_id ="
                                        + " ? ORDER BY agent_id")) {
            statement.setString(1, toolId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    agentIds.add(rs.getString(1));
                }
            }
        }
        return agentIds;
    }

    public List<String> impactedAgentIdsForServer(String serverId) throws SQLException {
        List<String> agentIds = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT DISTINCT agent_id FROM advisor_agent_tools WHERE tool_id IN"
                                        + " (SELECT tool_id FROM advisor_tools WHERE mcp_server_id"
                                        + " = ?) UNION SELECT DISTINCT agent_id FROM"
                                        + " advisor_agent_servers WHERE server_id = ? ORDER BY"
                                        + " agent_id")) {
            statement.setString(1, serverId);
            statement.setString(2, serverId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    agentIds.add(rs.getString(1));
                }
            }
        }
        return agentIds;
    }

    public void removeMissingMcpTools(String serverId, List<String> liveNames) throws SQLException {
        for (ToolRow tool : toolsOfServer(serverId)) {
            if (!liveNames.contains(tool.name())) {
                deleteTool(tool.toolId());
            }
        }
    }

    public Selection selection(String agentId) throws SQLException {
        List<String> toolIds = new ArrayList<>();
        List<String> serverIds = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT tool_id FROM advisor_agent_tools WHERE agent_id = ?")) {
                statement.setString(1, agentId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        toolIds.add(rows.getString(1));
                    }
                }
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT server_id FROM advisor_agent_servers WHERE agent_id = ?")) {
                statement.setString(1, agentId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        serverIds.add(rows.getString(1));
                    }
                }
            }
        }
        return new Selection(toolIds, serverIds);
    }

    public void replaceSelection(String agentId, List<String> toolIds, List<String> serverIds)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "DELETE FROM advisor_agent_tools WHERE agent_id = ?")) {
                statement.setString(1, agentId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "DELETE FROM advisor_agent_servers WHERE agent_id = ?")) {
                statement.setString(1, agentId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO advisor_agent_tools (agent_id, tool_id) VALUES (?, ?)")) {
                for (String toolId : toolIds) {
                    statement.setString(1, agentId);
                    statement.setString(2, toolId);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO advisor_agent_servers (agent_id, server_id) VALUES (?,"
                                    + " ?)")) {
                for (String serverId : serverIds) {
                    statement.setString(1, agentId);
                    statement.setString(2, serverId);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }
    }

    /** Approved tools this agent actually selected, including every approved tool of a subscribed server. */
    public List<ToolRow> effectiveTools(String agentId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT "
                                        + TOOL_COLUMNS
                                        + " FROM advisor_tools t WHERE t.approved = 1 AND t.origin"
                                        + " <> 'declared' AND t.lifecycle_status = 'ready' AND"
                                        + " (t.origin <> 'mcp' OR EXISTS (SELECT 1 FROM"
                                        + " advisor_mcp_servers m WHERE m.server_id ="
                                        + " t.mcp_server_id AND m.owner_user_id = t.owner_user_id"
                                        + " AND m.status IN ('connected', 'error'))) AND (EXISTS"
                                        + " (SELECT 1 FROM advisor_agent_tools a WHERE a.agent_id ="
                                        + " ? AND a.tool_id = t.tool_id) OR EXISTS (SELECT 1 FROM"
                                        + " advisor_agent_servers s WHERE s.agent_id = ? AND"
                                        + " s.server_id = t.mcp_server_id)) ORDER BY t.name")) {
            statement.setString(1, agentId);
            statement.setString(2, agentId);
            return tools(statement);
        }
    }

    public McpEndpointParser.McpEndpoint endpointOf(ServerRow server) {
        return new McpEndpointParser.McpEndpoint(
                server.name(),
                server.transport(),
                server.command(),
                readList(server.argsJson()),
                readMap(server.envJson()),
                server.url(),
                readMap(server.headersJson()));
    }

    public static boolean canView(String ownerUserId, String origin, String userId, boolean admin) {
        return userId != null && userId.equals(ownerUserId);
    }

    public static boolean canEdit(String ownerUserId, String origin, String userId, boolean admin) {
        return userId != null && userId.equals(ownerUserId);
    }

    public static String permissionOf(String permission) {
        if (PERMISSION_ALLOW.equals(permission)
                || PERMISSION_ASK.equals(permission)
                || PERMISSION_DENY.equals(permission)) {
            return permission;
        }
        throw new IllegalArgumentException("Unknown permission");
    }

    private Optional<ToolRow> findByServerAndName(String serverId, String name)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return findByServerAndName(connection, serverId, name);
        }
    }

    private Optional<ToolRow> findByServerAndName(
            Connection connection, String serverId, String name) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT "
                                + TOOL_COLUMNS
                                + " FROM advisor_tools WHERE mcp_server_id = ? AND name ="
                                + " ?")) {
            statement.setString(1, serverId);
            statement.setString(2, name);
            List<ToolRow> rows = tools(statement);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        }
    }

    private ToolRow insertTool(
            ToolImport.ImportedTool tool, String ownerUserId, String permission, String serverId)
            throws SQLException {
        String id;
        try (Connection connection = dataSource.getConnection()) {
            id = insertTool(connection, tool, ownerUserId, permission, serverId);
        }
        return findTool(id).orElseThrow();
    }

    String insertTool(
            Connection connection,
            ToolImport.ImportedTool tool,
            String ownerUserId,
            String permission,
            String serverId)
            throws SQLException {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO advisor_tools (tool_id, name, description, input_schema,"
                                + " origin, permission, owner_user_id, mcp_server_id, http_method,"
                                + " http_url, approved, created_at, updated_at, config_json) VALUES"
                                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, tool.name());
            statement.setString(3, tool.description());
            statement.setString(4, tool.inputSchema());
            statement.setString(5, tool.origin());
            statement.setString(6, permission);
            statement.setString(7, ownerUserId);
            statement.setString(8, serverId);
            statement.setString(9, tool.httpMethod());
            statement.setString(10, tool.httpUrl());
            statement.setLong(11, now);
            statement.setLong(12, now);
            statement.setString(13, tool.configJson() == null ? "{}" : tool.configJson());
            statement.executeUpdate();
        }
        return id;
    }

    private List<ToolRow> tools(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            List<ToolRow> tools = new ArrayList<>();
            while (rows.next()) {
                tools.add(
                        new ToolRow(
                                rows.getString("tool_id"),
                                rows.getString("name"),
                                rows.getString("description"),
                                rows.getString("input_schema"),
                                rows.getString("origin"),
                                rows.getString("permission"),
                                rows.getString("owner_user_id"),
                                rows.getString("mcp_server_id"),
                                rows.getString("http_method"),
                                rows.getString("http_url"),
                                rows.getInt("approved") == 1,
                                rows.getLong("created_at"),
                                rows.getLong("updated_at"),
                                rows.getLong("revision"),
                                rows.getString("config_json")));
            }
            return tools;
        }
    }

    private List<ServerRow> servers(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            List<ServerRow> servers = new ArrayList<>();
            while (rows.next()) {
                String pendingJson = "[]";
                try {
                    pendingJson = rows.getString("pending_json");
                    if (pendingJson == null) pendingJson = "[]";
                } catch (Exception ignored) {
                }
                servers.add(
                        new ServerRow(
                                rows.getString("server_id"),
                                rows.getString("name"),
                                rows.getString("transport"),
                                rows.getString("command"),
                                rows.getString("args_json"),
                                rows.getString("env_json"),
                                rows.getString("url"),
                                rows.getString("headers_json"),
                                rows.getString("status"),
                                rows.getString("status_message"),
                                rows.getString("owner_user_id"),
                                rows.getLong("created_at"),
                                rows.getLong("updated_at"),
                                rows.getLong("revision"),
                                pendingJson));
            }
            return servers;
        }
    }

    private String write(Object value) throws SQLException {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new SQLException("Could not store MCP connection", error);
        }
    }

    private List<String> readList(String value) {
        try {
            return json.readValue(value, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private Map<String, String> readMap(String value) {
        try {
            return json.readValue(value, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }

    private static final String TOOL_COLUMNS =
            "tool_id, name, description, input_schema, origin, permission, owner_user_id,"
                    + " mcp_server_id, http_method, http_url, approved, created_at, updated_at,"
                    + " revision, config_json";

    private static final String SERVER_COLUMNS =
            "server_id, name, transport, command, args_json, env_json, url, headers_json, status,"
                + " status_message, owner_user_id, created_at, updated_at, revision, pending_json";

    public record ToolRow(
            String toolId,
            String name,
            String description,
            String inputSchema,
            String origin,
            String permission,
            String ownerUserId,
            String mcpServerId,
            String httpMethod,
            String httpUrl,
            boolean approved,
            long createdAt,
            long updatedAt,
            long revision,
            String configJson) {}

    public record ServerRow(
            String serverId,
            String name,
            String transport,
            String command,
            String argsJson,
            String envJson,
            String url,
            String headersJson,
            String status,
            String statusMessage,
            String ownerUserId,
            long createdAt,
            long updatedAt,
            long revision,
            String pendingJson) {
        public ServerRow(
                String serverId,
                String name,
                String transport,
                String command,
                String argsJson,
                String envJson,
                String url,
                String headersJson,
                String status,
                String statusMessage,
                String ownerUserId,
                long createdAt,
                long updatedAt,
                long revision) {
            this(
                    serverId,
                    name,
                    transport,
                    command,
                    argsJson,
                    envJson,
                    url,
                    headersJson,
                    status,
                    statusMessage,
                    ownerUserId,
                    createdAt,
                    updatedAt,
                    revision,
                    "[]");
        }
    }

    public record Selection(List<String> toolIds, List<String> serverIds) {}

    public record DiscoveredTool(String name, String description, String inputSchema) {}
}
