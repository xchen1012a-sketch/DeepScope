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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Parameterized literal search for catalog list queries. SQL and columns are code-owned. */
public final class ListSearch {
    private ListSearch() {}

    public static String pattern(String query) {
        return "%"
                + query.strip()
                        .toLowerCase(Locale.ROOT)
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_")
                + "%";
    }

    public static PreparedStatement prepare(
            Connection connection,
            String sql,
            String query,
            List<String> columns,
            Object... parameters)
            throws SQLException {
        String keyword = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        String statementSql = sql;
        if (!keyword.isEmpty()) {
            int order = sql.toUpperCase(Locale.ROOT).lastIndexOf(" ORDER BY ");
            String body = order < 0 ? sql : sql.substring(0, order);
            String suffix = order < 0 ? "" : sql.substring(order);
            int where = body.toUpperCase(Locale.ROOT).indexOf(" WHERE ");
            String predicate =
                    columns.stream()
                            .map(column -> "LOWER(COALESCE(" + column + ", '')) LIKE ? ESCAPE '!'")
                            .collect(Collectors.joining(" OR "));
            statementSql =
                    (where < 0
                                    ? body + " WHERE "
                                    : body.substring(0, where)
                                            + " WHERE ("
                                            + body.substring(where + 7)
                                            + ") AND ")
                            + "("
                            + predicate
                            + ")"
                            + suffix;
        }
        PreparedStatement statement = connection.prepareStatement(statementSql);
        int index = 1;
        for (Object parameter : parameters) statement.setObject(index++, parameter);
        if (!keyword.isEmpty()) {
            String pattern = pattern(keyword);
            for (int i = 0; i < columns.size(); i++) statement.setString(index++, pattern);
        }
        return statement;
    }
}
