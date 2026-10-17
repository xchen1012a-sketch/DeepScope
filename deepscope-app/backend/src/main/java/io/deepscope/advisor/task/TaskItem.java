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

/**
 * 中心任务看板中的单个具象化任务实体。
 */
public record TaskItem(
        String taskId,
        String title,
        String description,
        String assigneeRole,
        List<String> blockedBy,
        TaskStatus status,
        String resultSummary,
        long createdAt,
        long updatedAt,
        long completedAt) {

    public TaskItem {
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
    }

    public TaskItem withStatus(TaskStatus newStatus, long now) {
        return withStatusAndResult(newStatus, this.resultSummary, now);
    }

    public TaskItem withStatusAndResult(TaskStatus newStatus, String newResultSummary, long now) {
        long completed = newStatus.isTerminal() ? now : this.completedAt;
        return new TaskItem(
                taskId,
                title,
                description,
                assigneeRole,
                blockedBy,
                newStatus,
                newResultSummary != null && !newResultSummary.isBlank()
                        ? newResultSummary.strip()
                        : this.resultSummary,
                createdAt,
                now,
                completed);
    }
}
