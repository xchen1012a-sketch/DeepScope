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
 * 任务完成与动态并发调度拉起的结果快照。
 *
 * @param completedTask 刚刚完成终态跃迁的任务
 * @param newlyDispatchedTasks 本次由于槽位释放或前置依赖达成而自动拉起为 RUNNING 的任务列表
 * @param newlyCancelledTasks 本次由于前置依赖失败而自动级联取消的任务列表
 * @param runningCount 当前看板中正在运行的任务数
 * @param availableSlots 当前剩余可用并发槽位数
 * @param isAllSucceeded 看板所有任务是否均已成功办结
 * @param hasUnfinishedTasks 看板是否还有未完成（运行中、就绪或阻塞）的任务
 */
public record TaskDispatchResult(
        TaskItem completedTask,
        List<TaskItem> newlyDispatchedTasks,
        List<TaskItem> newlyCancelledTasks,
        int runningCount,
        int availableSlots,
        boolean isAllSucceeded,
        boolean hasUnfinishedTasks) {}
