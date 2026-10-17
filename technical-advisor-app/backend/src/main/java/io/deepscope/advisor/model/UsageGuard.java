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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/** Stops a task when a configured limit is crossed. Zero means that limit is not set. */
public final class UsageGuard {

    private final ModelCatalog catalog;
    private final DataSource dataSource;
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public UsageGuard(ModelCatalog catalog, DataSource dataSource) {
        this.catalog = catalog;
        this.dataSource = dataSource;
    }

    public void check(String userId) throws SQLException {
        ModelCatalog.UsagePolicy policy = catalog.usage();
        if (policy.ratePerMinute() > 0 && tooFast(userId, policy.ratePerMinute())) {
            throw new IllegalStateException("入口请求太频繁");
        }
        if (policy.dailyTaskLimit() > 0 && tasksToday(userId) >= policy.dailyTaskLimit()) {
            throw new IllegalStateException("今天的任务数到了上限");
        }
    }

    private boolean tooFast(String userId, int limit) {
        long now = System.currentTimeMillis();
        Deque<Long> stamps = recent.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        synchronized (stamps) {
            while (!stamps.isEmpty() && now - stamps.peekFirst() > 60_000) {
                stamps.removeFirst();
            }
            if (stamps.size() >= limit) {
                return true;
            }
            stamps.addLast(now);
            return false;
        }
    }

    private int tasksToday(String userId) throws SQLException {
        long start =
                LocalDate.now(ZoneId.systemDefault())
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM advisor_sessions WHERE owner_user_id = ? AND"
                                        + " created_at >= ?")) {
            statement.setString(1, userId);
            statement.setLong(2, start);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
