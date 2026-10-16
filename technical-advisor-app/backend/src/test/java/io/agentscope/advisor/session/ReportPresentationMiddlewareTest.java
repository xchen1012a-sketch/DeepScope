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
package io.agentscope.advisor.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.tool.Toolkit;
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
        assertEquals(original.tools().size() - 1, captured.get().tools().size());
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
}
