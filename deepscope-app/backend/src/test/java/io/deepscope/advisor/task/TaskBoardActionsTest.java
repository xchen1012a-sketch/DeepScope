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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.agent.RuntimeContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskBoardActionsTest {

    private TaskBoardService service;
    private TaskBoardActions actions;
    private RuntimeContext rc;

    @BeforeEach
    void setUp() {
        service = new TaskBoardService();
        actions = new TaskBoardActions(service);
        rc = RuntimeContext.builder().sessionId("sess-test-123").build();
    }

    @Test
    void toolLifeCycleFlow() {
        // 1. 列表为空
        String emptyList = actions.taskList(rc);
        assertTrue(emptyList.contains("empty"));

        // 2. 创建第一个根任务
        String res1 =
                actions.taskCreate("task-1", "调研选型", "选择数据库与前端框架", "architect", List.of(), rc);
        assertTrue(res1.contains("created successfully"));
        assertTrue(res1.contains("READY"));

        // 3. 创建依赖 task-1 的子任务
        String res2 =
                actions.taskCreate("task-2", "编码实现", "编写核心逻辑", "developer", List.of("task-1"), rc);
        assertTrue(res2.contains("BLOCKED"));
        assertTrue(res2.contains("waiting for: [task-1]"));

        // 4. 查看看板全貌
        String boardList = actions.taskList(rc);
        assertTrue(boardList.contains("Total=2"));
        assertTrue(boardList.contains("Ready=1"));
        assertTrue(boardList.contains("Blocked/Pending=1"));

        // 5. 更新 task-1 为已办结：调度闸门自动联动解冻 task-2 并因槽位空闲直接拉起为 RUNNING
        String updateRes =
                actions.taskUpdate("task-1", "SUCCEEDED", "核心选型已确定为 PostgreSQL + React", rc);
        assertTrue(updateRes.contains("is now SUCCEEDED"));
        assertTrue(updateRes.contains("Automatically dispatched next ready task(s): [task-2]"));

        // 6. 查看单个任务详情
        String task1Detail = actions.taskGet("task-1", rc);
        assertTrue(task1Detail.contains("SUCCEEDED"));
        assertTrue(task1Detail.contains("PostgreSQL + React"));

        String task2Detail = actions.taskGet("task-2", rc);
        assertTrue(task2Detail.contains("RUNNING"));

        // 7. 测试任务调度工具 task_dispatch（此时无多余就绪任务）
        String dispatchRes = actions.taskDispatch(rc);
        assertTrue(dispatchRes.contains("No tasks dispatched"));
    }

    @Test
    void missingSessionContextThrowsException() {
        assertThrows(IllegalStateException.class, () -> actions.taskList(null));
        RuntimeContext blankRc = RuntimeContext.builder().sessionId("").build();
        assertThrows(IllegalStateException.class, () -> actions.taskList(blankRc));
    }
}
