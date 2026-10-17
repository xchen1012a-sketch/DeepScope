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
package io.deepscope.advisor.account;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;

/** Account-owned defaults, independent of the system model and existing conversations. */
public final class AccountPreferencesStore {
    public record Preferences(
            boolean thinkEnabled,
            String reasoningEffort,
            String defaultModelId,
            String defaultAgentId,
            boolean initialized) {}

    private final DataSource dataSource;

    public AccountPreferencesStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Preferences read(String userId) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return read(connection, userId);
        }
    }

    private Preferences read(Connection connection, String userId) throws SQLException {
        try (var query =
                connection.prepareStatement(
                        "SELECT think_enabled, reasoning_effort, default_model_id,"
                                + " default_agent_id FROM advisor_account_preferences WHERE user_id"
                                + " = ?")) {
            query.setString(1, userId);
            try (var rows = query.executeQuery()) {
                return rows.next()
                        ? new Preferences(
                                rows.getInt(1) == 1,
                                rows.getString(2),
                                rows.getString(3),
                                rows.getString(4),
                                true)
                        : new Preferences(false, "high", "", "", false);
            }
        }
    }

    /** Null fields are omitted, so simultaneous partial updates cannot erase each other. */
    public Optional<Preferences> write(
            String userId,
            Boolean think,
            String effort,
            String model,
            String agent,
            boolean initializeOnly)
            throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Lock the existing account even when its preferences row has not been created yet.
                try (var lock =
                        connection.prepareStatement(
                                "SELECT user_id FROM accounts WHERE user_id = ? FOR UPDATE")) {
                    lock.setString(1, userId);
                    try (var rows = lock.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Account no longer exists");
                    }
                }
                var previous = read(connection, userId);
                if (initializeOnly && previous.initialized()) {
                    connection.rollback();
                    return Optional.empty();
                }
                var value =
                        new Preferences(
                                think == null ? previous.thinkEnabled() : think,
                                effort == null ? previous.reasoningEffort() : effort,
                                model == null ? previous.defaultModelId() : model,
                                agent == null ? previous.defaultAgentId() : agent,
                                true);
                try (var update =
                        connection.prepareStatement(
                                "UPDATE advisor_account_preferences SET think_enabled=?,"
                                    + " reasoning_effort=?, default_model_id=?, default_agent_id=?,"
                                    + " updated_at=? WHERE user_id=?")) {
                    bind(update, userId, value);
                    if (update.executeUpdate() == 0) {
                        try (var insert =
                                connection.prepareStatement(
                                        "INSERT INTO advisor_account_preferences (think_enabled,"
                                                + " reasoning_effort, default_model_id,"
                                                + " default_agent_id, updated_at, user_id) VALUES"
                                                + " (?, ?, ?, ?, ?, ?)")) {
                            bind(insert, userId, value);
                            insert.executeUpdate();
                        }
                    }
                }
                connection.commit();
                return Optional.of(value);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private void bind(java.sql.PreparedStatement statement, String userId, Preferences value)
            throws SQLException {
        statement.setInt(1, value.thinkEnabled() ? 1 : 0);
        statement.setString(2, value.reasoningEffort());
        statement.setString(3, value.defaultModelId());
        statement.setString(4, value.defaultAgentId());
        statement.setLong(5, System.currentTimeMillis());
        statement.setString(6, userId);
    }
}
