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

package io.agentscope.advisor.db;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared COUNT and bounded SQL query for management lists. SQL fragments are code-owned. */
public final class JdbcPage {
    private JdbcPage() {}

    public static int page(HttpServletRequest request) {
        return number(request, "page", 1);
    }

    public static int size(HttpServletRequest request) {
        return number(request, "size", 10);
    }

    private static int number(HttpServletRequest request, String key, int fallback) {
        try {
            return request.getParameter(key) == null
                    ? fallback
                    : Integer.parseInt(request.getParameter(key));
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        }
    }

    public static void validate(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "page must be positive; size must be between 1 and 100");
        }
    }

    @FunctionalInterface
    public interface Reader<T> {
        List<T> read(PreparedStatement statement) throws SQLException;
    }

    public static <T> PageResult<T> fetch(
            Connection connection, Query query, int page, int size, Reader<T> reader)
            throws SQLException {
        validate(page, size);
        long total;
        try (var count =
                connection.prepareStatement(
                        "SELECT COUNT(*) FROM (" + query.sql() + ") page_count")) {
            query.bind(count);
            try (var rows = count.executeQuery()) {
                rows.next();
                total = rows.getLong(1);
            }
        }
        long totalPages = (total + size - 1) / size;
        int actualPage = (int) Math.min(page, Math.max(1L, totalPages));
        try (var statement =
                connection.prepareStatement(
                        query.sql() + " ORDER BY " + query.order + " LIMIT ? OFFSET ?")) {
            int index = query.bind(statement);
            statement.setInt(index++, size);
            statement.setLong(index, ((long) actualPage - 1) * size);
            return new PageResult<>(reader.read(statement), actualPage, size, total, totalPages);
        }
    }

    public static final class Query {
        private final String base;
        private final String order;
        private final List<String> predicates = new ArrayList<>();
        private final List<Object> parameters = new ArrayList<>();

        public Query(String base, String order) {
            this.base = base;
            this.order = order;
        }

        public Query where(String predicate, Object... values) {
            predicates.add("(" + predicate + ")");
            parameters.addAll(java.util.Arrays.asList(values));
            return this;
        }

        public Query equal(String column, String value) {
            return value == null || value.isBlank() ? this : where(column + " = ?", value);
        }

        public Query search(String query, List<String> columns) {
            if (query == null || query.isBlank()) return this;
            return where(
                    columns.stream()
                            .map(column -> "LOWER(COALESCE(" + column + ", '')) LIKE ? ESCAPE '!'")
                            .collect(java.util.stream.Collectors.joining(" OR ")),
                    columns.stream().map(column -> ListSearch.pattern(query)).toArray());
        }

        /** Inclusive creation start and exclusive end; callers resolve their local calendar dates. */
        public Query createdBetween(String from, String before) {
            Long start = timestamp(from);
            Long end = timestamp(before);
            if (start != null && end != null && start >= end)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid creation range");
            if (start != null) where("created_at >= ?", start);
            if (end != null) where("created_at < ?", end);
            return this;
        }

        private static Long timestamp(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                long timestamp = Long.parseLong(value);
                if (timestamp >= 0) return timestamp;
            } catch (NumberFormatException invalid) {
                // Invalid numeric forms share the same request validation response.
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid creation time");
        }

        private String sql() {
            return base
                    + (predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates));
        }

        private int bind(PreparedStatement statement) throws SQLException {
            int index = 1;
            for (Object value : parameters) statement.setObject(index++, value);
            return index;
        }
    }
}
