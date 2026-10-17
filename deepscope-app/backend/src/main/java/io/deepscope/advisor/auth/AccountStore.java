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
package io.deepscope.advisor.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** App-local administrator accounts and revocable, persisted login sessions. */
public final class AccountStore {
    public record Account(String userId, String username, String role, long createdAt) {
        public boolean isAdmin() {
            return "admin".equals(role);
        }
    }

    public record Login(String token, Account account) {}

    /** Self-service profile fields; the avatar bytes live on disk and are never returned here. */
    public record Profile(
            String userId,
            String username,
            String nickname,
            String avatarPath,
            long avatarUpdatedAt,
            long createdAt) {}

    public static class DuplicateAccountException extends RuntimeException {
        public DuplicateAccountException(String message) {
            super(message);
        }
    }

    private final DataSource dataSource;
    private final Clock clock;
    private final Duration sessionTtl;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash = PasswordHashes.hash(UUID.randomUUID().toString());

    public AccountStore(DataSource dataSource, Clock clock, Duration sessionTtl) {
        if (sessionTtl.isNegative() || sessionTtl.isZero())
            throw new IllegalArgumentException("Session TTL must be positive");
        this.dataSource = dataSource;
        this.clock = clock;
        this.sessionTtl = sessionTtl;
    }

