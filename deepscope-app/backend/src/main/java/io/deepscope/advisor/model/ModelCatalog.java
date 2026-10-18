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
package io.deepscope.advisor.model;

import io.deepscope.core.model.ModelContextWindows;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Site-wide chat and embedding endpoints. Secrets stay in this store. */
public final class ModelCatalog {

    public static final String CHAT = "chat";
    public static final String EMBEDDING = "embedding";

    private final DataSource dataSource;

    public ModelCatalog(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<Integer> contextWindow(String endpointId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT model_name, context_window FROM advisor_model_endpoints"
                                        + " WHERE endpoint_id = ?")) {
            statement.setString(1, endpointId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    Integer window = rows.getObject("context_window", Integer.class);
                    if (window != null && window > 0) {
                        return Optional.of(window);
                    }
                    String modelName = rows.getString("model_name");
                    int inferred = ModelContextWindows.infer(modelName);
                    return Optional.of(
                            inferred > 0 ? inferred : ModelContextWindows.DEFAULT_FALLBACK_WINDOW);
                }
                return Optional.empty();
            }
        }
    }

    public void saveContextWindow(String endpointId, int contextWindow) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_model_endpoints SET context_window = ? WHERE"
                                        + " endpoint_id = ?")) {
            statement.setInt(1, contextWindow);
            statement.setString(2, endpointId);
            statement.executeUpdate();
        }
    }

    public Endpoint insertCurrent(EndpointDraft draft, Integer dimensions) throws SQLException {
        return insert(draft, dimensions, draft.modelName(), true, null, null, true);
    }

    public Optional<Endpoint> knowledgePdf() throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT m.* FROM advisor_model_endpoints m JOIN advisor_model_roles"
                                        + " r ON r.endpoint_id=m.endpoint_id WHERE"
                                        + " r.role_name='knowledge_pdf' AND m.kind='chat'");
                var rows = statement.executeQuery()) {
            return rows.next() ? Optional.of(endpoint(rows)) : Optional.empty();
        }
    }

    public long revision(String endpointId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT updated_at FROM advisor_model_endpoints WHERE"
                                        + " endpoint_id=?")) {
            statement.setString(1, endpointId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : -1;
            }
        }
    }

    public void setKnowledgePdf(String endpointId, boolean enabled) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                assignKnowledgePdf(connection, endpointId, enabled);
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private static void assignKnowledgePdf(
            Connection connection, String endpointId, Boolean enabled) throws SQLException {
        if (enabled == null) return;
        try (var lock =
                        connection.prepareStatement(
                                "SELECT role_name FROM advisor_model_roles WHERE"
                                        + " role_name='knowledge_pdf' FOR UPDATE");
                var rows = lock.executeQuery()) {
            rows.next();
        }
        if (enabled) {
            try (var endpoint =
                    connection.prepareStatement(
                            "SELECT kind FROM advisor_model_endpoints WHERE endpoint_id=?")) {
                endpoint.setString(1, endpointId);
                try (var rows = endpoint.executeQuery()) {
                    if (!rows.next() || !CHAT.equals(rows.getString(1)))
                        throw new IllegalArgumentException("知识库 PDF 解析必须使用支持图片的对话模型");
                }
            }
        }
        try (var update =
                connection.prepareStatement(
                        enabled
                                ? "UPDATE advisor_model_roles SET endpoint_id=? WHERE"
                                        + " role_name='knowledge_pdf'"
                                : "UPDATE advisor_model_roles SET endpoint_id=NULL WHERE"
                                        + " role_name='knowledge_pdf' AND endpoint_id=?")) {
            update.setString(1, endpointId);
            update.executeUpdate();
        }
    }

    public Endpoint insert(
            EndpointDraft draft, Integer dimensions, String displayName, boolean current)
            throws SQLException {
        return insert(draft, dimensions, displayName, current, null, null, current);
    }

    public Endpoint insert(
            EndpointDraft draft,
            Integer dimensions,
            String displayName,
            boolean current,
            Boolean knowledgePdf)
            throws SQLException {
        return insert(draft, dimensions, displayName, current, knowledgePdf, null, current);
    }

    public Endpoint insert(
            EndpointDraft draft,
            Integer dimensions,
            String displayName,
            boolean current,
            Boolean knowledgePdf,
            String ownerUserId,
            boolean isPublic)
            throws SQLException {
        String id = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (current) {
                    clearCurrent(connection, draft.kind(), ownerUserId, isPublic);
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO advisor_model_endpoints"
                                    + " (endpoint_id,kind,provider,model_name,base_url,secret,current_flag,dimensions,created_at,updated_at,display_name,owner_user_id,is_public)"
                                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    statement.setString(1, id);
                    statement.setString(2, draft.kind());
                    statement.setString(3, draft.provider());
                    statement.setString(4, draft.modelName());
                    statement.setString(5, draft.baseUrl() == null ? "" : draft.baseUrl());
                    statement.setString(6, draft.secret());
                    statement.setInt(7, current ? 1 : 0);
                    statement.setObject(8, dimensions);
                    statement.setLong(9, System.currentTimeMillis());
                    statement.setLong(10, System.currentTimeMillis());
                    statement.setString(11, displayName);
                    statement.setString(12, ownerUserId);
                    statement.setInt(13, isPublic ? 1 : 0);
                    statement.executeUpdate();
                }
                assignKnowledgePdf(connection, id, knowledgePdf);
                connection.commit();
            } catch (SQLException | IllegalArgumentException error) {
                connection.rollback();
                throw error;
            }
        }
        return find(id).orElseThrow();
    }

    public Endpoint update(
            String id,
            EndpointDraft draft,
            Integer dimensions,
            String displayName,
            boolean makeCurrent)
            throws SQLException {
        return update(id, draft, dimensions, displayName, makeCurrent, null, null);
    }

    public Endpoint update(
            String id,
            EndpointDraft draft,
            Integer dimensions,
            String displayName,
            boolean makeCurrent,
            Boolean knowledgePdf)
            throws SQLException {
        return update(id, draft, dimensions, displayName, makeCurrent, knowledgePdf, null);
    }

    public Endpoint update(
            String id,
            EndpointDraft draft,
            Integer dimensions,
            String displayName,
            boolean makeCurrent,
            Boolean knowledgePdf,
            Boolean isPublic)
            throws SQLException {
        Endpoint original = find(id).orElseThrow();
        boolean publicFlag = isPublic != null ? isPublic : original.isPublic();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (makeCurrent) {
                    clearCurrent(connection, draft.kind(), original.ownerUserId(), publicFlag);
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_model_endpoints SET"
                                    + " model_name=?,base_url=?,secret=?,dimensions=?,display_name=?,current_flag=?,updated_at=?,context_window=NULL,provider=?,is_public=?"
                                    + " WHERE endpoint_id=?")) {
                    statement.setString(1, draft.modelName());
                    statement.setString(2, draft.baseUrl() == null ? "" : draft.baseUrl());
                    statement.setString(3, draft.secret());
                    statement.setObject(4, dimensions);
                    statement.setString(5, displayName);
                    statement.setInt(6, makeCurrent || original.current() ? 1 : 0);
                    statement.setLong(7, System.currentTimeMillis());
                    statement.setString(8, draft.provider());
                    statement.setInt(9, publicFlag ? 1 : 0);
                    statement.setString(10, id);
                    statement.executeUpdate();
                }
                assignKnowledgePdf(connection, id, knowledgePdf);
                connection.commit();
            } catch (SQLException | IllegalArgumentException error) {
                connection.rollback();
                throw error;
            }
        }
        return find(id).orElseThrow();
    }

    public void recordTest(String id, ModelProbe.Result result, long elapsedMs)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_model_endpoints SET"
                                    + " last_test_status=?,last_test_at=?,last_test_elapsed_ms=?,last_test_message=?"
                                    + " WHERE endpoint_id=?")) {
            statement.setString(1, result.ok() ? "passed" : "failed");
            statement.setLong(2, System.currentTimeMillis());
            statement.setLong(3, elapsedMs);
            statement.setString(4, result.ok() ? "连接成功" : result.failure());
            statement.setString(5, id);
            statement.executeUpdate();
        }
    }

    public java.util.Map<String, Object> metadata(String id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT * FROM advisor_model_endpoints WHERE endpoint_id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return java.util.Map.of();
                java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
                String name = rows.getString("display_name");
                result.put(
                        "displayName",
                        name == null || name.isBlank() ? rows.getString("model_name") : name);
                result.put("ownerUserId", rows.getString("owner_user_id"));
                result.put("isPublic", rows.getInt("is_public") == 1);
                result.put("lastTestStatus", rows.getString("last_test_status"));
                result.put("lastTestAt", rows.getObject("last_test_at"));
                result.put("lastTestElapsedMs", rows.getObject("last_test_elapsed_ms"));
                result.put("lastTestMessage", rows.getString("last_test_message"));
                return result;
            }
        }
    }

    public void makeCurrent(String endpointId) throws SQLException {
        Endpoint endpoint = find(endpointId).orElseThrow();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                clearCurrent(
                        connection, endpoint.kind(), endpoint.ownerUserId(), endpoint.isPublic());
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_model_endpoints SET current_flag = 1, updated_at ="
                                        + " ? WHERE endpoint_id = ?")) {
                    statement.setLong(1, System.currentTimeMillis());
                    statement.setString(2, endpointId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | IllegalArgumentException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void delete(String endpointId) throws SQLException {
        Endpoint endpoint = find(endpointId).orElse(null);
        if (endpoint == null) {
            return;
        }
        if (endpoint.current()) {
            throw new IllegalArgumentException("当前模型要先换掉才能删除");
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "DELETE FROM advisor_model_endpoints WHERE endpoint_id = ?")) {
            connection.setAutoCommit(false);
            try {
                try (var lock =
                        connection.prepareStatement(
                                "SELECT current_flag FROM advisor_model_endpoints WHERE"
                                        + " endpoint_id=? FOR UPDATE")) {
                    lock.setQueryTimeout(15);
                    lock.setString(1, endpointId);
                    try (var rows = lock.executeQuery()) {
                        if (!rows.next()) return;
                        if (rows.getInt(1) != 0) throw new IllegalArgumentException("当前模型要先换掉才能删除");
                    }
                }
                try (PreparedStatement references =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM advisor_sessions WHERE"
                                        + " model_endpoint_id=?")) {
                    references.setString(1, endpointId);
                    try (ResultSet rows = references.executeQuery()) {
                        rows.next();
                        if (rows.getInt(1) > 0)
                            throw new IllegalArgumentException("模型正在被会话使用，无法删除");
                    }
                }
                try (PreparedStatement references =
                        connection.prepareStatement(
                                "SELECT name FROM advisor_knowledge_bases WHERE"
                                        + " embedding_endpoint_id=? ORDER BY created_at")) {
                    references.setString(1, endpointId);
                    try (ResultSet rows = references.executeQuery()) {
                        List<String> names = new ArrayList<>();
                        while (rows.next()) {
                            names.add(rows.getString(1));
                        }
                        if (!names.isEmpty()) {
                            throw new IllegalArgumentException(
                                    "有 "
                                            + names.size()
                                            + " 个知识库正在使用该向量模型，无法删除: "
                                            + String.join("、", names));
                        }
                    }
                }
                statement.setString(1, endpointId);
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException conflict) {
            if ("23503".equals(conflict.getSQLState()))
                throw new IllegalArgumentException("模型正在被会话使用，无法删除");
            throw conflict;
        }
    }

    public List<Endpoint> list(String kind) throws SQLException {
        return list(kind, null);
    }

    public List<Endpoint> list(String kind, String query) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        io.deepscope.advisor.db.ListSearch.prepare(
                                connection,
                                "SELECT * FROM advisor_model_endpoints WHERE kind = ? ORDER BY"
                                        + " created_at",
                                query,
                                List.of("model_name", "display_name"),
                                kind)) {
            try (ResultSet rows = statement.executeQuery()) {
                List<Endpoint> endpoints = new ArrayList<>();
                while (rows.next()) {
                    endpoints.add(endpoint(rows));
                }
                return endpoints;
            }
        }
    }

    public io.deepscope.advisor.db.PageResult<Endpoint> page(
            String kind, String provider, String query, int page, int size) throws SQLException {
        return page(kind, provider, query, page, size, null);
    }

    public io.deepscope.advisor.db.PageResult<Endpoint> page(
            String kind, String provider, String query, int page, int size, String presetId)
            throws SQLException {
        var sql =
                new io.deepscope.advisor.db.JdbcPage.Query(
                                "SELECT * FROM advisor_model_endpoints", "created_at, endpoint_id")
                        .equal("kind", kind)
                        .equal("provider", provider)
                        .search(query, List.of("model_name", "display_name"));
        if (presetId != null && !presetId.isBlank()) {
            var preset =
                    ModelProviderPresets.find(presetId)
                            .orElseThrow(() -> new IllegalArgumentException("服务商无效"));
            if (!preset.id().equals("custom")) {
                sql.equal("provider", preset.provider());
                sql.where("RTRIM(base_url, '/') = ?", preset.baseUrl().replaceAll("/+$", ""));
            } else {
                sql.equal("provider", "custom");
            }
        }
        try (var connection = dataSource.getConnection()) {
            return io.deepscope.advisor.db.JdbcPage.fetch(
                    connection,
                    sql,
                    page,
                    size,
                    statement -> {
                        var endpoints = new ArrayList<Endpoint>();
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) endpoints.add(endpoint(rows));
                        }
                        return endpoints;
                    });
        }
    }

    public List<Endpoint> listVisible(String kind, String userId, boolean seesAll)
            throws SQLException {
        if (seesAll) {
            return list(kind);
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT * FROM advisor_model_endpoints WHERE kind = ? AND"
                                        + " (is_public = 1 OR (owner_user_id IS NOT NULL AND"
                                        + " owner_user_id = ?)) ORDER BY created_at")) {
            statement.setString(1, kind);
            statement.setString(2, userId == null ? "" : userId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Endpoint> endpoints = new ArrayList<>();
                while (rows.next()) {
                    endpoints.add(endpoint(rows));
                }
                return endpoints;
            }
        }
    }

    public io.deepscope.advisor.db.PageResult<Endpoint> pageVisible(
            String kind,
            String provider,
            String query,
            int page,
            int size,
            String presetId,
            String userId,
            boolean seesAll)
            throws SQLException {
        var sql =
                new io.deepscope.advisor.db.JdbcPage.Query(
                                "SELECT * FROM advisor_model_endpoints", "created_at, endpoint_id")
                        .equal("kind", kind)
                        .equal("provider", provider)
                        .search(query, List.of("model_name", "display_name"));
        if (!seesAll) {
            sql.where(
                    "(is_public = 1 OR (owner_user_id IS NOT NULL AND owner_user_id = ?))",
                    userId == null ? "" : userId);
        }
        if (presetId != null && !presetId.isBlank()) {
            var preset =
                    ModelProviderPresets.find(presetId)
                            .orElseThrow(() -> new IllegalArgumentException("服务商无效"));
            if (!preset.id().equals("custom")) {
                sql.equal("provider", preset.provider());
                sql.where("RTRIM(base_url, '/') = ?", preset.baseUrl().replaceAll("/+$", ""));
            } else {
                sql.equal("provider", "custom");
            }
        }
        try (var connection = dataSource.getConnection()) {
            return io.deepscope.advisor.db.JdbcPage.fetch(
                    connection,
                    sql,
                    page,
                    size,
                    statement -> {
                        var endpoints = new ArrayList<Endpoint>();
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) endpoints.add(endpoint(rows));
                        }
                        return endpoints;
                    });
        }
    }

    public Optional<Endpoint> current(String kind) throws SQLException {
        return current(kind, null, true);
    }

    public Optional<Endpoint> current(String kind, String userId, boolean seesAll)
            throws SQLException {
        if (userId != null && !userId.isBlank()) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT * FROM advisor_model_endpoints WHERE kind = ? AND"
                                            + " current_flag = 1 AND owner_user_id = ?")) {
                statement.setString(1, kind);
                statement.setString(2, userId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) return Optional.of(endpoint(rows));
                }
            }
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT * FROM advisor_model_endpoints WHERE kind = ? AND"
                                    + " current_flag = 1 AND (is_public = 1 OR owner_user_id IS"
                                    + " NULL OR owner_user_id = '') ORDER BY updated_at DESC LIMIT"
                                    + " 1")) {
            statement.setString(1, kind);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) return Optional.of(endpoint(rows));
            }
        }
        if (seesAll) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT * FROM advisor_model_endpoints WHERE kind = ? AND"
                                        + " current_flag = 1 ORDER BY updated_at DESC LIMIT 1")) {
                statement.setString(1, kind);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) return Optional.of(endpoint(rows));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Endpoint> find(String endpointId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT * FROM advisor_model_endpoints WHERE endpoint_id = ?")) {
            statement.setString(1, endpointId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(endpoint(rows)) : Optional.empty();
            }
        }
    }

    public UsagePolicy usage() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT * FROM advisor_usage_policy WHERE policy_id = 1");
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return UsagePolicy.unlimited();
            }
            return new UsagePolicy(
                    rows.getInt("turn_token_limit"),
                    rows.getInt("daily_task_limit"),
                    rows.getInt("rate_per_minute"));
        }
    }

    public void saveUsage(UsagePolicy policy) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO advisor_usage_policy (policy_id, turn_token_limit,"
                                    + " daily_task_limit, rate_per_minute) VALUES (1, ?, ?, ?) ON"
                                    + " CONFLICT (policy_id) DO UPDATE SET turn_token_limit ="
                                    + " EXCLUDED.turn_token_limit, daily_task_limit ="
                                    + " EXCLUDED.daily_task_limit, rate_per_minute ="
                                    + " EXCLUDED.rate_per_minute")) {
            statement.setInt(1, policy.turnTokenLimit());
            statement.setInt(2, policy.dailyTaskLimit());
            statement.setInt(3, policy.ratePerMinute());
            statement.executeUpdate();
        }
    }

    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        if (secret.length() <= 8) {
            return "••••••••";
        }
        return secret.substring(0, 3) + "••••" + secret.substring(secret.length() - 4);
    }

    private static void clearCurrent(
            Connection connection, String kind, String ownerUserId, boolean isPublic)
            throws SQLException {
        if (isPublic || ownerUserId == null || ownerUserId.isBlank()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "UPDATE advisor_model_endpoints SET current_flag = 0 WHERE kind = ? AND"
                                    + " (is_public = 1 OR owner_user_id IS NULL OR owner_user_id ="
                                    + " '')")) {
                statement.setString(1, kind);
                statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "UPDATE advisor_model_endpoints SET current_flag = 0 WHERE kind = ? AND"
                                    + " is_public = 0 AND owner_user_id = ?")) {
                statement.setString(1, kind);
                statement.setString(2, ownerUserId);
                statement.executeUpdate();
            }
        }
    }

    private static Endpoint endpoint(ResultSet rows) throws SQLException {
        int dimensions = rows.getInt("dimensions");
        boolean missing = rows.wasNull();
        String ownerUserId = null;
        try {
            ownerUserId = rows.getString("owner_user_id");
        } catch (SQLException ignored) {
        }
        boolean isPublic = false;
        try {
            isPublic = rows.getInt("is_public") == 1;
        } catch (SQLException ignored) {
        }
        return new Endpoint(
                rows.getString("endpoint_id"),
                rows.getString("kind"),
                rows.getString("provider"),
                rows.getString("model_name"),
                rows.getString("base_url"),
                rows.getString("secret"),
                rows.getInt("current_flag") == 1,
                missing ? null : dimensions,
                ownerUserId,
                isPublic);
    }

    public record EndpointDraft(
            String kind, String provider, String modelName, String baseUrl, String secret) {}

    public record Endpoint(
            String endpointId,
            String kind,
            String provider,
            String modelName,
            String baseUrl,
            String secret,
            boolean current,
            Integer dimensions,
            String ownerUserId,
            boolean isPublic) {
        public Endpoint(
                String endpointId,
                String kind,
                String provider,
                String modelName,
                String baseUrl,
                String secret,
                boolean current,
                Integer dimensions) {
            this(
                    endpointId,
                    kind,
                    provider,
                    modelName,
                    baseUrl,
                    secret,
                    current,
                    dimensions,
                    null,
                    false);
        }
    }

    public record UsagePolicy(int turnTokenLimit, int dailyTaskLimit, int ratePerMinute) {
        public static UsagePolicy unlimited() {
            return new UsagePolicy(0, 0, 0);
        }
    }
}
