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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskSchedulerTest {

    private TaskGovernorProperties properties;
    private TaskBoardService boardService;
    private TaskSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        // 配置容量：单轮上限 6 个，并发槽位上限 2 个
        properties = new TaskGovernorProperties(6, 2, 300);
        boardService = new TaskBoardService(properties);
        scheduler = new TaskSchedulerService(boardService, properties);
    }

    @Test
    void slotLimitingPreventsExcessiveConcurrentTasks() {
        String session = "s-concurrency";
        boardService.createTask(session, "t-1", "任务1", "d1", "worker", List.of());
        boardService.createTask(session, "t-2", "任务2", "d2", "worker", List.of());
        boardService.createTask(session, "t-3", "任务3", "d3", "worker", List.of());

        assertEquals(2, scheduler.getAvailableSlots(session));

        // 首次调度：只能拉起 2 个任务
        List<TaskItem> dispatched = scheduler.dispatchNextReadyTasks(session);
        assertEquals(2, dispatched.size());
        assertEquals("t-1", dispatched.get(0).taskId());
        assertEquals("t-2", dispatched.get(1).taskId());
        assertEquals(0, scheduler.getAvailableSlots(session));

        // 再次调度：无可用槽位，返回空列表
        List<TaskItem> secondBatch = scheduler.dispatchNextReadyTasks(session);
        assertTrue(secondBatch.isEmpty());

        // 手动尝试启动第 3 个任务，抛出槽位已满异常
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class, () -> scheduler.dispatchTask(session, "t-3"));
        assertTrue(ex.getMessage().contains("No available concurrency slots"));
    }

    @Test
    void taskCompletionAutomaticallyReleasesSlotAndPullsNextReadyTask() {
        String session = "s-pipeline";
        boardService.createTask(session, "t-1", "任务1", "d1", "worker", List.of());
        boardService.createTask(session, "t-2", "任务2", "d2", "worker", List.of());
        boardService.createTask(session, "t-3", "任务3", "d3", "worker", List.of());

        // 启动前两个
        scheduler.dispatchNextReadyTasks(session);
        assertEquals(2, boardService.runningCount(session));
        assertEquals(0, scheduler.getAvailableSlots(session));

        // t-1 办结并释放槽位：系统自动拉起 t-3 进入 RUNNING
        TaskDispatchResult result =
                scheduler.completeTask(session, "t-1", TaskStatus.SUCCEEDED, "t-1 办结成果");

        assertEquals(TaskStatus.SUCCEEDED, result.completedTask().status());
        assertEquals(1, result.newlyDispatchedTasks().size());
        assertEquals("t-3", result.newlyDispatchedTasks().get(0).taskId());
        assertEquals(TaskStatus.RUNNING, result.newlyDispatchedTasks().get(0).status());

        // 当前正在运行的任务为 t-2 和 t-3
        assertEquals(2, result.runningCount());
        assertEquals(0, result.availableSlots());
        assertFalse(result.isAllSucceeded());
        assertTrue(result.hasUnfinishedTasks());

        // t-2 办结：无新任务可拉起，剩余槽位恢复为 1
        TaskDispatchResult res2 =
                scheduler.completeTask(session, "t-2", TaskStatus.SUCCEEDED, "t-2 办结");
        assertTrue(res2.newlyDispatchedTasks().isEmpty());
        assertEquals(1, res2.runningCount());
        assertEquals(1, res2.availableSlots());

        // t-3 办结：全部成功
        TaskDispatchResult res3 =
                scheduler.completeTask(session, "t-3", TaskStatus.SUCCEEDED, "t-3 办结");
        assertEquals(0, res3.runningCount());
        assertEquals(2, res3.availableSlots());
        assertTrue(res3.isAllSucceeded());
        assertFalse(res3.hasUnfinishedTasks());
    }

    @Test
    void dagUnblockingWithAutomaticDynamicDispatch() {
        String session = "s-dag-flow";
        boardService.createTask(session, "t-parent", "父任务", "desc", "worker", List.of());
        boardService.createTask(session, "t-child", "子任务", "desc", "worker", List.of("t-parent"));

        // 初始状态：t-parent READY, t-child BLOCKED
        assertEquals(
                TaskStatus.READY, boardService.getTask(session, "t-parent").orElseThrow().status());
        assertEquals(
                TaskStatus.BLOCKED,
                boardService.getTask(session, "t-child").orElseThrow().status());

        // 拉起父任务
        scheduler.dispatchNextReadyTasks(session);
        assertEquals(
                TaskStatus.RUNNING,
                boardService.getTask(session, "t-parent").orElseThrow().status());

        // 父任务完成：子任务自动解冻并因为有空闲槽位立即进入 RUNNING
        TaskDispatchResult result =
                scheduler.completeTask(session, "t-parent", TaskStatus.SUCCEEDED, "父任务成果产出");

        assertEquals(1, result.newlyDispatchedTasks().size());
        assertEquals("t-child", result.newlyDispatchedTasks().get(0).taskId());
        assertEquals(TaskStatus.RUNNING, result.newlyDispatchedTasks().get(0).status());
    }

    @Test
    void failureCascadesCancellationAndDoesNotBlockPipeline() {
        String session = "s-failure-cascade";
        boardService.createTask(session, "t-fail", "失败任务", "desc", "worker", List.of());
        boardService.createTask(session, "t-dep", "下游依赖任务", "desc", "worker", List.of("t-fail"));
        boardService.createTask(session, "t-other", "独立任务", "desc", "worker", List.of());

        scheduler.dispatchNextReadyTasks(session);
        // t-fail 和 t-other 开始运行

        TaskDispatchResult failRes =
                scheduler.completeTask(session, "t-fail", TaskStatus.FAILED, "发生不可逆错误");

        assertEquals(TaskStatus.FAILED, failRes.completedTask().status());
        assertEquals(1, failRes.newlyCancelledTasks().size());
        assertEquals("t-dep", failRes.newlyCancelledTasks().get(0).taskId());
        assertEquals(TaskStatus.CANCELLED, failRes.newlyCancelledTasks().get(0).status());

        // 独立任务 t-other 依然正常运行
        assertEquals(
                TaskStatus.RUNNING,
                boardService.getTask(session, "t-other").orElseThrow().status());
        assertEquals(1, failRes.runningCount());
        assertEquals(1, failRes.availableSlots());
    }
}
