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
package io.agentscope.advisor.agent;

import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Workspace editing and inspection; runtime declaration parsing remains owned by the harness. */
public final class AgentWorkspace {
    public static final String SUBAGENT_TEMPLATE =
            "---\n"
                    + "description: Describe the investigation this subagent handles.\n"
                    + "---\n\n"
                    + "Report only verified facts with sources.\n";

    public Path file(AgentStore.AgentRow agent, String relative) {
        if (relative == null || relative.isBlank() || relative.contains("\\")) {
            throw badPath();
        }
        Path input;
        try {
            input = Path.of(relative);
        } catch (RuntimeException error) {
            throw badPath();
        }
        if (input.isAbsolute() || !definitionPath(input)) throw badPath();
        Path root = Path.of(agent.workspaceDir()).toAbsolutePath().normalize();
        Path current = root;
        if (Files.isSymbolicLink(root)) throw badPath();
        for (Path segment : input) {
            String name = segment.toString();
            if (name.isBlank() || name.startsWith(".")) throw badPath();
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw badPath();
        }
        if (!current.normalize().startsWith(root) || current.equals(root)) throw badPath();
        return current;
    }

    public List<Map<String, String>> tree(AgentStore.AgentRow agent) throws IOException {
        Path root = Path.of(agent.workspaceDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var paths = Files.walk(root)) {
            List<Map<String, String>> files = new ArrayList<>();
            for (Path path :
                    paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                            .sorted()
                            .toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (definitionPath(Path.of(relative))) {
                    file(agent, relative);
                    files.add(Map.of("path", relative));
                }
            }
            return files;
        }
    }

    public String read(AgentStore.AgentRow agent, String path) throws IOException {
        Path file = file(agent, path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return Files.readString(file);
    }

    public void write(AgentStore.AgentRow agent, String path, String content, boolean create)
            throws IOException {
        Path target = file(agent, path);
        if (content == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing content");
        if (path.startsWith("subagents/")) validateSubagent(agent, path, content);
        if (create && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "File already exists");
        }
        if (!create && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Files.createDirectories(target.getParent());
        Files.writeString(
                target,
                content,
                create ? StandardOpenOption.CREATE_NEW : StandardOpenOption.TRUNCATE_EXISTING);
    }

    public void delete(AgentStore.AgentRow agent, String path) throws IOException {
        Path target = file(agent, path);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Files.delete(target);
    }

    public void copy(AgentStore.AgentRow source, AgentStore.AgentRow target) throws IOException {
        for (Map<String, String> entry : tree(source)) {
            String path = entry.get("path");
            Path dest = file(target, path);
            Files.createDirectories(dest.getParent());
            Files.copy(file(source, path), dest);
        }
    }

    public Map<String, Object> sections(AgentStore.AgentRow agent, AgentProvisioner provisioner)
            throws IOException {
        Path root = Path.of(agent.workspaceDir());
        List<Map<String, String>> subagents = new ArrayList<>();
        List<Map<String, String>> knowledge = new ArrayList<>();
        List<Map<String, String>> skills = new ArrayList<>();
        for (var entry : tree(agent)) {
            String path = entry.get("path");
            if (path.startsWith("subagents/") && path.endsWith(".md")) {
                var spec = AgentSpecLoader.loadFromFile(file(agent, path), root);
                if (spec != null)
                    subagents.add(
                            Map.of(
                                    "name",
                                    spec.getName(),
                                    "description",
                                    spec.getDescription(),
                                    "file",
                                    path));
            } else if (path.startsWith("knowledge/")) {
                knowledge.add(Map.of("file", path));
            }
        }
        Path skillsDir = root.resolve("skills");
        if (Files.isDirectory(skillsDir, LinkOption.NOFOLLOW_LINKS)) {
            for (var skill :
                    new FileSystemSkillRepository(skillsDir, false, null, true).getAllSkills()) {
                skills.add(Map.of("name", skill.getName(), "description", skill.getDescription()));
            }
        }
        return Map.of(
                "skills",
                skills,
                "subagents",
                subagents,
                "knowledge",
                knowledge,
                "tools",
                provisioner.tools(agent.agentId()));
    }

    private void validateSubagent(AgentStore.AgentRow agent, String path, String content) {
        if (!path.matches("subagents/[^/]+\\.md")
                || AgentSpecLoader.parse(
                                content,
                                Path.of(path).getFileName().toString().replaceFirst("\\.md$", ""),
                                Path.of(agent.workspaceDir()))
                        == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid subagent definition");
        }
    }

    private static boolean definitionPath(Path path) {
        for (Path segment : path) {
            if (segment.toString().startsWith(".")) return false;
        }
        String root = path.getName(0).toString();
        return path.getNameCount() == 1 && (root.equals("AGENTS.md") || root.equals("tools.json"))
                || path.getNameCount() > 1
                        && List.of("skills", "subagents", "knowledge").contains(root);
    }

    private static ResponseStatusException badPath() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid workspace path");
    }
}
