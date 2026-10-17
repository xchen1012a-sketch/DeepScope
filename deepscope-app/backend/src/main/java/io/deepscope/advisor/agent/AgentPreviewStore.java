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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Persistence for isolated agent preview trial sessions.
 *
 * <p>Preview sessions are strictly isolated from official SessionStore and TimelineStore.
 */
public final class AgentPreviewStore {

    public static final Duration PREVIEW_TTL = Duration.ofHours(24);

    public record PreviewRow(
            String previewId,
            String draftId,
            int draftRevision,
            String ownerUserId,
            String state,
            String historyJson,
            long createdAt,
            long updatedAt,
            long expiresAt) {}

    private final DataSource dataSource;

    public AgentPreviewStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public PreviewRow create(String draftId, int draftRevision, String ownerUserId)
            throws SQLException {
        String previewId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expiresAt = now + PREVIEW_TTL.toMillis();
        String initialHistory = "[]";
        String initialState = "idle";

        String sql =
                "INSERT INTO advisor_agent_preview_sessions (preview_id, draft_id, draft_revision,"
                        + " owner_user_id, state, history_json, created_at, updated_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, previewId);
            statement.setString(2, draftId);
            statement.setInt(3, draftRevision);
            statement.setString(4, ownerUserId);
            statement.setString(5, initialState);
            statement.setString(6, initialHistory);
            statement.setLong(7, now);
            statement.setLong(8, now);
            statement.setLong(9, expiresAt);
            statement.executeUpdate();
        }

        return new PreviewRow(
                previewId,
                draftId,
                draftRevision,
                ownerUserId,
                initialState,
                initialHistory,
                now,
                now,
                expiresAt);
    }

    public Optional<PreviewRow> findById(String previewId) throws SQLException {
        String sql =
                "SELECT preview_id, draft_id, draft_revision, owner_user_id, state, history_json,"
                        + " created_at, updated_at, expires_at FROM"
                        + " advisor_agent_preview_sessions WHERE preview_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, previewId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return Optional.of(fromCurrentRow(rows));
                }
                return Optional.empty();
            }
        }
    }

    public void updateHistory(String previewId, String state, String historyJson)
            throws SQLException {
        long now = System.currentTimeMillis();
        String sql =
                "UPDATE advisor_agent_preview_sessions SET state = ?, history_json = ?,"
                        + " updated_at = ? WHERE preview_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state);
            statement.setString(2, historyJson);
            statement.setLong(3, now);
            statement.setString(4, previewId);
            statement.executeUpdate();
        }
    }

    public boolean delete(String previewId) throws SQLException {
        String sql = "DELETE FROM advisor_agent_preview_sessions WHERE preview_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, previewId);
            return statement.executeUpdate() > 0;
        }
    }

    public int deleteExpired() throws SQLException {
        long now = System.currentTimeMillis();
        String sql = "DELETE FROM advisor_agent_preview_sessions WHERE expires_at < ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            return statement.executeUpdate();
        }
    }

    private static PreviewRow fromCurrentRow(ResultSet rows) throws SQLException {
        return new PreviewRow(
                rows.getString("preview_id"),
                rows.getString("draft_id"),
                rows.getInt("draft_revision"),
                rows.getString("owner_user_id"),
                rows.getString("state"),
                rows.getString("history_json"),
                rows.getLong("created_at"),
                rows.getLong("updated_at"),
                rows.getLong("expires_at"));
    }
}
