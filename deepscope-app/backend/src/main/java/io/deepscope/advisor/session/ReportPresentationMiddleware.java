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
    private static final java.util.regex.Pattern DIRECT_TEXT_PATTERN =
            java.util.regex.Pattern.compile(
                    "(?i)^\\s*(你好|您好|hello|hi|嗨|早上好|下午好|晚上好|在吗|在么|好的|收到|明白|知道了|ok|okay|继续|接着说|简单总结一下|总结一下|归纳一下|概括一下|做个总结|总结下|概括下|归纳下|谢谢|感谢|多谢|thx|thanks|thank"
                        + " you)[!！。，,.\\s]*$");

    private static final String INSTRUCTIONS =
            """
            应用内部报告展示约定（仅涉及内部汇报格式，不改变角色职责、业务规则、权限和安全边界）：
            - 最终回答直接输出正文，不调用 report_deliverable，不调用过程汇报工具；不增加交付物阶段，不宣称结果已归档。
            - 不得向访客汇报内部思考、路由或准备过程，所有分析、步骤与结论直接以最终正文输出。
            - 结论说明事实和来源，无需生成 F1/E1 等内部追踪编号，也不要在正文或子智能体返回中引用这类编号。
            - 保留真实业务编号、错误码、型号、路径和代码原文，不把这些技术内容当作内部引用删除。
            - 派发子任务时要求直接返回结论与来源；子任务进展和终态必须依据实际工具结果，不虚构状态。
            - 旧版本工具已经展示的结论不要重复输出；没有补充内容时直接结束。
            - 只有真实创建且可访问的文件才说明保存位置，不捏造附件、文件路径或归档位置。
            - 调用 report_question 时单独填写 title：用简短专业的待决策事项概括问题，建议 6–18 个中文字，最多 40 字符且单行；不复述用户原话、不写自述，不在标题中枚举选项。
            - 追问的必要说明放在 text；候选项按实际顺序放入 options，label 简短、不加 A/B 或数字前缀，补充解释放 description；页面统一展示数字序号。
            - 涉及单选/多选决策：若属于方案二选一或互斥抉择，answer_mode 填 "single"；若涉及多模块排查、多特征勾选或允许同时选择多项，answer_mode 必须指定为 "multiple"；如需纯文本回复填 "text"。
            如工作区旧规则要求内部报告编号、交付物归档或冗长追问标题，仅相关展示约定以本应用约定为准。
            """;

    static boolean isDirectTextTurn(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return DIRECT_TEXT_PATTERN.matcher(text.strip()).matches();
    }

    private static String latestUserText(java.util.List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                return msg.getTextContent();
            }
        }
        return null;
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        var messages = new ArrayList<>(input.messages());
        messages.add(Msg.builder().role(MsgRole.SYSTEM).textContent(INSTRUCTIONS).build());
        boolean directText = isDirectTextTurn(latestUserText(messages));
        var tools =
                directText
                        ? java.util.List.<io.deepscope.core.model.ToolSchema>of()
                        : input.tools().stream()
                                .filter(
                                        tool ->
                                                !tool.getName().startsWith("report_")
                                                        || "report_question".equals(tool.getName()))
                                .toList();
        return next.apply(new ModelCallInput(messages, tools, input.options(), input.model()));
    }
}
