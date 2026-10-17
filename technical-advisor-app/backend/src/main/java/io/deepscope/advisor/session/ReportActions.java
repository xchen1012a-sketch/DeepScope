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

import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.event.AgentEventEmitter;
import io.deepscope.core.event.CustomEvent;
import io.deepscope.core.message.ToolResultBlock;
import io.deepscope.core.tool.Tool;
import io.deepscope.core.tool.ToolParam;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The report-action protocol (TDS 3.1): the main agent calls these tools to report each step of
 * its work. Every call first appends the structured event to the PostgreSQL timeline, then emits
 * the same event to the visitor over the AG-UI stream as a CUSTOM event named
 * {@link #EVENT_NAME}. The legacy deliverable entry point preserves its answer as an ordinary
 * message instead of introducing an archive stage.
 */
@Component
public class ReportActions {

    /** AG-UI CUSTOM event name shared with the frontend renderer. */
    public static final String EVENT_NAME = "advisor_event";

    private static final Logger log = LoggerFactory.getLogger(ReportActions.class);

    private final TimelineStore timeline;
    private final SessionStore sessions;

    public ReportActions(TimelineStore timeline, SessionStore sessions) {
        this.timeline = timeline;
        this.sessions = sessions;
    }

    @Tool(
            name = "report_route",
            description =
                    "路由汇报：进入技术调查或技能执行时，向访客宣布本轮走哪条路（可与首个检索/读取工具在同一轮并行调用）。日常寒暄、简短追问或无需查资料即可直接回答的简单问题无需调用，直接回复即可。mode=specialist"
                        + " 时必须给 skill_name。")
    public Mono<ToolResultBlock> reportRoute(
            @ToolParam(
                            name = "mode",
                            description = "specialist=命中专精领域；general=通用模式",
                            required = true)
                    String mode,
            @ToolParam(name = "skill_name", description = "命中的技能（领域）名；通用模式留空", required = false)
                    String skillName,
            @ToolParam(name = "reason", description = "为什么走这条路由，访客可见", required = true)
                    String reason,
            @ToolParam(name = "matched_on", description = "命中的技能描述片段，便于核对；可留空", required = false)
                    String matchedOn,
            RuntimeContext runtimeContext) {
        Map<String, Object> payload =
                payload(
                        "mode",
                        mode,
                        "skillName",
                        skillName,
                        "reason",
                        reason,
                        "matchedOn",
                        matchedOn);
        return record(TimelineStore.ROUTE, payload, "已向访客宣布路由。", runtimeContext);
    }

    @Tool(name = "report_skill_load", description = "技能加载汇报：路由命中专精领域并读取技能文件后调用。")
    public Mono<ToolResultBlock> reportSkillLoad(
            @ToolParam(name = "skill_name", description = "加载的技能名", required = true)
                    String skillName,
            @ToolParam(
                            name = "path",
                            description = "读取的技能文件路径，如 skills/repo-diligence/SKILL.md",
                            required = true)
                    String path,
            @ToolParam(name = "resource_count", description = "该技能附带的参考资料与脚本数量", required = true)
                    int resourceCount,
            @ToolParam(name = "note", description = "一两句说明：加载了什么、将按什么执行", required = true)
                    String note,
            RuntimeContext runtimeContext) {
        Map<String, Object> payload =
                payload(
                        "skillName",
                        skillName,
                        "path",
                        path,
                        "resourceCount",
                        resourceCount,
                        "note",
                        note);
        return record(TimelineStore.LOAD_SKILL, payload, "已向访客展示技能加载。", runtimeContext);
    }

    @Tool(
            name = "report_decompose",
            description = "拆解汇报：宣布把任务拆成哪些子任务、谁自办谁派发、为什么。任务无需拆解时" + "给 trivial_reason 并留空 subtasks。")
    public Mono<ToolResultBlock> reportDecompose(
            @ToolParam(name = "reason", description = "为什么这么拆，访客可见", required = true) String reason,
            @ToolParam(
                            name = "subtasks",
                            description =
                                    "子任务数组，每项含字段：id、title（目标）、question（要查清的关键问题）、needs（所需信息数组）、expected（预期产出）、dependsOn（依赖的子任务"
                                        + " id 数组）、execution（self 或"
                                        + " dispatch）、executionReason（为什么自办/派发）",
                            required = false)
                    List<Map<String, Object>> subtasks,
            @ToolParam(name = "trivial_reason", description = "任务过于简单无需拆解时的显式说明", required = false)
                    String trivialReason,
            RuntimeContext runtimeContext) {
        Map<String, Object> payload =
                payload(
                        "reason",
                        reason,
                        "subtasks",
                        orEmptyList(subtasks),
                        "trivialReason",
                        trivialReason);
        return record(TimelineStore.DECOMPOSE, payload, "已向访客展示拆解。", runtimeContext);
    }

    @Tool(
            name = "report_dispatch",
            description = "派发登记：每次派子智能体调查时调用。四要素各不相同：目标、关注点与边界、" + "上下文、交付格式。")
    public Mono<ToolResultBlock> reportDispatch(
            @ToolParam(name = "subtask_id", description = "对应的子任务 id", required = true)
                    String subtaskId,
            @ToolParam(name = "name", description = "该子智能体的显示名，如“事实调查员 · 依赖风险”", required = true)
                    String name,
            @ToolParam(name = "task", description = "四要素之一：要查清什么问题，具体到可直接开工", required = true)
                    String task,
            @ToolParam(name = "focus", description = "四要素之二：关注点与边界，明确不查什么", required = true)
                    String focus,
            @ToolParam(name = "context", description = "四要素之三：只给该任务必要的背景信息", required = true)
                    String context,
            @ToolParam(
                            name = "deliverable_format",
                            description = "四要素之四：返回结论、事实和来源；无需生成内部引用编号",
                            required = true)
                    String deliverableFormat,
            RuntimeContext runtimeContext) {
        Map<String, Object> spec =
                payload(
                        "task",
                        task,
                        "focus",
                        focus,
                        "context",
                        context,
                        "deliverableFormat",
                        deliverableFormat);
        Map<String, Object> payload = payload("subtaskId", subtaskId, "name", name, "spec", spec);
        return record(TimelineStore.DISPATCH, payload, "已登记派发。", runtimeContext);
    }

    @Tool(name = "report_self_task", description = "自办登记：主智能体亲自调查的条目，附查到的事实。")
    public Mono<ToolResultBlock> reportSelfTask(
            @ToolParam(name = "subtask_id", description = "对应的子任务 id", required = true)
                    String subtaskId,
            @ToolParam(name = "title", description = "自办条目的目标", required = true) String title,
            @ToolParam(name = "note", description = "一两句说明查了什么", required = true) String note,
            @ToolParam(
                            name = "facts",
                            description =
                                    "查到的事实数组，每项含 fact（事实）、source（来源）、"
                                            + "conclusion（一句话结论）；没有来源的条目不许出现；id 可省略，由系统关联",
                            required = false)
                    List<Map<String, Object>> facts,
            RuntimeContext runtimeContext) {
        Map<String, Object> payload =
                payload(
                        "subtaskId",
                        subtaskId,
                        "title",
                        title,
                        "note",
                        note,
                        "facts",
                        identifiedFacts(facts));
        return record(TimelineStore.SELF_TASK, payload, "已登记自办调查。", runtimeContext);
    }

    @Tool(
            name = "report_facts",
            description = "事实回流：子智能体返回结果后调用（或自办补充事实）。每条包含事实、来源、结论；没有来源不许出现，无需生成内部引用编号。")
    public Mono<ToolResultBlock> reportFacts(
            @ToolParam(
                            name = "subagent_id",
                            description = "产出这些事实的子智能体（派发时登记的 subtask_id）",
                            required = true)
                    String subagentId,
            @ToolParam(
                            name = "facts",
                            description = "事实数组，每项含 fact、source、conclusion；id 可省略，由系统关联",
                            required = true)
                    List<Map<String, Object>> facts,
            RuntimeContext runtimeContext) {
        Map<String, Object> payload =
                payload("subagentId", subagentId, "facts", identifiedFacts(facts));
        return record(TimelineStore.SUBAGENT_FACTS, payload, "调查结果已更新。", runtimeContext);
    }

    @Tool(name = "report_subagent_settled", description = "子智能体落定：每个派发出去的调查结束时调用，报告最终状态。")
    public Mono<ToolResultBlock> reportSubagentSettled(
            @ToolParam(name = "subagent_id", description = "对应的子任务 id", required = true)
                    String subagentId,
            @ToolParam(
                            name = "status",
                            description = "done/failed/timeout/empty 之一",
                            required = true)
                    String status,
            @ToolParam(name = "summary", description = "总体结论；失败/超时/空时写明缺失的部分", required = true)
                    String summary,
            RuntimeContext runtimeContext) {
        Map<String, Object> payload =
                payload("subagentId", subagentId, "status", status, "summary", summary);
        return record(TimelineStore.SUBAGENT_SETTLED, payload, "子智能体已落定。", runtimeContext);
    }

    @Tool(name = "report_deliverable", description = "旧版本兼容入口，新的回答直接输出正文，不需要调用本工具。")
    @Deprecated
    public Mono<ToolResultBlock> reportDeliverable(
            @ToolParam(name = "title", description = "交付物标题", required = true) String title,
            @ToolParam(name = "domain_name", description = "所属领域名；通用模式填“通用模式”", required = true)
                    String domainName,
            @ToolParam(name = "mode", description = "specialist 或 general", required = true)
                    String mode,
            @ToolParam(
                            name = "sections",
                            description =
                                    "分节数组，每项含 key、title，body（可选，markdown 正文）或"
                                            + " items（条目数组：text、severity 可选"
                                            + " blocker/major/minor、citations 依据编号数组、speculative 可选"
                                            + " true、conflict 可选、recommendation 可选）",
                            required = true)
                    List<Map<String, Object>> sections,
            @ToolParam(
                            name = "evidence",
                            description = "依据清单数组：id、fact、source，与正文 citations 一一对应",
                            required = true)
                    List<Map<String, Object>> evidence,
            @ToolParam(name = "suggested_follow_ups", description = "建议的追问（可选数组）", required = false)
                    List<String> suggestedFollowUps,
            RuntimeContext runtimeContext) {
        Map<String, Object> body =
                payload(
                        "title",
                        title,
                        "domainName",
                        domainName,
                        "mode",
                        mode,
                        "sections",
                        orEmptyList(sections),
                        "evidence",
                        orEmptyList(evidence),
                        "suggestedFollowUps",
                        orEmptyList(suggestedFollowUps));
        String text = LegacyDeliverableText.render(body, List.of());
        if (text.isBlank()) {
            return Mono.just(ToolResultBlock.text("请直接输出最终正文。"));
        }
        return record(
                TimelineStore.ADVISOR_MESSAGE, payload("text", text), "结果已展示。", runtimeContext);
    }

    public Mono<ToolResultBlock> reportQuestion(
            String text, List<String> suggestions, RuntimeContext runtimeContext) {
        return reportQuestion(text, suggestions, null, null, runtimeContext);
    }

    public Mono<ToolResultBlock> reportQuestion(
            String text,
            List<String> suggestions,
            List<Map<String, Object>> options,
            String answerMode,
            RuntimeContext runtimeContext) {
        return reportQuestion(text, suggestions, options, answerMode, null, runtimeContext);
    }

    @Tool(
            name = "report_question",
            description =
                    "追问：仅在需要访客补充信息时调用，一次只问一个决策事项。title 简短专业，不写背景、自述或选项列表；options"
                            + " 简短且有区分度，不添加编号。能直接回答时不追问；发问后正常结束本回合，不另加已发送问题的说明。")
    public Mono<ToolResultBlock> reportQuestion(
            @ToolParam(name = "text", description = "问题及必要说明，不重复枚举选项，不写冗长自述", required = true)
                    String text,
            @ToolParam(name = "suggestions", description = "可选的回复建议数组", required = false)
                    List<String> suggestions,
            @ToolParam(
                            name = "options",
                            description = "2–6 个选项，每项含 label 及可选 description；优先于 suggestions",
                            required = false)
                    List<Map<String, Object>> options,
            @ToolParam(
                            name = "answer_mode",
                            description = "single/multiple/text；缺省按选项推断",
                            required = false)
                    String answerMode,
            @ToolParam(
                            name = "title",
                            description = "动态概括当前待确认事项的单行短标题，建议 6–18 个中文字，最多 40 字符；不引用原话、不加编号或列出选项",
                            required = false)
                    String title,
            RuntimeContext runtimeContext) {
        return Mono.defer(
                () -> {
                    try {
                        return record(
                                TimelineStore.QUESTION,
                                QuestionService.question(
                                        text, suggestions, options, answerMode, title),
                                "已向访客提问，等待回复。",
                                runtimeContext);
                    } catch (IllegalArgumentException error) {
                        return Mono.just(
                                ToolResultBlock.error(
                                        "追问参数错误：title 必须单行且不超过 40 字符；请检查问题、选项及回答模式后重新提交。"));
                    }
                });
    }

    @Tool(
            name = "report_proposal",
            description = "提案新子智能体：识别到能力缺口、现有子智能体无法覆盖时调用，" + "向访客提交提案并等待确认；未确认前不得启用。")
    public Mono<ToolResultBlock> reportProposal(
            @ToolParam(name = "name", description = "提案的子智能体名称", required = true) String name,
            @ToolParam(name = "description", description = "一句话说明它能做什么", required = true)
                    String description,
            @ToolParam(name = "goal", description = "提案原因与目标：现有能力为什么不够、它要查清什么", required = true)
                    String goal,
            @ToolParam(
                            name = "content",
                            description = "子智能体定义内容，格式与工作区现有子智能体说明文件一致",
                            required = true)
                    String content,
            RuntimeContext runtimeContext) {
        String sessionId = sessionId(runtimeContext);
        try {
            var session = sessionId == null ? null : sessions.find(sessionId).orElse(null);
            if (session == null || session.agentId() == null) {
                return Mono.just(ToolResultBlock.error("自由模式不允许提案；请先创建锁定主智能体的会话。"));
            }
        } catch (SQLException error) {
            return Mono.just(ToolResultBlock.error("提案会话检查失败。"));
        }
        Map<String, Object> payload =
                payload("name", name, "description", description, "goal", goal, "content", content);
        return record(TimelineStore.PROPOSAL, payload, "已向访客提交子智能体提案，等待确认。", runtimeContext);
    }

    @Tool(name = "report_note", description = "过程说明：向访客说明接下来一两句要做什么、为什么。")
    public Mono<ToolResultBlock> reportNote(
            @ToolParam(name = "text", description = "说明内容，一两句话", required = true) String text,
            RuntimeContext runtimeContext) {
        return record(TimelineStore.ADVISOR_NOTE, payload("text", text), "已说明。", runtimeContext);
    }

    @Tool(name = "report_error", description = "过程出错：某环节失败且影响交付时调用，向访客说明失败原因。")
    public Mono<ToolResultBlock> reportError(
            @ToolParam(name = "text", description = "失败说明：什么失败、影响哪部分交付", required = true)
                    String text,
            RuntimeContext runtimeContext) {
        return record(TimelineStore.ERROR, payload("text", text), "已向访客说明失败。", runtimeContext);
    }

    private Mono<ToolResultBlock> record(
            String kind, Map<String, Object> payload, String ack, RuntimeContext runtimeContext) {
        String sessionId = sessionId(runtimeContext);
        return Mono.deferContextual(
                context -> {
                    Map<String, Object> value = new LinkedHashMap<>(payload);
                    value.put("kind", kind);
                    boolean knownSession = sessionId != null && sessionExists(sessionId);
                    if (TimelineStore.QUESTION.equals(kind) && !knownSession) {
                        return Mono.just(ToolResultBlock.error("追问落库失败：会话不存在。"));
                    }
                    if (knownSession) {
                        try {
                            TimelineStore.AppendedEvent appended =
                                    TimelineStore.QUESTION.equals(kind)
                                            ? timeline.appendQuestion(sessionId, payload)
                                            : timeline.append(sessionId, kind, payload);
                            value = new LinkedHashMap<>(appended.payload());
                            value.put("kind", kind);
                            value.put("seq", appended.seq());
                            value.put("turn", appended.turn());
                        } catch (QuestionService.QuestionException error) {
                            return Mono.just(ToolResultBlock.error("已有问题待回答，请等待访客回复。"));
                        } catch (SQLException error) {
                            log.warn(
                                    "Failed to persist {} event for session {}",
                                    kind,
                                    sessionId,
                                    error);
                            return Mono.just(ToolResultBlock.error("汇报落库失败：" + error.getMessage()));
                        }
                    }
                    Map<String, Object> eventValue = value;
                    AgentEventEmitter.fromContext(context)
                            .ifPresent(
                                    emitter ->
                                            emitter.emit(new CustomEvent(EVENT_NAME, eventValue)));
                    return Mono.just(ToolResultBlock.text(ack));
                });
    }

    private boolean sessionExists(String sessionId) {
        try {
            return sessions.find(sessionId).isPresent();
        } catch (SQLException error) {
            log.warn("Failed to look up session {}", sessionId, error);
            return false;
        }
    }

    private static String sessionId(RuntimeContext runtimeContext) {
        return runtimeContext == null ? null : runtimeContext.getSessionId();
    }

    private static Map<String, Object> payload(Object... pairs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                payload.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return payload;
    }

    private static List<Object> orEmptyList(List<?> list) {
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    private static List<Map<String, Object>> identifiedFacts(List<Map<String, Object>> facts) {
        if (facts == null) {
            return List.of();
        }
        return facts.stream()
                .map(
                        fact -> {
                            Map<String, Object> value = new LinkedHashMap<>(fact);
                            if (!(value.get("id") instanceof String id) || id.isBlank()) {
                                value.put("id", "fact-" + UUID.randomUUID());
                            }
                            return value;
                        })
                .toList();
    }
}
