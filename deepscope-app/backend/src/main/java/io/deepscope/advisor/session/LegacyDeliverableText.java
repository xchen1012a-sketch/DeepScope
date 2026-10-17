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
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only compatibility for old reports; archive metadata alone is not an answer. */
final class LegacyDeliverableText {
    private LegacyDeliverableText() {}

    static String render(Map<?, ?> payload, List<String> replies) {
        Set<String> existing = new HashSet<>();
        for (String reply : replies) {
            existing.add(normalize(reply));
            for (String paragraph :
                    reply.split("\\r?\\n\\s*\\r?\\n|\\r?\\n(?=\\s*(?:[-*+]|\\d+[.)])\\s)"))
                existing.add(normalize(paragraph));
        }
        List<String> sections = new ArrayList<>();
        for (Object raw : list(payload.get("sections"))) {
            if (!(raw instanceof Map<?, ?> section)) continue;
            List<String> content = new ArrayList<>();
            String body = take(section.get("body"), existing);
            if (!body.isEmpty()) content.add(body);
            for (Object rawItem : list(section.get("items"))) {
                if (!(rawItem instanceof Map<?, ?> item)) continue;
                for (String key : List.of("text", "conflict", "recommendation")) {
                    String value = take(item.get(key), existing);
                    if (!value.isEmpty()) content.add("- " + value);
                }
            }
            if (content.isEmpty()) continue;
            String title = section.get("title") instanceof String str ? str.trim() : "";
            sections.add(
                    (title.isEmpty() ? "" : "## " + title + "\n\n") + String.join("\n\n", content));
        }
        for (Object raw : list(payload.get("evidence"))) {
            if (!(raw instanceof Map<?, ?> fact)) continue;
            for (String key : List.of("fact", "source")) {
                String value = take(fact.get(key), existing);
                if (!value.isEmpty()) sections.add("- " + value);
            }
        }
        for (Object raw : list(payload.get("suggestedFollowUps"))) {
            String value = take(raw, existing);
            if (!value.isEmpty()) sections.add("- " + value);
        }
        return String.join("\n\n", sections);
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private static String take(Object raw, Set<String> existing) {
        if (!(raw instanceof String value) || value.isBlank()) return "";
        String key = normalize(value);
        if (existing.contains(key)) return "";
        existing.add(key);
        return value.trim();
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim().replaceFirst("^(?:[-*+]|\\d+[.)])\\s+", "");
    }
}
