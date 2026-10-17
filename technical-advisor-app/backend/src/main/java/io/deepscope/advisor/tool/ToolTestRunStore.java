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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/** Durable test idempotency shares the existing change-request table under the test: namespace. */
final class ToolTestRunStore {
    private final DataSource dataSource;
    private final ObjectMapper json;

    ToolTestRunStore(ToolCatalog catalog, ObjectMapper json) {
        this.dataSource = catalog.dataSource();
        this.json = json;
    }

    record Outcome(
            String state,
            ToolHttpExecutor.Result result,
            Integer errorStatus,
            String errorCode,
            String errorMessage,
            Map<String, Object> errorDetails) {}

    record Stored(String digest, Outcome outcome) {
        ToolHttpExecutor.Result result() {
            if ("running".equals(outcome.state()))
                return ToolHttpExecutor.failure("execution_unknown", System.nanoTime());
            if (outcome.errorCode() != null)
                throw new ToolProblem(
                        outcome.errorStatus(),
                        outcome.errorCode(),
                        outcome.errorMessage(),
                        outcome.errorDetails() == null ? Map.of() : outcome.errorDetails());
            return outcome.result();
        }

        String status() {
            if ("running".equals(outcome.state())) return "unknown";
            if ("failed".equals(outcome.state())) return "failed";
            return outcome.result() != null
                            && "execution_cancelled".equals(outcome.result().errorCode())
                    ? "cancelled"
                    : "completed";
        }
    }

    Optional<Stored> find(String owner, String runId) {
        try (var connection = dataSource.getConnection();
                var query =
                        connection.prepareStatement(
                                "SELECT digest,result_json FROM advisor_tool_change_requests WHERE"
                                        + " owner_user_id=? AND request_key=?")) {
            query.setString(1, owner);
            query.setString(2, key(runId));
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(
                        new Stored(
                                rows.getString(1),
                                json.readValue(rows.getString(2), Outcome.class)));
            }
        } catch (Exception error) {
            throw unavailable();
        }
    }

    boolean reserve(String owner, String runId, String digest) {
        try (var connection = dataSource.getConnection();
                var insert =
                        connection.prepareStatement(
                                "INSERT INTO advisor_tool_change_requests"
                                    + " (owner_user_id,request_key,digest,result_json,created_at)"
                                    + " VALUES (?,?,?,?,?)")) {
            insert.setString(1, owner);
            insert.setString(2, key(runId));
            insert.setString(3, digest);
            insert.setString(4, "{\"state\":\"running\"}");
            insert.setLong(5, System.currentTimeMillis());
            insert.executeUpdate();
            return true;
        } catch (SQLException error) {
            if ("23505".equals(error.getSQLState())) return false;
            throw unavailable();
        }
    }

    void complete(
            String owner,
            String runId,
            String digest,
            ToolHttpExecutor.Result result,
            Throwable error) {
        ToolProblem problem =
                error instanceof ToolProblem known
                        ? known
                        : error != null
                                ? new ToolProblem(502, "connection_failed", "Test execution failed")
                                : null;
        Outcome outcome =
                problem == null
                        ? new Outcome("completed", result, null, null, null, null)
                        : new Outcome(
                                "failed",
                                null,
                                problem.status(),
                                problem.code(),
                                problem.getMessage(),
                                problem.body());
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE advisor_tool_change_requests SET result_json=? WHERE"
                                        + " owner_user_id=? AND request_key=? AND digest=?")) {
            update.setString(1, json.writeValueAsString(outcome));
            update.setString(2, owner);
            update.setString(3, key(runId));
            update.setString(4, digest);
            if (update.executeUpdate() != 1) throw unavailable();
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    private static String key(String runId) {
        return "test:" + runId;
    }

    private static ToolProblem unavailable() {
        return new ToolProblem(
                503,
                "execution_record_unavailable",
                "The execution record is unavailable; no new request will be sent without it");
    }
}
