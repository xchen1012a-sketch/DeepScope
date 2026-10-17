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

import io.deepscope.core.agui.event.AguiEvent;
import io.deepscope.core.event.AgentEvent;
import io.deepscope.core.event.AgentResultEvent;
import io.deepscope.core.event.ModelCallStartEvent;
import io.deepscope.core.event.TextBlockDeltaEvent;
import io.deepscope.core.message.GenerateReason;
import io.deepscope.core.message.TextBlock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-observed timing and confirmed display identity; never predicts a model's next step. */
final class ConversationPresentation {
    static final String EVENT_NAME = "advisor_presentation";
    private final String runId;
    private Long startedAt;
    private Long endedAt;
    private String callId;
    private final Map<String, StringBuilder> texts = new LinkedHashMap<>();
    private final Map<String, Long> firstContent = new LinkedHashMap<>();
    private List<String> finalIds = List.of();
    private String outcome = "unknown";
    private String finalOutcome;

    ConversationPresentation(String runId) {
        this.runId = runId;
    }

    void accept(AgentEvent source, List<AguiEvent> events, long now) {
        if (source instanceof ModelCallStartEvent start) {
            callId = start.getReplyId();
            texts.clear();
            firstContent.clear();
            finalIds = List.of();
        }
        for (var event : events) {
            if (event instanceof AguiEvent.RunStarted && startedAt == null) startedAt = now;
            if (source instanceof TextBlockDeltaEvent delta
                    && callId != null
                    && callId.equals(delta.getReplyId())
                    && event instanceof AguiEvent.TextMessageContent content
                    && content.delta() != null
                    && !content.delta().isEmpty()) {
                texts.computeIfAbsent(content.messageId(), ignored -> new StringBuilder())
                        .append(content.delta());
                firstContent.putIfAbsent(content.messageId(), now);
            }
        }
        if (source instanceof AgentResultEvent resultEvent && resultEvent.getResult() != null) {
            var result = resultEvent.getResult();
            var reason = result.getGenerateReason();
            finalOutcome =
                    reason == GenerateReason.MODEL_STOP
                            ? "completed"
                            : reason == GenerateReason.MAX_ITERATIONS
                                            && (result.getMetadata() == null
                                                    || !Boolean.TRUE.equals(
                                                            result.getMetadata()
                                                                    .get(
                                                                            io.deepscope.core
                                                                                    .message
                                                                                    .MessageMetadataKeys
                                                                                    .SUMMARY_FAILED)))
                                    ? "limited"
                                    : null;
            if (reason == GenerateReason.INTERRUPTED) outcome = "cancelled";
            else if (reason == GenerateReason.PERMISSION_ASKING
                    || reason == GenerateReason.TOOL_SUSPENDED) outcome = "awaiting_action";
            String visible =
                    result.getContentBlocks(TextBlock.class).stream()
                            .map(TextBlock::getText)
                            .filter(java.util.Objects::nonNull)
                            .reduce("", String::concat);
            String streamed =
                    texts.values().stream().map(StringBuilder::toString).reduce("", String::concat);
            finalIds =
                    finalOutcome != null && !visible.isBlank() && visible.equals(streamed)
                            ? texts.entrySet().stream()
                                    .filter(entry -> !entry.getValue().toString().isBlank())
                                    .map(Map.Entry::getKey)
                                    .toList()
                            : List.of();
        }
        for (var event : events) {
            if (event instanceof AguiEvent.RunError) {
                endedAt = now;
                outcome = "failed";
                finalIds = List.of();
            } else if (event instanceof AguiEvent.RunFinished finish) {
                endedAt = now;
                if (finish.outcome() != null) outcome = "awaiting_action";
                else if (finalOutcome != null) outcome = finalOutcome;
                if (!hasFinalOutcome(outcome)) finalIds = List.of();
            }
        }
    }

