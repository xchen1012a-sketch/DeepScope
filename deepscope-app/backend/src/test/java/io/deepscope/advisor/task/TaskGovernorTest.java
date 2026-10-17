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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.agent.RuntimeContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskGovernorTest {

    private TaskGovernorProperties properties;
    private TaskBoardService service;

    @BeforeEach
    void setUp() {
        properties = new TaskGovernorProperties(4, 2, 300);
        service = new TaskBoardService(properties);
    }

    @Test
    void defaultQuotaEnforcementOnBoard() {
        TaskBoard defaultBoard = new TaskBoard("sess-default", 8);
        for (int i = 1; i <= 8; i++) {
            defaultBoard.addTask("t-" + i, "任务" + i, "描述" + i, "worker", List.of());
        }
        assertEquals(8, defaultBoard.totalCount());

        TaskQuotaExceededException ex =
                assertThrows(
                        TaskQuotaExceededException.class,
                        () -> defaultBoard.addTask("t-9", "任务9", "超额任务", "worker", List.of()));
        assertTrue(
                ex.getMessage().contains("Task board quota exceeded: maximum allowed tasks is 8"));
        assertEquals(8, ex.getMaxLimit());
    }

    @Test
    void configuredQuotaEnforcementViaService() {
        for (int i = 1; i <= 4; i++) {
            service.createTask("sess-custom", "task-" + i, "标题" + i, "说明" + i, "worker", List.of());
        }

        TaskQuotaExceededException ex =
                assertThrows(
                        TaskQuotaExceededException.class,
                        () ->
                                service.createTask(
                                        "sess-custom",
                                        "task-5",
                                        "超出熔断任务",
                                        "说明5",
                                        "worker",
                                        List.of()));
        assertTrue(ex.getMessage().contains("maximum allowed tasks is 4"));
    }

    @Test
    void actionsGracefullyCatchesQuotaExceeded() {
        TaskBoardActions actions = new TaskBoardActions(service);
        RuntimeContext rc = RuntimeContext.builder().sessionId("sess-tool-quota").build();

        for (int i = 1; i <= 4; i++) {
            String res = actions.taskCreate("tk-" + i, "子任务" + i, "内容", "worker", List.of(), rc);
            assertTrue(res.contains("created successfully"));
        }

        String fifthRes = actions.taskCreate("tk-5", "子任务5", "内容", "worker", List.of(), rc);
        assertTrue(fifthRes.startsWith("Error: Task board quota exceeded"));
        assertTrue(fifthRes.contains("maximum allowed tasks is 4"));
    }
}
