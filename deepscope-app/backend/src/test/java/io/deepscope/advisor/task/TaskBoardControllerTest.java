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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TaskBoardControllerTest {

    private TaskBoardService boardService;
    private TaskSchedulerService schedulerService;
    private TaskBoardController controller;

    @BeforeEach
    void setUp() {
        TaskGovernorProperties properties = new TaskGovernorProperties(6, 2, 300, "FILE");
        boardService = new TaskBoardService(properties);
        schedulerService = new TaskSchedulerService(boardService, properties);
        controller = new TaskBoardController(boardService, schedulerService);
    }

    @Test
    void getBoardReturnsNotFoundWhenEmpty() {
        ResponseEntity<TaskBoardController.TaskBoardResponse> res =
                controller.getBoard("non-existent");
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
    }

    @Test
    void getBoardReturnsPopulatedBoard() {
        String session = "s-ctrl-1";
        boardService.createTask(session, "t-1", "标题1", "描述1", "analyst", List.of());
        boardService.createTask(session, "t-2", "标题2", "描述2", "coder", List.of("t-1"));

        ResponseEntity<TaskBoardController.TaskBoardResponse> res = controller.getBoard(session);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(2, res.getBody().totalCount());
        assertEquals(1, res.getBody().readyCount());
        assertEquals(1, res.getBody().blockedCount());
    }

    @Test
    void retryTaskSetsStatusReadyAndTriggersDispatch() {
        String session = "s-ctrl-retry";
        boardService.createTask(session, "t-err", "异常任务", "描述", "worker", List.of());
        boardService.updateTask(session, "t-err", TaskStatus.FAILED, "执行超时");

        ResponseEntity<TaskItem> res = controller.retryTask(session, "t-err");
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());

        // 由于有空闲槽位，重试后自动被拉起为 RUNNING
        TaskItem current = boardService.getTask(session, "t-err").orElseThrow();
        assertEquals(TaskStatus.RUNNING, current.status());
    }

    @Test
    void skipTaskMarksSucceededAndUnblocksDownstream() {
        String session = "s-ctrl-skip";
        boardService.createTask(session, "t-parent", "父任务", "描述", "worker", List.of());
        boardService.createTask(session, "t-child", "子任务", "描述", "worker", List.of("t-parent"));

        ResponseEntity<TaskItem> res = controller.skipTask(session, "t-parent", "需求变更无需处理");
        assertEquals(HttpStatus.OK, res.getStatusCode());

        // 父任务标记办结，子任务成功解冻并拉起为 RUNNING
        TaskItem child = boardService.getTask(session, "t-child").orElseThrow();
        assertEquals(TaskStatus.RUNNING, child.status());
    }
}