    Map<String, Object> snapshot() {
        if (startedAt == null) return null;
        var result = new LinkedHashMap<String, Object>();
        result.put("runId", runId);
        result.put("runStartedAt", startedAt);
        result.put("outcome", outcome);
        if ("limited".equals(outcome)) result.put("terminationReason", "MAX_ITERATIONS");
        result.put("finalMessageIds", hasFinalOutcome(outcome) ? finalIds : List.of());
        if (endedAt != null) result.put("runEndedAt", endedAt);
        if (hasFinalOutcome(outcome) && !finalIds.isEmpty()) {
            long first = finalIds.stream().mapToLong(firstContent::get).min().orElseThrow();
            if (first >= startedAt && (endedAt == null || first <= endedAt))
                result.put("finalStartedAt", first);
        }
        return result;
    }

    static Map<String, Object> validate(
            Object version, Object value, List<Map<String, Object>> parts) {
        if (version == null && value == null) return null;
        if (!(version instanceof Number v)
                || v.doubleValue() != 1
                || !(value instanceof Map<?, ?> raw)
                || !(raw.get("runId") instanceof String run)
                || run.isBlank()
                || !(raw.get("outcome") instanceof String outcome)
                || !java.util.Set.of(
                                "completed",
                                "limited",
                                "failed",
                                "cancelled",
                                "awaiting_action",
                                "unknown")
                        .contains(outcome)
                || !(raw.get("finalMessageIds") instanceof List<?> ids))
            throw new IllegalArgumentException("Invalid presentation");
        if ("limited".equals(outcome) && !"MAX_ITERATIONS".equals(raw.get("terminationReason")))
            throw new IllegalArgumentException("Missing limit reason");
        Long start = raw.get("runStartedAt") == null ? null : timestamp(raw.get("runStartedAt"));
        Long end = raw.get("runEndedAt") == null ? null : timestamp(raw.get("runEndedAt"));
        Long first =
                raw.get("finalStartedAt") == null ? null : timestamp(raw.get("finalStartedAt"));
        if ((end != null && start != null && end < start)
                || (first != null && (start == null || first < start || end == null || first > end))
                || (!hasFinalOutcome(outcome) && (!ids.isEmpty() || first != null))
                || (hasFinalOutcome(outcome) && (start == null || end == null))
                || (!ids.isEmpty() && first == null && !"limited".equals(outcome))
                || (ids.isEmpty() && first != null))
            throw new IllegalArgumentException("Invalid presentation timing");
        var unique = new java.util.HashSet<String>();
        for (Object id : ids) {
            if (!(id instanceof String message)
                    || message.isBlank()
                    || !unique.add(message)
                    || parts == null
                    || parts.stream()
                            .noneMatch(
                                    part ->
                                            "text".equals(part.get("kind"))
                                                    && run.equals(part.get("runId"))
                                                    && message.equals(part.get("messageId"))
                                                    && part.get("text") instanceof String text
                                                    && !text.isBlank()))
                throw new IllegalArgumentException("Invalid final message identity");
        }
        if (parts != null && parts.stream().anyMatch(part -> !run.equals(part.get("runId"))))
            throw new IllegalArgumentException("Mismatched run identity");
        var result = new LinkedHashMap<String, Object>();
        result.put("runId", run);
        result.put("outcome", outcome);
        if (start != null) result.put("runStartedAt", start);
        result.put("finalMessageIds", new java.util.ArrayList<>(ids));
        if ("limited".equals(outcome)) result.put("terminationReason", "MAX_ITERATIONS");
        if (end != null) result.put("runEndedAt", end);
        if (first != null) result.put("finalStartedAt", first);
        return result;
    }

    private static boolean hasFinalOutcome(String outcome) {
        return "completed".equals(outcome) || "limited".equals(outcome);
    }

    private static long timestamp(Object value) {
        if (!(value instanceof Number n)
                || !Double.isFinite(n.doubleValue())
                || n.doubleValue() < 0
                || n.doubleValue() > 9007199254740991L
                || n.doubleValue() != n.longValue())
            throw new IllegalArgumentException("Invalid timestamp");
        return n.longValue();
    }
}
