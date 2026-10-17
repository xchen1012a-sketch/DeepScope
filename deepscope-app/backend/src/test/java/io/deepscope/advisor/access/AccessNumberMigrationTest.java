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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.db.AdvisorSchema;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class AccessNumberMigrationTest {
    @Test
    void migratesLegacyIdentifiersAndKeepsNumbersStableAcrossInitializationAndDeletion()
            throws Exception {
        var source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:numbers-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        try (var connection = source.getConnection();
                var sql = connection.createStatement()) {
            sql.execute(
                    "CREATE TABLE accounts (user_id TEXT PRIMARY KEY, username TEXT UNIQUE NOT"
                            + " NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL, created_at"
                            + " BIGINT NOT NULL)");
            sql.execute(
                    "INSERT INTO accounts VALUES ('existing-user', 'legacy', 'fake-hash', 'user',"
                            + " 1)");
            sql.execute(
                    "CREATE TABLE advisor_roles (role_id TEXT PRIMARY KEY, name TEXT NOT NULL, code"
                        + " TEXT UNIQUE NOT NULL, description TEXT NOT NULL, sort_order INTEGER NOT"
                        + " NULL, status TEXT NOT NULL, builtin INTEGER NOT NULL, created_at BIGINT"
                        + " NOT NULL)");
            sql.execute(
                    "INSERT INTO advisor_roles VALUES ('existing-role', 'Legacy', 'legacy', '', 1,"
                            + " 'active', 0, 1)");
        }
        AdvisorSchema.initialize(source);
        var catalog = new AccessCatalog(source);
        var accountStore = new AccountStore(source, Clock.systemUTC(), Duration.ofHours(1));
        accountStore.createUser("second", "fake-password");
        var second = catalog.createRole("Second", "second", 1, "");
        AdvisorSchema.initialize(source);
        try (var connection = source.getConnection();
                var sql = connection.createStatement()) {
            try (var rows =
                    sql.executeQuery(
                            "SELECT user_id, user_number FROM accounts ORDER BY user_number")) {
                assertTrue(rows.next());
                assertEquals("existing-user", rows.getString(1));
                assertEquals(1, rows.getLong(2));
                assertTrue(rows.next());
                assertEquals(2, rows.getLong(2));
            }
            try (var rows =
                    sql.executeQuery(
                            "SELECT role_id, role_number FROM advisor_roles ORDER BY"
                                    + " role_number")) {
                assertTrue(rows.next());
                assertEquals("existing-role", rows.getString(1));
                assertEquals(1, rows.getLong(2));
                assertTrue(rows.next());
                assertEquals(second.roleId(), rows.getString(1));
                assertEquals(2, rows.getLong(2));
            }
            sql.execute("DELETE FROM accounts WHERE user_id = 'existing-user'");
            sql.execute("DELETE FROM advisor_roles WHERE role_id = 'existing-role'");
        }
        accountStore.createUser("third", "fake-password");
        catalog.createRole("Third", "third", 1, "");
        try (var connection = source.getConnection();
                var sql = connection.createStatement()) {
            for (String table : new String[] {"accounts", "advisor_roles"}) {
                String column = table.equals("accounts") ? "user_number" : "role_number";
                try (var rows =
                        sql.executeQuery(
                                "SELECT " + column + " FROM " + table + " ORDER BY " + column)) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getLong(1));
                    assertTrue(rows.next());
                    assertEquals(3, rows.getLong(1));
                }
            }
        }
    }
}
