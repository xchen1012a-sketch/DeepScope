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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persistence and concurrency control for agent creation drafts.
 *
 * <p>Drafts are strictly isolated from the runtime registry and official agent tables. Every draft
 * update uses optimistic revision locking (If-Match / 409 Conflict).
 */
public final class AgentCreationDraftStore {

    public static final String MODE_DESCRIBE = "describe";
    public static final String MODE_COPY = "copy";
    public static final String MODE_MANUAL = "manual";

    private static final String SELECT_COLUMNS =
            "draft_id, owner_user_id, mode, source_agent_id, purpose, name, description, behavior,"
                    + " tools_json, skills_json, resources_json, request_key, request_hash,"
                    + " revision, created_at, updated_at";

    private final DataSource dataSource;

    public AgentCreationDraftStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    DataSource dataSource() {
        return dataSource;
    }

    public record Activation(String state, String agentId, String operationId) {}

    public Activation activation(String draftId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT activation_state, activated_agent_id,"
                                    + " activation_operation_id FROM advisor_agent_creation_drafts"
                                    + " WHERE draft_id = ?")) {
            statement.setString(1, draftId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                return new Activation(rows.getString(1), rows.getString(2), rows.getString(3));
            }
        }
    }

    public void requireEditable(String draftId) throws SQLException {
        if (!"draft".equals(activation(draftId).state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "draft_already_activated");
    }

    public record DraftRow(
            String draftId,
            String ownerUserId,
            String mode,
            String sourceAgentId,
            String purpose,
            String name,
            String description,
            String behavior,
            String toolsJson,
            String skillsJson,
            String resourcesJson,
            String requestKey,
            String requestHash,
            int revision,
            long createdAt,
            long updatedAt) {}

    public record DraftUpdate(
            String purpose,
            String name,
            String description,
            String behavior,
            String toolsJson,
            String skillsJson,
            String resourcesJson) {}

    /**
     * Creates a new draft or returns the existing one for an idempotent requestKey. Throws 409 if
     * the same requestKey is submitted with a different requestHash.
     */
    public DraftRow create(
            String ownerUserId,
            String mode,
            String sourceAgentId,
            String purpose,
            String name,
            String description,
            String behavior,
            String toolsJson,
            String skillsJson,
            String resourcesJson,
            String requestKey,
            String requestHash)
            throws SQLException {
        if (ownerUserId == null || ownerUserId.isBlank()) {
            throw new IllegalArgumentException("Draft owner user ID must not be blank");
        }
        String resolvedMode = mode == null || mode.isBlank() ? MODE_DESCRIBE : mode.strip();
        String resolvedPurpose = purpose == null ? "" : purpose.strip();
        String resolvedName = name == null ? "" : name.strip();
        String resolvedDesc = description == null ? "" : description.strip();
        String resolvedBehavior = behavior == null ? "" : behavior.strip();
        String resolvedTools = toolsJson == null || toolsJson.isBlank() ? "[]" : toolsJson.strip();
        String resolvedSkills =
                skillsJson == null || skillsJson.isBlank() ? "[]" : skillsJson.strip();
        String resolvedResources =
                resourcesJson == null || resourcesJson.isBlank() ? "[]" : resourcesJson.strip();
        String resolvedKey = requestKey == null || requestKey.isBlank() ? null : requestKey.strip();
        String resolvedHash =
                requestHash == null || requestHash.isBlank() ? null : requestHash.strip();

        if (resolvedKey != null) {
            Optional<DraftRow> existing = findByOwnerAndKey(ownerUserId, resolvedKey);
            if (existing.isPresent()) {
                DraftRow draft = existing.get();
                if (Objects.equals(draft.requestHash(), resolvedHash)) {
                    return draft;
                }
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency conflict: key already used with different request content");
            }
        }

        String draftId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        int initialRevision = 1;

        String sql =
                "INSERT INTO advisor_agent_creation_drafts (draft_id, owner_user_id, mode,"
                        + " source_agent_id, purpose, name, description, behavior, tools_json,"
                        + " skills_json, resources_json, request_key, request_hash, revision,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, draftId);
            statement.setString(2, ownerUserId);
            statement.setString(3, resolvedMode);
            statement.setString(4, sourceAgentId);
            statement.setString(5, resolvedPurpose);
            statement.setString(6, resolvedName);
            statement.setString(7, resolvedDesc);
            statement.setString(8, resolvedBehavior);
            statement.setString(9, resolvedTools);
            statement.setString(10, resolvedSkills);
            statement.setString(11, resolvedResources);
            statement.setString(12, resolvedKey);
            statement.setString(13, resolvedHash);
            statement.setInt(14, initialRevision);
            statement.setLong(15, now);
            statement.setLong(16, now);
            statement.executeUpdate();
        }

        return new DraftRow(
                draftId,
                ownerUserId,
                resolvedMode,
                sourceAgentId,
                resolvedPurpose,
                resolvedName,
                resolvedDesc,
                resolvedBehavior,
                resolvedTools,
                resolvedSkills,
                resolvedResources,
                resolvedKey,
                resolvedHash,
                initialRevision,
                now,
                now);
    }

    public Optional<DraftRow> findById(String draftId) throws SQLException {
        String sql =
                "SELECT "
                        + SELECT_COLUMNS
                        + " FROM advisor_agent_creation_drafts WHERE draft_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, draftId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return Optional.of(fromCurrentRow(rows));
                }
                return Optional.empty();
            }
        }
    }

    public Optional<DraftRow> findByOwnerAndKey(String ownerUserId, String requestKey)
            throws SQLException {
        String sql =
                "SELECT "
                        + SELECT_COLUMNS
                        + " FROM advisor_agent_creation_drafts WHERE owner_user_id = ? AND"
                        + " request_key = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUserId);
            statement.setString(2, requestKey);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return Optional.of(fromCurrentRow(rows));
                }
                return Optional.empty();
            }
        }
    }

    public List<DraftRow> listVisible(String userId, boolean isAdmin) throws SQLException {
        return listVisible(userId, isAdmin, null);
    }

    public List<DraftRow> listVisible(String userId, boolean isAdmin, String query)
            throws SQLException {
        String sql =
                "SELECT "
                        + SELECT_COLUMNS
                        + " FROM advisor_agent_creation_drafts"
                        + (isAdmin
                                ? " WHERE activation_state = 'draft'"
                                : " WHERE activation_state = 'draft' AND owner_user_id = ?")
                        + " ORDER BY updated_at DESC";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        io.agentscope.advisor.db.ListSearch.prepare(
                                connection,
                                sql,
                                query,
                                List.of("name", "description", "purpose"),
                                isAdmin ? new Object[0] : new Object[] {userId})) {
            try (ResultSet rows = statement.executeQuery()) {
                List<DraftRow> drafts = new ArrayList<>();
                while (rows.next()) {
                    drafts.add(fromCurrentRow(rows));
                }
                return drafts;
            }
        }
    }

    /**
     * Atomically updates the draft if the current revision matches expectedRevision. Throws 409
     * when the revision is stale.
     */
    public DraftRow update(String draftId, int expectedRevision, DraftUpdate update)
            throws SQLException {
        requireEditable(draftId);
        Optional<DraftRow> existing = findById(draftId);
        if (existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found");
        }

        long now = System.currentTimeMillis();
        String sql =
                "UPDATE advisor_agent_creation_drafts SET purpose = ?, name = ?, description = ?,"
                        + " behavior = ?, tools_json = ?, skills_json = ?, resources_json = ?,"
                        + " revision = revision + 1, updated_at = ? WHERE draft_id = ? AND revision"
                        + " = ? AND activation_state = 'draft'";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, update.purpose() == null ? "" : update.purpose().strip());
            statement.setString(2, update.name() == null ? "" : update.name().strip());
            statement.setString(
                    3, update.description() == null ? "" : update.description().strip());
            statement.setString(4, update.behavior() == null ? "" : update.behavior().strip());
            statement.setString(
                    5,
                    update.toolsJson() == null || update.toolsJson().isBlank()
                            ? "[]"
                            : update.toolsJson().strip());
            statement.setString(
                    6,
                    update.skillsJson() == null || update.skillsJson().isBlank()
                            ? "[]"
                            : update.skillsJson().strip());
            statement.setString(
                    7,
                    update.resourcesJson() == null || update.resourcesJson().isBlank()
                            ? "[]"
                            : update.resourcesJson().strip());
            statement.setLong(8, now);
            statement.setString(9, draftId);
            statement.setInt(10, expectedRevision);

            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Draft was modified concurrently (revision conflict: expected "
                                + expectedRevision
                                + ")");
            }
        }

        return findById(draftId).orElseThrow();
    }

    public boolean delete(String draftId) throws SQLException {
        String sql =
                "DELETE FROM advisor_agent_creation_drafts WHERE draft_id = ? AND activation_state"
                        + " = 'draft'";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, draftId);
            if (statement.executeUpdate() > 0) return true;
            if (findById(draftId).isPresent())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "draft_already_activated");
            return false;
        }
    }

    public static boolean canView(DraftRow draft, String userId, boolean isAdmin) {
        return isAdmin || (userId != null && userId.equals(draft.ownerUserId()));
    }

    public static boolean canEdit(DraftRow draft, String userId, boolean isAdmin) {
        return isAdmin
                || (userId != null
                        && draft.ownerUserId() != null
                        && draft.ownerUserId().equals(userId));
    }

    private static DraftRow fromCurrentRow(ResultSet rows) throws SQLException {
        return new DraftRow(
                rows.getString("draft_id"),
                rows.getString("owner_user_id"),
                rows.getString("mode"),
                rows.getString("source_agent_id"),
                rows.getString("purpose"),
                rows.getString("name"),
                rows.getString("description"),
                rows.getString("behavior"),
                rows.getString("tools_json"),
                rows.getString("skills_json"),
                rows.getString("resources_json"),
                rows.getString("request_key"),
                rows.getString("request_hash"),
                rows.getInt("revision"),
                rows.getLong("created_at"),
                rows.getLong("updated_at"));
    }
}
