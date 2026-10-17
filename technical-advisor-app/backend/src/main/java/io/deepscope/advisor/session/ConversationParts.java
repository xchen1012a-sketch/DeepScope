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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ordered console projection; does not modify authoritative native conversation records. */
final class ConversationParts {
    private final List<Map<String, Object>> parts = new ArrayList<>();

    synchronized void accept(AguiEvent event, Map<String, Map<String, Object>> tools) {
        String run = event.getRunId();
        if (event instanceof AguiEvent.TextMessageStart start)
            text("text", "", start.messageId(), run);
        else if (event instanceof AguiEvent.TextMessageContent content)
            text("text", content.delta(), content.messageId(), run);
        else if (event instanceof AguiEvent.TextMessageEnd) close("text");
        else if (event instanceof AguiEvent.ReasoningStart) text("reasoning", "", null, run);
        else if (event instanceof AguiEvent.ReasoningMessageStart start)
            text("reasoning", "", start.messageId(), run);
        else if (event instanceof AguiEvent.ReasoningMessageContent content)
            text("reasoning", content.delta(), content.messageId(), run);
        else if (event instanceof AguiEvent.ReasoningEnd
                || event instanceof AguiEvent.ReasoningMessageEnd) close("reasoning");
        else if (event instanceof AguiEvent.ToolCallStart start)
            tool(start.toolCallId(), run, tools);
        else if (event instanceof AguiEvent.ToolCallArgs args) tool(args.toolCallId(), run, tools);
        else if (event instanceof AguiEvent.ToolCallResult result)
            tool(result.toolCallId(), run, tools);
        else if (event instanceof AguiEvent.Custom custom
                && "advisor_event".equals(custom.name())
                && custom.value() instanceof Map<?, ?> value
                && value.get("kind") instanceof String kind) {
            closeTail();
            var recorded = new LinkedHashMap<String, Object>();
            recorded.put("kind", kind);
            var payload = new LinkedHashMap<String, Object>();
            value.forEach(
                    (key, field) -> {
                        if (key instanceof String name
                                && !name.equals("kind")
                                && !name.equals("seq")) payload.put(name, field);
                    });
            recorded.put("payload", payload);
            if (value.get("seq") instanceof Number seq) recorded.put("seq", seq);
            String status =
                    "running".equals(value.get("status"))
                            ? "running"
                            : "failed".equals(value.get("status")) || "error".equals(kind)
                                    ? "failed"
                                    : "done";
            for (var existing : parts) {
                if ("event".equals(existing.get("kind"))
                        && existing.get("event") instanceof Map<?, ?> before
                        && ((value.get("seq") != null && value.get("seq").equals(before.get("seq")))
                                || ("compaction".equals(kind)
                                        && kind.equals(before.get("kind"))
                                        && "running".equals(existing.get("status"))))) {
                    existing.put("event", recorded);
                    existing.put("status", status);
                    return;
                }
            }
            var part = create("event", run);
            part.put("event", recorded);
            part.put("status", status);
            parts.add(part);
        }
    }

    private void text(String kind, String delta, String message, String run) {
        var last = tail();
        boolean same =
                last != null
                        && kind.equals(last.get("kind"))
                        && "running".equals(last.get("status"))
                        && (message == null
                                || last.get("messageId") == null
                                || message.equals(last.get("messageId")));
        if (!same) {
            closeTail();
            last = create(kind, run);
            last.put("text", "");
            parts.add(last);
        }
        if (message != null) last.put("messageId", message);
        last.put("text", last.get("text").toString() + (delta == null ? "" : delta));
    }

    private void tool(String id, String run, Map<String, Map<String, Object>> tools) {
        var value = tools.get(id);
        if (value == null) return;
        for (var part : parts) {
            if ("tool".equals(part.get("kind"))
                    && part.get("tool") instanceof Map<?, ?> existing
                    && id.equals(existing.get("id"))) {
                part.put("tool", value);
                part.put("status", value.getOrDefault("status", "running"));
                return;
            }
        }
        closeTail();
        var part = create("tool", run);
        part.put("tool", value);
        part.put("status", value.getOrDefault("status", "running"));
        parts.add(part);
    }

    private Map<String, Object> create(String kind, String run) {
        var part = new LinkedHashMap<String, Object>();
        part.put("id", run + ":part:" + (parts.size() + 1));
        part.put("runId", run);
        part.put("kind", kind);
        part.put("status", "running");
        return part;
    }

    private Map<String, Object> tail() {
        return parts.isEmpty() ? null : parts.get(parts.size() - 1);
    }

    private void close(String kind) {
        var last = tail();
        if (last != null && kind.equals(last.get("kind"))) closeTail();
    }

    private void closeTail() {
        var last = tail();
        if (last != null
                && !"tool".equals(last.get("kind"))
                && !"event".equals(last.get("kind"))
                && "running".equals(last.get("status"))) last.put("status", "done");
    }

    synchronized List<Map<String, Object>> snapshot() {
        var result = new ArrayList<Map<String, Object>>();
        for (var part : parts) {
            var copy = new LinkedHashMap<>(part);
            if ("running".equals(copy.get("status"))) copy.put("status", "failed");
            if (copy.get("tool") instanceof Map<?, ?> raw) {
                var tool = new LinkedHashMap<String, Object>();
                raw.forEach((key, value) -> tool.put(key.toString(), value));
                if (!"done".equals(tool.get("status")) && !"failed".equals(tool.get("status")))
                    tool.put("status", "failed");
                copy.put("tool", tool);
            }
            result.add(copy);
        }
        return result;
    }

    /** Validate the optional additive JSON projection before cancelling server-side fallback. */
    static List<Map<String, Object>> validate(Object version, Object value) {
        if (version == null && value == null) return null;
        if (!(version instanceof Number number)
                || number.doubleValue() != 1.0
                || !(value instanceof List<?> list))
            throw new IllegalArgumentException("Invalid conversation parts version");
        Set<String> ids = new HashSet<>();
        var result = new ArrayList<Map<String, Object>>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)
                    || !(map.get("id") instanceof String id)
                    || id.isBlank()
                    || !ids.add(id)
                    || !(map.get("runId") instanceof String)
                    || !(map.get("status") instanceof String status)
                    || !Set.of("running", "done", "failed").contains(status))
                throw new IllegalArgumentException("Invalid conversation part");
            String kind = String.valueOf(map.get("kind"));
            if ((kind.equals("text") || kind.equals("reasoning"))
                    && map.get("text") instanceof String) {
                // Text remains data, never instructions.
            } else if (kind.equals("tool")
                    && map.get("tool") instanceof Map<?, ?> tool
                    && tool.get("id") instanceof String) {
                // Existing tool-call payload remains compatible.
            } else if (kind.equals("event")
                    && map.get("event") instanceof Map<?, ?> event
                    && event.get("kind") instanceof String
                    && event.get("payload") instanceof Map<?, ?>) {
                // Application process cards retain their original payload.
            } else throw new IllegalArgumentException("Invalid conversation part content");
            var copy = new LinkedHashMap<String, Object>();
            map.forEach((key, field) -> copy.put(key.toString(), field));
            result.add(copy);
        }
        return result;
    }
}
