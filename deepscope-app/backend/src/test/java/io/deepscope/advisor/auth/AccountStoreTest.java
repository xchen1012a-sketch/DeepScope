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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.advisor.db.AdvisorSchema;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class AccountStoreTest {
    private static final String TEST_PASSWORD = "test-password-only";
    private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");

    /** In-memory PostgreSQL-dialect database so tests run without a PostgreSQL server. */
    private static DataSource newDatabase() throws SQLException {
        DataSource dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:accounts-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        return dataSource;
    }

    private static AccountStore store(DataSource database, Instant time) {
        return new AccountStore(database, Clock.fixed(time, ZoneOffset.UTC), Duration.ofHours(12));
    }

    @Test
    void loginRejectsUnknownAccountAndWrongPassword() throws Exception {
        var database = newDatabase();
        var accounts = store(database, NOW);
        accounts.bootstrap("test-admin", TEST_PASSWORD);
        assertTrue(accounts.login("missing", TEST_PASSWORD).isEmpty());
        assertTrue(accounts.login("test-admin", "wrong-password").isEmpty());
        var login = accounts.login("test-admin", TEST_PASSWORD).orElseThrow();
        assertEquals("test-admin", login.account().username());
        assertNotNull(accounts.authenticate(login.token()).orElseThrow());
        assertTrue(accounts.authenticate("invented-token").isEmpty());
    }

    @Test
    void preservesAccountAndSessionAcrossReopenWithoutResettingPassword() throws Exception {
        var database = newDatabase();
        var accounts = store(database, NOW);
        accounts.bootstrap("test-admin", TEST_PASSWORD);
        var login = accounts.login("test-admin", TEST_PASSWORD).orElseThrow();
        var reopened = store(database, NOW.plusSeconds(60));
        reopened.bootstrap("test-admin", "different-password");
        assertTrue(reopened.login("test-admin", "different-password").isEmpty());
        assertTrue(reopened.login("test-admin", TEST_PASSWORD).isPresent());
        assertEquals(login.account(), reopened.authenticate(login.token()).orElseThrow());
    }

    @Test
    void renamedBuiltinAdminDoesNotRecreateTheOldBootstrapAccountOnRestart() throws Exception {
        var database = newDatabase();
        var configuration = new AuthConfiguration();
        var properties = new AuthProperties("original-admin", TEST_PASSWORD, Duration.ofHours(12));
        var accounts = configuration.accountStore(database, properties);
        new io.deepscope.advisor.access.AccessCatalog(database).ensureBuiltin();
        var account = accounts.login("original-admin", TEST_PASSWORD).orElseThrow().account();
        accounts.setPassword(account.userId(), "changed-password-only");
        accounts.updateProfile(account.userId(), "renamed-admin", "Admin", "Keep");
        var reopened = configuration.accountStore(database, properties);
        assertTrue(reopened.login("original-admin", TEST_PASSWORD).isEmpty());
        assertTrue(reopened.login("renamed-admin", TEST_PASSWORD).isEmpty());
        assertEquals(
                account.userId(),
                reopened.login("renamed-admin", "changed-password-only")
                        .orElseThrow()
                        .account()
                        .userId());
        try (var connection = database.getConnection();
                var query = connection.createStatement();
                var rows = query.executeQuery("SELECT COUNT(*) FROM accounts")) {
            rows.next();
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void expiresAndRevokesOnlyTheSelectedSession() throws Exception {
        var database = newDatabase();
        var accounts = store(database, NOW);
        accounts.bootstrap("test-admin", TEST_PASSWORD);
        var first = accounts.login("test-admin", TEST_PASSWORD).orElseThrow();
        var second = accounts.login("test-admin", TEST_PASSWORD).orElseThrow();
        assertNotEquals(first.token(), second.token());
        accounts.logout(first.token());
        assertTrue(accounts.authenticate(first.token()).isEmpty());
        assertTrue(accounts.authenticate(second.token()).isPresent());
        assertTrue(
                store(database, NOW.plus(Duration.ofHours(12)))
                        .authenticate(second.token())
                        .isEmpty());
    }

    @Test
    void persistsOnlyPasswordHashAndTokenDigest() throws Exception {
        var database = newDatabase();
        var accounts = store(database, NOW);
        accounts.bootstrap("test-admin", TEST_PASSWORD);
        var login = accounts.login("test-admin", TEST_PASSWORD).orElseThrow();
        try (var connection = database.getConnection();
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT password_hash FROM accounts")) {
                assertTrue(rows.next());
                assertNotEquals(TEST_PASSWORD, rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT token_hash FROM login_sessions")) {
                assertTrue(rows.next());
                assertNotEquals(login.token(), rows.getString(1));
            }
        }
    }

    @Test
    void selfServiceProfilePasswordAndSessionRevocation() throws Exception {
        var database = newDatabase();
        var accounts = store(database, NOW);
        accounts.bootstrap("test-admin", TEST_PASSWORD);
        var login = accounts.login("test-admin", TEST_PASSWORD).orElseThrow();
        String userId = login.account().userId();

        // 昵称与头像：管理员写的备注不被自改昵称覆盖；头像只存路径与更新时间
        accounts.updateProfile(userId, "管理员", "由管理员填写的备注");
        accounts.setNickname(userId, "张三");
        var profile = accounts.profile(userId).orElseThrow();
        assertEquals("张三", profile.nickname());
        assertEquals("test-admin", profile.username());
        assertEquals("", profile.avatarPath());
        assertEquals(0L, profile.avatarUpdatedAt());
        accounts.setAvatar(userId, "avatars/" + userId + "/a.png");
        assertEquals(
                "avatars/" + userId + "/a.png",
                accounts.profile(userId).orElseThrow().avatarPath());
        assertTrue(accounts.profile(userId).orElseThrow().avatarUpdatedAt() > 0);
        accounts.setAvatar(userId, "");
        assertEquals("", accounts.profile(userId).orElseThrow().avatarPath());
        assertEquals(0L, accounts.profile(userId).orElseThrow().avatarUpdatedAt());
        try (var connection = database.getConnection();
                var rows =
                        connection.createStatement().executeQuery("SELECT remark FROM accounts")) {
            assertTrue(rows.next());
            assertEquals("由管理员填写的备注", rows.getString(1));
        }

        // 校验当前密码
        assertTrue(accounts.verifyPassword(userId, TEST_PASSWORD));
        assertFalse(accounts.verifyPassword(userId, "wrong-password"));
        assertFalse(accounts.verifyPassword(userId, "  "));

        // 改密后只保留当前会话
        var other = accounts.login("test-admin", TEST_PASSWORD).orElseThrow();
        accounts.revokeOthers(userId, other.token());
        assertTrue(accounts.authenticate(login.token()).isEmpty());
        assertTrue(accounts.authenticate(other.token()).isPresent());
        accounts.setPassword(userId, "new-password-only");
        assertTrue(accounts.verifyPassword(userId, "new-password-only"));
        assertTrue(accounts.authenticate(other.token()).isPresent());
    }
}
