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

package io.deepscope.advisor.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JdbcPageTest {
    @Test
    void filtersBeforeCountingAndPagingAndTreatsWildcardsLiterally() throws Exception {
        try (var connection =
                DriverManager.getConnection("jdbc:h2:mem:" + java.util.UUID.randomUUID())) {
            connection
                    .createStatement()
                    .execute(
                            "CREATE TABLE items (id INT, name VARCHAR, owner VARCHAR, status"
                                    + " VARCHAR)");
            connection
                    .createStatement()
                    .execute(
                            "INSERT INTO items VALUES"
                                + " (1,'100%_a','me','ready'),(2,'100%_b','me','ready'),(3,'100%_c','other','ready'),(4,'100%_d','me','draft')");
            var query =
                    new JdbcPage.Query("SELECT * FROM items", "id")
                            .equal("owner", "me")
                            .equal("status", "ready")
                            .search("%_", List.of("name"));
            var page =
                    JdbcPage.fetch(
                            connection,
                            query,
                            2,
                            1,
                            statement -> {
                                var result = new ArrayList<String>();
                                try (var rows = statement.executeQuery()) {
                                    while (rows.next()) result.add(rows.getString("name"));
                                }
                                return result;
                            });
            assertEquals(List.of("100%_b"), page.items());
            assertEquals(2, page.total());
            assertEquals(2, page.totalPages());
            var beyondLast =
                    JdbcPage.fetch(
                            connection,
                            query,
                            3,
                            1,
                            statement -> {
                                var result = new ArrayList<String>();
                                try (var rows = statement.executeQuery()) {
                                    while (rows.next()) result.add(rows.getString("name"));
                                }
                                return result;
                            });
            assertEquals(List.of("100%_b"), beyondLast.items());
            assertEquals(2, beyondLast.page());
            connection.createStatement().execute("DELETE FROM items WHERE id=2");
            var afterDelete =
                    JdbcPage.fetch(
                            connection,
                            query,
                            2,
                            1,
                            statement -> {
                                var result = new ArrayList<String>();
                                try (var rows = statement.executeQuery()) {
                                    while (rows.next()) result.add(rows.getString("name"));
                                }
                                return result;
                            });
            assertEquals(1, afterDelete.page());
            assertEquals(List.of("100%_a"), afterDelete.items());
        }
    }

    @Test
    void rejectsInvalidBounds() {
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> JdbcPage.validate(0, 10));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> JdbcPage.validate(1, 101));
    }
}
