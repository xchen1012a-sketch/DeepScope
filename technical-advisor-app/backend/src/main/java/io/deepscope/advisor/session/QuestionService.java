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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Question validation and immutable answer snapshots; transaction boundaries live in TimelineStore. */
public final class QuestionService {

    /** Stable API error without exposing inaccessible session or question data. */
    public static final class QuestionException extends IllegalArgumentException {
        private final int status;
        private final String code;

        QuestionException(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    private QuestionService() {}

    static Map<String, Object> question(
            String text,
            List<String> suggestions,
            List<Map<String, Object>> options,
            String answerMode) {
        return question(text, suggestions, options, answerMode, null);
    }

    static Map<String, Object> question(
            String text,
            List<String> suggestions,
            List<Map<String, Object>> options,
            String answerMode,
            String title) {
        String prompt = required(text, 1000);
        String heading;
        if (title == null || title.isBlank()) {
            String first = prompt.split("(?<=[。！？?!])|\\R", 2)[0].replaceAll("\\s+", " ").strip();
            heading =
                    first.codePointCount(0, first.length()) > 40
                            ? first.substring(0, first.offsetByCodePoints(0, 39)) + "…"
                            : first;
        } else {
            heading = required(title, 80);
            if (heading.codePointCount(0, heading.length()) > 40 || heading.matches("(?s).*\\R.*"))
                throw invalid();
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        if (options != null) {
            if (options.size() < 2 || options.size() > 6) throw invalid();
            for (Map<String, Object> option : options) {
                if (option == null) throw invalid();
                String label = required(option.get("label"), 80);
                if (!labels.add(label)) throw invalid();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", "opt-" + (normalized.size() + 1));
                item.put("label", label);
                if (option.get("description") != null) {
                    String description = optional(option.get("description"), 160);
                    if (!description.isBlank()) item.put("description", description);
                }
                normalized.add(item);
            }
        } else if (suggestions != null) {
            if (suggestions.size() > 6) throw invalid();
            for (String suggestion : suggestions) {
                String label = required(suggestion, 80);
                if (!labels.add(label)) throw invalid();
                normalized.add(Map.of("id", "opt-" + (normalized.size() + 1), "label", label));
            }
        }
        String mode = answerMode == null ? (normalized.isEmpty() ? "text" : "single") : answerMode;
        if (!Set.of("single", "multiple", "text").contains(mode)
                || ("text".equals(mode) && !normalized.isEmpty())
                || (!"text".equals(mode) && normalized.isEmpty())) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 2);
        result.put("questionId", UUID.randomUUID().toString());
        result.put("text", prompt);
        result.put("title", heading);
        result.put("answerMode", mode);
        result.put("allowCustom", true);
        result.put("options", normalized);
        result.put("suggestions", normalized.stream().map(item -> item.get("label")).toList());
        return result;
    }

    /** Read-only compatibility projection; historical rows are never rewritten. */
    static Map<String, Object> project(Map<String, Object> payload, int seq, int turn) {
        Map<String, Object> result = new LinkedHashMap<>(payload);
        if (!(payload.get("questionId") instanceof String id) || id.isBlank()) {
            List<Map<String, Object>> options = new ArrayList<>();
            if (payload.get("suggestions") instanceof List<?> suggestions) {
                for (Object value : suggestions) {
                    if (value instanceof String label && !label.isBlank())
                        options.add(Map.of("id", "opt-" + (options.size() + 1), "label", label));
                }
            }
            result.put("questionId", "legacy-" + seq);
            result.put("answerMode", options.isEmpty() ? "text" : "single");
            result.put("options", options);
            result.put("allowCustom", true);
        }
        result.put("seq", seq);
        result.put("turn", turn);
        return result;
    }

    static boolean sameQuestion(Map<String, Object> first, Map<String, Object> second) {
        return first.get("text").equals(second.get("text"))
                && first.get("answerMode").equals(second.get("answerMode"))
                && first.get("options").equals(second.get("options"));
    }

    static String questionId(Object reply) {
        return required(replyMap(reply).get("questionId"), 100);
    }

    static Map<String, Object> answer(Map<String, Object> question, Object raw, String text) {
        Map<?, ?> reply = raw == null ? Map.of() : replyMap(raw);
        String questionId = String.valueOf(question.get("questionId"));
        if (raw != null && !questionId.equals(questionId(raw))) throw invalid();
        String requestId =
                raw == null ? UUID.randomUUID().toString() : required(reply.get("requestId"), 100);
        String custom = optional(raw == null ? text : reply.get("customText"), 4000);
        String attachmentContext = optional(reply.get("attachmentContext"), 240000);
        Object selected = reply.get("selectedOptionIds");
        if (selected != null && !(selected instanceof List<?>)) throw invalid();
        List<?> supplied = selected instanceof List<?> list ? list : List.of();
        Set<String> ids = new HashSet<>();
        for (Object id : supplied) {
            if (!(id instanceof String value) || !ids.add(value)) throw invalid();
        }
        List<Map<?, ?>> options = options(question);
        List<String> selectedIds = new ArrayList<>();
        List<String> selectedLabels = new ArrayList<>();
        for (Map<?, ?> option : options) {
            if (ids.remove(String.valueOf(option.get("id")))) {
                selectedIds.add(String.valueOf(option.get("id")));
                selectedLabels.add(String.valueOf(option.get("label")));
            }
        }
        String mode = String.valueOf(question.get("answerMode"));
        if (!ids.isEmpty()
                || (selectedIds.isEmpty() && custom.isEmpty())
                || ("text".equals(mode) && !selectedIds.isEmpty())
                || ("single".equals(mode)
                        && (selectedIds.size() > 1
                                || (!selectedIds.isEmpty() && !custom.isEmpty())))) throw invalid();
        String summary = String.join("、", selectedLabels);
        if (!custom.isEmpty()) summary += (summary.isEmpty() ? "" : "\n") + custom;
        String context =
                "对上一条追问的回答\n问题："
                        + question.get("text")
                        + (selectedLabels.isEmpty()
                                ? ""
                                : "\n选择：" + String.join("、", selectedLabels))
                        + (custom.isEmpty() ? "" : "\n补充：" + custom);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("questionId", questionId);
        snapshot.put("requestId", requestId);
        snapshot.put("questionSeq", question.get("seq"));
        snapshot.put("questionText", question.get("text"));
        snapshot.put("answerMode", mode);
        snapshot.put("options", options);
        snapshot.put("selectedOptionIds", selectedIds);
        snapshot.put("customText", custom);
        snapshot.put("contextText", context);
        snapshot.put("attachmentContext", attachmentContext);
        return Map.of("text", summary, "questionReply", snapshot);
    }

    static boolean sameAnswer(Map<String, Object> before, Map<String, Object> after) {
        Map<?, ?> first = replyMap(before.get("questionReply"));
        Map<?, ?> second = replyMap(after.get("questionReply"));
        return first.get("selectedOptionIds").equals(second.get("selectedOptionIds"))
                && first.get("customText").equals(second.get("customText"))
                && java.util.Objects.toString(first.get("attachmentContext"), "")
                        .equals(second.get("attachmentContext"));
    }

    private static List<Map<?, ?>> options(Map<String, Object> question) {
        List<Map<?, ?>> result = new ArrayList<>();
        if (question.get("options") instanceof List<?> list)
            for (Object item : list) if (item instanceof Map<?, ?> map) result.add(map);
        return result;
    }

    private static Map<?, ?> replyMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid();
        return map;
    }

    private static String required(Object value, int max) {
        String result = optional(value, max);
        if (result.isEmpty()) throw invalid();
        return result;
    }

    private static String optional(Object value, int max) {
        if (value == null) return "";
        if (!(value instanceof String text) || text.strip().length() > max) throw invalid();
        return text.strip();
    }

    static QuestionException invalid() {
        return new QuestionException(400, "question_reply_invalid");
    }

    static QuestionException notFound() {
        return new QuestionException(404, "question_not_found");
    }

    static QuestionException conflict(String code) {
        return new QuestionException(409, code);
    }
}
