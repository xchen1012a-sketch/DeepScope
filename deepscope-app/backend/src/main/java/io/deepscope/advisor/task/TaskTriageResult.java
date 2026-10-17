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
 * 任务复杂度自主研判与分类分流结论。
 */
public record TaskTriageResult(
        Decision decision, boolean multiDomain, boolean parallelizable, String reason) {

    public enum Decision {
        DIRECT_ANSWER, // 路径 A：单兵直答，无需任何拆解
        LINEAR_SUBAGENT, // 路径 B：单任务线性委托，独立深度探索
        MULTI_AGENT_BOARD // 路径 C：多品类且可独立并行，正式启动中心看板与并发调度
    }
}
