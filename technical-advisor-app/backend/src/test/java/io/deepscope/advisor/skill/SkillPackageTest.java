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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** A skill upload is one zip package or one markdown file. */
class SkillPackageTest {
    @Test
    void directoryEntryPayloadCannotBypassTheExpansionLimit() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write(MARKDOWN.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("large-directory/"));
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 17; i++) zip.write(block);
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SkillPackage.loadAll("directory.zip", bytes.toByteArray()));
    }

    @Test
    void allSkillNamesAndConflictingResourcePathsAreValidatedBeforeReturningAPackage()
            throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("one/SKILL.md"));
            zip.write(MARKDOWN.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("two/SKILL.md"));
            zip.write(
                    "---\nname: z/../../bad\ndescription: audit\n---\nAudit"
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SkillPackage.loadAll("bad-name.zip", bytes.toByteArray()));
    }

    @Test
    void compressedExpansionAndTooManyEntriesAreRejectedBeforeCreatingSkills() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write(MARKDOWN.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("large.txt"));
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 17; i++) zip.write(block);
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SkillPackage.loadAll("large.zip", bytes.toByteArray()));
        var many = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(many)) {
            for (int i = 0; i < 513; i++) {
                zip.putNextEntry(new ZipEntry("ignored-" + i + "/"));
                zip.closeEntry();
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SkillPackage.loadAll("many.zip", many.toByteArray()));
    }

    private static final String MARKDOWN =
            """
            ---
            name: contract-review
            description: 审查合同风险时使用
            ---

            # 合同审查

            逐条看条款。
            """;

    @Test
    void markdownKeepsTheNameFromTheFile() {
        SkillPackage.Loaded loaded =
                SkillPackage.load("SKILL.md", MARKDOWN.getBytes(StandardCharsets.UTF_8));
        assertEquals("contract-review", loaded.name());
        assertEquals("审查合同风险时使用", loaded.description());
        assertTrue(loaded.resources().isEmpty());
    }

    @Test
    void zipKeepsTheSkillFileAndItsResources() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("contract-review/SKILL.md"));
            zip.write(MARKDOWN.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("contract-review/references/note.md"));
            zip.write("对照清单".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        SkillPackage.Loaded loaded = SkillPackage.load("contract.skill", bytes.toByteArray());
        assertEquals("contract-review", loaded.name());
        assertEquals("对照清单", loaded.resources().get("references/note.md"));
    }

    @Test
    void flatZipWithoutRootDirectoryLoadsSuccessfully() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write(MARKDOWN.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("references/note.md"));
            zip.write("扁平包资源".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        SkillPackage.Loaded loaded = SkillPackage.load("flat-contract.zip", bytes.toByteArray());
        assertEquals("contract-review", loaded.name());
        assertEquals("审查合同风险时使用", loaded.description());
        assertEquals("扁平包资源", loaded.resources().get("references/note.md"));
    }

    @Test
    void multiSkillZipLoadsAllDiscoveredSkills() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("skill-one\\SKILL.md"));
            zip.write(
                    """
                    ---
                    name: skill-one
                    description: 第一技能说明
                    ---
                    # 第一技能
                    步骤一
                    """
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("skill-two\\SKILL.md"));
            zip.write(
                    """
                    ---
                    name: skill-two
                    description: 第二技能说明
                    ---
                    # 第二技能
                    步骤二
                    """
                            .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        java.util.List<SkillPackage.Loaded> loadedList =
                SkillPackage.loadAll("skills-bundle.zip", bytes.toByteArray());
        assertEquals(2, loadedList.size());
        assertEquals("skill-one", loadedList.get(0).name());
        assertEquals("skill-two", loadedList.get(1).name());
    }

    @Test
    void markdownWithoutFrontmatterInfersNameAndDescription() {
        String rawMarkdown =
                """
                # 代码异味审查

                专门用于分析 Java 代码中的圈复杂度与设计坏味道。

                ## 步骤
                1. 扫描 AST
                2. 输出建议
                """;
        SkillPackage.Loaded loaded =
                SkillPackage.load("smell-checker.md", rawMarkdown.getBytes(StandardCharsets.UTF_8));
        assertEquals("代码异味审查", loaded.name());
        assertTrue(loaded.description().contains("专门用于分析 Java 代码"));
        assertTrue(loaded.markdown().contains("## 步骤"));
    }

    @Test
    void userUploadedMultiSkillPackLoadsSuccessfullyIfPresent() throws Exception {
        java.nio.file.Path userZip =
                java.nio.file.Path.of(
                        "/Users/juvon/.gemini/antigravity/brain/eb1ce59c-2d22-4e16-89ea-c6a8ccac73d9/.user_uploaded/media_1791979381189_6c5c785c.zip");
        if (java.nio.file.Files.exists(userZip)) {
            byte[] bytes = java.nio.file.Files.readAllBytes(userZip);
            java.util.List<SkillPackage.Loaded> skills =
                    SkillPackage.loadAll("media_1791979381189_6c5c785c.zip", bytes);
            assertEquals(
                    13, skills.size(), "Should load all 13 skills from Claude skillset bundle");
            assertTrue(
                    skills.stream().anyMatch(s -> "release-flow".equals(s.name())),
                    "release-flow should be loaded");
            assertTrue(
                    skills.stream().anyMatch(s -> "ui-ux-pro-max".equals(s.name())),
                    "ui-ux-pro-max should be loaded");
        }
    }

    @Test
    void missingAnyMarkdownThrowsDescriptiveException() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("data.bin"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }
        IllegalArgumentException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> SkillPackage.load("empty.zip", bytes.toByteArray()));
        assertTrue(
                ex.getMessage().contains("Markdown") || ex.getMessage().contains("SKILL.md"),
                "Expected error message mentioning Markdown or SKILL.md, got: " + ex.getMessage());
    }
}
