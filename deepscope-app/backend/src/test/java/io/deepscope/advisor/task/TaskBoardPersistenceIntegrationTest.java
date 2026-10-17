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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskBoardPersistenceIntegrationTest {

    private Path tempDir;
    private FileTaskBoardStore store;
    private TaskGovernorProperties properties;
    private TaskBoardService service;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("task-service-integration-test");
        store = new FileTaskBoardStore(tempDir);
        properties = new TaskGovernorProperties(5, 2, 300, "FILE");
        service = new TaskBoardService(properties, store);
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
    void serviceMemoryCacheClearTransparentlyRehydratesFromStore() {
        String session = "sess-integ-cache";

        service.createTask(session, "t-1", "标题1", "描述1", "worker", List.of());
        service.createTask(session, "t-2", "标题2", "描述2", "worker", List.of("t-1"));
        service.updateTask(session, "t-1", TaskStatus.SUCCEEDED, "完成t-1");

        // 模拟新的应用实例/缓存被清除，只保留磁盘文件
        TaskBoardService freshInstance = new TaskBoardService(properties, store);

        // 新实例查询该会话
        List<TaskItem> tasks = freshInstance.listTasks(session);
        assertEquals(2, tasks.size());

        TaskItem t1 = freshInstance.getTask(session, "t-1").orElseThrow();
        assertEquals(TaskStatus.SUCCEEDED, t1.status());

        TaskItem t2 = freshInstance.getTask(session, "t-2").orElseThrow();
        assertEquals(TaskStatus.READY, t2.status());
    }
}
