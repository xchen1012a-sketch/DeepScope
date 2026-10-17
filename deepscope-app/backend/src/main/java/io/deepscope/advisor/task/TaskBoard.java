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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话维度的中心任务看板聚合根，维护任务拓扑依赖（DAG）与状态流转。
 */
public class TaskBoard {

    private final String sessionId;
    private final int maxTasks;
    private final Map<String, TaskItem> tasks = new LinkedHashMap<>();
    private long lastUpdatedAt;

    public TaskBoard(String sessionId, int maxTasks) {
        if (maxTasks <= 0) {
            throw new IllegalArgumentException("maxTasks must be greater than 0");
        }
        this.sessionId = sessionId;
        this.maxTasks = maxTasks;
        this.lastUpdatedAt = System.currentTimeMillis();
    }

    public TaskBoard(String sessionId) {
        this(sessionId, 8);
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getMaxTasks() {
        return maxTasks;
    }

    public synchronized long getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    /**
     * 导出当前看板的持久化快照。
     */
    public synchronized TaskBoardSnapshot toSnapshot() {
        return new TaskBoardSnapshot(sessionId, maxTasks, lastUpdatedAt, listTasks());
    }

    /**
     * 从持久化快照现场重建看板，并执行孤儿任务扫描自愈（Orphan Sweeper）。
     */
    public static TaskBoard fromSnapshot(TaskBoardSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot cannot be null");
        }
        TaskBoard board = new TaskBoard(snapshot.sessionId(), snapshot.maxTasks());
        long now = System.currentTimeMillis();

        if (snapshot.tasks() != null) {
            for (TaskItem item : snapshot.tasks()) {
                TaskItem toAdd = item;
                // 孤儿子任务收敛（Orphan Sweeper）：若因系统崩溃/重启导致任务残留为 RUNNING，自动转为 FAILED 并记录
                if (item.status() == TaskStatus.RUNNING) {
                    toAdd =
                            item.withStatusAndResult(
                                    TaskStatus.FAILED,
                                    "Task interrupted by system restart or process termination."
                                            + " Ready for agent retry.",
                                    now);
                }
                board.tasks.put(toAdd.taskId(), toAdd);
            }
        }
        board.evaluateReadiness(now);
        board.lastUpdatedAt = snapshot.lastUpdatedAt() > 0 ? snapshot.lastUpdatedAt() : now;
        return board;
    }

    public synchronized List<TaskItem> listTasks() {
        return Collections.unmodifiableList(new ArrayList<>(tasks.values()));
    }

