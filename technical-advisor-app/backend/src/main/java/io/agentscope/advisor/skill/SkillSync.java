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
package io.agentscope.advisor.skill;

import io.agentscope.advisor.agent.AgentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * Makes the agent's workspace skill directory match the skills checked right now.
 *
 * <p>The running conversation reads that directory on the next message, so a disable or a new
 * check applies without opening another conversation.
 */
public final class SkillSync {

    private final SkillCatalog catalog;

    public SkillSync(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    public void sync(AgentStore.AgentRow agent) throws SQLException, IOException {
        Path workspace = Path.of(agent.workspaceDir());
        Path skills = SkillPaths.contained(workspace, workspace.resolve("skills"));
        Files.createDirectories(skills);
        Set<String> keep = new HashSet<>();
        for (SkillCatalog.SkillRow skill :
                catalog.effective(agent.agentId(), agent.ownerUserId())) {
            if (skill.liveDir() == null) {
                continue;
            }
            keep.add(skill.name());
            SkillDocuments.copyDirectory(
                    catalog.checkedLivePath(skill),
                    SkillPaths.contained(workspace, skills.resolve(SkillPaths.name(skill.name()))));
        }
        if (!Files.isDirectory(skills)) {
            return;
        }
        try (var children = Files.list(skills)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child) && !keep.contains(child.getFileName().toString())) {
                    deleteTree(child);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
