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
package io.agentscope.advisor.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Skill catalog. Drafts stay out of the live directory. An agent only receives published skills
 * it has checked.
 */
public final class SkillCatalog {

    public static final String DRAFT = "draft";
    public static final String PUBLISHED = "published";
    public static final String PERSONAL = "personal";

    private final DataSource dataSource;
    private final Path root;

    public SkillCatalog(DataSource dataSource, Path root) {
        this.dataSource = dataSource;
        this.root = root;
    }

    public List<SkillRow> list(String userId, boolean admin) throws SQLException {
        return list(userId, admin, null);
    }

    public List<SkillRow> list(String userId, boolean admin, String query) throws SQLException {
        String sql =
                "SELECT "
                        + COLUMNS
                        + " FROM advisor_skills WHERE owner_user_id = ?"
                        + " ORDER BY origin, name";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        io.agentscope.advisor.db.ListSearch.prepare(
                                connection, sql, query, List.of("name", "description"), userId)) {
            return read(statement);
        }
    }

    public io.agentscope.advisor.db.PageResult<SkillRow> page(
            String userId, boolean admin, String query, String status, int page, int size)
            throws SQLException {
        var sql =
                new io.agentscope.advisor.db.JdbcPage.Query(
                                "SELECT " + COLUMNS + " FROM advisor_skills",
                                "origin, name, skill_id")
                        .where("owner_user_id = ?", userId)
                        .equal("status", status)
                        .search(query, List.of("name", "description"));
        try (var connection = dataSource.getConnection()) {
            return io.agentscope.advisor.db.JdbcPage.fetch(connection, sql, page, size, this::read);
        }
    }

    public Optional<SkillRow> find(String skillId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT " + COLUMNS + " FROM advisor_skills WHERE skill_id = ?")) {
            statement.setString(1, skillId);
            List<SkillRow> rows = read(statement);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        }
    }

    public SkillRow createDraft(
            String ownerUserId, String name, String description, String markdown)
            throws SQLException, IOException {
        return createDraft(ownerUserId, name, description, markdown, Map.of());
    }

    public SkillRow createDraft(
            String ownerUserId,
            String name,
            String description,
            String markdown,
            Map<String, String> resources)
            throws SQLException, IOException {
        return createDrafts(
                        ownerUserId,
                        List.of(new SkillPackage.Loaded(name, description, markdown, resources)))
                .get(0);
    }

    public List<SkillRow> createDrafts(String ownerUserId, List<SkillPackage.Loaded> values)
            throws SQLException, IOException {
        List<SkillRow> created = new ArrayList<>();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (var value : values)
                    created.add(
                            createDraft(
                                    connection,
                                    ownerUserId,
                                    value.name(),
                                    value.description(),
                                    value.markdown(),
                                    value.resources()));
                connection.commit();
                return List.copyOf(created);
            } catch (SQLException | IOException | RuntimeException error) {
                connection.rollback();
                for (var row : created) {
                    try {
                        deleteTree(draftPath(row));
                    } catch (IOException cleanup) {
                        error.addSuppressed(cleanup);
                    }
                }
                throw error;
            }
        }
    }

    private SkillRow createDraft(
            Connection connection,
            String ownerUserId,
            String name,
            String description,
            String markdown,
            Map<String, String> resources)
            throws SQLException, IOException {
        SkillPaths.name(name);
        rejectDuplicate(ownerUserId, name, null);
        String id = UUID.randomUUID().toString();
        Path dir =
                SkillPaths.contained(
                        root,
                        root.resolve("drafts").resolve(SkillPaths.name(ownerUserId)).resolve(id));
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), markdown);
            for (Map.Entry<String, String> resource : resources.entrySet()) {
                Path target = dir.resolve(resource.getKey()).normalize();
                if (!target.startsWith(dir) || target.equals(dir.resolve("SKILL.md"))) {
                    throw new IllegalArgumentException("Skill resource path is not allowed");
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, resource.getValue());
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO advisor_skills (skill_id, name, description, status,"
                                    + " origin, owner_user_id, draft_dir, live_dir, created_at,"
                                    + " updated_at) VALUES (?, ?, ?, 'draft', 'personal', ?, ?,"
                                    + " NULL, ?, ?)")) {
                statement.setString(1, id);
                statement.setString(2, name.strip());
                statement.setString(3, description.strip());
                statement.setString(4, ownerUserId);
                statement.setString(5, dir.toString());
                statement.setLong(6, now);
                statement.setLong(7, now);
                statement.executeUpdate();
            }
            return new SkillRow(
                    id,
                    name.strip(),
                    description.strip(),
                    DRAFT,
                    PERSONAL,
                    ownerUserId,
                    dir.toString(),
                    null,
                    now,
                    now);
        } catch (SQLException | IOException | RuntimeException error) {
            try {
                deleteTree(dir);
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    public void publish(SkillRow skill) throws SQLException, IOException {
        Path live = livePath(skill.ownerUserId(), skill.name());
        SkillDocuments.copyDirectory(draftPath(skill), live);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_skills SET status = 'published', live_dir = ?,"
                                        + " updated_at = ? WHERE skill_id = ?")) {
            statement.setString(1, live.toString());
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, skill.skillId());
            statement.executeUpdate();
        }
    }

    public void unpublish(SkillRow skill) throws SQLException, IOException {
        if (skill.liveDir() != null && PERSONAL.equals(skill.origin())) {
            deleteTree(checkedLivePath(skill));
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_skills SET status = 'draft', live_dir = NULL,"
                                        + " updated_at = ? WHERE skill_id = ?")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, skill.skillId());
            statement.executeUpdate();
        }
    }

    public SkillRow update(SkillRow skill, String name, String description, String markdown)
            throws SQLException, IOException {
        String safeName = name == null || name.isBlank() ? skill.name() : name.strip();
        SkillPaths.name(safeName);
        String safeDesc = description == null ? skill.description() : description.strip();
        rejectDuplicate(skill.ownerUserId(), safeName, skill.skillId());
        Path draftDir = draftPath(skill);
        Files.createDirectories(draftDir);
        if (markdown != null && !markdown.isBlank()) {
            Files.writeString(draftDir.resolve("SKILL.md"), markdown);
        }
        Path live = skill.liveDir() != null ? checkedLivePath(skill) : null;
        if (PUBLISHED.equals(skill.status())) {
            Path newLive = livePath(skill.ownerUserId(), safeName);
            if (live != null && !live.equals(newLive)) {
                deleteTree(live);
            }
            live = newLive;
            SkillDocuments.copyDirectory(draftDir, live);
        }
        long now = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_skills SET name = ?, description = ?, live_dir = ?,"
                                        + " updated_at = ? WHERE skill_id = ?")) {
            statement.setString(1, safeName);
            statement.setString(2, safeDesc);
            statement.setString(3, live != null ? live.toString() : null);
            statement.setLong(4, now);
            statement.setString(5, skill.skillId());
            statement.executeUpdate();
        }
        return find(skill.skillId()).orElseThrow();
    }

    public String readMarkdown(SkillRow skill) throws IOException {
        Path draft = draftPath(skill).resolve("SKILL.md");
        if (Files.exists(draft)) {
            return Files.readString(draft);
        }
        if (skill.liveDir() != null) {
            Path live = checkedLivePath(skill).resolve("SKILL.md");
            if (Files.exists(live)) {
                return Files.readString(live);
            }
        }
        return "";
    }

    public boolean deleteDraft(SkillRow skill) throws SQLException, IOException {
        if (!DRAFT.equals(skill.status())) {
            return false;
        }
        deleteTree(draftPath(skill));
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement selection =
                    connection.prepareStatement(
                            "DELETE FROM advisor_agent_skills WHERE skill_id = ?")) {
                selection.setString(1, skill.skillId());
                selection.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM advisor_skills WHERE skill_id = ?")) {
                statement.setString(1, skill.skillId());
                return statement.executeUpdate() > 0;
            }
        }
    }

    public List<String> selection(String agentId) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT skill_id FROM advisor_agent_skills WHERE agent_id = ?")) {
            statement.setString(1, agentId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getString(1));
                }
            }
        }
        return ids;
    }

    public void replaceSelection(String agentId, List<String> skillIds) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "DELETE FROM advisor_agent_skills WHERE agent_id = ?")) {
                statement.setString(1, agentId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO advisor_agent_skills (agent_id, skill_id) VALUES (?,"
                                    + " ?)")) {
                for (String skillId : skillIds) {
                    statement.setString(1, agentId);
                    statement.setString(2, skillId);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }
    }

    /** Published skills checked on this agent: the agent owner's skills. */
    public List<SkillRow> effective(String agentId, String ownerUserId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT "
                                        + COLUMNS
                                        + " FROM advisor_skills s WHERE s.status = 'published' AND"
                                        + " EXISTS (SELECT 1 FROM advisor_agent_skills a WHERE"
                                        + " a.agent_id = ? AND a.skill_id = s.skill_id) AND"
                                        + " s.owner_user_id = ? ORDER BY"
                                        + " s.name")) {
            statement.setString(1, agentId);
            statement.setString(2, ownerUserId == null ? "" : ownerUserId);
            return read(statement);
        }
    }

    public List<String> agentsUsing(String skillId) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT agent_id FROM advisor_agent_skills WHERE skill_id = ?")) {
            statement.setString(1, skillId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getString(1));
                }
            }
        }
        return ids;
    }

    public static boolean canView(SkillRow skill, String userId, boolean admin) {
        return userId != null && userId.equals(skill.ownerUserId());
    }

    public static boolean canEdit(SkillRow skill, String userId, boolean admin) {
        return userId != null && userId.equals(skill.ownerUserId());
    }

    private Path livePath(String owner, String name) {
        return SkillPaths.contained(
                root,
                root.resolve("live")
                        .resolve(SkillPaths.name(owner))
                        .resolve(SkillPaths.name(name)));
    }

    Path checkedLivePath(SkillRow skill) {
        Path expected = livePath(skill.ownerUserId(), skill.name());
        if (skill.liveDir() == null
                || !expected.equals(Path.of(skill.liveDir()).toAbsolutePath().normalize()))
            throw new IllegalArgumentException("invalid_skill_path");
        return expected;
    }

    private Path draftPath(SkillRow skill) {
        Path expected =
                SkillPaths.contained(
                        root,
                        root.resolve("drafts")
                                .resolve(SkillPaths.name(skill.ownerUserId()))
                                .resolve(SkillPaths.name(skill.skillId())));
        if (!expected.equals(Path.of(skill.draftDir()).toAbsolutePath().normalize()))
            throw new IllegalArgumentException("invalid_skill_path");
        return expected;
    }

    private void rejectDuplicate(String ownerUserId, String name, String except)
            throws SQLException {
        for (SkillRow skill : list(ownerUserId, false)) {
            if (skill.name().equals(name.strip()) && !skill.skillId().equals(except)) {
                throw new IllegalArgumentException("Skill name already exists");
            }
        }
    }

    private List<SkillRow> read(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            List<SkillRow> skills = new ArrayList<>();
            while (rows.next()) {
                skills.add(
                        new SkillRow(
                                rows.getString("skill_id"),
                                rows.getString("name"),
                                rows.getString("description"),
                                rows.getString("status"),
                                rows.getString("origin"),
                                rows.getString("owner_user_id"),
                                rows.getString("draft_dir"),
                                rows.getString("live_dir"),
                                rows.getLong("created_at"),
                                rows.getLong("updated_at")));
            }
            return skills;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final String COLUMNS =
            "skill_id, name, description, status, origin, owner_user_id, draft_dir, live_dir,"
                    + " created_at, updated_at";

    public record SkillRow(
            String skillId,
            String name,
            String description,
            String status,
            String origin,
            String ownerUserId,
            String draftDir,
            String liveDir,
            long createdAt,
            long updatedAt) {}
}
