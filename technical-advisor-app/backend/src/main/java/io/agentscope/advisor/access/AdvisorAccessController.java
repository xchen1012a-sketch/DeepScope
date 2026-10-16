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
package io.agentscope.advisor.access;

import io.agentscope.advisor.auth.AccountStore;
import io.agentscope.advisor.auth.ApiAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** User and role administration. Only accounts with permission management may call these. */
@RestController
public class AdvisorAccessController {

    private final AccessCatalog catalog;
    private final AccessGuard access;
    private final AccountStore accounts;
    private final DataSource dataSource;

    public AdvisorAccessController(
            AccessCatalog catalog,
            AccessGuard access,
            AccountStore accounts,
            DataSource dataSource) {
        this.catalog = catalog;
        this.access = access;
        this.accounts = accounts;
        this.dataSource = dataSource;
    }

    @GetMapping("/api/advisor/roles")
    public ResponseEntity<List<Map<String, Object>>> roles(HttpServletRequest request)
            throws SQLException {
        if ("user-assignment".equals(request.getParameter("purpose"))) {
            var account = ApiAuthenticationFilter.account(request).orElse(null);
            if (account == null
                    || !(access.allowed(account, "user.view")
                            || access.allowed(account, "role.grant")))
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            List<Map<String, Object>> options = new ArrayList<>();
            for (var role : catalog.roles(request.getParameter("q")))
                options.add(Map.of("roleId", role.roleId(), "name", role.name()));
            return ResponseEntity.ok(options);
        }
        if (forbidden(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<Map<String, Object>> view = new ArrayList<>();
        for (AccessCatalog.RoleRow role : catalog.roles(request.getParameter("q"))) {
            view.add(roleView(role));
        }
        return ResponseEntity.ok(view);
    }

    @GetMapping("/api/advisor/permissions/tree")
    public ResponseEntity<?> permissionTree(HttpServletRequest request) throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElse(null);
        if (account == null || !access.allowed(account, "role.grant")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(AccessPoints.permissionTree());
    }

    @PostMapping("/api/advisor/roles")
    public ResponseEntity<?> createRole(
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws SQLException {
        var account = manager(request);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            AccessCatalog.RoleRow role =
                    catalog.createRole(
                            text(body, "name"),
                            text(body, "code"),
                            number(body, "sort"),
                            text(body, "description"),
                            body.containsKey("status") ? text(body, "status") : "active");
            catalog.audit(account.userId(), "role.create", role.code());
            return ResponseEntity.status(HttpStatus.CREATED).body(roleView(role));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        }
    }

    @GetMapping(value = "/api/advisor/roles", params = "page")
    public ResponseEntity<?> rolesPage(HttpServletRequest request) throws SQLException {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        var page =
                catalog.rolesPage(
                        request.getParameter("q"),
                        request.getParameter("status"),
                        request.getParameter("code"),
                        io.agentscope.advisor.db.JdbcPage.page(request),
                        io.agentscope.advisor.db.JdbcPage.size(request),
                        request.getParameter("createdFrom"),
                        request.getParameter("createdBefore"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (var role : page.items()) items.add(roleView(role));
        return ResponseEntity.ok(
                new io.agentscope.advisor.db.PageResult<>(
                        items, page.page(), page.size(), page.total(), page.totalPages()));
    }

    @PutMapping("/api/advisor/roles/{roleId}")
    public ResponseEntity<?> updateRole(
            @PathVariable String roleId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        var account = manager(request);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        AccessCatalog.RoleRow role = catalog.findRole(roleId).orElse(null);
        if (role == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            catalog.updateRole(
                    role,
                    text(body, "name"),
                    text(body, "code"),
                    number(body, "sort"),
                    body.containsKey("description")
                            ? text(body, "description")
                            : role.description(),
                    body.containsKey("status") ? text(body, "status") : role.status());
            catalog.audit(account.userId(), "role.update", roleId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        }
    }

    @PatchMapping("/api/advisor/roles/{roleId}/status")
    public ResponseEntity<?> roleStatus(
            @PathVariable String roleId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        var account = manager(request);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        AccessCatalog.RoleRow role = catalog.findRole(roleId).orElse(null);
        if (role == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            catalog.setRoleStatus(role, text(body, "status"));
            catalog.audit(account.userId(), "role.status", roleId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        }
    }

    @PutMapping("/api/advisor/roles/{roleId}/permissions")
    public ResponseEntity<?> permissions(
            @PathVariable String roleId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        var account = manager(request);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var role = catalog.findRole(roleId).orElse(null);
        if (role == null) {
            return ResponseEntity.notFound().build();
        }
        List<String> permissions = new ArrayList<>();
        if (body.get("permissions") instanceof List<?> items) {
            for (Object item : items) {
                if (item != null) {
                    permissions.add(item.toString());
                }
            }
        }
        try {
            catalog.replacePermissions(roleId, permissions);
            catalog.audit(account.userId(), "role.permissions", roleId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        }
    }

    @GetMapping("/api/advisor/roles/{roleId}/permissions")
    public ResponseEntity<?> rolePermissions(
            @PathVariable String roleId, HttpServletRequest request) throws SQLException {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (catalog.findRole(roleId).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("permissions", catalog.permissionsOfRole(roleId)));
    }

    @DeleteMapping("/api/advisor/roles/{roleId}")
    public ResponseEntity<?> deleteRole(@PathVariable String roleId, HttpServletRequest request)
            throws SQLException {
        var account = manager(request);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        AccessCatalog.RoleRow role = catalog.findRole(roleId).orElse(null);
        if (role == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            catalog.deleteRole(role);
            catalog.audit(account.userId(), "role.delete", roleId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        }
    }

    @GetMapping("/api/advisor/users")
    public ResponseEntity<List<Map<String, Object>>> users(HttpServletRequest request)
            throws SQLException {
        if (forbidden(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(listUsers(request.getParameter("q")));
    }

    @PostMapping("/api/advisor/users")
    public ResponseEntity<?> createUser(
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws SQLException {
        var account = manager(request);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String username = text(body, "username");
        if (!roleIds(body).isEmpty() && !access.allowed(account, "role.grant"))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String password = text(body, "password");
        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "登录名和初始密码都要填"));
        }
        if (userIdOf(username) != null) {
            return ResponseEntity.badRequest().body(Map.of("message", "登录名已经有了"));
        }
        try {
            accounts.createUser(username, password);
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        }
        String userId = userIdOf(username);
        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "登录名已经有了"));
        }
        accounts.updateProfile(userId, text(body, "nickname"), text(body, "remark"));
        catalog.replaceGrants(userId, roleIds(body));
        catalog.audit(account.userId(), "user.create", username);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping(value = "/api/advisor/users", params = "page")
    public ResponseEntity<?> usersPage(HttpServletRequest request) throws SQLException {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        var sql =
                new io.agentscope.advisor.db.JdbcPage.Query(
                                "SELECT user_id, user_number, username, nickname, remark, status,"
                                        + " builtin, created_at FROM accounts",
                                "created_at, user_id")
                        .equal("status", request.getParameter("status"));
        String roleId = request.getParameter("roleId");
        if (roleId != null && !roleId.isBlank())
            sql.where(
                    "EXISTS (SELECT 1 FROM advisor_account_roles grants WHERE grants.user_id ="
                            + " accounts.user_id AND grants.role_id = ?)",
                    roleId);
        sql.search(request.getParameter("q"), List.of("username"))
                .search(request.getParameter("nickname"), List.of("nickname"))
                .createdBetween(
                        request.getParameter("createdFrom"), request.getParameter("createdBefore"));
        try (var connection = dataSource.getConnection()) {
            return ResponseEntity.ok(
                    io.agentscope.advisor.db.JdbcPage.fetch(
                            connection,
                            sql,
                            io.agentscope.advisor.db.JdbcPage.page(request),
                            io.agentscope.advisor.db.JdbcPage.size(request),
                            statement -> {
                                List<Map<String, Object>> items = new ArrayList<>();
                                try (var rows = statement.executeQuery()) {
                                    while (rows.next()) {
                                        Map<String, Object> view = new LinkedHashMap<>();
                                        String userId = rows.getString("user_id");
                                        view.put("userId", userId);
                                        view.put("userNumber", rows.getLong("user_number"));
                                        view.put("username", rows.getString("username"));
                                        view.put("nickname", rows.getString("nickname"));
                                        view.put("remark", rows.getString("remark"));
                                        view.put("status", rows.getString("status"));
                                        view.put("builtin", rows.getInt("builtin") == 1);
                                        view.put("createdAt", rows.getLong("created_at"));
                                        view.put("roleIds", allRoleIds(userId));
                                        items.add(view);
                                    }
                                }
                                return items;
                            }));
        }
    }

    @PutMapping("/api/advisor/users/{userId}")
    public ResponseEntity<?> updateUser(
            @PathVariable String userId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        var account = manager(request);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (userIdOfId(userId) == null) {
            return ResponseEntity.notFound().build();
        }
        if (protectedUser(account, userId)
                || (body.containsKey("roleIds")
                        && !new java.util.HashSet<>(allRoleIds(userId))
                                .equals(new java.util.HashSet<>(roleIds(body)))
                        && !access.allowed(account, "role.grant")))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            if (body.containsKey("username"))
                accounts.updateProfile(
                        userId,
                        text(body, "username"),
                        text(body, "nickname"),
                        text(body, "remark"));
            else accounts.updateProfile(userId, text(body, "nickname"), text(body, "remark"));
            String password = text(body, "password");
            if (password != null) {
                accounts.setPassword(userId, password);
            }
            if (body.containsKey("roleIds")) catalog.replaceGrants(userId, roleIds(body));
            catalog.audit(account.userId(), "user.update", userId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        }
    }

    @PatchMapping("/api/advisor/users/{userId}/status")
    public ResponseEntity<?> userStatus(
            @PathVariable String userId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        var account = manager(request);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (protectedUser(account, userId))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if ("disabled".equals(text(body, "status")) && catalog.holdsLastSuperAdmin(userId)) {
            return ResponseEntity.badRequest().body(Map.of("message", "至少要留一个超级管理员"));
        }
        try {
            accounts.setStatus(userId, text(body, "status"));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
        }
        catalog.audit(account.userId(), "user.status", userId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/api/advisor/users/{userId}/password")
    public ResponseEntity<?> resetPassword(
            @PathVariable String userId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        var account = manager(request);
        if (account == null || protectedUser(account, userId))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (userIdOfId(userId) == null) return ResponseEntity.notFound().build();
        try {
            accounts.setPassword(userId, text(body, "password"));
            catalog.audit(account.userId(), "user.password", userId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("message", invalid.getMessage()));
        }
    }

    @PutMapping("/api/advisor/users/{userId}/roles")
    public ResponseEntity<?> assignRoles(
            @PathVariable String userId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        var account = manager(request);
        if (account == null || protectedUser(account, userId))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (userIdOfId(userId) == null) return ResponseEntity.notFound().build();
        try {
            catalog.replaceGrants(userId, roleIds(body));
            catalog.audit(account.userId(), "user.roles", userId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("message", invalid.getMessage()));
        }
    }

    @DeleteMapping("/api/advisor/users/{userId}")
    public ResponseEntity<?> deleteUser(@PathVariable String userId, HttpServletRequest request)
            throws SQLException {
        var account = manager(request);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (protectedUser(account, userId))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (isBuiltin(userId)) {
            return ResponseEntity.badRequest().body(Map.of("message", "内置账号不能删除"));
        }
        if (catalog.holdsLastSuperAdmin(userId)) {
            return ResponseEntity.badRequest().body(Map.of("message", "至少要留一个超级管理员"));
        }
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement roles =
                    connection.prepareStatement(
                            "DELETE FROM advisor_account_roles WHERE user_id = ?")) {
                roles.setString(1, userId);
                roles.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM accounts WHERE user_id = ?")) {
                statement.setString(1, userId);
                if (statement.executeUpdate() == 0) {
                    return ResponseEntity.notFound().build();
                }
            }
        }
        catalog.audit(account.userId(), "user.delete", userId);
        return ResponseEntity.noContent().build();
    }

    private AccountStore.Account manager(HttpServletRequest request) throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElse(null);
        String path =
                org.springframework.web.util.UrlPathHelper.defaultInstance.getLookupPathForRequest(
                        request);
        String domain = path.startsWith("/api/advisor/users") ? "user" : "role";
        if (account == null || !access.allowed(account, AccessPoints.operation(request, domain))) {
            return null;
        }
        return account;
    }

    private boolean protectedUser(AccountStore.Account actor, String userId) throws SQLException {
        if (access.allowed(actor, AccessPoints.RBAC)) return false;
        if (isBuiltin(userId) || allRoleIds(userId).contains(AccessPoints.SUPER_ADMIN_ROLE_ID))
            return true;
        var target = catalog.permissions(userId);
        for (String permission : target) if (!access.allowed(actor, permission)) return true;
        return false;
    }

    private boolean forbidden(HttpServletRequest request) throws SQLException {
        return manager(request) == null;
    }

    private Map<String, Object> roleView(AccessCatalog.RoleRow role) throws SQLException {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("roleId", role.roleId());
        view.put("roleNumber", role.roleNumber());
        view.put("name", role.name());
        view.put("code", role.code());
        view.put("description", role.description());
        view.put("sort", role.sort());
        view.put("status", role.status());
        view.put("builtin", role.builtin());
        view.put("createdAt", role.createdAt());
        view.put("permissions", catalog.permissionsOfRole(role.roleId()));
        return view;
    }

    private List<Map<String, Object>> listUsers(String query) throws SQLException {
        List<Map<String, Object>> users = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        io.agentscope.advisor.db.ListSearch.prepare(
                                connection,
                                "SELECT user_id, user_number, username, nickname, remark, status,"
                                        + " builtin, role, created_at FROM accounts ORDER BY"
                                        + " created_at",
                                query,
                                List.of("username"));
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String userId = rows.getString("user_id");
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("userId", userId);
                view.put("userNumber", rows.getLong("user_number"));
                view.put("username", rows.getString("username"));
                view.put("nickname", rows.getString("nickname"));
                view.put("remark", rows.getString("remark"));
                view.put("status", rows.getString("status"));
                view.put("builtin", rows.getInt("builtin") == 1);
                view.put("createdAt", rows.getLong("created_at"));
                view.put("roleIds", allRoleIds(userId));
                users.add(view);
            }
        }
        return users;
    }

    private List<String> allRoleIds(String userId) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT role_id FROM advisor_account_roles WHERE user_id = ?")) {
            statement.setString(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getString(1));
                }
            }
        }
        return ids;
    }

    private String userIdOf(String username) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT user_id FROM accounts WHERE username = ?")) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private String userIdOfId(String userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT user_id FROM accounts WHERE user_id = ?")) {
            statement.setString(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private boolean isBuiltin(String userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT builtin FROM accounts WHERE user_id = ?")) {
            statement.setString(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == 1;
            }
        }
    }

    private static List<String> roleIds(Map<String, Object> body) {
        List<String> roleIds = new ArrayList<>();
        if (body.get("roleIds") instanceof List<?> items) {
            for (Object item : items) {
                if (item != null) {
                    roleIds.add(item.toString());
                }
            }
        }
        return roleIds;
    }

    private static String text(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        String value = body.get(key).toString().strip();
        return value.isBlank() ? null : value;
    }

    private static int number(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!body.containsKey(key)) return 10;
        if (!(value instanceof Number number)
                || number.doubleValue() != number.intValue()
                || number.intValue() < 0) throw new IllegalArgumentException("排序必须是非负整数");
        return number.intValue();
    }
}
