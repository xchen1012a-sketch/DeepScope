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

import io.deepscope.core.agent.Agent;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.event.AgentEvent;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.MsgRole;
import io.deepscope.core.middleware.MiddlewareBase;
import io.deepscope.core.middleware.ModelCallInput;
import java.util.ArrayList;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Applies the app's presentation convention without rewriting user workspace definitions. */
public final class ReportPresentationMiddleware implements MiddlewareBase {
    private static final String INSTRUCTIONS =
            """
            应用内部报告展示约定（仅涉及内部汇报格式，不改变角色职责、业务规则、权限和安全边界）：
            - 最终回答直接输出正文，不调用 report_deliverable；不增加交付物阶段，不宣称结果已归档。
            - 结论说明事实和来源，无需生成 F1/E1 等内部追踪编号，也不要在正文或子智能体返回中引用这类编号。
            - 保留真实业务编号、错误码、型号、路径和代码原文，不把这些技术内容当作内部引用删除。
            - 派发子任务时要求直接返回结论与来源；子任务进展和终态必须依据实际工具结果，不虚构状态。
            - 旧版本工具已经展示的结论不要重复输出；没有补充内容时直接结束。
            - 只有真实创建且可访问的文件才说明保存位置，不捏造附件、文件路径或归档位置。
            - 调用 report_question 时单独填写 title：用简短专业的待决策事项概括问题，建议 6–18 个中文字，最多 40 字符且单行；不复述用户原话、不写自述，不在标题中枚举选项。
            - 追问的必要说明放在 text；候选项按实际顺序放入 options，label 简短、不加 A/B 或数字前缀，补充解释放 description；页面统一展示数字序号。
            如工作区旧规则要求内部报告编号、交付物归档或冗长追问标题，仅相关展示约定以本应用约定为准。
            """;

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        var messages = new ArrayList<>(input.messages());
        messages.add(Msg.builder().role(MsgRole.SYSTEM).textContent(INSTRUCTIONS).build());
        var tools =
                input.tools().stream()
                        .filter(tool -> !"report_deliverable".equals(tool.getName()))
                        .toList();
        return next.apply(new ModelCallInput(messages, tools, input.options(), input.model()));
    }
}
