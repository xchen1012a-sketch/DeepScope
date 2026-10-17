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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FileTaskBoardStoreTest {

    private Path tempDir;
    private FileTaskBoardStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("task-board-file-store-test");
        store = new FileTaskBoardStore(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(
                                path -> {
                                    try {
                                        Files.deleteIfExists(path);
                                    } catch (IOException ignored) {
                                    }
                                });
            }
        }
    }

    @Test
    void saveAndLoadBoardStatePreservesAllFields() {
        String sessionId = "sess-file-1";
        TaskBoard board = new TaskBoard(sessionId, 6);
        board.addTask("t-1", "调研", "调研技术方案", "researcher", List.of());
        board.addTask("t-2", "实现", "编写核心模块", "developer", List.of("t-1"));
        board.updateTask("t-1", TaskStatus.SUCCEEDED, "技术方案已输出");

        store.saveBoard(board);

        // 验证文件持久化存在
        Path expectedFile =
                tempDir.resolve("sessions").resolve(sessionId).resolve("task-board.json");
        assertTrue(Files.exists(expectedFile));

        // 从文件重新加载现场
        Optional<TaskBoard> loadedOpt = store.loadBoard(sessionId);
        assertTrue(loadedOpt.isPresent());
        TaskBoard loaded = loadedOpt.get();

        assertEquals(sessionId, loaded.getSessionId());
        assertEquals(6, loaded.getMaxTasks());
        assertEquals(2, loaded.totalCount());

        TaskItem t1 = loaded.getTask("t-1").orElseThrow();
        assertEquals(TaskStatus.SUCCEEDED, t1.status());
        assertEquals("技术方案已输出", t1.resultSummary());

        // t-1 办结使得 t-2 现场解冻就绪
        TaskItem t2 = loaded.getTask("t-2").orElseThrow();
        assertEquals(TaskStatus.READY, t2.status());
        assertEquals(List.of("t-1"), t2.blockedBy());
    }

    @Test
    void deleteBoardRemovesFile() {
        String sessionId = "sess-del";
        TaskBoard board = new TaskBoard(sessionId, 5);
        board.addTask("t-1", "临时任务", "说明", "worker", List.of());
        store.saveBoard(board);

        assertTrue(store.loadBoard(sessionId).isPresent());

        store.deleteBoard(sessionId);
        assertFalse(store.loadBoard(sessionId).isPresent());
    }
}
