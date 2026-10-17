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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 动态并发调度器：负责控制并发槽位、动态拉起就绪任务以及办结联动。
 */
@Service
public class TaskSchedulerService {

    private final TaskBoardService boardService;
    private final TaskGovernorProperties properties;

    @Autowired
    public TaskSchedulerService(TaskBoardService boardService, TaskGovernorProperties properties) {
        this.boardService = boardService;
        this.properties = properties != null ? properties : TaskGovernorProperties.defaults();
    }

    public TaskSchedulerService(TaskBoardService boardService) {
        this(boardService, TaskGovernorProperties.defaults());
    }

    public TaskGovernorProperties getProperties() {
        return properties;
    }

    /**
     * 计算指定会话看板当前的剩余可用并发槽位数。
     */
    public int getAvailableSlots(String sessionId) {
        TaskBoard board = boardService.getOrCreateBoard(sessionId);
        synchronized (board) {
            int running = board.runningCount();
            return Math.max(0, properties.maxConcurrentTasks() - running);
        }
    }

    /**
     * 自动从看板就绪队列拉起下一批任务进入 RUNNING 状态（受当前并发槽位数上限约束）。
     *
     * @return 本轮成功分配槽位并进入 RUNNING 状态的任务列表
     */
    public List<TaskItem> dispatchNextReadyTasks(String sessionId) {
        TaskBoard board = boardService.getOrCreateBoard(sessionId);
        List<TaskItem> dispatched = new ArrayList<>();

        synchronized (board) {
            int availableSlots =
                    Math.max(0, properties.maxConcurrentTasks() - board.runningCount());
            if (availableSlots <= 0) {
                return dispatched;
            }

            List<TaskItem> readyTasks = board.getReadyTasks();
            for (TaskItem ready : readyTasks) {
                if (dispatched.size() >= availableSlots) {
                    break;
                }
                TaskItem running = board.updateTask(ready.taskId(), TaskStatus.RUNNING, null);
                dispatched.add(running);
            }
            if (!dispatched.isEmpty()) {
                boardService.persistBoard(sessionId);
            }
        }
        return dispatched;
    }

    /**
     * 手动触发指定任务进入 RUNNING 状态（严格校验槽位余量与就绪前置条件）。
     */
    public Optional<TaskItem> dispatchTask(String sessionId, String taskId) {
        TaskBoard board = boardService.getOrCreateBoard(sessionId);
        synchronized (board) {
            if (board.runningCount() >= properties.maxConcurrentTasks()) {
                throw new IllegalStateException(
                        "No available concurrency slots (max: "
                                + properties.maxConcurrentTasks()
                                + ", running: "
                                + board.runningCount()
                                + ")");
            }
            TaskItem item =
                    board.getTask(taskId)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Task not found: " + taskId));
            if (item.status() != TaskStatus.READY) {
                throw new IllegalStateException(
                        "Task [" + taskId + "] is not READY (status: " + item.status() + ")");
            }
            TaskItem running = board.updateTask(taskId, TaskStatus.RUNNING, null);
            boardService.persistBoard(sessionId);
            return Optional.of(running);
        }
    }

    /**
     * 当某子任务执行结束（SUCCEEDED / FAILED / CANCELLED）时调用：
     * 1. 提交任务终态及交付物；
     * 2. 状态机自动联动 DAG 依赖解冻或级联取消；
     * 3. 释放并发槽位，并自动从就绪队列拉起下一批就绪任务。
     */
    public TaskDispatchResult completeTask(
            String sessionId, String taskId, TaskStatus status, String resultSummary) {
        if (!status.isTerminal()) {
            throw new IllegalArgumentException(
                    "Status must be terminal (SUCCEEDED, FAILED, CANCELLED): " + status);
        }

        TaskBoard board = boardService.getOrCreateBoard(sessionId);
        TaskItem completed;
        List<TaskItem> newlyCancelled = new ArrayList<>();
        List<TaskItem> newlyDispatched;

        synchronized (board) {
            // 记录变更前已处于 CANCELLED 的任务
            List<String> previouslyCancelled =
                    board.listTasks().stream()
                            .filter(t -> t.status() == TaskStatus.CANCELLED)
                            .map(TaskItem::taskId)
                            .toList();

            // 更新任务状态（此步已联动更新下游依赖）
            completed = board.updateTask(taskId, status, resultSummary);

            // 识别本次级联取消的下游任务
            for (TaskItem item : board.listTasks()) {
                if (item.status() == TaskStatus.CANCELLED
                        && !item.taskId().equals(taskId)
                        && !previouslyCancelled.contains(item.taskId())) {
                    newlyCancelled.add(item);
                }
            }

            // 自动拉起空余槽位对应的下一批就绪任务
            newlyDispatched = dispatchNextReadyTasks(sessionId);
            boardService.persistBoard(sessionId);
        }

        return new TaskDispatchResult(
                completed,
                newlyDispatched,
                newlyCancelled,
                board.runningCount(),
                getAvailableSlots(sessionId),
                board.isAllSucceeded(),
                board.hasUnfinishedTasks());
    }
}
