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
package io.deepscope.advisor.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads and writes the skill document the runtime already understands. */
public final class SkillDocuments {

    private SkillDocuments() {}

    public static String write(
            String name,
            String description,
            String purpose,
            String dimensions,
            String steps,
            String delivery)
            throws IOException {
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("A skill needs a name and when it applies");
        }
        return "---\n"
                + "name: "
                + name.strip()
                + "\n"
                + "description: "
                + description.strip()
                + "\n"
                + "---\n\n# "
                + name.strip()
                + "\n\n## 领域用途\n\n"
                + (purpose == null ? "" : purpose.strip())
                + "\n\n## 判断维度\n\n"
                + (dimensions == null ? "" : dimensions.strip())
                + "\n\n## 处理步骤\n\n"
                + (steps == null ? "" : steps.strip())
                + "\n\n## 交付模板\n\n"
                + (delivery == null ? "" : delivery.strip())
                + "\n";
    }

    public static Map<String, String> read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("body", text);
        if (!text.startsWith("---")) {
            return fields;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return fields;
        }
        String front = text.substring(3, end);
        for (String line : front.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                fields.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
            }
        }
        String body = text.substring(end + 4);
        fields.put("purpose", section(body, "领域用途"));
        fields.put("dimensions", section(body, "判断维度"));
        fields.put("steps", section(body, "处理步骤"));
        fields.put("delivery", section(body, "交付模板"));
        return fields;
    }

    public static void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        Files.createDirectories(target);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path dest = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String section(String body, String heading) {
        String marker = "## " + heading;
        int start = body.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int content = body.indexOf('\n', start);
        if (content < 0) {
            return "";
        }
        int next = body.indexOf("\n## ", content);
        String slice = next < 0 ? body.substring(content + 1) : body.substring(content + 1, next);
        return slice.strip();
    }
}
