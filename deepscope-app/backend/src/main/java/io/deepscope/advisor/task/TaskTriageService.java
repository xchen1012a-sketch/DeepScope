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

import java.util.regex.Pattern;

/**
 * 任务复杂度自主研判服务：评估任务的品类跨度、并发可行性与工作量，进行三级分流。
 */
public class TaskTriageService {

    private static final Pattern MULTI_MODULE_PARALLEL_PATTERN =
            Pattern.compile("(?i)(同时|分别|并行|分头|多模块|全栈|前后端|前端和后端|数据库.*前端|架构.*实现|跨领域|多方面|协同拆解|多任务)");

    private static final Pattern HEAVY_SINGLE_INVESTIGATION_PATTERN =
            Pattern.compile("(?i)(深度排查|长文本分析|完整审计|全面扫描|逐行审查|深度重构)");

    /**
     * 对用户输入进行自适应研判与分流决策。
     */
    public TaskTriageResult triage(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return new TaskTriageResult(
                    TaskTriageResult.Decision.DIRECT_ANSWER, false, false, "输入为空或常规问候，单兵极速直答。");
        }

        String text = userPrompt.strip();

        // 1. 简短对话、日常问答、直接咨询 -> 路径 A（单兵直答）
        if (text.length() < 30 && !MULTI_MODULE_PARALLEL_PATTERN.matcher(text).find()) {
            return new TaskTriageResult(
                    TaskTriageResult.Decision.DIRECT_ANSWER,
                    false,
                    false,
                    "单品类轻量任务，主 Agent 直接回答，无额外治理开销。");
        }

        // 2. 检测是否具备多品类与并发特征
        boolean isMultiParallel = MULTI_MODULE_PARALLEL_PATTERN.matcher(text).find();
        if (isMultiParallel) {
            return new TaskTriageResult(
                    TaskTriageResult.Decision.MULTI_AGENT_BOARD,
                    true,
                    true,
                    "检测到任务涉及跨模块多品类且具备独立并行条件，正式启动中心看板与多智能体协同。");
        }

        // 3. 检测是否属于单品类重度探索任务
        boolean isHeavySingle =
                HEAVY_SINGLE_INVESTIGATION_PATTERN.matcher(text).find() || text.length() > 200;
        if (isHeavySingle) {
            return new TaskTriageResult(
                    TaskTriageResult.Decision.LINEAR_SUBAGENT,
                    false,
                    false,
                    "单品类但属于深度探索任务，采用单任务线性委托，防止中间过程污染主上下文。");
        }

        // 4. 默认轻量处理
        return new TaskTriageResult(
                TaskTriageResult.Decision.DIRECT_ANSWER, false, false, "常规需求，由主 Agent 直接统筹完成。");
    }
}
