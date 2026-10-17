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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.advisor.agent.AgentStore;
import io.deepscope.advisor.db.AdvisorSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** A checked skill is on disk for the next message. Unchecking or unpublishing removes it. */
class SkillSyncTest {

    @TempDir Path root;

    private SkillCatalog catalog;
    private SkillSync sync;
    private AgentStore.AgentRow agent;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:skills-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        catalog = new SkillCatalog(dataSource, root.resolve("catalog"));
        sync = new SkillSync(catalog);
        Path workspace = root.resolve("workspace");
        Files.createDirectories(workspace);
        agent =
                new AgentStore.AgentRow(
                        "agent-1",
                        "助手",
                        "",
                        "personal",
                        "carol",
                        workspace.toString(),
                        root.resolve("state").toString(),
                        0,
                        0);
    }

    @Test
    void publishingCannotFollowAnOwnersDirectorySymlink() throws Exception {
        var skill =
                catalog.createDraft(
                        "carol",
                        "safe",
                        "audit",
                        "---\nname: safe\ndescription: audit\n---\nAudit");
        Path outside = root.resolve("symlink-target");
        Files.createDirectories(outside);
        Files.createDirectories(root.resolve("catalog/live"));
        try {
            Files.createSymbolicLink(root.resolve("catalog/live/carol"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable on this test host");
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> catalog.publish(skill));
        assertFalse(Files.exists(outside.resolve("safe/SKILL.md")));
    }

    @Test
    void unsafeRenameCannotWriteOrDeleteOutsideTheOwnersDirectory() throws Exception {
        var skill =
                catalog.createDraft(
                        "carol",
                        "safe",
                        "audit",
                        "---\nname: safe\ndescription: audit\n---\nAudit");
        Path outside = root.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "must survive");
        for (String name : List.of(outside.toString(), "../outside", "a/b", "a\\b", "..", "CON")) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> catalog.update(skill, name, "", "audit"));
        }
        assertTrue(Files.exists(outside.resolve("keep.txt")));
        assertFalse(Files.exists(outside.resolve("SKILL.md")));
        assertTrue(catalog.find(skill.skillId()).orElseThrow().name().equals("safe"));
    }

    @Test
    void historicalUntrustedLivePathCannotBeRemoved() throws Exception {
        Path outside = root.resolve("outside-history");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "must survive");
        var bad =
                new SkillCatalog.SkillRow(
                        "bad",
                        "safe",
                        "",
                        "published",
                        "personal",
                        "carol",
                        root.resolve("draft").toString(),
                        outside.toString(),
                        0,
                        0);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> catalog.unpublish(bad));
        assertTrue(Files.exists(outside.resolve("keep.txt")));
    }

    @Test
    void theNextMessageSeesACheckAndLosesItWhenUncheckedOrUnpublished() throws Exception {
        String markdown = SkillDocuments.write("合同审查", "审查合同风险时使用", "找风险", "条款", "逐条读", "风险清单");
        var skill = catalog.createDraft("carol", "合同审查", "审查合同风险时使用", markdown);
        assertTrue(catalog.effective(agent.agentId(), "carol").isEmpty());
        sync.sync(agent);
        assertFalse(Files.exists(Path.of(agent.workspaceDir()).resolve("skills/合同审查/SKILL.md")));

        catalog.publish(skill);
        catalog.replaceSelection(agent.agentId(), List.of(skill.skillId()));
        sync.sync(agent);
        assertTrue(Files.exists(Path.of(agent.workspaceDir()).resolve("skills/合同审查/SKILL.md")));

        catalog.replaceSelection(agent.agentId(), List.of());
        sync.sync(agent);
        assertFalse(Files.exists(Path.of(agent.workspaceDir()).resolve("skills/合同审查")));

        catalog.replaceSelection(agent.agentId(), List.of(skill.skillId()));
        sync.sync(agent);
        catalog.unpublish(catalog.find(skill.skillId()).orElseThrow());
        sync.sync(agent);
        assertEquals(0, catalog.effective(agent.agentId(), "carol").size());
        assertFalse(Files.exists(Path.of(agent.workspaceDir()).resolve("skills/合同审查")));
    }
}
