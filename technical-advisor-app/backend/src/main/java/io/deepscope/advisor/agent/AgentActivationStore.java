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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Database boundaries of activation; files and the registry are prepared between transactions. */
final class AgentActivationStore {
    private final DataSource dataSource;
    private final ObjectMapper json;

    record Reservation(String operationId, String agentId, boolean acquired) {}

    AgentActivationStore(DataSource dataSource, ObjectMapper json) {
        this.dataSource = dataSource;
        this.json = json;
    }

    Reservation reserve(String draftId, int revision, String key, String hash) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String previousId;
                try (var statement =
                        connection.prepareStatement(
                                "SELECT revision, activation_state, activation_operation_id,"
                                    + " activated_agent_id FROM advisor_agent_creation_drafts WHERE"
                                    + " draft_id = ? FOR UPDATE")) {
                    statement.setString(1, draftId);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                        String state = rows.getString("activation_state");
                        if ("needs_review".equals(state))
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "activation_recovery_required");
                        if ("activated".equals(state) || "publishing".equals(state)) {
                            var result =
                                    new Reservation(
                                            rows.getString("activation_operation_id"),
                                            rows.getString("activated_agent_id"),
                                            false);
                            connection.commit();
                            return result;
                        }
                        if (rows.getInt("revision") != revision)
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "revision_conflict");
                        previousId = rows.getString("activated_agent_id");
                    }
                }
                String operation = UUID.randomUUID().toString();
                String agent = previousId == null ? "agent-" + UUID.randomUUID() : previousId;
                try (var query =
                        connection.prepareStatement(
                                "SELECT operation_id, request_hash FROM"
                                    + " advisor_agent_creation_operations WHERE draft_id = ? AND"
                                    + " operation_type = 'activation' AND request_key = ?")) {
                    query.setString(1, draftId);
                    query.setString(2, key);
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) {
                            if (!hash.equals(rows.getString("request_hash")))
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "idempotency_conflict");
                            operation = rows.getString("operation_id");
                        } else {
                            try (var insert =
                                    connection.prepareStatement(
                                            "INSERT INTO"
                                                + " advisor_agent_creation_operations(operation_id,draft_id,operation_type,request_key,request_hash,base_revision,state,stage,preallocated_agent_id,started_at)"
                                                + " VALUES"
                                                + " (?,?,'activation',?,?,?,'running','staging',?,?)")) {
                                insert.setString(1, operation);
                                insert.setString(2, draftId);
                                insert.setString(3, key);
                                insert.setString(4, hash);
                                insert.setInt(5, revision);
                                insert.setString(6, agent);
                                insert.setLong(7, System.currentTimeMillis());
                                insert.executeUpdate();
                            }
                        }
                    }
                }
                try (var statement =
                        connection.prepareStatement(
                                "UPDATE advisor_agent_creation_drafts SET"
                                    + " activation_state='publishing', activation_operation_id=?,"
                                    + " activated_agent_id=? WHERE draft_id=?")) {
                    statement.setString(1, operation);
                    statement.setString(2, agent);
                    statement.setString(3, draftId);
                    statement.executeUpdate();
                }
                try (var statement =
                        connection.prepareStatement(
                                "UPDATE advisor_agent_creation_operations SET state='running',"
                                        + " stage='staging', finished_at=NULL, error_code=NULL,"
                                        + " error_message=NULL WHERE operation_id=?")) {
                    statement.setString(1, operation);
                    statement.executeUpdate();
                }
                connection.commit();
                return new Reservation(operation, agent, true);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    AgentStore.AgentRow stage(
            Reservation reservation, AgentCreationDraftStore.DraftRow draft, AgentStore agents)
            throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!strings(draft.resourcesJson()).isEmpty())
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_draft_resources");
                var row =
                        agents.createWithId(
                                connection,
                                reservation.agentId(),
                                draft.name(),
                                draft.description(),
                                draft.ownerUserId(),
                                "staging");
                for (String tool : strings(draft.toolsJson())) {
                    checkResource(
                            connection,
                            "SELECT 1 FROM advisor_tools WHERE tool_id=? AND owner_user_id=? AND"
                                + " approved=1 AND permission <> 'deny' AND origin <> 'declared'",
                            tool,
                            draft.ownerUserId());
                    bind(
                            connection,
                            "INSERT INTO advisor_agent_tools(agent_id,tool_id) VALUES (?,?)",
                            row.agentId(),
                            tool);
                }
                for (String skill : strings(draft.skillsJson())) {
                    checkResource(
                            connection,
                            "SELECT 1 FROM advisor_skills WHERE skill_id=? AND owner_user_id=? AND"
                                    + " status='published'",
                            skill,
                            draft.ownerUserId());
                    bind(
                            connection,
                            "INSERT INTO advisor_agent_skills(agent_id,skill_id) VALUES (?,?)",
                            row.agentId(),
                            skill);
                }
                connection.commit();
                return row;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private List<String> strings(String value) {
        try {
            var items = json.readValue(value, new TypeReference<List<String>>() {});
            if (items == null
                    || items.stream().anyMatch(item -> item == null || item.isBlank())
                    || items.stream().distinct().count() != items.size())
                throw new IllegalArgumentException();
            return items;
        } catch (Exception error) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_draft_capabilities", error);
        }
    }

    private void checkResource(Connection connection, String sql, String id, String owner)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, owner);
            try (var rows = statement.executeQuery()) {
                if (!rows.next())
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "invalid_draft_capabilities");
            }
        }
    }

    private void bind(Connection connection, String sql, String agent, String id)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, agent);
            statement.setString(2, id);
            statement.executeUpdate();
        }
    }

    void finish(String draftId, Reservation reservation, boolean success) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement =
                        connection.prepareStatement(
                                "UPDATE advisor_agent_creation_drafts SET activation_state=? WHERE"
                                        + " draft_id=? AND activation_operation_id=? AND"
                                        + " activation_state='publishing'")) {
                    statement.setString(1, success ? "activated" : "draft");
                    statement.setString(2, draftId);
                    statement.setString(3, reservation.operationId());
                    if (statement.executeUpdate() != 1)
                        throw new SQLException("Activation ownership changed");
                }
                if (success) {
                    try (var statement =
                            connection.prepareStatement(
                                    "UPDATE advisor_agents SET lifecycle='active' WHERE agent_id=?"
                                            + " AND lifecycle='staging'")) {
                        statement.setString(1, reservation.agentId());
                        if (statement.executeUpdate() != 1)
                            throw new SQLException("Prepared agent missing");
                    }
                } else {
                    for (String table :
                            List.of(
                                    "advisor_agent_tools",
                                    "advisor_agent_skills",
                                    "advisor_agent_servers",
                                    "advisor_agent_libraries")) {
                        try (var statement =
                                connection.prepareStatement(
                                        "DELETE FROM " + table + " WHERE agent_id=?")) {
                            statement.setString(1, reservation.agentId());
                            statement.executeUpdate();
                        }
                    }
                    try (var statement =
                            connection.prepareStatement(
                                    "DELETE FROM advisor_agents WHERE agent_id=? AND"
                                            + " lifecycle='staging'")) {
                        statement.setString(1, reservation.agentId());
                        statement.executeUpdate();
                    }
                }
                try (var statement =
                        connection.prepareStatement(
                                "UPDATE advisor_agent_creation_operations SET state=?, stage=?,"
                                        + " finished_at=?, error_code=? WHERE operation_id=?")) {
                    statement.setString(1, success ? "succeeded" : "failed");
                    statement.setString(2, success ? "completed" : "failed");
                    statement.setLong(3, System.currentTimeMillis());
                    statement.setString(4, success ? null : "activation_failed");
                    statement.setString(5, reservation.operationId());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }
}
