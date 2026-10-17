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

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListSearchTest {
    private List<String> search(String q, boolean admin) throws Exception {
        try (Connection connection =
                DriverManager.getConnection("jdbc:h2:mem:" + java.util.UUID.randomUUID())) {
            connection
                    .createStatement()
                    .execute(
                            "CREATE TABLE items (name VARCHAR, description VARCHAR, owner_id"
                                    + " VARCHAR)");
            connection
                    .createStatement()
                    .execute(
                            "INSERT INTO items VALUES ('Alpha', 'Target', 'me'), ('Beta', 'Target',"
                                    + " 'other'), ('100%_done', NULL, 'me')");
            String sql =
                    "SELECT name FROM items WHERE owner_id = ?"
                            + (admin ? " OR owner_id IS NOT NULL" : "")
                            + " ORDER BY name";
            try (var statement =
                            ListSearch.prepare(
                                    connection, sql, q, List.of("name", "description"), "me");
                    var rows = statement.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (rows.next()) names.add(rows.getString(1));
                return names;
            }
        }
    }

    @Test
    void queryMatchesNameOrDescriptionWithoutEscapingOwnership() throws Exception {
        assertEquals(List.of("Alpha"), search(" target ", false));
        assertEquals(List.of("Beta"), search("beta", true));
        assertEquals(List.of(), search("beta", false));
    }

    @Test
    void wildcardAndSqlCharactersAreLiteralInput() throws Exception {
        assertEquals(List.of("100%_done"), search("%_", true));
        assertEquals(List.of(), search("' OR 1=1 --", true));
    }

    @Test
    void emptyQueryRetainsOriginalVisibilityAndSort() throws Exception {
        assertEquals(List.of("100%_done", "Alpha"), search("", false));
        assertEquals(List.of("100%_done", "Alpha", "Beta"), search(null, true));
    }
}
