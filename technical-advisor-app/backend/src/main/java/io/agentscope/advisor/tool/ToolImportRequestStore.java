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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** Import replay snapshots and account serialization use the existing change-request table. */
final class ToolImportRequestStore {
    private final DataSource source;
    private final ObjectMapper json;

    ToolImportRequestStore(ToolCatalog catalog, ObjectMapper advisorObjectMapper) {
        source = catalog.dataSource();
        json = advisorObjectMapper;
    }

    @FunctionalInterface
    interface Work {
        List<ToolCatalog.ToolRow> apply(Connection connection) throws SQLException;
    }

    Optional<List<ToolCatalog.ToolRow>> find(String owner, String key, String digest)
            throws SQLException {
        if (key == null) return Optional.empty();
        try (Connection connection = source.getConnection()) {
            return find(connection, owner, key, digest);
        }
    }

    Optional<List<ToolCatalog.ToolRow>> findUnderLock(String owner, String key, String digest)
            throws SQLException {
        if (key == null) return Optional.empty();
        ensureLock(owner);
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var lock =
                        connection.prepareStatement(
                                "UPDATE advisor_tool_change_requests SET created_at=? WHERE"
                                        + " owner_user_id=? AND request_key='import:lock'")) {
                    lock.setLong(1, System.currentTimeMillis());
                    lock.setString(2, owner);
                    if (lock.executeUpdate() != 1) throw new SQLException("Missing import lock");
                }
                var replay = find(connection, owner, key, digest);
                connection.commit();
                return replay;
            } catch (Exception error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw sql;
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new SQLException("Could not check import replay", error);
            }
        }
    }

    List<ToolCatalog.ToolRow> commit(String owner, String key, String digest, Work work)
            throws SQLException {
        ensureLock(owner);
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // This row lock arbitrates independent app instances, including requests without
                // keys.
                // SQLite also acquires its write lock here, before the first transactional read.
                try (var lock =
                        connection.prepareStatement(
                                "UPDATE advisor_tool_change_requests SET created_at=? WHERE"
                                        + " owner_user_id=? AND request_key='import:lock'")) {
                    lock.setLong(1, System.currentTimeMillis());
                    lock.setString(2, owner);
                    if (lock.executeUpdate() != 1) throw new SQLException("Missing import lock");
                }
                var replay = find(connection, owner, key, digest);
                if (replay.isPresent()) {
                    connection.commit();
                    return replay.get();
                }
                List<ToolCatalog.ToolRow> result = work.apply(connection);
                if (key != null) {
                    try (var insert =
                            connection.prepareStatement(
                                    "INSERT INTO advisor_tool_change_requests"
                                        + " (owner_user_id,request_key,digest,result_json,created_at)"
                                        + " VALUES (?,?,?,?,?)")) {
                        insert.setString(1, owner);
                        insert.setString(2, requestKey(key));
                        insert.setString(3, digest);
                        insert.setString(4, json.writeValueAsString(result));
                        insert.setLong(5, System.currentTimeMillis());
                        insert.executeUpdate();
                    }
                }
                connection.commit();
                return result;
            } catch (Exception error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw sql;
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new SQLException("Could not store import result", error);
            }
        }
    }

    private void ensureLock(String owner) throws SQLException {
        try (Connection connection = source.getConnection();
                var insert =
                        connection.prepareStatement(
                                "INSERT INTO advisor_tool_change_requests"
                                    + " (owner_user_id,request_key,digest,result_json,created_at)"
                                    + " VALUES (?,'import:lock','lock','[]',?)")) {
            insert.setString(1, owner);
            insert.setLong(2, System.currentTimeMillis());
            insert.executeUpdate();
        } catch (SQLException error) {
            if (!"23505".equals(error.getSQLState()) && error.getErrorCode() != 19) throw error;
        }
    }

    private Optional<List<ToolCatalog.ToolRow>> find(
            Connection connection, String owner, String key, String digest) throws SQLException {
        if (key == null) return Optional.empty();
        try (var query =
                connection.prepareStatement(
                        "SELECT digest,result_json FROM advisor_tool_change_requests WHERE"
                                + " owner_user_id=? AND request_key=?")) {
            query.setString(1, owner);
            query.setString(2, requestKey(key));
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                if (!digest.equals(rows.getString(1)))
                    throw new ToolProblem(
                            409,
                            "idempotency_conflict",
                            "This key was already used for different import content");
                try {
                    return Optional.of(
                            json.readValue(
                                    rows.getString(2),
                                    new TypeReference<List<ToolCatalog.ToolRow>>() {}));
                } catch (java.io.IOException error) {
                    throw new SQLException("Invalid stored import result", error);
                }
            }
        }
    }

    private static String requestKey(String key) {
        return "import:request:" + key;
    }
}
