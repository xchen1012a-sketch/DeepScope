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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrphanTaskSweeperTest {

    private Path tempDir;
    private FileTaskBoardStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("task-orphan-sweeper-test");
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
    void runningTaskSweptToFailedOnRehydrationAfterCrash() {
        String sessionId = "sess-orphan-crash";
        TaskBoard originalBoard = new TaskBoard(sessionId, 5);

        // 创建两个任务，t-1 处于 RUNNING，t-2 依赖 t-1
        originalBoard.addTask("t-1", "核心计算", "执行长时间运算", "worker", List.of());
        originalBoard.addTask("t-2", "后续汇总", "汇总运算成果", "worker", List.of("t-1"));
        originalBoard.updateTask("t-1", TaskStatus.RUNNING, null);

        // 持久化保存（此时 t-1 是 RUNNING）
        store.saveBoard(originalBoard);

        // 模拟进程崩溃/重启：重新加载快照
        TaskBoard restoredBoard = store.loadBoard(sessionId).orElseThrow();

        // 验证孤儿收敛：t-1 自动转为 FAILED 并附带自愈说明
        TaskItem restoredT1 = restoredBoard.getTask("t-1").orElseThrow();
        assertEquals(TaskStatus.FAILED, restoredT1.status());
        assertTrue(restoredT1.resultSummary().contains("interrupted by system restart"));

        // 验证因 t-1 失败，下游依赖 t-2 级联取消（CANCELLED），防止死锁
        TaskItem restoredT2 = restoredBoard.getTask("t-2").orElseThrow();
        assertEquals(TaskStatus.CANCELLED, restoredT2.status());

        // 验证并发槽位完全释放（0 running）
        assertEquals(0, restoredBoard.runningCount());
    }
}
