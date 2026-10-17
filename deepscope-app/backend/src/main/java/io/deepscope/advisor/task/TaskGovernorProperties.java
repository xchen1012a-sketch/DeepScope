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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 任务派发与并发治理配置项（对标工业级 Agentic 调度规范）。
 */
@ConfigurationProperties(prefix = "deepscope.advisor.task-governor")
public record TaskGovernorProperties(
        /** 单轮任务看板允许创建的最大子任务数（熔断上限），防止无限递归拆解 */
        @DefaultValue("8") int maxTasksPerBoard,
        /** 最大同时执行的活跃子任务/子智能体并发槽位数，避免 API 429 与系统超载 */
        @DefaultValue("3") int maxConcurrentTasks,
        /** 单任务超时熔断时间（秒），默认 300 秒 */
        @DefaultValue("300") long taskTimeoutSeconds,
        /** 持久化模式：FILE（工作区本地文件）或 DB（关系型数据库） */
        @DefaultValue("FILE") String persistenceMode) {

    @ConstructorBinding
    public TaskGovernorProperties {}

    public TaskGovernorProperties(
            int maxTasksPerBoard, int maxConcurrentTasks, long taskTimeoutSeconds) {
        this(maxTasksPerBoard, maxConcurrentTasks, taskTimeoutSeconds, "FILE");
    }

    public static TaskGovernorProperties defaults() {
        return new TaskGovernorProperties(8, 3, 300, "FILE");
    }
}
