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
package io.deepscope.advisor.session;

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
 * Advisor chat sessions in PostgreSQL, one row per conversation and always owned by exactly one
 * account (per-user isolation). The session id doubles as the AG-UI thread id so the agent runtime
 * and the business timeline share one identity.
 */
public final class SessionStore {

    /** Session lifecycle mirrors TDS 3.3: idle → running → awaiting_visitor → idle, failed anytime. */
    public static final String STATUS_RUNNING = "running";

    public static final String STATUS_AWAITING_VISITOR = "awaiting_visitor";
    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_FAILED = "failed";

    /**
     * Terminal state for a run that never reached a recorded outcome: the client dropped mid-turn
     * (refresh, disconnect, stop) so no closing event was ever appended. Stale running rows are
     * closed into this state by {@link #closeStaleRunning(long)} instead of sticking on
     * "running" forever.
     */
    public static final String STATUS_INTERRUPTED = "interrupted";

    /** No run may legitimately exceed the 10-minute turn budget; add a safety margin. */
    private static final long STALE_RUNNING_MILLIS = 15 * 60 * 1000L;

    /** A chat session as shown in the sidebar. */
    public record SessionRow(
            String sessionId,
            String ownerUserId,
            String title,
            String status,
            String agentId,
            long createdAt,
            long updatedAt,
            String modelEndpointId) {
        public SessionRow(
                String sessionId,
                String ownerUserId,
                String title,
                String status,
                String agentId,
                long createdAt,
                long updatedAt) {
            this(sessionId, ownerUserId, title, status, agentId, createdAt, updatedAt, null);
        }
    }

    private final DataSource dataSource;

    public SessionStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public SessionRow create(String ownerUserId, String title) throws SQLException {
        return create(ownerUserId, title, null);
    }

