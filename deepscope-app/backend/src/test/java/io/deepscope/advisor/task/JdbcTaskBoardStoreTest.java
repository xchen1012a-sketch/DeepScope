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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.advisor.db.AdvisorSchema;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class JdbcTaskBoardStoreTest {

    private DataSource dataSource;
    private JdbcTaskBoardStore store;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:taskboard-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        store = new JdbcTaskBoardStore(dataSource);
    }

    @Test
    void databaseSaveAndLoadRehydratesState() {
        String sessionId = "sess-db-1";
        TaskBoard board = new TaskBoard(sessionId, 7);
        board.addTask("tk-1", "架构设计", "产出设计文档", "architect", List.of());
        board.addTask("tk-2", "编码实现", "实现业务逻辑", "developer", List.of("tk-1"));
        board.updateTask("tk-1", TaskStatus.SUCCEEDED, "架构已就绪");

        store.saveBoard(board);

        Optional<TaskBoard> loadedOpt = store.loadBoard(sessionId);
        assertTrue(loadedOpt.isPresent());
        TaskBoard loaded = loadedOpt.get();

        assertEquals(sessionId, loaded.getSessionId());
        assertEquals(7, loaded.getMaxTasks());
        assertEquals(2, loaded.totalCount());

        TaskItem tk1 = loaded.getTask("tk-1").orElseThrow();
        assertEquals(TaskStatus.SUCCEEDED, tk1.status());
        assertEquals("架构已就绪", tk1.resultSummary());

        TaskItem tk2 = loaded.getTask("tk-2").orElseThrow();
        assertEquals(TaskStatus.READY, tk2.status());
    }

    @Test
    void databaseUpdateAndOrphanSweeping() {
        String sessionId = "sess-db-sweeper";
        TaskBoard board = new TaskBoard(sessionId, 4);
        board.addTask("tk-run", "运行中任务", "模拟断电未办结", "worker", List.of());
        board.updateTask("tk-run", TaskStatus.RUNNING, null);

        store.saveBoard(board);

        // 重建时孤儿收敛
        TaskBoard loaded = store.loadBoard(sessionId).orElseThrow();
        TaskItem tkRun = loaded.getTask("tk-run").orElseThrow();
        assertEquals(TaskStatus.FAILED, tkRun.status());
        assertTrue(tkRun.resultSummary().contains("interrupted by system restart"));
    }

    @Test
    void databaseDeleteRemovesRecord() {
        String sessionId = "sess-db-del";
        TaskBoard board = new TaskBoard(sessionId, 5);
        board.addTask("t-tmp", "临时", "desc", "worker", List.of());
        store.saveBoard(board);

        assertTrue(store.loadBoard(sessionId).isPresent());

        store.deleteBoard(sessionId);
        assertFalse(store.loadBoard(sessionId).isPresent());
    }
}
