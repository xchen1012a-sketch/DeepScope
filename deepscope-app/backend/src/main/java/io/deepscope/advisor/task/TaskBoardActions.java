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

import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.tool.Tool;
import io.deepscope.core.tool.ToolParam;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 主 Agent 操作中心任务看板与调度闸门规则的标准工具集（对标 Claude Code 工业级 Task Tool 规范）。
 */
@Component
public class TaskBoardActions {

    private final TaskBoardService boardService;
    private final TaskSchedulerService schedulerService;

    @Autowired
    public TaskBoardActions(TaskBoardService boardService, TaskSchedulerService schedulerService) {
        this.boardService = boardService;
        this.schedulerService = schedulerService;
    }

    public TaskBoardActions(TaskBoardService boardService) {
        this(boardService, new TaskSchedulerService(boardService, boardService.getProperties()));
    }

    @Tool(
            name = "task_create",
            description = "在中心任务看板上创建结构化子任务实体。支持指定前置依赖项（blocked_by），受单轮最大任务配额闸门保护，超额将拦截报错并要求归并。")
    public String taskCreate(
            @ToolParam(name = "task_id", description = "任务唯一标识符，如 task-1、task-2", required = true)
                    String taskId,
            @ToolParam(name = "title", description = "简明的任务目标标题（建议 6~20 个中文字）", required = true)
                    String title,
            @ToolParam(name = "description", description = "任务的具体执行要求与预期验收标准", required = true)
                    String description,
            @ToolParam(
                            name = "assignee_role",
                            description = "指派的子智能体角色，例如 code-analyst、test-engineer",
                            required = false)
                    String assigneeRole,
            @ToolParam(
                            name = "blocked_by",
                            description = "当前任务所依赖的前置任务 ID 列表，前置任务未全部成功前，当前任务不可运行",
                            required = false)
                    List<String> blockedBy,
            RuntimeContext runtimeContext) {
        String sessionId = resolveSessionId(runtimeContext);
        try {
            TaskItem item =
                    boardService.createTask(
                            sessionId, taskId, title, description, assigneeRole, blockedBy);
            return "Task created successfully: ["
                    + item.taskId()
                    + "] '"
                    + item.title()
                    + "', status: "
                    + item.status()
                    + (item.blockedBy().isEmpty() ? "" : ", waiting for: " + item.blockedBy());
        } catch (TaskQuotaExceededException e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(
            name = "task_update",
            description = "更新中心任务看板中某个子任务的状态或提交办结结论。任务办结（SUCCEEDED）时会自动解冻依赖它的后续任务并动态拉起空闲槽位。")
    public String taskUpdate(
            @ToolParam(name = "task_id", description = "需要更新的任务 ID", required = true) String taskId,
            @ToolParam(
                            name = "status",
                            description = "任务的新状态：PENDING、RUNNING、SUCCEEDED、FAILED、CANCELLED",
                            required = true)
                    String status,
            @ToolParam(
                            name = "result_summary",
                            description = "任务办结时的精炼交接结论（包含核心发现、产出文件与遗留风险，不超过 200 字）",
                            required = false)
                    String resultSummary,
            RuntimeContext runtimeContext) {
        String sessionId = resolveSessionId(runtimeContext);
        TaskStatus newStatus;
        try {
            newStatus = TaskStatus.valueOf(status.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return "Error: Invalid task status '"
                    + status
                    + "'. Allowed: PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED";
        }

        if (newStatus.isTerminal()) {
            TaskDispatchResult result =
                    schedulerService.completeTask(sessionId, taskId, newStatus, resultSummary);
            StringBuilder sb = new StringBuilder();
            sb.append("Task updated: [")
                    .append(result.completedTask().taskId())
                    .append("] is now ")
                    .append(result.completedTask().status())
                    .append(".");
            if (!result.newlyDispatchedTasks().isEmpty()) {
                sb.append(" Automatically dispatched next ready task(s): ")
                        .append(
                                result.newlyDispatchedTasks().stream()
                                        .map(TaskItem::taskId)
                                        .toList())
                        .append(".");
            }
            if (!result.newlyCancelledTasks().isEmpty()) {
                sb.append(" Cascaded cancellation to: ")
                        .append(
                                result.newlyCancelledTasks().stream()
                                        .map(TaskItem::taskId)
                                        .toList())
                        .append(".");
            }
            List<TaskItem> readyTasks = boardService.getReadyTasks(sessionId);
            sb.append(" Currently ready tasks on board: ")
                    .append(readyTasks.stream().map(TaskItem::taskId).toList())
                    .append(" (running: ")
                    .append(result.runningCount())
                    .append(", available slots: ")
                    .append(result.availableSlots())
                    .append(")");
            return sb.toString();
        } else {
            TaskItem updated = boardService.updateTask(sessionId, taskId, newStatus, resultSummary);
            List<TaskItem> readyTasks = boardService.getReadyTasks(sessionId);
            List<TaskItem> runningTasks = boardService.getRunningTasks(sessionId);
            return "Task updated: ["
                    + updated.taskId()
                    + "] is now "
                    + updated.status()
                    + ". Currently running: "
                    + runningTasks.stream().map(TaskItem::taskId).toList()
                    + ", ready on board: "
                    + readyTasks.stream().map(TaskItem::taskId).toList();
        }
    }

    @Tool(
            name = "task_dispatch",
            description = "根据当前并发配额槽位，从看板中自动拉起下一个或多个就绪（READY）任务进入执行中（RUNNING）状态。")
    public String taskDispatch(RuntimeContext runtimeContext) {
        String sessionId = resolveSessionId(runtimeContext);
        List<TaskItem> dispatched = schedulerService.dispatchNextReadyTasks(sessionId);
        if (dispatched.isEmpty()) {
            return "No tasks dispatched. Either no tasks are in READY state, or concurrency slots"
                    + " are full (running: "
                    + boardService.getRunningTasks(sessionId).size()
                    + ", available slots: "
                    + schedulerService.getAvailableSlots(sessionId)
                    + ").";
        }
        return "Dispatched "
                + dispatched.size()
                + " task(s) to RUNNING: "
                + dispatched.stream().map(t -> "[" + t.taskId() + "] " + t.title()).toList()
                + " (available slots remaining: "
                + schedulerService.getAvailableSlots(sessionId)
                + ")";
    }

    @Tool(name = "task_get", description = "查询中心任务看板中指定任务的详细信息、执行状态与精炼交付物。")
    public String taskGet(
            @ToolParam(name = "task_id", description = "要查询的任务 ID", required = true) String taskId,
            RuntimeContext runtimeContext) {
        String sessionId = resolveSessionId(runtimeContext);
        return boardService
                .getTask(sessionId, taskId)
                .map(
                        item ->
                                "Task: ["
                                        + item.taskId()
                                        + "]\nTitle: "
                                        + item.title()
                                        + "\nStatus: "
                                        + item.status()
                                        + "\nAssignee: "
                                        + item.assigneeRole()
                                        + "\nBlockedBy: "
                                        + item.blockedBy()
                                        + "\nDescription: "
                                        + item.description()
                                        + "\nResult: "
                                        + (item.resultSummary() == null
                                                ? "<none>"
                                                : item.resultSummary()))
                .orElse("Error: Task not found with id: " + taskId);
    }

    @Tool(name = "task_list", description = "查询当前会话的中心任务看板全貌，返回所有任务列表、完成进度及就绪情况。")
    public String taskList(RuntimeContext runtimeContext) {
        String sessionId = resolveSessionId(runtimeContext);
        List<TaskItem> tasks = boardService.listTasks(sessionId);
        if (tasks.isEmpty()) {
            return "Current Task Board is empty. No tasks created yet.";
        }

        int maxTasks = boardService.getBoard(sessionId).map(TaskBoard::getMaxTasks).orElse(8);
        int availableSlots = schedulerService.getAvailableSlots(sessionId);

        StringBuilder sb = new StringBuilder();
        int succeeded = 0;
        int running = 0;
        int ready = 0;
        int blocked = 0;
        int failed = 0;
        int cancelled = 0;

        sb.append("=== Central Task Board (Session: ").append(sessionId).append(") ===\n");
        for (TaskItem item : tasks) {
            sb.append("- [")
                    .append(item.status())
                    .append("] ")
                    .append(item.taskId())
                    .append(": ")
                    .append(item.title());
            if (!item.blockedBy().isEmpty()) {
                sb.append(" (blocked by: ").append(item.blockedBy()).append(")");
            }
            if (item.resultSummary() != null && !item.resultSummary().isBlank()) {
                sb.append(" -> ").append(item.resultSummary());
            }
            sb.append("\n");

            switch (item.status()) {
                case SUCCEEDED -> succeeded++;
                case RUNNING -> running++;
                case READY -> ready++;
                case BLOCKED, PENDING -> blocked++;
                case FAILED -> failed++;
                case CANCELLED -> cancelled++;
            }
        }
        sb.append(
                String.format(
                        "Summary: Total=%d/%d, Succeeded=%d, Running=%d, Ready=%d,"
                                + " Blocked/Pending=%d, Failed=%d, Cancelled=%d (Available"
                                + " Slots=%d)\n",
                        tasks.size(),
                        maxTasks,
                        succeeded,
                        running,
                        ready,
                        blocked,
                        failed,
                        cancelled,
                        availableSlots));
        return sb.toString();
    }

    private static String resolveSessionId(RuntimeContext rc) {
        if (rc != null && rc.getSessionId() != null && !rc.getSessionId().isBlank()) {
            return rc.getSessionId();
        }
        throw new IllegalStateException("Active session context is required for task operations");
    }
}
