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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side projector for the execution process drawer's paginated Timeline and Subagents tabs.
 *
 * <p>Mirrors the block and subagent grouping rules of the frontend so each page can be requested on
 * demand via {@code GET /api/advisor/sessions/{sessionId}/timeline} and {@code GET
 * /api/advisor/sessions/{sessionId}/subagents} with identical anchors and card shapes.
 */
final class SessionProcessProjector {

    private static final Set<String> HIDDEN_KINDS =
            Set.of("route", "agent_route", "usage", "decompose", "subagent_facts");

    private static final Set<String> HIDDEN_TOOL_NAMES =
            Set.of(
                    "report_route",
                    "report_skill_load",
                    "report_decompose",
                    "report_dispatch",
                    "report_self_task",
                    "report_facts",
                    "report_subagent_settled",
                    "report_deliverable",
                    "report_question",
                    "report_proposal",
                    "report_note",
                    "report_error",
                    "task_dispatch");

    private static final Set<String> OUTSIDE_KINDS =
            Set.of("proposal", "proposal_resolved", "error", "question");

    private static final Set<String> TIMELINE_KINDS =
            Set.of(
                    "user_message",
                    "thinking",
                    "advisor_message",
                    "tool_call",
                    "compaction",
                    "load_skill",
                    "dispatch",
                    "self_task",
                    "subagent_settled",
                    "advisor_note",
                    "question",
                    "proposal",
                    "proposal_resolved",
                    "error");

    private static final Set<String> SETTLED_STATUSES =
            Set.of("done", "failed", "timeout", "empty");

    private record ParsedEvent(
            int seq, int turn, String kind, Map<String, Object> payload, long createdAt) {}

    private record DraftCard(
            String partId, int seq, String kind, String status, String summary, long createdAt) {}

    private SessionProcessProjector() {}

