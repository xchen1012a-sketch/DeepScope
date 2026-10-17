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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskTriageTest {

    private TaskTriageService triageService;

    @BeforeEach
    void setUp() {
        triageService = new TaskTriageService();
    }

    @Test
    void casualConversationsRouteToDirectAnswer() {
        TaskTriageResult r1 = triageService.triage("你好！");
        assertEquals(TaskTriageResult.Decision.DIRECT_ANSWER, r1.decision());
        assertFalse(r1.multiDomain());
        assertFalse(r1.parallelizable());

        TaskTriageResult r2 = triageService.triage("请问系统当前版本号是多少？");
        assertEquals(TaskTriageResult.Decision.DIRECT_ANSWER, r2.decision());
    }

    @Test
    void multiModuleCrossDomainParallelRequestsRouteToMultiAgentBoard() {
        TaskTriageResult r1 = triageService.triage("请帮我同时设计前端界面交互和后端鉴权接口，并分别实现核心逻辑");
        assertEquals(TaskTriageResult.Decision.MULTI_AGENT_BOARD, r1.decision());
        assertTrue(r1.multiDomain());
        assertTrue(r1.parallelizable());
        assertTrue(r1.reason().contains("中心看板"));

        TaskTriageResult r2 = triageService.triage("把这个需求拆解成多个任务，分头并行排查数据库与前端展示问题");
        assertEquals(TaskTriageResult.Decision.MULTI_AGENT_BOARD, r2.decision());
        assertTrue(r2.parallelizable());
    }

    @Test
    void heavySingleDomainExplorationRoutesToLinearSubagent() {
        TaskTriageResult r1 = triageService.triage("对整个项目的安全权限配置进行全面扫描与深度排查，找出所有潜在越权隐患");
        assertEquals(TaskTriageResult.Decision.LINEAR_SUBAGENT, r1.decision());
        assertFalse(r1.multiDomain());
        assertFalse(r1.parallelizable());
        assertTrue(r1.reason().contains("单任务线性委托"));
    }
}
