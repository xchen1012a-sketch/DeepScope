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
package io.agentscope.advisor.agent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persistence for asynchronous draft operations (candidate generation and activation).
 *
 * <p>Enforces idempotency keys per operation type and tracks operational lifecycle states
 * (queued, running, succeeded, failed, interrupted, canceled).
 */
public final class AgentCreationOperationStore {

    public static final String TYPE_GENERATION = "generation";
    public static final String TYPE_ACTIVATION = "activation";

    public static final String STATE_QUEUED = "queued";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_SUCCEEDED = "succeeded";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_INTERRUPTED = "interrupted";
    public static final String STATE_CANCELED = "canceled";

    private static final String SELECT_COLUMNS =
            "operation_id, draft_id, operation_type, request_key, request_hash, base_revision,"
                    + " state, stage, candidate_json, preallocated_agent_id, error_code,"
                    + " error_message, started_at, finished_at";

    private final DataSource dataSource;

    public AgentCreationOperationStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record CreationOperation(
            String operationId,
            String draftId,
            String operationType,
            String requestKey,
            String requestHash,
            int baseRevision,
            String state,
            String stage,
            String candidateJson,
            String preallocatedAgentId,
            String errorCode,
            String errorMessage,
            long startedAt,
            Long finishedAt) {}

    public CreationOperation create(
            String draftId,
            String operationType,
            String requestKey,
            String requestHash,
            int baseRevision)
            throws SQLException {
        if (draftId == null || draftId.isBlank()) {
            throw new IllegalArgumentException("Draft ID must not be blank");
        }
        String opType =
                operationType == null || operationType.isBlank()
                        ? TYPE_GENERATION
                        : operationType.strip();
        String reqKey =
                requestKey == null || requestKey.isBlank()
                        ? UUID.randomUUID().toString()
                        : requestKey.strip();
        String reqHash = requestHash == null ? "" : requestHash.strip();

        Optional<CreationOperation> existing = findByDraftAndKey(draftId, opType, reqKey);
        if (existing.isPresent()) {
            CreationOperation op = existing.get();
            if (Objects.equals(op.requestHash(), reqHash)) {
                return op;
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency conflict: operation key already used with different request hash");
        }

        String operationId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String initialState = STATE_QUEUED;
        String initialStage = "queued";

        String sql =
                "INSERT INTO advisor_agent_creation_operations (operation_id, draft_id,"
                        + " operation_type, request_key, request_hash, base_revision, state, stage,"
                        + " started_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, operationId);
            statement.setString(2, draftId);
            statement.setString(3, opType);
            statement.setString(4, reqKey);
            statement.setString(5, reqHash);
            statement.setInt(6, baseRevision);
            statement.setString(7, initialState);
            statement.setString(8, initialStage);
            statement.setLong(9, now);
            statement.executeUpdate();
        }

        return new CreationOperation(
                operationId,
                draftId,
                opType,
                reqKey,
                reqHash,
                baseRevision,
                initialState,
                initialStage,
                null,
                null,
                null,
                null,
                now,
                null);
    }

    public Optional<CreationOperation> findById(String operationId) throws SQLException {
        String sql =
                "SELECT "
                        + SELECT_COLUMNS
                        + " FROM advisor_agent_creation_operations WHERE operation_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, operationId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return Optional.of(fromCurrentRow(rows));
                }
                return Optional.empty();
            }
        }
    }

    public Optional<CreationOperation> findByDraftAndKey(
            String draftId, String operationType, String requestKey) throws SQLException {
        String sql =
                "SELECT "
                        + SELECT_COLUMNS
                        + " FROM advisor_agent_creation_operations WHERE draft_id = ? AND"
                        + " operation_type = ? AND request_key = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, draftId);
            statement.setString(2, operationType);
            statement.setString(3, requestKey);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return Optional.of(fromCurrentRow(rows));
                }
                return Optional.empty();
            }
        }
    }

    public void updateState(
            String operationId,
            String state,
            String stage,
            String candidateJson,
            String preallocatedAgentId,
            String errorCode,
            String errorMessage)
            throws SQLException {
        boolean isFinished =
                STATE_SUCCEEDED.equals(state)
                        || STATE_FAILED.equals(state)
                        || STATE_INTERRUPTED.equals(state)
                        || STATE_CANCELED.equals(state);
        Long finishedAt = isFinished ? System.currentTimeMillis() : null;

        String sql =
                "UPDATE advisor_agent_creation_operations SET state = ?, stage = ?, candidate_json"
                    + " = COALESCE(?, candidate_json), preallocated_agent_id = COALESCE(?,"
                    + " preallocated_agent_id), error_code = ?, error_message = ?, finished_at = ?"
                    + " WHERE operation_id = ? AND (operation_type <> 'generation' OR state IN"
                    + " ('queued','running'))";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state);
            statement.setString(2, stage == null ? "" : stage);
            if (candidateJson != null) {
                statement.setString(3, candidateJson);
            } else {
                statement.setNull(3, Types.VARCHAR);
            }
            if (preallocatedAgentId != null) {
                statement.setString(4, preallocatedAgentId);
            } else {
                statement.setNull(4, Types.VARCHAR);
            }
            if (errorCode != null) {
                statement.setString(5, errorCode);
            } else {
                statement.setNull(5, Types.VARCHAR);
            }
            if (errorMessage != null) {
                statement.setString(6, errorMessage);
            } else {
                statement.setNull(6, Types.VARCHAR);
            }
            if (finishedAt != null) {
                statement.setLong(7, finishedAt);
            } else {
                statement.setNull(7, Types.BIGINT);
            }
            statement.setString(8, operationId);
            statement.executeUpdate();
        }
    }

    public int markInterruptedOnStartup() throws SQLException {
        long now = System.currentTimeMillis();
        String sql =
                "UPDATE advisor_agent_creation_operations SET state = ?, stage = ?,"
                        + " finished_at = ? WHERE state IN (?, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, STATE_INTERRUPTED);
            statement.setString(2, "interrupted_on_startup");
            statement.setLong(3, now);
            statement.setString(4, STATE_QUEUED);
            statement.setString(5, STATE_RUNNING);
            return statement.executeUpdate();
        }
    }

    public boolean isActivationPending(String agentId) throws SQLException {
        if (agentId == null || agentId.isBlank()) return false;
        String sql =
                "SELECT 1 FROM advisor_agent_creation_operations WHERE preallocated_agent_id = ?"
                        + " AND state != ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, agentId);
            statement.setString(2, STATE_SUCCEEDED);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static CreationOperation fromCurrentRow(ResultSet rows) throws SQLException {
        long finishedVal = rows.getLong("finished_at");
        Long finishedAt = rows.wasNull() ? null : finishedVal;
        return new CreationOperation(
                rows.getString("operation_id"),
                rows.getString("draft_id"),
                rows.getString("operation_type"),
                rows.getString("request_key"),
                rows.getString("request_hash"),
                rows.getInt("base_revision"),
                rows.getString("state"),
                rows.getString("stage"),
                rows.getString("candidate_json"),
                rows.getString("preallocated_agent_id"),
                rows.getString("error_code"),
                rows.getString("error_message"),
                rows.getLong("started_at"),
                finishedAt);
    }
}
