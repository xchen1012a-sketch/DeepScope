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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 任务看板管理服务：提供会话隔离的中心任务看板管理、容量配额接入及双模持久化能力。
 */
@Service
public class TaskBoardService {

    private final TaskGovernorProperties properties;
    private final TaskBoardStore store;
    private final Map<String, TaskBoard> boards = new ConcurrentHashMap<>();

    @Autowired
    public TaskBoardService(
            TaskGovernorProperties properties, @Autowired(required = false) TaskBoardStore store) {
        this.properties = properties != null ? properties : TaskGovernorProperties.defaults();
        this.store = store;
    }

    public TaskBoardService(TaskGovernorProperties properties) {
        this(properties, null);
    }

    public TaskBoardService() {
        this(TaskGovernorProperties.defaults(), null);
    }

    public TaskGovernorProperties getProperties() {
        return properties;
    }

    public TaskBoardStore getStore() {
        return store;
    }

    public TaskBoard getOrCreateBoard(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be blank");
        }
        return boards.computeIfAbsent(
                sessionId,
                id -> {
                    if (store != null) {
                        Optional<TaskBoard> loaded = store.loadBoard(id);
                        if (loaded.isPresent()) {
                            return loaded.get();
                        }
                    }
                    TaskBoard newBoard = new TaskBoard(id, properties.maxTasksPerBoard());
                    if (store != null) {
                        store.saveBoard(newBoard);
                    }
                    return newBoard;
                });
    }

    public Optional<TaskBoard> getBoard(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        TaskBoard board = boards.get(sessionId);
        if (board == null && store != null) {
            Optional<TaskBoard> loaded = store.loadBoard(sessionId);
            if (loaded.isPresent()) {
                boards.put(sessionId, loaded.get());
                return loaded;
            }
        }
        return Optional.ofNullable(board);
    }

    public TaskItem createTask(
            String sessionId,
            String taskId,
            String title,
            String description,
            String assigneeRole,
            List<String> blockedBy) {
        TaskBoard board = getOrCreateBoard(sessionId);
        TaskItem item = board.addTask(taskId, title, description, assigneeRole, blockedBy);
        if (store != null) {
            store.saveBoard(board);
        }
        return item;
    }

    public TaskItem updateTask(
            String sessionId, String taskId, TaskStatus status, String resultSummary) {
        TaskBoard board = getOrCreateBoard(sessionId);
        TaskItem item = board.updateTask(taskId, status, resultSummary);
        if (store != null) {
            store.saveBoard(board);
        }
        return item;
    }

    public void persistBoard(String sessionId) {
        if (store != null && sessionId != null) {
            getBoard(sessionId).ifPresent(store::saveBoard);
        }
    }

    public List<TaskItem> listTasks(String sessionId) {
        return getBoard(sessionId).map(TaskBoard::listTasks).orElse(List.of());
    }

    public Optional<TaskItem> getTask(String sessionId, String taskId) {
        return getBoard(sessionId).flatMap(b -> b.getTask(taskId));
    }

    public List<TaskItem> getReadyTasks(String sessionId) {
        return getBoard(sessionId).map(TaskBoard::getReadyTasks).orElse(List.of());
    }

    public List<TaskItem> getRunningTasks(String sessionId) {
        return getBoard(sessionId).map(TaskBoard::getRunningTasks).orElse(List.of());
    }

    public int runningCount(String sessionId) {
        return getBoard(sessionId).map(TaskBoard::runningCount).orElse(0);
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            boards.remove(sessionId);
            if (store != null) {
                store.deleteBoard(sessionId);
            }
        }
    }
}