    /** Startup bootstrap never replaces a password or recreates a renamed builtin administrator. */
    public void bootstrap(String username, String password) throws SQLException {
        if (username.isBlank() && password.isBlank()) return;
        if (username.isBlank()
                || password.isBlank()
                || username.length() > 100
                || password.length() > 256)
            throw new IllegalArgumentException("Invalid administrator bootstrap configuration");
        if (find(username).isPresent() || hasBuiltinAdministrator()) return;
        String hash = PasswordHashes.hash(password);
        try (var connection = dataSource.getConnection();
                var insert =
                        connection.prepareStatement(
                                "INSERT INTO accounts (user_id, username, password_hash, role,"
                                        + " created_at) VALUES (?, ?, ?, 'admin', ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, username);
            insert.setString(3, hash);
            insert.setLong(4, clock.millis());
            insert.executeUpdate();
        }
    }

    private boolean hasBuiltinAdministrator() throws SQLException {
        try (var connection = dataSource.getConnection();
                var query =
                        connection.prepareStatement(
                                "SELECT 1 FROM accounts WHERE builtin = 1 AND role = 'admin'");
                var rows = query.executeQuery()) {
            return rows.next();
        }
    }

    /** Adds a regular-user account for permission-related tests and future RBAC stages. */
    public void createUser(String username, String password) throws SQLException {
        if (username.isBlank()
                || password.isBlank()
                || username.length() > 100
                || password.length() > 256)
            throw new IllegalArgumentException("Invalid account credentials");
        if (find(username).isPresent()) return;
        String hash = PasswordHashes.hash(password);
        try (var connection = dataSource.getConnection();
                var insert =
                        connection.prepareStatement(
                                "INSERT INTO accounts (user_id, username, password_hash, role,"
                                        + " created_at) VALUES (?, ?, ?, 'user', ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, username);
            insert.setString(3, hash);
            insert.setLong(4, clock.millis());
            insert.executeUpdate();
        }
    }

    public Login register(String email, String password, String defaultRoleIdentifier)
            throws SQLException {
        if (email == null || email.isBlank() || email.length() > 100) {
            throw new IllegalArgumentException("Invalid email");
        }
        if (password == null || password.isBlank() || password.length() > 256) {
            throw new IllegalArgumentException("Invalid password");
        }
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var check =
                        connection.prepareStatement(
                                "SELECT user_id, password_hash, role, created_at, status FROM"
                                        + " accounts WHERE LOWER(username) = ?")) {
                    check.setString(1, normalizedEmail);
                    try (var rs = check.executeQuery()) {
                        if (rs.next()) {
                            String existingUserId = rs.getString("user_id");
                            String existingHash = rs.getString("password_hash");
                            String existingRole = rs.getString("role");
                            long existingCreatedAt = rs.getLong("created_at");
                            String existingStatus = rs.getString("status");

                            if (PasswordHashes.matches(password, existingHash)
                                    && !"disabled".equals(existingStatus)) {
                                byte[] bytes = new byte[32];
                                random.nextBytes(bytes);
                                String token =
                                        Base64.getUrlEncoder()
                                                .withoutPadding()
                                                .encodeToString(bytes);
                                try (var insertSession =
                                        connection.prepareStatement(
                                                "INSERT INTO login_sessions (token_hash, user_id,"
                                                        + " expires_at) VALUES (?, ?, ?)")) {
                                    insertSession.setString(1, tokenHash(token));
                                    insertSession.setString(2, existingUserId);
                                    insertSession.setLong(
                                            3, clock.instant().plus(sessionTtl).toEpochMilli());
                                    insertSession.executeUpdate();
                                }
                                connection.commit();
                                Account account =
                                        new Account(
                                                existingUserId,
                                                normalizedEmail,
                                                existingRole,
                                                existingCreatedAt);
                                return new Login(token, account);
                            }

                            connection.rollback();
                            throw new DuplicateAccountException(
                                    "Email already exists: " + normalizedEmail);
                        }
                    }
                }

                String roleId = null;
                String roleIdentifier =
                        defaultRoleIdentifier == null || defaultRoleIdentifier.isBlank()
                                ? io.deepscope.advisor.access.AccessPoints.CHAT_ROLE_ID
                                : defaultRoleIdentifier.trim();
                try (var roleQuery =
                        connection.prepareStatement(
                                "SELECT role_id FROM advisor_roles WHERE role_id = ? OR code ="
                                        + " ?")) {
                    roleQuery.setString(1, roleIdentifier);
                    roleQuery.setString(2, roleIdentifier);
                    try (var rs = roleQuery.executeQuery()) {
                        if (rs.next()) {
                            roleId = rs.getString(1);
                        }
                    }
                }

                long now = clock.millis();

                if (roleId == null) {
                    roleId =
                            io.deepscope.advisor.access.AccessPoints.CHAT_ROLE_ID.equals(
                                                    roleIdentifier)
                                            || "chat".equals(roleIdentifier)
                                    ? io.deepscope.advisor.access.AccessPoints.CHAT_ROLE_ID
                                    : UUID.randomUUID().toString();
                    String roleCode =
                            io.deepscope.advisor.access.AccessPoints.CHAT_ROLE_ID.equals(
                                                    roleIdentifier)
                                            || "chat".equals(roleIdentifier)
                                    ? "chat"
                                    : roleIdentifier;
                    try (var insertRole =
                            connection.prepareStatement(
                                    "INSERT INTO advisor_roles (role_id, name, code, description,"
                                            + " sort_order, status, builtin, created_at) VALUES"
                                            + " (?, ?, ?, ?, ?, ?, ?, ?)")) {
                        insertRole.setString(1, roleId);
                        insertRole.setString(2, "普通用户");
                        insertRole.setString(3, roleCode);
                        insertRole.setString(4, "仅拥有会话使用权限的基础角色。");
                        insertRole.setInt(5, 2);
                        insertRole.setString(6, "active");
                        insertRole.setInt(7, 1);
                        insertRole.setLong(8, now);
                        insertRole.executeUpdate();
                    }
                    try (var insertPerm =
                            connection.prepareStatement(
                                    "INSERT INTO advisor_role_permissions (role_id, permission)"
                                            + " VALUES (?, ?)")) {
                        insertPerm.setString(1, roleId);
                        insertPerm.setString(2, io.deepscope.advisor.access.AccessPoints.CHAT);
                        insertPerm.executeUpdate();
                    }
                }

                String userId = UUID.randomUUID().toString();
                String hash = PasswordHashes.hash(password);
                try (var insert =
                        connection.prepareStatement(
                                "INSERT INTO accounts (user_id, username, password_hash, role,"
                                        + " created_at) VALUES (?, ?, ?, 'user', ?)")) {
                    insert.setString(1, userId);
                    insert.setString(2, normalizedEmail);
                    insert.setString(3, hash);
                    insert.setLong(4, now);
                    insert.executeUpdate();
                }

                try (var grant =
                        connection.prepareStatement(
                                "INSERT INTO advisor_account_roles (user_id, role_id) VALUES (?,"
                                        + " ?)")) {
                    grant.setString(1, userId);
                    grant.setString(2, roleId);
                    grant.executeUpdate();
                }

                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                try (var insertSession =
                        connection.prepareStatement(
                                "INSERT INTO login_sessions (token_hash, user_id, expires_at)"
                                        + " VALUES (?, ?, ?)")) {
                    insertSession.setString(1, tokenHash(token));
                    insertSession.setString(2, userId);
                    insertSession.setLong(3, clock.instant().plus(sessionTtl).toEpochMilli());
                    insertSession.executeUpdate();
                }

                connection.commit();
                Account account = new Account(userId, normalizedEmail, "user", now);
                return new Login(token, account);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public Optional<Login> login(String username, String password) throws SQLException {
        if (username == null
                || password == null
                || username.isBlank()
                || username.length() > 100
                || password.length() > 256) return Optional.empty();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Password changes lock this same row: old-password logins cannot insert late.
                var stored = find(connection, username, true);
                String hash = stored.map(StoredAccount::passwordHash).orElse(dummyPasswordHash);
                if (!PasswordHashes.matches(password, hash)
                        || stored.isEmpty()
                        || "disabled".equals(stored.orElseThrow().status())) {
                    connection.rollback();
                    return Optional.empty();
                }
                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                Account account = stored.orElseThrow().account();
                try (var cleanup =
                        connection.prepareStatement(
                                "DELETE FROM login_sessions WHERE expires_at <= ?")) {
                    cleanup.setLong(1, clock.millis());
                    cleanup.executeUpdate();
                }
                try (var insert =
                        connection.prepareStatement(
                                "INSERT INTO login_sessions (token_hash, user_id, expires_at)"
                                        + " VALUES (?, ?, ?)")) {
                    insert.setString(1, tokenHash(token));
                    insert.setString(2, account.userId());
                    insert.setLong(3, clock.instant().plus(sessionTtl).toEpochMilli());
                    insert.executeUpdate();
                }
                connection.commit();
                return Optional.of(new Login(token, account));
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public Optional<Account> authenticate(String token) throws SQLException {
        if (token == null || token.length() != 43) return Optional.empty();
        try (var connection = dataSource.getConnection();
                var query =
                        connection.prepareStatement(
                                "SELECT a.user_id, a.username, a.role, a.created_at FROM accounts"
                                        + " a JOIN login_sessions s ON s.user_id = a.user_id WHERE"
                                        + " s.token_hash = ? AND s.expires_at > ? AND a.status <>"
                                        + " 'disabled'")) {
            query.setString(1, tokenHash(token));
            query.setLong(2, clock.millis());
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(account(rows)) : Optional.empty();
            }
        }
    }

    public void logout(String token) throws SQLException {
        if (token == null) return;
        try (var connection = dataSource.getConnection();
                var delete =
                        connection.prepareStatement(
                                "DELETE FROM login_sessions WHERE token_hash = ?")) {
            delete.setString(1, tokenHash(token));
            delete.executeUpdate();
        }
    }

    public void revokeAll(String userId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var delete =
                        connection.prepareStatement(
                                "DELETE FROM login_sessions WHERE user_id = ?")) {
            delete.setString(1, userId);
            delete.executeUpdate();
        }
    }

    public void setPassword(String userId, String password) throws SQLException {
        if (password == null || password.isBlank() || password.length() > 256) {
            throw new IllegalArgumentException("Invalid account credentials");
        }
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE accounts SET password_hash = ? WHERE user_id = ?")) {
            update.setString(1, PasswordHashes.hash(password));
            update.setString(2, userId);
            update.executeUpdate();
        }
    }

