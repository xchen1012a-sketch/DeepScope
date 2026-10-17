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
package io.deepscope.advisor.access;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** Roles, grants, and the single builtin super-admin role. */
public final class AccessCatalog {

    private final DataSource dataSource;

    public AccessCatalog(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void ensureBuiltin() throws SQLException {
        if (findRole(AccessPoints.SUPER_ADMIN_ROLE_ID).isEmpty()) {
            long now = System.currentTimeMillis();
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "INSERT INTO advisor_roles (role_id, name, code, description,"
                                            + " sort_order, status, builtin, created_at) VALUES (?,"
                                            + " ?, ?, ?, 1, 'active', 1, ?)")) {
                statement.setString(1, AccessPoints.SUPER_ADMIN_ROLE_ID);
                statement.setString(2, "超级管理员");
                statement.setString(3, "admin");
                statement.setString(4, "拥有全部权限，包含用户与角色管理。");
                statement.setLong(5, now);
                statement.executeUpdate();
            }
            replacePermissions(AccessPoints.SUPER_ADMIN_ROLE_ID, AccessPoints.ALL);
        }
        if (findRole(AccessPoints.CHAT_ROLE_ID).isEmpty() && findRoleByCode("chat").isEmpty()) {
            long now = System.currentTimeMillis();
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "INSERT INTO advisor_roles (role_id, name, code, description,"
                                            + " sort_order, status, builtin, created_at) VALUES (?,"
                                            + " ?, ?, ?, 2, 'active', 1, ?)")) {
                statement.setString(1, AccessPoints.CHAT_ROLE_ID);
                statement.setString(2, "普通用户");
                statement.setString(3, "chat");
                statement.setString(4, "仅拥有会话使用权限的基础角色。");
                statement.setLong(5, now);
                statement.executeUpdate();
            }
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "INSERT INTO advisor_role_permissions (role_id, permission)"
                                            + " VALUES (?, ?)")) {
                statement.setString(1, AccessPoints.CHAT_ROLE_ID);
                statement.setString(2, AccessPoints.CHAT);
                statement.executeUpdate();
            }
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement admins =
                        connection.prepareStatement(
                                "SELECT user_id FROM accounts WHERE role = 'admin'");
                ResultSet rows = admins.executeQuery()) {
            while (rows.next()) {
                String userId = rows.getString(1);
                grant(userId, AccessPoints.SUPER_ADMIN_ROLE_ID);
                try (PreparedStatement mark =
                        connection.prepareStatement(
                                "UPDATE accounts SET builtin = 1 WHERE user_id = ? AND role ="
                                        + " 'admin'")) {
                    mark.setString(1, userId);
                    mark.executeUpdate();
                }
            }
        }
    }

    public List<RoleRow> roles() throws SQLException {
        return roles(null);
    }

    public List<RoleRow> roles(String query) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        io.deepscope.advisor.db.ListSearch.prepare(
                                connection,
                                "SELECT * FROM advisor_roles ORDER BY sort_order, created_at",
                                query,
                                List.of("name"));
                ResultSet rows = statement.executeQuery()) {
            List<RoleRow> roles = new ArrayList<>();
            while (rows.next()) roles.add(role(rows));
            return roles;
        }
    }

    public Optional<RoleRow> findRole(String roleId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT * FROM advisor_roles WHERE role_id = ?")) {
            statement.setString(1, roleId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(role(rows)) : Optional.empty();
            }
        }
    }

    public Optional<RoleRow> findRoleByCode(String code) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT * FROM advisor_roles WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(role(rows)) : Optional.empty();
            }
        }
    }

    public io.deepscope.advisor.db.PageResult<RoleRow> rolesPage(
            String query, String status, String code, int page, int size) throws SQLException {
        return rolesPage(query, status, code, page, size, null, null);
    }

    public io.deepscope.advisor.db.PageResult<RoleRow> rolesPage(
            String query,
            String status,
            String code,
            int page,
            int size,
            String createdFrom,
            String createdBefore)
            throws SQLException {
        var sql =
                new io.deepscope.advisor.db.JdbcPage.Query(
                                "SELECT * FROM advisor_roles", "sort_order, created_at, role_id")
                        .equal("status", status)
                        .search(code, List.of("code"))
                        .search(query, List.of("name"))
                        .createdBetween(createdFrom, createdBefore);
        try (var connection = dataSource.getConnection()) {
            return io.deepscope.advisor.db.JdbcPage.fetch(
                    connection,
                    sql,
                    page,
                    size,
                    statement -> {
                        List<RoleRow> items = new ArrayList<>();
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) items.add(role(rows));
                        }
                        return items;
                    });
        }
    }

    public RoleRow createRole(String name, String code, int sort, String description)
            throws SQLException {
        return createRole(name, code, sort, description, "active");
    }

    public RoleRow createRole(String name, String code, int sort, String description, String status)
            throws SQLException {
        validateRole(name, code, sort, status);
        name = name.strip();
        code = code.strip();
        if (codeExists(code, null)) {
            throw new IllegalArgumentException("角色标识已存在");
        }
        String roleId = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO advisor_roles (role_id, name, code, description,"
                                    + " sort_order, status, builtin, created_at) VALUES (?, ?, ?,"
                                    + " ?, ?, ?, 0, ?)")) {
            statement.setString(1, roleId);
            statement.setString(2, name.strip());
            statement.setString(3, code.strip());
            statement.setString(4, description == null ? "" : description.strip());
            statement.setInt(5, sort);
            statement.setString(6, status);
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        }
        return findRole(roleId).orElseThrow();
    }

    public void updateRole(RoleRow role, String name, String code, int sort, String description)
            throws SQLException {
        updateRole(role, name, code, sort, description, role.status());
    }

    public void updateRole(
            RoleRow role, String name, String code, int sort, String description, String status)
            throws SQLException {
        if (role.builtin() && !"active".equals(status))
            throw new IllegalArgumentException("内置角色不能停用");
        validateRole(name, code, sort, status);
        name = name.strip();
        code = code.strip();
        if (codeExists(code, role.roleId())) {
            throw new IllegalArgumentException("角色标识已存在");
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_roles SET name = ?, code = ?, description = ?,"
                                        + " sort_order = ?, status = ? WHERE role_id = ?")) {
            statement.setString(1, name.strip());
            statement.setString(2, code.strip());
            statement.setString(3, description == null ? "" : description.strip());
            statement.setInt(4, sort);
            statement.setString(5, status);
            statement.setString(6, role.roleId());
            statement.executeUpdate();
        }
    }

    private static void validateRole(String name, String code, int sort, String status) {
        if (name == null || name.isBlank() || code == null || code.isBlank())
            throw new IllegalArgumentException("请输入角色名称和角色标识");
        if (sort < 0) throw new IllegalArgumentException("排序必须是非负整数");
        if (!"active".equals(status) && !"disabled".equals(status))
            throw new IllegalArgumentException("角色状态无效");
    }

    public void setRoleStatus(RoleRow role, String status) throws SQLException {
        if (role.builtin()) {
            throw new IllegalArgumentException("内置角色不能停用");
        }
        if (!"active".equals(status) && !"disabled".equals(status))
            throw new IllegalArgumentException("角色状态无效");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_roles SET status = ? WHERE role_id = ?")) {
            statement.setString(1, status);
            statement.setString(2, role.roleId());
            statement.executeUpdate();
        }
    }

    public void replacePermissions(String roleId, List<String> permissions) throws SQLException {
        findRole(roleId).orElseThrow();
        if (AccessPoints.SUPER_ADMIN_ROLE_ID.equals(roleId)
                && !permissions.contains(AccessPoints.RBAC))
            throw new IllegalArgumentException("请保留权限管理权限，避免管理员失去管理入口");
        if (!AccessPoints.ALL.containsAll(permissions)) {
            throw new IllegalArgumentException("包含未知权限");
        }
        permissions = new ArrayList<>(new LinkedHashSet<>(permissions));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete =
                        connection.prepareStatement(
                                "DELETE FROM advisor_role_permissions WHERE role_id = ?")) {
                    delete.setString(1, roleId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO advisor_role_permissions (role_id, permission) VALUES"
                                        + " (?, ?)")) {
                    for (String permission : permissions) {
                        insert.setString(1, roleId);
                        insert.setString(2, permission);
                        insert.addBatch();
                    }
                    insert.executeBatch();
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

    public List<String> permissionsOfRole(String roleId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT permission FROM advisor_role_permissions WHERE role_id = ?"
                                        + " ORDER BY permission")) {
            statement.setString(1, roleId);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> permissions = new ArrayList<>();
                while (rows.next()) {
                    permissions.add(rows.getString(1));
                }
                return permissions;
            }
        }
    }

    public void deleteRole(RoleRow role) throws SQLException {
        if (role.builtin()) {
            throw new IllegalArgumentException("内置角色不能删除");
        }
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement roles =
                    connection.prepareStatement(
                            "DELETE FROM advisor_account_roles WHERE role_id = ?")) {
                roles.setString(1, role.roleId());
                roles.executeUpdate();
            }
            try (PreparedStatement perms =
                    connection.prepareStatement(
                            "DELETE FROM advisor_role_permissions WHERE role_id = ?")) {
                perms.setString(1, role.roleId());
                perms.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM advisor_roles WHERE role_id = ?")) {
                statement.setString(1, role.roleId());
                statement.executeUpdate();
            }
        }
    }

    public void grant(String userId, String roleId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO advisor_account_roles (user_id, role_id) SELECT ?,"
                                        + " ? WHERE NOT EXISTS (SELECT 1 FROM advisor_account_roles"
                                        + " WHERE user_id = ? AND role_id = ?)")) {
            statement.setString(1, userId);
            statement.setString(2, roleId);
            statement.setString(3, userId);
            statement.setString(4, roleId);
            statement.executeUpdate();
        }
    }

    public void replaceGrants(String userId, List<String> roleIds) throws SQLException {
        if (holdsLastSuperAdmin(userId) && !roleIds.contains(AccessPoints.SUPER_ADMIN_ROLE_ID)) {
            throw new IllegalArgumentException("至少要留一个超级管理员");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete =
                        connection.prepareStatement(
                                "DELETE FROM advisor_account_roles WHERE user_id = ?")) {
                    delete.setString(1, userId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO advisor_account_roles (user_id, role_id) VALUES (?,"
                                        + " ?)")) {
                    for (String roleId : roleIds) {
                        insert.setString(1, userId);
                        insert.setString(2, roleId);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public List<String> roleIds(String userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT r.role_id FROM advisor_account_roles ar JOIN advisor_roles"
                                        + " r ON r.role_id = ar.role_id WHERE ar.user_id = ? AND"
                                        + " r.status = 'active'")) {
            statement.setString(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rows.next()) {
                    ids.add(rows.getString(1));
                }
                return ids;
            }
        }
    }

    public boolean hasAssignedRole(String userId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT 1 FROM advisor_account_roles WHERE user_id = ?")) {
            statement.setString(1, userId);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    public Set<String> permissions(String userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT DISTINCT p.permission FROM advisor_account_roles ar JOIN"
                                    + " advisor_roles r ON r.role_id = ar.role_id JOIN"
                                    + " advisor_role_permissions p ON p.role_id = r.role_id WHERE"
                                    + " ar.user_id = ? AND r.status = 'active'")) {
            statement.setString(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                Set<String> permissions = new LinkedHashSet<>();
                while (rows.next()) {
                    permissions.add(rows.getString(1));
                }
                return permissions;
            }
        }
    }

    public int activeSuperAdmins() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM advisor_account_roles ar JOIN accounts a ON"
                                    + " a.user_id = ar.user_id WHERE ar.role_id = ? AND a.status ="
                                    + " 'active'"); ) {
            statement.setString(1, AccessPoints.SUPER_ADMIN_ROLE_ID);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    public boolean holdsLastSuperAdmin(String userId) throws SQLException {
        return roleIds(userId).contains(AccessPoints.SUPER_ADMIN_ROLE_ID)
                && activeSuperAdmins() <= 1;
    }

    public void audit(String actorUserId, String action, String detail) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO advisor_audit (audit_id, actor_user_id, action,"
                                        + " detail, created_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, actorUserId);
            statement.setString(3, action);
            statement.setString(4, detail == null ? "" : detail);
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    public int userCount(String roleId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM advisor_account_roles WHERE role_id = ?")) {
            statement.setString(1, roleId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private boolean codeExists(String code, String exceptRoleId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT role_id FROM advisor_roles WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return false;
                }
                return exceptRoleId == null || !exceptRoleId.equals(rows.getString(1));
            }
        }
    }

    private static RoleRow role(ResultSet rows) throws SQLException {
        return new RoleRow(
                rows.getString("role_id"),
                rows.getString("name"),
                rows.getString("code"),
                rows.getString("description"),
                rows.getInt("sort_order"),
                rows.getString("status"),
                rows.getInt("builtin") == 1,
                rows.getLong("created_at"),
                rows.getLong("role_number"));
    }

    public record RoleRow(
            String roleId,
            String name,
            String code,
            String description,
            int sort,
            String status,
            boolean builtin,
            long createdAt,
            long roleNumber) {}
}