    static Map<String, Object> paginate(List<Map<String, Object>> allItems, int page, int size) {
        int safeSize = Math.max(1, Math.min(100, size));
        int safePage = Math.max(1, page);
        int total = allItems.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / safeSize));
        int fromIndex = Math.min(total, (safePage - 1) * safeSize);
        int toIndex = Math.min(total, fromIndex + safeSize);
        List<Map<String, Object>> slice =
                fromIndex < toIndex ? allItems.subList(fromIndex, toIndex) : List.of();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", slice);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", total);
        result.put("totalPages", totalPages);
        result.put("hasMore", safePage < totalPages);
        return result;
    }

    static List<Map<String, Object>> projectTimeline(
            List<TimelineStore.TimelineEntry> rawEntries, ObjectMapper json) {
        List<ParsedEvent> events = parseEntries(rawEntries, json);
        Map<Integer, List<ParsedEvent>> byTurn = new TreeMap<>();
        for (ParsedEvent event : events) {
            byTurn.computeIfAbsent(event.turn(), k -> new ArrayList<>()).add(event);
        }

        List<Map<String, Object>> cards = new ArrayList<>();
        int counter = 0;
        for (List<ParsedEvent> turnEvents : byTurn.values()) {
            List<DraftCard> rendered = buildTurnDrafts(turnEvents);
            for (DraftCard draft : rendered) {
                counter += 1;
                if (!TIMELINE_KINDS.contains(draft.kind())) {
                    continue;
                }
                String anchor =
                        draft.partId() != null && !draft.partId().isBlank()
                                ? "flow-" + encodeUriComponent(draft.partId())
                                : "flow-" + counter;
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("anchor", anchor);
                card.put("index", counter);
                card.put("seq", draft.seq());
                card.put("kind", draft.kind());
                card.put("status", draft.status());
                card.put("summary", draft.summary());
                card.put("createdAt", draft.createdAt());
                cards.add(card);
            }
        }
        Collections.reverse(cards);
        return cards;
    }

    static List<Map<String, Object>> projectSubagents(
            List<TimelineStore.TimelineEntry> rawEntries, ObjectMapper json) {
        List<ParsedEvent> events = parseEntries(rawEntries, json);
        Map<String, List<Map<String, Object>>> stepsByTask = subagentStepsFromReplay(events);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();

        for (ParsedEvent event : events) {
            if ("dispatch".equals(event.kind())) {
                String id = text(event.payload(), "subtaskId");
                if (id.isEmpty()) continue;
                Map<String, Object> record = ensureSubagent(byId, id, stepsByTask);
                String name = text(event.payload(), "name");
                if (!name.isEmpty()) {
                    record.put("name", name);
                }
                Object specObj = event.payload().get("spec");
                if (specObj instanceof Map<?, ?> spec) {
                    String task = text(spec, "task");
                    if (!task.isEmpty()) {
                        record.put("task", task);
                    }
                }
                if (!record.containsKey("startedAt")) {
                    record.put("startedAt", event.createdAt());
                }
            } else if ("tool_call".equals(event.kind())) {
                String toolName = toolNameOf(event.payload(), event.seq());
                if ("task_dispatch".equals(toolName)
                        || "agent_spawn".equals(toolName)
                        || "invoke_subagent".equals(toolName)) {
                    extractToolSubagents(event, byId, stepsByTask, json);
                } else if ("task_update".equals(toolName)) {
                    updateTaskFromTool(event, byId, json);
                }
            } else if ("subagent_facts".equals(event.kind())) {
                String id = text(event.payload(), "subagentId");
                if (id.isEmpty()) continue;
                Map<String, Object> record = ensureSubagent(byId, id, stepsByTask);
                Object factsObj = event.payload().get("facts");
                if (factsObj instanceof List<?> incoming) {
                    @SuppressWarnings("unchecked")
                    List<Object> existing = (List<Object>) record.get("facts");
                    existing.addAll(incoming);
                    record.put("factCount", existing.size());
                }
            } else if ("subagent_settled".equals(event.kind())) {
                String id = text(event.payload(), "subagentId");
                if (id.isEmpty()) continue;
                Map<String, Object> record = ensureSubagent(byId, id, stepsByTask);
                String status = text(event.payload(), "status");
                record.put("status", SETTLED_STATUSES.contains(status) ? status : "failed");
                String summary = text(event.payload(), "summary");
                if (!summary.isEmpty()) {
                    record.put("summary", summary);
                }
                record.put("endedAt", event.createdAt());
            } else if ("advisor_message".equals(event.kind())) {
                for (Map<String, Object> record : byId.values()) {
                    if ("running".equals(record.get("status"))) {
                        record.put("status", "done");
                        if (!record.containsKey("endedAt")) {
                            record.put("endedAt", event.createdAt());
                        }
                    }
                }
            }
        }

        List<Map<String, Object>> list = new ArrayList<>(byId.values());
        Collections.reverse(list);
        return list;
    }

    private static final Pattern DISPATCHED_PATTERN =
            Pattern.compile("\\[([a-zA-Z0-9_-]+)\\]\\s*([^,\\]]+)");

    private static void extractToolSubagents(
            ParsedEvent event,
            Map<String, Map<String, Object>> byId,
            Map<String, List<Map<String, Object>>> stepsByTask,
            ObjectMapper json) {
        String toolName = toolNameOf(event.payload(), event.seq());
        Map<String, Object> args = parseArgs(event.payload().get("args"), json);
        String result = text(event.payload(), "result");

        if ("task_dispatch".equals(toolName)) {
            Matcher matcher = DISPATCHED_PATTERN.matcher(result);
            boolean matched = false;
            while (matcher.find()) {
                matched = true;
                String id = matcher.group(1).trim();
                String taskTitle = matcher.group(2).trim();
                Map<String, Object> record = ensureSubagent(byId, id, stepsByTask);
                record.put("name", taskTitle.isEmpty() ? id : taskTitle);
                record.put("task", taskTitle);
                if (!record.containsKey("startedAt")) {
                    record.put("startedAt", event.createdAt());
                }
            }
            if (!matched) {
                String id = text(args, "task_id");
                if (id.isEmpty()) id = text(args, "taskId");
                if (id.isEmpty()) id = text(event.payload(), "id");
                if (!id.isEmpty()) {
                    Map<String, Object> record = ensureSubagent(byId, id, stepsByTask);
                    String title = text(args, "title");
                    if (!title.isEmpty()) {
                        record.put("name", title);
                        record.put("task", title);
                    }
                    if (!record.containsKey("startedAt")) {
                        record.put("startedAt", event.createdAt());
                    }
                }
            }
        } else if ("agent_spawn".equals(toolName) || "invoke_subagent".equals(toolName)) {
            String taskText = text(args, "task");
            Matcher tm =
                    Pattern.compile(
                                    "(?:看板任务|任务|task)[\\s:：]*([a-zA-Z0-9_-]+)",
                                    Pattern.CASE_INSENSITIVE)
                            .matcher(taskText);
            String id = "";
            if (tm.find() && byId.containsKey(tm.group(1).trim())) {
                id = tm.group(1).trim();
            }
            if (id.isEmpty()) id = text(args, "task_id");
            if (id.isEmpty()) id = text(args, "taskId");
            if (id.isEmpty()) id = text(args, "label");
            if (id.isEmpty()) id = text(args, "id");
            if (id.isEmpty()) id = text(args, "agent_key");
            if (id.isEmpty()) id = text(args, "agent_id");
            if (id.isEmpty()) id = text(event.payload(), "id");
            if (id.isEmpty()) id = "spawn-" + event.seq();

            Map<String, Object> record = ensureSubagent(byId, id, stepsByTask);
            String name = text(args, "label");
            if (name.isEmpty()) name = text(args, "name");
            if (name.isEmpty()) name = text(args, "agent_id");
            if (!name.isEmpty()) record.put("name", name);

            String task = text(args, "task");
            if (task.isEmpty()) task = text(args, "label");
            if (!task.isEmpty()) record.put("task", task);

            if (!record.containsKey("startedAt")) {
                record.put("startedAt", event.createdAt());
            }
            if ("failed".equals(toolStatusOf(event.payload()))) {
                record.put("status", "failed");
            }
        }
    }

    private static void updateTaskFromTool(
            ParsedEvent event, Map<String, Map<String, Object>> byId, ObjectMapper json) {
        Map<String, Object> args = parseArgs(event.payload().get("args"), json);
        String id = text(args, "task_id");
        if (id.isEmpty()) id = text(args, "taskId");
        if (id.isEmpty()) return;

        Map<String, Object> record = byId.get(id);
        if (record == null) return;

        String status = text(args, "status").toUpperCase(Locale.ROOT);
        if ("SUCCEEDED".equals(status)) {
            record.put("status", "done");
            record.put("endedAt", event.createdAt());
            String summary = text(args, "result_summary");
            if (!summary.isEmpty()) {
                record.put("summary", summary);
            }
        } else if ("FAILED".equals(status)) {
            record.put("status", "failed");
            record.put("endedAt", event.createdAt());
            String summary = text(args, "result_summary");
            if (!summary.isEmpty()) {
                record.put("summary", summary);
            }
        }
    }

    private static Map<String, Object> parseArgs(Object raw, ObjectMapper json) {
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        if (raw instanceof String str
                && !str.isBlank()
                && str.startsWith("{")
                && str.endsWith("}")) {
            try {
                return json.readValue(str, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
            }
        }
        return Map.of();
    }

    private static Map<String, Object> ensureSubagent(
            Map<String, Map<String, Object>> byId,
            String id,
            Map<String, List<Map<String, Object>>> stepsByTask) {
        return byId.computeIfAbsent(
                id,
                key -> {
                    Map<String, Object> created = new LinkedHashMap<>();
                    created.put("id", key);
                    created.put("name", key);
                    created.put("task", "");
                    created.put("status", "running");
                    created.put("factCount", 0);
                    created.put("facts", new ArrayList<>());
                    created.put("summary", "");
                    created.put(
                            "steps",
                            new ArrayList<>(
                                    stepsByTask.getOrDefault(key, Collections.emptyList())));
                    return created;
                });
    }

    private static Map<String, List<Map<String, Object>>> subagentStepsFromReplay(
            List<ParsedEvent> events) {
        Set<String> dispatched = new HashSet<>();
        Map<String, List<String>> byName = new HashMap<>();
        for (ParsedEvent event : events) {
            if ("dispatch".equals(event.kind())) {
                String subtaskId = text(event.payload(), "subtaskId");
                if (subtaskId.isEmpty()) continue;
                dispatched.add(subtaskId);
                String name = text(event.payload(), "name");
                if (!name.isEmpty()) {
                    byName.computeIfAbsent(name, k -> new ArrayList<>()).add(subtaskId);
                }
            } else if ("tool_call".equals(event.kind())) {
                String toolName = toolNameOf(event.payload(), event.seq());
                if ("task_dispatch".equals(toolName)) {
                    String result = text(event.payload(), "result");
                    Matcher matcher = DISPATCHED_PATTERN.matcher(result);
                    while (matcher.find()) {
                        String id = matcher.group(1).trim();
                        String name = matcher.group(2).trim();
                        dispatched.add(id);
                        if (!name.isEmpty()) {
                            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(id);
                        }
                    }
                }
            }
        }

        Map<String, List<Map<String, Object>>> byId = new HashMap<>();
        Map<String, Integer> claimedByName = new HashMap<>();
        for (ParsedEvent event : events) {
            if (!"subagent_step".equals(event.kind())) continue;
            String subtaskId = replaySubtaskId(event, dispatched, byName, claimedByName);
            if (subtaskId == null) continue;
            Map<String, Object> step = stepFromReplay(event, subtaskId);
            if (step == null) continue;
            byId.computeIfAbsent(subtaskId, k -> new ArrayList<>()).add(step);
        }
        for (List<Map<String, Object>> steps : byId.values()) {
            steps.sort(Comparator.comparingInt(step -> intVal(step.get("order"), 0)));
        }
        return byId;
    }

    private static String replaySubtaskId(
            ParsedEvent event,
            Set<String> dispatched,
            Map<String, List<String>> byName,
            Map<String, Integer> claimedByName) {
        String explicit = text(event.payload(), "subtaskId");
        if (!explicit.isEmpty()) {
            return dispatched.contains(explicit) ? explicit : null;
        }
        String name = text(event.payload(), "name");
        if (name.isEmpty()) return null;
        List<String> candidates = byName.getOrDefault(name, List.of());
        int used = claimedByName.getOrDefault(name, 0);
        if (used >= candidates.size()) return null;
        claimedByName.put(name, used + 1);
        return candidates.get(used);
    }

    private static Map<String, Object> stepFromReplay(ParsedEvent event, String subtaskId) {
        String kind = text(event.payload(), "kind");
        int order = intVal(event.payload().get("order"), 0);
        if ("thinking".equals(kind)) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("subtaskId", subtaskId);
            step.put("order", order);
            step.put("kind", "thinking");
            step.put("text", text(event.payload(), "text"));
            return step;
        }
        if (!"tool_call".equals(kind)) return null;
        String rawStatus = text(event.payload(), "status");
        String status =
                "failed".equals(rawStatus)
                        ? "failed"
                        : "running".equals(rawStatus) ? "running" : "done";
        String toolCallId = text(event.payload(), "toolCallId");
        String name = text(event.payload(), "name");
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("id", toolCallId);
        tool.put("name", name.isEmpty() ? toolCallId : name);
        tool.put("args", text(event.payload(), "args"));
        String result = text(event.payload(), "result");
        if (!result.isEmpty()) {
            tool.put("result", result);
        }
        tool.put("status", status);
        Object startedAt = event.payload().get("startedAt");
        tool.put("startedAt", startedAt instanceof Number num ? num.longValue() : null);
        Object endedAt = event.payload().get("endedAt");
        if (endedAt instanceof Number num) {
            tool.put("endedAt", num.longValue());
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("subtaskId", subtaskId);
        step.put("order", order);
        step.put("kind", "tool_call");
        step.put("tool", tool);
        return step;
    }

    private static List<DraftCard> buildTurnDrafts(List<ParsedEvent> events) {
        events = normalizeDeliverables(events);
        List<DraftCard> partsDrafts = buildPartsTurnDrafts(events);
        if (partsDrafts != null) {
            return partsDrafts;
        }
        ParsedEvent userEvent =
                events.stream()
                        .filter(e -> "user_message".equals(e.kind()))
                        .findFirst()
                        .orElse(null);
        List<DraftCard> process = new ArrayList<>();
        List<DraftCard> outside = new ArrayList<>();
        DraftCard finalCandidate = null;

        for (ParsedEvent event : events) {
            if (HIDDEN_KINDS.contains(event.kind()) || "user_message".equals(event.kind())) {
                continue;
            }
            if (OUTSIDE_KINDS.contains(event.kind())) {
                outside.add(
                        new DraftCard(
                                null,
                                event.seq(),
                                event.kind(),
                                "error".equals(event.kind()) ? "failed" : "done",
                                summaryOf(event.payload()),
                                event.createdAt()));
                continue;
            }
            if ("tool_call".equals(event.kind())) {
                String name = toolNameOf(event.payload(), event.seq());
                if (HIDDEN_TOOL_NAMES.contains(name)) continue;
                String status = toolStatusOf(event.payload());
                process.add(
                        new DraftCard(
                                null, event.seq(), "tool_call", status, name, event.createdAt()));
                continue;
            }
            if ("dispatch".equals(event.kind())) {
                process.add(
                        new DraftCard(
                                null,
                                event.seq(),
                                "dispatch",
                                "done",
                                summaryOf(event.payload()),
                                event.createdAt()));
                continue;
            }
            if ("advisor_message".equals(event.kind())) {
                String reasoning = text(event.payload(), "reasoning");
                if (!reasoning.isEmpty()) {
                    process.add(
                            new DraftCard(
                                    null,
                                    event.seq(),
                                    "thinking",
                                    "done",
                                    thinkLine(reasoning),
                                    event.createdAt()));
                }
                String body = text(event.payload(), "text");
                if (!body.isEmpty()) {
                    if (finalCandidate != null) {
                        process.add(finalCandidate);
                    }
                    finalCandidate =
                            new DraftCard(
                                    null,
                                    event.seq(),
                                    "advisor_message",
                                    "done",
                                    body,
                                    event.createdAt());
                }
                continue;
            }
            if ("compaction".equals(event.kind())) {
                String status =
                        "running".equals(text(event.payload(), "status")) ? "running" : "done";
                Object inTok = event.payload().get("inputTokens");
                Object keptTok = event.payload().get("keptTokens");
                String summary;
                if (inTok instanceof Number && keptTok instanceof Number) {
                    summary = inTok + " → " + keptTok + " tokens";
                } else if (inTok instanceof Number) {
                    summary = inTok + " tokens";
                } else {
                    summary = summaryOf(event.payload());
                }
                process.add(
                        new DraftCard(
                                null,
                                event.seq(),
                                "compaction",
                                status,
                                summary,
                                event.createdAt()));
                continue;
            }
            process.add(
                    new DraftCard(
                            null,
                            event.seq(),
                            event.kind(),
                            "done",
                            summaryOf(event.payload()),
                            event.createdAt()));
        }

        int finalSeq = finalCandidate != null ? finalCandidate.seq() : -1;
        boolean lastToolAfterReply =
                finalCandidate != null
                        && process.stream()
                                .anyMatch(
                                        item ->
                                                "tool_call".equals(item.kind())
                                                        && item.seq() > finalSeq);
        DraftCard finalText = finalCandidate != null && !lastToolAfterReply ? finalCandidate : null;
        if (finalCandidate != null && finalText == null) {
            process.add(finalCandidate);
        }

        List<DraftCard> rendered = new ArrayList<>();
        if (userEvent != null) {
            String userText = text(userEvent.payload(), "text");
            rendered.add(
                    new DraftCard(
                            null,
                            userEvent.seq(),
                            "user_message",
                            "done",
                            userText,
                            userEvent.createdAt()));
        }
        rendered.addAll(process);
        if (finalText != null) {
            rendered.add(finalText);
        }
        rendered.addAll(outside);
        return rendered;
    }

    private static List<DraftCard> buildPartsTurnDrafts(List<ParsedEvent> events) {
        List<ParsedEvent> snapshots =
                events.stream()
                        .filter(
                                e ->
                                        "advisor_message".equals(e.kind())
                                                && intVal(e.payload().get("partsVersion"), 0) == 1
                                                && e.payload().get("parts") instanceof List<?>)
                        .toList();
        if (snapshots.isEmpty()) {
            return null;
        }
        List<DraftCard> ordered = new ArrayList<>();
        Set<Integer> represented = new HashSet<>();
        for (ParsedEvent snapshot : snapshots) {
            List<?> parts = (List<?>) snapshot.payload().get("parts");
            for (Object raw : parts) {
                if (!(raw instanceof Map<?, ?> part)) continue;
                String partId = text(part, "id");
                String partKind = text(part, "kind");
                if (partId.isEmpty() || partKind.isEmpty()) continue;
                String status = "failed".equals(text(part, "status")) ? "failed" : "done";
                if ("text".equals(partKind) || "reasoning".equals(partKind)) {
                    String partText = text(part, "text");
                    if (partText.trim().isEmpty()) continue;
                    boolean isText = "text".equals(partKind);
                    ordered.add(
                            new DraftCard(
                                    partId,
                                    snapshot.seq(),
                                    isText ? "advisor_message" : "thinking",
                                    status,
                                    isText ? partText : thinkLine(partText),
                                    snapshot.createdAt()));
                } else if ("tool".equals(partKind)
                        && part.get("tool") instanceof Map<?, ?> toolMap) {
                    String toolName = toolNameOf(toolMap, snapshot.seq());
                    if (HIDDEN_TOOL_NAMES.contains(toolName)) continue;
                    String toolStatus = toolStatusOf(toolMap);
                    if ("failed".equals(status) && "running".equals(toolStatus)) {
                        toolStatus = "failed";
                    }
                    ordered.add(
                            new DraftCard(
                                    partId,
                                    snapshot.seq(),
                                    "tool_call",
                                    toolStatus,
                                    toolName,
                                    snapshot.createdAt()));
                } else if ("event".equals(partKind)
                        && part.get("event") instanceof Map<?, ?> evMap) {
                    int evSeq = intVal(evMap.get("seq"), snapshot.seq());
                    represented.add(evSeq);
                    String evKind = text(evMap, "kind");
                    if (evKind.isEmpty()
                            || HIDDEN_KINDS.contains(evKind)
                            || "user_message".equals(evKind)) {
                        continue;
                    }
                    ParsedEvent original =
                            events.stream().filter(e -> e.seq() == evSeq).findFirst().orElse(null);
                    Map<?, ?> evPayload =
                            evMap.get("payload") instanceof Map<?, ?> m ? m : Map.of();
                    long createdAt = original != null ? original.createdAt() : snapshot.createdAt();
                    ordered.add(
                            new DraftCard(
                                    partId,
                                    evSeq,
                                    evKind,
                                    status,
                                    summaryOf(evPayload),
                                    createdAt));
                }
            }
        }
        for (ParsedEvent event : events) {
            if (OUTSIDE_KINDS.contains(event.kind()) && !represented.contains(event.seq())) {
                ordered.add(
                        new DraftCard(
                                null,
                                event.seq(),
                                event.kind(),
                                "error".equals(event.kind()) ? "failed" : "done",
                                summaryOf(event.payload()),
                                event.createdAt()));
            }
            if (Boolean.TRUE.equals(event.payload().get("legacyDeliverable"))
                    && ordered.stream()
                            .noneMatch(
                                    card -> card.summary().equals(text(event.payload(), "text")))) {
                ordered.add(
                        new DraftCard(
                                "legacy-deliverable-" + event.seq(),
                                event.seq(),
                                "advisor_message",
                                "done",
                                text(event.payload(), "text"),
                                event.createdAt()));
            }
        }
        List<DraftCard> rendered = new ArrayList<>();
        ParsedEvent userEvent =
                events.stream()
                        .filter(e -> "user_message".equals(e.kind()))
                        .findFirst()
                        .orElse(null);
        if (userEvent != null) {
            rendered.add(
                    new DraftCard(
                            null,
                            userEvent.seq(),
                            "user_message",
                            "done",
                            text(userEvent.payload(), "text"),
                            userEvent.createdAt()));
        }
        rendered.addAll(ordered);
        return rendered;
    }

    private static List<ParsedEvent> normalizeDeliverables(List<ParsedEvent> events) {
        List<String> replies = new ArrayList<>();
        for (ParsedEvent event : events) {
            if (!"advisor_message".equals(event.kind())) continue;
            if (event.payload().get("parts") instanceof List<?> parts) {
                for (Object raw : parts) {
                    if (raw instanceof Map<?, ?> part && "text".equals(part.get("kind")))
                        replies.add(text(part, "text"));
                }
            } else replies.add(text(event.payload(), "text"));
        }
        Map<String, String> convertedById = new HashMap<>();
        for (ParsedEvent event : events) {
            if ("deliverable".equals(event.kind()))
                conclusion("seq:" + event.seq(), event.payload(), replies, convertedById);
        }
        List<ParsedEvent> normalized = new ArrayList<>();
        for (ParsedEvent event : events) {
            if ("deliverable".equals(event.kind())) {
                String body =
                        conclusion("seq:" + event.seq(), event.payload(), replies, convertedById);
                if (!body.isEmpty())
                    normalized.add(
                            new ParsedEvent(
                                    event.seq(),
                                    event.turn(),
                                    "advisor_message",
                                    Map.of("text", body, "legacyDeliverable", true),
                                    event.createdAt()));
                continue;
            }
            if (!"advisor_message".equals(event.kind())
                    || !(event.payload().get("parts") instanceof List<?> parts)) {
                normalized.add(event);
                continue;
            }
            List<Object> converted = new ArrayList<>();
            for (Object raw : parts) {
                if (raw instanceof Map<?, ?> part
                        && "event".equals(part.get("kind"))
                        && part.get("event") instanceof Map<?, ?> embedded
                        && "deliverable".equals(embedded.get("kind"))) {
                    String body =
                            conclusion(
                                    embedded.get("seq") instanceof Number seq
                                            ? "seq:" + seq.intValue()
                                            : "part:" + text(part, "id"),
                                    embedded.get("payload") instanceof Map<?, ?> payload
                                            ? payload
                                            : Map.of(),
                                    replies,
                                    convertedById);
                    if (!body.isEmpty()) {
                        Map<Object, Object> copy = new LinkedHashMap<>(part);
                        copy.put("kind", "text");
                        copy.put("text", body);
                        converted.add(copy);
                    }
                } else converted.add(raw);
            }
            Map<String, Object> payload = new LinkedHashMap<>(event.payload());
            payload.put("parts", converted);
            normalized.add(
                    new ParsedEvent(
                            event.seq(), event.turn(), event.kind(), payload, event.createdAt()));
        }
        return normalized;
    }

    private static String conclusion(
            String id, Map<?, ?> payload, List<String> replies, Map<String, String> convertedById) {
        if (convertedById.containsKey(id)) return convertedById.get(id);
        String body = LegacyDeliverableText.render(payload, replies);
        if (!body.isEmpty()) replies.add(body);
        convertedById.put(id, body);
        return body;
    }

    private static List<ParsedEvent> parseEntries(
            List<TimelineStore.TimelineEntry> rawEntries, ObjectMapper json) {
        List<ParsedEvent> out = new ArrayList<>(rawEntries.size());
        for (TimelineStore.TimelineEntry entry : rawEntries) {
            Map<String, Object> payload = Map.of();
            if (entry.payload() != null && !entry.payload().isBlank()) {
                try {
                    payload =
                            json.readValue(
                                    entry.payload(), new TypeReference<Map<String, Object>>() {});
                } catch (JsonProcessingException ignored) {
                    payload = Map.of();
                }
            }
            out.add(
                    new ParsedEvent(
                            entry.seq(), entry.turn(), entry.kind(), payload, entry.createdAt()));
        }
        out.sort(Comparator.comparingInt(ParsedEvent::seq));
        return out;
    }

    private static String summaryOf(Map<?, ?> payload) {
        for (String key : List.of("text", "title", "summary", "skillName", "name", "reason")) {
            String value = text(payload, key);
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String toolNameOf(Map<?, ?> payload, int fallbackSeq) {
        String name = text(payload, "name");
        if (!name.isEmpty()) return name;
        String id = text(payload, "id");
        return !id.isEmpty() ? id : String.valueOf(fallbackSeq);
    }

    private static String toolStatusOf(Map<?, ?> payload) {
        String status = text(payload, "status");
        if ("failed".equals(status)) return "failed";
        if ("done".equals(status)) return "done";
        return "running";
    }

    private static String thinkLine(String raw) {
        if (raw == null || raw.isBlank()) return "";
        for (String line : raw.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }

    private static String text(Map<?, ?> map, String key) {
        if (map == null) return "";
        Object val = map.get(key);
        return val instanceof String str ? str : "";
    }

    private static int intVal(Object val, int fallback) {
        return val instanceof Number num ? num.intValue() : fallback;
    }

    private static String encodeUriComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
    }
}