    /** Verifies and changes a password together with revocation, or changes nothing. */
    public boolean changePassword(
            String userId, String current, String replacement, String keepToken)
            throws SQLException {
        if (replacement == null
                || replacement.isBlank()
                || replacement.length() < 8
                || replacement.length() > 256)
            throw new IllegalArgumentException("Invalid new password");
        if (current == null || current.length() > 256 || keepToken == null) return false;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var query =
                        connection.prepareStatement(
                                "SELECT password_hash FROM accounts WHERE user_id = ? FOR"
                                        + " UPDATE")) {
                    query.setString(1, userId);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next() || !PasswordHashes.matches(current, rows.getString(1))) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                try (var update =
                        connection.prepareStatement(
                                "UPDATE accounts SET password_hash=? WHERE user_id=?")) {
                    update.setString(1, PasswordHashes.hash(replacement));
                    update.setString(2, userId);
                    update.executeUpdate();
                }
                try (var revoke =
                        connection.prepareStatement(
                                "DELETE FROM login_sessions WHERE user_id=? AND token_hash<>?")) {
                    revoke.setString(1, userId);
                    revoke.setString(2, tokenHash(keepToken));
                    revoke.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public void updateProfile(String userId, String nickname, String remark) throws SQLException {
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE accounts SET nickname = ?, remark = ? WHERE user_id = ?")) {
            update.setString(1, nickname == null ? "" : nickname);
            update.setString(2, remark == null ? "" : remark);
            update.setString(3, userId);
            update.executeUpdate();
        }
    }

