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
package io.deepscope.advisor.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.message.Msg;
import io.deepscope.core.message.MsgRole;
import io.deepscope.core.middleware.ModelCallInput;
import io.deepscope.core.tool.Toolkit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ReportPresentationMiddlewareTest {
    @Test
    void newModelRequestsOmitTheLegacyToolWhileRecoveryCanStillResolveIt() {
        var toolkit = new Toolkit();
        toolkit.registerTool(new ReportActions(null, null));
        var questionTool =
                toolkit.getToolSchemas().stream()
                        .filter(tool -> tool.getName().equals("report_question"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                ((java.util.Map<?, ?>) questionTool.getParameters().get("properties"))
                        .containsKey("title"));
        var user = Msg.builder().role(MsgRole.USER).textContent("检查 E102 错误码").build();
        var original = new ModelCallInput(List.of(user), toolkit.getToolSchemas(), null, null);
        var captured = new AtomicReference<ModelCallInput>();
        new ReportPresentationMiddleware()
                .onModelCall(
                        null,
                        null,
                        original,
                        input -> {
                            captured.set(input);
                            return Flux.empty();
                        })
                .blockLast();

        assertNotNull(toolkit.getTool("report_deliverable"));
        assertTrue(
                original.tools().stream()
                        .anyMatch(tool -> tool.getName().equals("report_deliverable")));
        assertFalse(
                captured.get().tools().stream()
                        .anyMatch(tool -> tool.getName().equals("report_deliverable")));
        assertEquals(1, captured.get().tools().size());
        assertEquals("report_question", captured.get().tools().get(0).getName());
        assertSame(user, captured.get().messages().get(0));
        assertEquals("检查 E102 错误码", user.getTextContent());
        assertEquals(1, original.messages().size());
    }

    @Test
    void requestAddsOnlyInternalPresentationRulesAndKeepsBusinessInstructions() {
        var business = Msg.builder().role(MsgRole.SYSTEM).textContent("只处理测试任务；禁止写入文件。").build();
        var captured = new AtomicReference<ModelCallInput>();
        new ReportPresentationMiddleware()
                .onModelCall(
                        null,
                        null,
                        new ModelCallInput(List.of(business), List.of(), null, null),
                        input -> {
                            captured.set(input);
                            return Flux.empty();
                        })
                .blockLast();

        assertSame(business, captured.get().messages().get(0));
        String guidance = captured.get().messages().get(1).getTextContent();
        assertEquals(MsgRole.SYSTEM, captured.get().messages().get(1).getRole());
        assertTrue(guidance.contains("最终回答直接输出正文"));
        assertTrue(guidance.contains("F1/E1"));
        assertTrue(guidance.contains("业务编号、错误码、型号、路径和代码原文"));
        assertTrue(guidance.contains("不改变角色职责、业务规则、权限和安全边界"));
        assertTrue(guidance.contains("不要重复输出"));
        assertTrue(guidance.contains("单独填写 title"));
        assertTrue(guidance.contains("不在标题中枚举选项"));
    }

    @Test
    void pureGreetingOrSummaryIntentGatesAllTools() {
        var toolkit = new Toolkit();
        toolkit.registerTool(new ReportActions(null, null));

        // 1. 问候语门控：纯闲聊问候彻底关掉工具箱
        var greeting = Msg.builder().role(MsgRole.USER).textContent("你好！").build();
        var capturedGreeting = new AtomicReference<ModelCallInput>();
        new ReportPresentationMiddleware()
                .onModelCall(
                        null,
                        null,
                        new ModelCallInput(List.of(greeting), toolkit.getToolSchemas(), null, null),
                        input -> {
                            capturedGreeting.set(input);
                            return Flux.empty();
                        })
                .blockLast();
        assertTrue(capturedGreeting.get().tools().isEmpty(), "日常问候语必须彻底锁死工具箱，下发空工具列表");

        // 2. 就近总结门控：就近总结意图彻底关掉工具箱
        var summary = Msg.builder().role(MsgRole.USER).textContent("简单总结一下").build();
        var capturedSummary = new AtomicReference<ModelCallInput>();
        new ReportPresentationMiddleware()
                .onModelCall(
                        null,
                        null,
                        new ModelCallInput(List.of(summary), toolkit.getToolSchemas(), null, null),
                        input -> {
                            capturedSummary.set(input);
                            return Flux.empty();
                        })
                .blockLast();
        assertTrue(capturedSummary.get().tools().isEmpty(), "就近总结意图必须彻底锁死工具箱，下发空工具列表");
    }

    @Test
    void journey1_casualChatAndCommonKnowledge_gatesAllTools() {
        var toolkit = new Toolkit();
        toolkit.registerTool(new ReportActions(null, null));
        var middleware = new ReportPresentationMiddleware();

        List<String> casualInputs = List.of("你好", "您好！", "hi", "好的", "明白", "继续", "谢谢！");
        for (String text : casualInputs) {
            var user = Msg.builder().role(MsgRole.USER).textContent(text).build();
            var captured = new AtomicReference<ModelCallInput>();
            middleware
                    .onModelCall(
                            null,
                            null,
                            new ModelCallInput(List.of(user), toolkit.getToolSchemas(), null, null),
                            input -> {
                                captured.set(input);
                                return Flux.empty();
                            })
                    .blockLast();
            assertTrue(
                    captured.get().tools().isEmpty(),
                    "旅程一【日常闲聊与常识】：输入 '" + text + "' 必须彻底锁死工具箱，下发空工具列表");
        }
    }

    @Test
    void journey2_nearbyTargetedSummary_declaresScopeAndGatesTools() {
        var toolkit = new Toolkit();
        toolkit.registerTool(new ReportActions(null, null));
        var middleware = new ReportPresentationMiddleware();

        List<String> summaryInputs = List.of("简单总结一下", "总结一下", "归纳一下", "概括一下");
        for (String text : summaryInputs) {
            var user = Msg.builder().role(MsgRole.USER).textContent(text).build();
            var captured = new AtomicReference<ModelCallInput>();
            middleware
                    .onModelCall(
                            null,
                            null,
                            new ModelCallInput(List.of(user), toolkit.getToolSchemas(), null, null),
                            input -> {
                                captured.set(input);
                                return Flux.empty();
                            })
                    .blockLast();
            assertTrue(
                    captured.get().tools().isEmpty(),
                    "旅程二【就近总结】：输入 '" + text + "' 必须关闭所有工具调用，防止模型胡乱检索");
        }
    }

    @Test
    void journey3_interactiveDecisionCard_onlyReportQuestionExposed() {
        var toolkit = new Toolkit();
        toolkit.registerTool(new ReportActions(null, null));
        var middleware = new ReportPresentationMiddleware();

        var user = Msg.builder().role(MsgRole.USER).textContent("我想重构系统，但不知道如何选择合适的技术栈").build();
        var captured = new AtomicReference<ModelCallInput>();
        middleware
                .onModelCall(
                        null,
                        null,
                        new ModelCallInput(List.of(user), toolkit.getToolSchemas(), null, null),
                        input -> {
                            captured.set(input);
                            return Flux.empty();
                        })
                .blockLast();

        var toolNames =
                captured.get().tools().stream()
                        .map(io.deepscope.core.model.ToolSchema::getName)
                        .toList();
        assertEquals(List.of("report_question"), toolNames, "旅程三【人机提问卡片】：只有提问卡片暴露，所有过程汇报彻底剔除");
    }
}
