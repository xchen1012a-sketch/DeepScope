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

/**
 * 当单轮任务看板创建的任务数量超出熔断阈值时抛出，强制主 Agent 归并子任务。
 */
public class TaskQuotaExceededException extends IllegalStateException {

    private final int maxLimit;

    public TaskQuotaExceededException(int maxLimit) {
        super(
                "Task board quota exceeded: maximum allowed tasks is "
                        + maxLimit
                        + ". Please consolidate, group, or summarize subtasks to prevent unbounded"
                        + " recursion.");
        this.maxLimit = maxLimit;
    }

    public int getMaxLimit() {
        return maxLimit;
    }
}
