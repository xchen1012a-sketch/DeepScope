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

import io.deepscope.core.skill.AgentSkill;
import io.deepscope.core.skill.util.SkillUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Turns an uploaded zip package or markdown file into one or more skills the catalog can store. */
public final class SkillPackage {
    private static final int MAX_ENTRY_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;

    public static final class ArchiveLimitException extends IllegalArgumentException {
        ArchiveLimitException() {
            super("skill_archive_limit_exceeded");
        }
    }

    private static final String SKILL_FILE_NAME = "SKILL.md";

    private SkillPackage() {}

    /** Loads the primary or first skill from an upload. */
    public static Loaded load(String filename, byte[] bytes) {
        List<Loaded> all = loadAll(filename, bytes);
        if (all.isEmpty()) {
            throw new IllegalArgumentException("未能在上传文件中找到有效的技能定义");
        }
        return all.get(0);
    }

    /** Loads all skills discovered within an uploaded zip package or markdown file. */
    public static List<Loaded> loadAll(String filename, byte[] bytes) {
        if (filename == null || bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Upload a zip package or a markdown file");
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".zip") || lower.endsWith(".skill")) {
            return loadZipAll(filename, bytes);
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            if (bytes.length > MAX_ENTRY_BYTES) throw new ArchiveLimitException();
            return List.of(loadSingleMarkdown(filename, bytes));
        }
        throw new IllegalArgumentException("Upload a zip package or a markdown file");
    }

    private static Loaded loadSingleMarkdown(String filename, byte[] bytes) {
        String baseName = sanitizeBaseName(filename);
        var loaded = parseWithFallback(bytes, baseName, baseName, Map.of());
        SkillPaths.name(loaded.name());
        return loaded;
    }

    private static List<Loaded> loadZipAll(String filename, byte[] bytes) {
        Map<String, byte[]> entries = new HashMap<>();
        int count = 0;
        long total = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++count > 512) throw new ArchiveLimitException();
                String name = entry.getName().replace('\\', '/').trim();
                if (name.startsWith("/")
                        || name.contains(":")
                        || java.nio.file.Path.of(name).isAbsolute()
                        || java.util.Arrays.stream(name.split("/"))
                                .anyMatch(part -> part.equals("..") || part.equals(".")))
                    throw new IllegalArgumentException("invalid_skill_path");
                // Filter out junk/system metadata files
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                long entryBytes = 0;
                while ((read = zis.read(buffer)) != -1) {
                    total += read;
                    entryBytes += read;
                    if (entryBytes > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES)
                        throw new ArchiveLimitException();
                    if (!entry.isDirectory() && !isJunkEntry(name)) out.write(buffer, 0, read);
                }
                if (!entry.isDirectory()
                        && !isJunkEntry(name)
                        && entries.putIfAbsent(name, out.toByteArray()) != null)
                    throw new IllegalArgumentException("Duplicate skill resource");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取压缩包内容，请检查文件是否损坏", e);
        }

        if (entries.isEmpty()) {
            throw new IllegalArgumentException("压缩包内容为空");
        }

        // Locate skill definition entries
        List<String> skillMdKeys = findSkillEntryKeys(entries);
        if (skillMdKeys.size() > 64) throw new ArchiveLimitException();
        if (skillMdKeys.isEmpty()) {
            throw new IllegalArgumentException("压缩包内未找到任何有效的 Markdown 技能定义 (SKILL.md 或 .md 文件)");
        }

        List<Loaded> result = new ArrayList<>();
        for (String skillKey : skillMdKeys) {
            byte[] skillBytes = entries.get(skillKey);
            if (skillBytes == null) {
                continue;
            }

            // Determine directory prefix
            String prefix = "";
            String dirName = "";
            int lastSlash = skillKey.lastIndexOf('/');
            if (lastSlash >= 0) {
                prefix = skillKey.substring(0, lastSlash + 1);
                dirName = skillKey.substring(0, lastSlash);
                int prevSlash = dirName.lastIndexOf('/');
                if (prevSlash >= 0) {
                    dirName = dirName.substring(prevSlash + 1);
                }
            } else {
                dirName = sanitizeBaseName(filename);
            }

            // Collect relative resources belonging to this prefix
            Map<String, String> resources = new HashMap<>();
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                String k = e.getKey();
                if (k.equals(skillKey)) {
                    continue;
                }
                if (!prefix.isEmpty() && k.startsWith(prefix)) {
                    // Check if it belongs to another deeper skill
                    if (!isUnderOtherSkill(k, prefix, skillMdKeys)) {
                        String relativeName = k.substring(prefix.length());
                        resources.put(
                                relativeName, new String(e.getValue(), StandardCharsets.UTF_8));
                    }
                } else if (prefix.isEmpty() && skillMdKeys.size() == 1) {
                    resources.put(k, new String(e.getValue(), StandardCharsets.UTF_8));
                }
            }

            Loaded loaded = parseWithFallback(skillBytes, dirName, filename, resources);
            SkillPaths.name(loaded.name());
            validateResources(loaded.resources());
            result.add(loaded);
        }

        // Sort by name for deterministic order
        result.sort(Comparator.comparing(Loaded::name));
        return Collections.unmodifiableList(result);
    }

    private static void validateResources(Map<String, String> resources) {
        var paths = new java.util.HashSet<java.nio.file.Path>();
        for (String resource : resources.keySet()) {
            var path = java.nio.file.Path.of(resource);
            if (path.isAbsolute()
                    || resource.contains("\\")
                    || resource.contains(":")
                    || path.normalize().startsWith("..")
                    || resource.equalsIgnoreCase("SKILL.md")
                    || !paths.add(path.normalize()))
                throw new IllegalArgumentException("invalid_skill_path");
            for (var parent = path.getParent(); parent != null; parent = parent.getParent()) {
                if (resources.containsKey(parent.toString().replace('\\', '/')))
                    throw new IllegalArgumentException("invalid_skill_path");
            }
        }
    }

    private static boolean isUnderOtherSkill(
            String path, String currentPrefix, List<String> allSkillKeys) {
        for (String otherKey : allSkillKeys) {
            int lastSlash = otherKey.lastIndexOf('/');
            if (lastSlash > 0) {
                String otherPrefix = otherKey.substring(0, lastSlash + 1);
                if (!otherPrefix.equals(currentPrefix)
                        && otherPrefix.startsWith(currentPrefix)
                        && path.startsWith(otherPrefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> findSkillEntryKeys(Map<String, byte[]> entries) {
        List<String> explicitSkillMds = new ArrayList<>();
        List<String> otherMds = new ArrayList<>();

        for (String name : entries.keySet()) {
            String lower = name.toLowerCase();
            if (lower.equals(SKILL_FILE_NAME.toLowerCase())
                    || lower.endsWith("/" + SKILL_FILE_NAME.toLowerCase())) {
                explicitSkillMds.add(name);
            } else if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
                otherMds.add(name);
            }
        }

        if (!explicitSkillMds.isEmpty()) {
            return explicitSkillMds;
        }

        // If no explicit SKILL.md, look for reasonable markdown candidates
        if (otherMds.size() == 1) {
            return otherMds;
        }

        // Prioritize instructions.md, prompt.md, rules.md, readme.md
        List<String> candidates = new ArrayList<>();
        for (String m : otherMds) {
            String base = m.substring(m.lastIndexOf('/') + 1).toLowerCase();
            if (base.startsWith("instructions")
                    || base.startsWith("prompt")
                    || base.startsWith("rules")
                    || base.startsWith("readme")) {
                candidates.add(m);
            }
        }
        return candidates.isEmpty() ? otherMds : candidates;
    }

    private static boolean isJunkEntry(String name) {
        String lower = name.toLowerCase();
        return lower.startsWith("__macosx/")
                || lower.contains("/__macosx/")
                || lower.endsWith(".ds_store")
                || lower.endsWith("thumbs.db")
                || lower.startsWith(".git/")
                || lower.contains("/.git/");
    }

    /**
     * Parses markdown into an AgentSkill with Zero-Error fallback for missing frontmatter,
     * titles, and descriptions.
     */
    private static Loaded parseWithFallback(
            byte[] bytes, String fallbackName, String filename, Map<String, String> resources) {
        String text = new String(bytes, StandardCharsets.UTF_8).stripLeading();

        // 1. Try standard parser first
        try {
            AgentSkill skill = SkillUtil.createFrom(text, resources);
            if (skill.getName() != null
                    && !skill.getName().isBlank()
                    && skill.getDescription() != null
                    && !skill.getDescription().isBlank()) {
                return new Loaded(
                        skill.getName(),
                        skill.getDescription(),
                        document(skill),
                        skill.getResources() == null ? Map.of() : skill.getResources());
            }
        } catch (Exception ignored) {
            // Fall through to lenient auto-repair
        }

        // 2. Lenient inference of name and description
        String inferredName = extractInferredName(text, fallbackName, filename);
        String inferredDescription = extractInferredDescription(text, inferredName);
        String body = extractBodyWithoutFrontmatter(text);
        if (body.isBlank()) {
            body = "# " + inferredName + "\n\n" + inferredDescription + "\n";
        }

        String normalizedMarkdown =
                "---\nname: "
                        + quote(inferredName)
                        + "\ndescription: "
                        + quote(inferredDescription)
                        + "\n---\n\n"
                        + body
                        + "\n";

        AgentSkill skill = SkillUtil.createFrom(normalizedMarkdown, resources);
        return new Loaded(
                skill.getName(),
                skill.getDescription(),
                normalizedMarkdown,
                skill.getResources() == null ? Map.of() : skill.getResources());
    }

    private static String extractInferredName(String text, String fallbackDir, String filename) {
        // Pattern 1: frontmatter name:
        Matcher nameMatcher =
                Pattern.compile("(?m)^name:\\s*[\"']?([^\"'\\r\\n]+)[\"']?\\s*$").matcher(text);
        if (nameMatcher.find()) {
            String found = nameMatcher.group(1).trim();
            if (!found.isBlank()) {
                return found;
            }
        }

        // Pattern 2: First level 1 markdown title # Title
        Matcher titleMatcher = Pattern.compile("(?m)^#\\s+([^\\r\\n]+)$").matcher(text);
        if (titleMatcher.find()) {
            String title = titleMatcher.group(1).trim();
            if (!title.isBlank()) {
                return title;
            }
        }

        if (fallbackDir != null && !fallbackDir.isBlank()) {
            return fallbackDir.trim();
        }

        return sanitizeBaseName(filename);
    }

    private static String extractInferredDescription(String text, String defaultTitle) {
        // Pattern 1: frontmatter description:
        Matcher descMatcher =
                Pattern.compile("(?m)^description:\\s*[\"']?([^\"'\\r\\n]+)[\"']?\\s*$")
                        .matcher(text);
        if (descMatcher.find()) {
            String found = descMatcher.group(1).trim();
            if (!found.isBlank()) {
                return found;
            }
        }

        // Pattern 2: First non-heading non-empty line
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()
                    || trimmed.startsWith("---")
                    || trimmed.startsWith("#")
                    || trimmed.startsWith("name:")
                    || trimmed.startsWith("description:")
                    || trimmed.startsWith("author:")
                    || trimmed.startsWith("version:")) {
                continue;
            }
            if (trimmed.length() > 150) {
                return trimmed.substring(0, 150) + "…";
            }
            return trimmed;
        }

        return "来自导入技能包: " + defaultTitle;
    }

    private static String extractBodyWithoutFrontmatter(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.stripLeading();
        if (stripped.startsWith("---")) {
            int second = stripped.indexOf("---", 3);
            if (second > 0) {
                return stripped.substring(second + 3).strip();
            }
        }
        return stripped;
    }

    private static String sanitizeBaseName(String filename) {
        if (filename == null) {
            return "custom_skill";
        }
        String name = filename;
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.isBlank() ? "custom_skill" : name;
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value.replace("\"", "\\\"");
        return "\"" + safe + "\"";
    }

    private static String document(AgentSkill skill) {
        return "---\nname: "
                + quote(skill.getName())
                + "\ndescription: "
                + quote(skill.getDescription())
                + "\n---\n\n"
                + skill.getSkillContent();
    }

    public record Loaded(
            String name, String description, String markdown, Map<String, String> resources) {}
}