    /** Updates administrator-editable fields together without changing grants or credentials. */
    public void updateProfile(String userId, String username, String nickname, String remark)
            throws SQLException {
        if (username == null || username.isBlank() || username.strip().length() > 100)
            throw new IllegalArgumentException("请输入有效账号（最多100个字符）");
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE accounts SET username = ?, nickname = ?, remark = ? WHERE"
                                        + " user_id = ?")) {
            update.setString(1, username.strip());
            update.setString(2, nickname == null ? "" : nickname);
            update.setString(3, remark == null ? "" : remark);
            update.setString(4, userId);
            update.executeUpdate();
        } catch (SQLException conflict) {
            if ("23505".equals(conflict.getSQLState())) throw new IllegalArgumentException("账号已存在");
            throw conflict;
        }
    }

    public void setStatus(String userId, String status) throws SQLException {
        if (!"active".equals(status) && !"disabled".equals(status)) {
            throw new IllegalArgumentException("账号状态无效");
        }
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE accounts SET status = ? WHERE user_id = ?"
                                        + " AND (builtin = 0 OR ? = 'active')")) {
            update.setString(1, status);
            update.setString(2, userId);
            update.setString(3, status);
            if (update.executeUpdate() == 0) {
                throw new IllegalArgumentException("内置账号不能停用或账号不存在");
            }
        }
        if ("disabled".equals(status)) {
            revokeAll(userId);
        }
    }

    /** The caller's own profile; a missing row means the account disappeared. */
    public Optional<Profile> profile(String userId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var query =
                        connection.prepareStatement(
                                "SELECT user_id, username, nickname, avatar_path,"
                                    + " avatar_updated_at, created_at FROM accounts WHERE user_id ="
                                    + " ?")) {
            query.setString(1, userId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(
                        new Profile(
                                rows.getString("user_id"),
                                rows.getString("username"),
                                rows.getString("nickname") == null
                                        ? ""
                                        : rows.getString("nickname"),
                                rows.getString("avatar_path") == null
                                        ? ""
                                        : rows.getString("avatar_path"),
                                rows.getLong("avatar_updated_at"),
                                rows.getLong("created_at")));
            }
        }
    }

    /** Confirms the current password before a self-service change. */
    public boolean verifyPassword(String userId, String password) throws SQLException {
        if (password == null || password.isBlank() || password.length() > 256) return false;
        String hash = dummyPasswordHash;
        try (var connection = dataSource.getConnection();
                var query =
                        connection.prepareStatement(
                                "SELECT password_hash FROM accounts WHERE user_id = ?")) {
            query.setString(1, userId);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    String stored = rows.getString("password_hash");
                    if (stored != null) hash = stored;
                }
            }
        }
        return PasswordHashes.matches(password, hash);
    }

    /** Self-service nickname change keeps the remark an administrator may have written. */
    public void setNickname(String userId, String nickname) throws SQLException {
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE accounts SET nickname = ? WHERE user_id = ?")) {
            update.setString(1, nickname == null ? "" : nickname);
            update.setString(2, userId);
            update.executeUpdate();
        }
    }

    public void setAvatar(String userId, String avatarPath) throws SQLException {
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE accounts SET avatar_path = ?, avatar_updated_at = CASE WHEN"
                                    + " ? = 0 THEN 0 ELSE GREATEST(?, avatar_updated_at + 1) END"
                                    + " WHERE user_id = ?")) {
            update.setString(1, avatarPath == null ? "" : avatarPath);
            update.setLong(2, avatarPath == null || avatarPath.isBlank() ? 0L : clock.millis());
            update.setLong(3, clock.millis());
            update.setString(4, userId);
            update.executeUpdate();
        }
    }

    /** Password changes sign other devices out but keep the session that made the change. */
    public void revokeOthers(String userId, String keepToken) throws SQLException {
        try (var connection = dataSource.getConnection();
                var delete =
                        connection.prepareStatement(
                                "DELETE FROM login_sessions WHERE user_id = ? AND token_hash <>"
                                        + " ?")) {
            delete.setString(1, userId);
            delete.setString(2, keepToken == null ? "" : tokenHash(keepToken));
            delete.executeUpdate();
        }
    }

    private record StoredAccount(Account account, String passwordHash, String status) {}

    private Optional<StoredAccount> find(String username) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return find(connection, username, false);
        }
    }

    private Optional<StoredAccount> find(
            java.sql.Connection connection, String username, boolean lock) throws SQLException {
        try (var query =
                connection.prepareStatement(
                        "SELECT user_id, username, password_hash, role, status, created_at"
                                + " FROM accounts WHERE username = ? OR LOWER(username) = LOWER(?)"
                                + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, username);
            query.setString(2, username);
            try (var rows = query.executeQuery()) {
                return rows.next()
                        ? Optional.of(
                                new StoredAccount(
                                        account(rows),
                                        rows.getString("password_hash"),
                                        rows.getString("status")))
                        : Optional.empty();
            }
        }
    }

    private static Account account(ResultSet rows) throws SQLException {
        return new Account(
                rows.getString("user_id"),
                rows.getString("username"),
                rows.getString("role"),
                rows.getLong("created_at"));
    }

    private static String tokenHash(String token) {
        try {
            return Base64.getEncoder()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Token hashing unavailable", error);
        }
    }
}