    public synchronized Optional<TaskItem> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * 登记或追加一个新的任务实体，并自动计算其初始依赖状态（BLOCKED 或 READY）。
     */
    public synchronized TaskItem addTask(
            String taskId,
            String title,
            String description,
            String assigneeRole,
            List<String> blockedBy) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId cannot be blank");
        }
        if (tasks.containsKey(taskId)) {
            throw new IllegalArgumentException("Task already exists with id: " + taskId);
        }
        if (tasks.size() >= maxTasks) {
            throw new TaskQuotaExceededException(maxTasks);
        }

        long now = System.currentTimeMillis();
        TaskStatus initialStatus = TaskStatus.PENDING;

        TaskItem item =
                new TaskItem(
                        taskId,
                        title == null ? "" : title.strip(),
                        description == null ? "" : description.strip(),
                        assigneeRole == null ? "worker" : assigneeRole.strip(),
                        blockedBy,
                        initialStatus,
                        null,
                        now,
                        now,
                        0);

        tasks.put(taskId, item);
        evaluateReadiness(now);
        this.lastUpdatedAt = now;
        return tasks.get(taskId);
    }

    /**
     * 更新任务状态或结果。当任务办结时，自动触发下游依赖的级联解冻或级联取消。
     */
    public synchronized TaskItem updateTask(
            String taskId, TaskStatus status, String resultSummary) {
        TaskItem current = tasks.get(taskId);
        if (current == null) {
            throw new IllegalArgumentException("Task not found with id: " + taskId);
        }

        long now = System.currentTimeMillis();
        TaskItem updated = current.withStatusAndResult(status, resultSummary, now);
        tasks.put(taskId, updated);

        // 状态跃迁后，级联评估整个拓扑图的就绪状态
        evaluateReadiness(now);
        this.lastUpdatedAt = now;
        return tasks.get(taskId);
    }

    /**
     * DAG 依赖评估引擎：
     * 1. 遍历所有处于 PENDING 或 BLOCKED 的任务；
     * 2. 若其所有前置依赖项均已 SUCCEEDED，则流转为 READY；
     * 3. 若有前置依赖项发生 FAILED 或 CANCELLED，则下游任务级联 CANCELLED；
     * 4. 否则保持 BLOCKED。
     */
    public synchronized void evaluateReadiness(long now) {
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<String, TaskItem> entry : tasks.entrySet()) {
                TaskItem item = entry.getValue();
                if (item.status().isTerminal() || item.status() == TaskStatus.RUNNING) {
                    continue;
                }

                if (item.blockedBy().isEmpty()) {
                    if (item.status() != TaskStatus.READY) {
                        tasks.put(entry.getKey(), item.withStatus(TaskStatus.READY, now));
                        changed = true;
                    }
                    continue;
                }

                boolean anyFailedOrCancelled = false;
                boolean allSucceeded = true;

                for (String depId : item.blockedBy()) {
                    TaskItem dep = tasks.get(depId);
                    if (dep == null) {
                        // 前置依赖项尚未创建或等待中，保持 BLOCKED，不提前误判取消
                        allSucceeded = false;
                        continue;
                    }
                    if (dep.status() == TaskStatus.FAILED || dep.status() == TaskStatus.CANCELLED) {
                        anyFailedOrCancelled = true;
                        break;
                    }
                    if (dep.status() != TaskStatus.SUCCEEDED) {
                        allSucceeded = false;
                    }
                }

                if (anyFailedOrCancelled) {
                    tasks.put(entry.getKey(), item.withStatus(TaskStatus.CANCELLED, now));
                    changed = true;
                } else if (allSucceeded) {
                    if (item.status() != TaskStatus.READY) {
                        tasks.put(entry.getKey(), item.withStatus(TaskStatus.READY, now));
                        changed = true;
                    }
                } else {
                    if (item.status() != TaskStatus.BLOCKED) {
                        tasks.put(entry.getKey(), item.withStatus(TaskStatus.BLOCKED, now));
                        changed = true;
                    }
                }
            }
        } while (changed);
    }

    public synchronized List<TaskItem> getReadyTasks() {
        return tasks.values().stream().filter(item -> item.status() == TaskStatus.READY).toList();
    }

    public synchronized boolean hasUnfinishedTasks() {
        return tasks.values().stream().anyMatch(item -> !item.status().isTerminal());
    }

    public synchronized boolean isAllSucceeded() {
        return !tasks.isEmpty()
                && tasks.values().stream().allMatch(item -> item.status() == TaskStatus.SUCCEEDED);
    }

    public synchronized List<TaskItem> getRunningTasks() {
        return tasks.values().stream().filter(item -> item.status() == TaskStatus.RUNNING).toList();
    }

    public synchronized int totalCount() {
        return tasks.size();
    }

    public synchronized int succeededCount() {
        return (int)
                tasks.values().stream()
                        .filter(item -> item.status() == TaskStatus.SUCCEEDED)
                        .count();
    }

    public synchronized int runningCount() {
        return (int)
                tasks.values().stream().filter(item -> item.status() == TaskStatus.RUNNING).count();
    }

    public synchronized int readyCount() {
        return (int)
                tasks.values().stream().filter(item -> item.status() == TaskStatus.READY).count();
    }

    public synchronized int blockedCount() {
        return (int)
                tasks.values().stream()
                        .filter(
                                item ->
                                        item.status() == TaskStatus.BLOCKED
                                                || item.status() == TaskStatus.PENDING)
                        .count();
    }

    public synchronized int failedCount() {
        return (int)
                tasks.values().stream().filter(item -> item.status() == TaskStatus.FAILED).count();
    }

    public synchronized int cancelledCount() {
        return (int)
                tasks.values().stream()
                        .filter(item -> item.status() == TaskStatus.CANCELLED)
                        .count();
    }
}
