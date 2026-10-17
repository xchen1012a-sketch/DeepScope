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
package io.deepscope.advisor.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 关系型数据库存储模式（模式 B）：
 * 存储在关系数据库（PostgreSQL / H2）的 advisor_task_boards 表中，支持事务与跨节点共享。
 */
public class JdbcTaskBoardStore implements TaskBoardStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcTaskBoardStore.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcTaskBoardStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public JdbcTaskBoardStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    @Override
    public Optional<TaskBoard> loadBoard(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }

        String sql =
                "SELECT max_tasks, last_updated_at, tasks_json FROM advisor_task_boards WHERE"
                        + " session_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int maxTasks = rs.getInt("max_tasks");
                    long lastUpdatedAt = rs.getLong("last_updated_at");
                    String tasksJson = rs.getString("tasks_json");
                    List<TaskItem> tasks =
                            objectMapper.readValue(
                                    tasksJson, new TypeReference<List<TaskItem>>() {});
                    TaskBoardSnapshot snapshot =
                            new TaskBoardSnapshot(sessionId, maxTasks, lastUpdatedAt, tasks);
                    return Optional.of(TaskBoard.fromSnapshot(snapshot));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load task board from database for session: {}", sessionId, e);
        }
        return Optional.empty();
    }

    @Override
    public void saveBoard(TaskBoard board) {
        if (board == null) {
            return;
        }

        TaskBoardSnapshot snapshot = board.toSnapshot();
        String tasksJson;
        try {
            tasksJson = objectMapper.writeValueAsString(snapshot.tasks());
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to serialize tasks to JSON for session: " + board.getSessionId(), e);
        }

        try (Connection conn = dataSource.getConnection()) {
            String updateSql =
                    "UPDATE advisor_task_boards SET max_tasks = ?, last_updated_at = ?, tasks_json"
                            + " = ? WHERE session_id = ?";
            try (PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
                updatePs.setInt(1, snapshot.maxTasks());
                updatePs.setLong(2, snapshot.lastUpdatedAt());
                updatePs.setString(3, tasksJson);
                updatePs.setString(4, snapshot.sessionId());
                int rows = updatePs.executeUpdate();
                if (rows == 0) {
                    String insertSql =
                            "INSERT INTO advisor_task_boards (session_id, max_tasks,"
                                    + " last_updated_at, tasks_json) VALUES (?, ?, ?, ?)";
                    try (PreparedStatement insertPs = conn.prepareStatement(insertSql)) {
                        insertPs.setString(1, snapshot.sessionId());
                        insertPs.setInt(2, snapshot.maxTasks());
                        insertPs.setLong(3, snapshot.lastUpdatedAt());
                        insertPs.setString(4, tasksJson);
                        insertPs.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            log.error(
                    "Failed to persist task board to database for session: {}",
                    board.getSessionId(),
                    e);
            throw new RuntimeException(
                    "Database error saving task board: " + board.getSessionId(), e);
        }
    }

    @Override
    public void deleteBoard(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String sql = "DELETE FROM advisor_task_boards WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to delete task board from database for session: {}", sessionId, e);
        }
    }
}
