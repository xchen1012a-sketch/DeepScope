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

import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.model.Model;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Application-layer AG-UI event enrichment for the conversation page.
 *
 * <p>The AG-UI extension is deliberately left untouched. Two facts the page needs are therefore
 * carried on top of the protocol events here, both optional so older readers keep working:
 *
 * <ul>
 *   <li>the tool result state ({@code success} / {@code error} / {@code denied} / {@code
 *       interrupted}) — the built-in converter drops {@link ToolResultEndEvent#getState()}, so the
 *       page cannot tell a failed tool from a finished one. It rides in {@code rawEvent}, the
 *       field AG-UI already reserves for the source event's own data.
 *   <li>the current model's context window and name on the {@code token_usage} custom event,
 *       because the protocol usage record has neither field. Both come from the application's
 *       own model bean, so no extension contract changes.
 * </ul>
 *
 * <p>Registered as a bean: the AG-UI Spring Boot starter collects every {@link AguiEventEnricher}
 * bean into the adapter configuration.
 */
public final class AdvisorAguiEventEnricher implements AguiEventEnricher {

    /** AG-UI custom event name carrying one model call's token usage. */
    static final String TOKEN_USAGE_EVENT = "token_usage";

    /** AG-UI custom event name emitted by ConversationCompactor. */
    static final String COMPACTION_EVENT = "compaction";

    /** Field added to {@code token_usage.value} with the current model's context window. */
    static final String CONTEXT_WINDOW_FIELD = "contextWindow";

    /** Field added to {@code token_usage.value} with the model actually in use. */
    static final String MODEL_NAME_FIELD = "modelName";

    /** Field added to {@code TOOL_CALL_RESULT.rawEvent} with the tool result state. */
    static final String TOOL_STATE_FIELD = "state";

    private final Model model;
    private final TimelineStore timeline;
    private final SessionStore sessions;

    public AdvisorAguiEventEnricher(Model model) {
        this(model, null, null);
    }

    public AdvisorAguiEventEnricher(Model model, TimelineStore timeline, SessionStore sessions) {
        this.model = model;
        this.timeline = timeline;
        this.sessions = sessions;
    }

    @Override
    public List<AguiEvent> enrich(
            AgentEvent source, List<AguiEvent> events, AguiStreamContext context) {
        List<AguiEvent> enriched = new ArrayList<>(events.size());
        for (AguiEvent event : events) {
            enriched.add(enrichOne(source, event, context));
        }
        return List.copyOf(enriched);
    }

    private AguiEvent enrichOne(AgentEvent source, AguiEvent event, AguiStreamContext context) {
        if (source instanceof ToolResultEndEvent end
                && event instanceof AguiEvent.ToolCallResult result) {
            return withToolState(result, end);
        }
        if (event instanceof AguiEvent.Custom custom && TOKEN_USAGE_EVENT.equals(custom.name())) {
            return withContextWindow(custom, source);
        }
        if (event instanceof AguiEvent.Custom custom && COMPACTION_EVENT.equals(custom.name())) {
            return withCompactionAdvisorEvent(custom, context);
        }
        return event;
    }

    private AguiEvent withCompactionAdvisorEvent(
            AguiEvent.Custom custom, AguiStreamContext context) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (custom.value() instanceof Map<?, ?> value) {
            for (Map.Entry<?, ?> entry : value.entrySet()) {
                if (entry.getKey() != null) {
                    payload.put(entry.getKey().toString(), entry.getValue());
                }
            }
        }
        Map<String, Object> next = new java.util.LinkedHashMap<>(payload);
        next.put("kind", TimelineStore.COMPACTION);
        String sessionId =
                custom.threadId() != null
                        ? custom.threadId()
                        : (context != null ? context.getThreadId() : null);
        if ("done".equals(payload.get("status"))
                && timeline != null
                && sessions != null
                && sessionId != null) {
            try {
                if (sessions.find(sessionId).isPresent()) {
                    TimelineStore.AppendedEvent appended =
                            timeline.append(sessionId, TimelineStore.COMPACTION, payload);
                    next.put("seq", appended.seq());
                    next.put("turn", appended.turn());
                }
            } catch (java.sql.SQLException ignored) {
                // Best-effort persistence for compaction timeline entry
            }
        }
        return new AguiEvent.Custom(
                custom.threadId(),
                custom.runId(),
                ReportActions.EVENT_NAME,
                next,
                custom.timestamp(),
                custom.rawEvent());
    }

    private static AguiEvent withToolState(
            AguiEvent.ToolCallResult result, ToolResultEndEvent end) {
        if (end.getState() == null) {
            return result;
        }
        Map<String, Object> raw = Map.of(TOOL_STATE_FIELD, end.getState().getValue());
        return new AguiEvent.ToolCallResult(
                result.threadId(),
                result.runId(),
                result.toolCallId(),
                result.content(),
                result.role(),
                result.messageId(),
                result.timestamp(),
                raw);
    }

    private AguiEvent withContextWindow(AguiEvent.Custom custom, AgentEvent source) {
        if (!(custom.value() instanceof Map<?, ?> value)) {
            return custom;
        }
        Map<String, Object> next = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (entry.getKey() != null) {
                next.put(entry.getKey().toString(), entry.getValue());
            }
        }
        // Each field is only filled in when missing: whatever the converter already
        // reported wins, so this stays a pure gap filler.
        var snapshot = source == null ? null : source.getMetadata();
        Object callWindow =
                snapshot == null
                        ? null
                        : snapshot.get(
                                io.agentscope.advisor.model.SelectedModelMiddleware
                                        .CONTEXT_WINDOW_METADATA);
        Object callName =
                snapshot == null
                        ? null
                        : snapshot.get(
                                io.agentscope.advisor.model.SelectedModelMiddleware
                                        .MODEL_NAME_METADATA);
        String modelName = callName instanceof String name ? name : modelName();
        if (modelName != null && !(next.get(MODEL_NAME_FIELD) instanceof String)) {
            next.put(MODEL_NAME_FIELD, modelName);
        }
        int window = callWindow instanceof Number number ? number.intValue() : contextWindow();
        if (window > 0 && !(next.get(CONTEXT_WINDOW_FIELD) instanceof Number)) {
            next.put(CONTEXT_WINDOW_FIELD, window);
        }
        if (next.size() == value.size()) {
            return custom;
        }
        return new AguiEvent.Custom(
                custom.threadId(),
                custom.runId(),
                custom.name(),
                next,
                custom.timestamp(),
                custom.rawEvent());
    }

    private int contextWindow() {
        if (model == null) {
            return 0;
        }
        try {
            return model.getContextWindowSize();
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }

    private String modelName() {
        if (model == null) {
            return null;
        }
        try {
            String name = model.getModelName();
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
