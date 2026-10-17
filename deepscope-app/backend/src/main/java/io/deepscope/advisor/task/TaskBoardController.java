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
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务看板 REST API：为前端提供多任务协同进度流、状态查询与人工审批干预能力。
 */
@RestController
@RequestMapping("/api/advisor/sessions/{sessionId}/tasks")
public class TaskBoardController {

    private final TaskBoardService boardService;
    private final TaskSchedulerService schedulerService;

    public TaskBoardController(
            TaskBoardService boardService, TaskSchedulerService schedulerService) {
        this.boardService = boardService;
        this.schedulerService = schedulerService;
    }

    public record TaskBoardResponse(
            String sessionId,
            int maxTasks,
            long lastUpdatedAt,
            int totalCount,
            int succeededCount,
            int runningCount,
            int readyCount,
            int blockedCount,
            int failedCount,
            int cancelledCount,
            int availableSlots,
            List<TaskItem> tasks) {}

    @GetMapping
    public ResponseEntity<TaskBoardResponse> getBoard(@PathVariable String sessionId) {
        Optional<TaskBoard> boardOpt = boardService.getBoard(sessionId);
        if (boardOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TaskBoard board = boardOpt.get();
        synchronized (board) {
            TaskBoardResponse res =
                    new TaskBoardResponse(
                            board.getSessionId(),
                            board.getMaxTasks(),
                            board.getLastUpdatedAt(),
                            board.totalCount(),
                            board.succeededCount(),
                            board.runningCount(),
                            board.readyCount(),
                            board.blockedCount(),
                            board.failedCount(),
                            board.cancelledCount(),
                            schedulerService.getAvailableSlots(sessionId),
                            board.listTasks());
            return ResponseEntity.ok(res);
        }
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<TaskItem> retryTask(
            @PathVariable String sessionId, @PathVariable String taskId) {
        TaskBoard board = boardService.getOrCreateBoard(sessionId);
        TaskItem item = board.updateTask(taskId, TaskStatus.READY, "用户人工触发重试");
        schedulerService.dispatchNextReadyTasks(sessionId);
        boardService.persistBoard(sessionId);
        return ResponseEntity.ok(item);
    }

    @PostMapping("/{taskId}/skip")
    public ResponseEntity<TaskItem> skipTask(
            @PathVariable String sessionId,
            @PathVariable String taskId,
            @RequestParam(defaultValue = "用户人工跳过") String reason) {
        TaskDispatchResult res =
                schedulerService.completeTask(
                        sessionId, taskId, TaskStatus.SUCCEEDED, "【人工跳过】" + reason);
        return ResponseEntity.ok(res.completedTask());
    }
}