    /** Creates a session, optionally bound to one agent (lock mode; null means free mode). */
    public SessionRow create(String ownerUserId, String title, String agentId) throws SQLException {
        String sessionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String resolvedTitle = title == null || title.isBlank() ? "" : title.strip();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO advisor_sessions (session_id, owner_user_id, title,"
                                + " status, agent_id, created_at, updated_at, model_endpoint_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, (SELECT endpoint_id FROM"
                                + " advisor_model_endpoints WHERE kind='chat' AND current_flag=1"
                                + " AND (is_public=1 OR (owner_user_id IS NOT NULL AND"
                                + " owner_user_id=?)) ORDER BY CASE WHEN owner_user_id=? THEN 0"
                                + " ELSE 1 END LIMIT 1))")) {
                statement.setString(1, sessionId);
                statement.setString(2, ownerUserId);
                statement.setString(3, resolvedTitle);
                statement.setString(4, STATUS_IDLE);
                statement.setString(5, agentId);
                statement.setLong(6, now);
                statement.setLong(7, now);
                statement.setString(8, ownerUserId == null ? "" : ownerUserId);
                statement.setString(9, ownerUserId == null ? "" : ownerUserId);
                statement.executeUpdate();
            }
        }
        return find(sessionId).orElseThrow();
    }

    public List<SessionRow> list(String ownerUserId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT session_id, owner_user_id, title, status, agent_id,"
                                        + " created_at, updated_at, model_endpoint_id FROM"
                                        + " advisor_sessions WHERE owner_user_id = ? ORDER BY"
                                        + " updated_at DESC")) {
            statement.setString(1, ownerUserId);
            try (ResultSet rows = statement.executeQuery()) {
                List<SessionRow> sessions = new ArrayList<>();
                while (rows.next()) {
                    sessions.add(fromCurrentRow(rows));
                }
                return sessions;
            }
        }
    }

    public Optional<SessionRow> find(String sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT session_id, owner_user_id, title, status, agent_id,"
                                        + " created_at, updated_at, model_endpoint_id FROM"
                                        + " advisor_sessions WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(fromCurrentRow(rows)) : Optional.empty();
            }
        }
    }

    /** Locks a session to one agent, the persistence behind session routing. */
    public boolean bindAgent(String sessionId, String agentId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_sessions SET agent_id = ?, updated_at = ? WHERE"
                                        + " session_id = ?")) {
            statement.setString(1, agentId);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, sessionId);
            return statement.executeUpdate() > 0;
        }
    }

    /** Persists only this owner's conversation choice; null resumes the system default. */
    public boolean selectModel(String sessionId, String ownerUserId, String endpointId)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_sessions SET model_endpoint_id = ?, updated_at = ?"
                                        + " WHERE session_id = ? AND owner_user_id = ?")) {
            statement.setString(1, endpointId);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, sessionId);
            statement.setString(4, ownerUserId);
            return statement.executeUpdate() > 0;
        }
    }

    public boolean rename(String sessionId, String ownerUserId, String title) throws SQLException {
        if (title == null || title.isBlank()) {
            return false;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_sessions SET title = ?, updated_at = ? WHERE"
                                        + " session_id = ? AND owner_user_id = ?")) {
            statement.setString(1, title.strip());
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, sessionId);
            statement.setString(4, ownerUserId);
            return statement.executeUpdate() > 0;
        }
    }

    public boolean delete(String sessionId, String ownerUserId) throws SQLException {
        return delete(sessionId, ownerUserId, false);
    }

    /** Atomically refuses a batch item's deletion after a concurrent run has started. */
    public boolean deleteIfNotRunning(String sessionId, String ownerUserId) throws SQLException {
        return delete(sessionId, ownerUserId, true);
    }

    private boolean delete(String sessionId, String ownerUserId, boolean requireIdle)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "DELETE FROM advisor_sessions WHERE session_id = ? AND"
                                    + " owner_user_id = ?"
                                    + (requireIdle ? " AND status <> 'running'" : ""))) {
                statement.setString(1, sessionId);
                statement.setString(2, ownerUserId);
                return statement.executeUpdate() > 0;
            }
        }
    }

    /**
     * Closes every "running" session whose last update is older than the run budget into the
     * interrupted terminal state. Called on read paths (list/detail) so a dropped client can no
     * longer leave a session stuck on "running" forever.
     *
     * @param now current epoch millis
     * @return number of rows closed
     */
    public int closeStaleRunning(long now) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_sessions SET status = ?, updated_at = ? WHERE"
                                        + " status = ? AND updated_at < ?")) {
            statement.setString(1, STATUS_INTERRUPTED);
            statement.setLong(2, now);
            statement.setString(3, STATUS_RUNNING);
            statement.setLong(4, now - STALE_RUNNING_MILLIS);
            return statement.executeUpdate();
        }
    }

    public List<SessionRow> staleRunning(long now) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT * FROM advisor_sessions WHERE status = ? AND updated_at"
                                        + " < ?")) {
            statement.setString(1, STATUS_RUNNING);
            statement.setLong(2, now - STALE_RUNNING_MILLIS);
            try (ResultSet rows = statement.executeQuery()) {
                List<SessionRow> result = new ArrayList<>();
                while (rows.next()) result.add(fromCurrentRow(rows));
                return result;
            }
        }
    }

    public void interruptIfStale(String sessionId, long now) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_sessions SET status = ?, updated_at = ? WHERE"
                                        + " session_id = ? AND status = ? AND updated_at < ?")) {
            statement.setString(1, STATUS_INTERRUPTED);
            statement.setLong(2, now);
            statement.setString(3, sessionId);
            statement.setString(4, STATUS_RUNNING);
            statement.setLong(5, now - STALE_RUNNING_MILLIS);
            statement.executeUpdate();
        }
    }

    public void updateStatus(String sessionId, String status) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_sessions SET status = ?, updated_at = ? WHERE"
                                        + " session_id = ?")) {
            statement.setString(1, status);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, sessionId);
            statement.executeUpdate();
        }
    }

    private static SessionRow fromCurrentRow(ResultSet rows) throws SQLException {
        return new SessionRow(
                rows.getString("session_id"),
                rows.getString("owner_user_id"),
                rows.getString("title"),
                rows.getString("status"),
                rows.getString("agent_id"),
                rows.getLong("created_at"),
                rows.getLong("updated_at"),
                rows.getString("model_endpoint_id"));
    }
}
