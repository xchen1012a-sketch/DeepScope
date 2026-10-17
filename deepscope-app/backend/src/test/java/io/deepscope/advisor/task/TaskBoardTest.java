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

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskBoardTest {

    private TaskBoard board;

    @BeforeEach
    void setUp() {
        board = new TaskBoard("test-session");
    }

    @Test
    void taskWithoutDependenciesIsImmediatelyReady() {
        TaskItem item = board.addTask("t-1", "调研方案", "调研两种架构方案", "researcher", List.of());
        assertEquals("t-1", item.taskId());
        assertEquals(TaskStatus.READY, item.status());
        assertEquals(1, board.getReadyTasks().size());
        assertEquals("t-1", board.getReadyTasks().get(0).taskId());
    }

    @Test
    void taskWithUnfinishedDependenciesIsBlockedUntilDependencySucceeds() {
        TaskItem t1 = board.addTask("t-1", "后端接口开发", "实现用户接口", "backend", List.of());
        TaskItem t2 = board.addTask("t-2", "前端组件对接", "对接后端接口渲染表格", "frontend", List.of("t-1"));

        assertEquals(TaskStatus.READY, t1.status());
        assertEquals(TaskStatus.BLOCKED, t2.status());
        assertEquals(List.of("t-1"), t2.blockedBy());

        // t-1 开始运行，t-2 依然保持 BLOCKED
        board.updateTask("t-1", TaskStatus.RUNNING, null);
        assertEquals(TaskStatus.BLOCKED, board.getTask("t-2").orElseThrow().status());

        // t-1 办结并提交交付物，t-2 自动解冻为 READY
        board.updateTask("t-1", TaskStatus.SUCCEEDED, "用户接口已实现并测试通过");
        TaskItem t2Updated = board.getTask("t-2").orElseThrow();
        assertEquals(TaskStatus.READY, t2Updated.status());
        assertEquals(List.of("t-2"), board.getReadyTasks().stream().map(TaskItem::taskId).toList());
    }

    @Test
    void dependencyFailureCascadesCancellationToDownstreamTasks() {
        board.addTask("t-1", "数据库建表", "创建核心表", "dba", List.of());
        board.addTask("t-2", "DAO层编写", "编写数据访问代码", "backend", List.of("t-1"));
        board.addTask("t-3", "上层业务逻辑", "编写业务调用", "backend", List.of("t-2"));

        assertEquals(TaskStatus.READY, board.getTask("t-1").orElseThrow().status());
        assertEquals(TaskStatus.BLOCKED, board.getTask("t-2").orElseThrow().status());
        assertEquals(TaskStatus.BLOCKED, board.getTask("t-3").orElseThrow().status());

        // t-1 失败，级联导致 t-2 和 t-3 均被取消
        board.updateTask("t-1", TaskStatus.FAILED, "数据库权限不足，建表失败");
        assertEquals(TaskStatus.FAILED, board.getTask("t-1").orElseThrow().status());
        assertEquals(TaskStatus.CANCELLED, board.getTask("t-2").orElseThrow().status());
        assertEquals(TaskStatus.CANCELLED, board.getTask("t-3").orElseThrow().status());
        assertTrue(board.getReadyTasks().isEmpty());
    }

    @Test
    void multiBranchDAGResolvesProperly() {
        // T1 -> T2 -> T4
        // T1 -> T3 -> T4
        board.addTask("t-1", "基础架构选型", "确定核心栈", "architect", List.of());
        board.addTask("t-2", "后端核心骨架", "搭建服务骨架", "backend", List.of("t-1"));
        board.addTask("t-3", "前端脚手架搭建", "搭建UI项目", "frontend", List.of("t-1"));
        board.addTask("t-4", "联调测试", "双端全链路跑通", "tester", List.of("t-2", "t-3"));

        assertEquals(TaskStatus.READY, board.getTask("t-1").orElseThrow().status());
        assertEquals(TaskStatus.BLOCKED, board.getTask("t-2").orElseThrow().status());
        assertEquals(TaskStatus.BLOCKED, board.getTask("t-3").orElseThrow().status());
        assertEquals(TaskStatus.BLOCKED, board.getTask("t-4").orElseThrow().status());

        // T1 办结，T2 与 T3 同时解冻就绪
        board.updateTask("t-1", TaskStatus.SUCCEEDED, "选型完成");
        assertEquals(TaskStatus.READY, board.getTask("t-2").orElseThrow().status());
        assertEquals(TaskStatus.READY, board.getTask("t-3").orElseThrow().status());
        assertEquals(TaskStatus.BLOCKED, board.getTask("t-4").orElseThrow().status());

        // T2 办结，但 T3 尚未办结，T4 仍必须保持 BLOCKED
        board.updateTask("t-2", TaskStatus.SUCCEEDED, "后端骨架已就绪");
        assertEquals(TaskStatus.BLOCKED, board.getTask("t-4").orElseThrow().status());

        // T3 也办结，T4 所有前置达成，正式激活为 READY
        board.updateTask("t-3", TaskStatus.SUCCEEDED, "前端骨架已就绪");
        assertEquals(TaskStatus.READY, board.getTask("t-4").orElseThrow().status());
    }

    @Test
    void rejectDuplicateTaskIds() {
        board.addTask("t-1", "任务1", "说明", "role", List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> board.addTask("t-1", "任务1重复", "说明", "role", List.of()));
    }

    @Test
    void outOfOrderTaskCreationKeepsDependentTaskBlockedUntilPrerequisiteCreatedAndFinished() {
        // 先创建依赖项 t-parent 的 t-child，此时 t-parent 尚未创建
        board.addTask("t-child", "子任务", "依赖未创建的父任务", "worker", List.of("t-parent"));
        assertEquals(TaskStatus.BLOCKED, board.getTask("t-child").orElseThrow().status());

        // 随后补齐 t-parent
        board.addTask("t-parent", "父任务", "父级前置工作", "worker", List.of());
        assertEquals(TaskStatus.READY, board.getTask("t-parent").orElseThrow().status());
        assertEquals(TaskStatus.BLOCKED, board.getTask("t-child").orElseThrow().status());

        // 父任务办结，子任务顺利解冻为 READY
        board.updateTask("t-parent", TaskStatus.SUCCEEDED, "父任务成果");
        assertEquals(TaskStatus.READY, board.getTask("t-child").orElseThrow().status());
    }
}
