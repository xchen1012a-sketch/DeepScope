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

import io.deepscope.advisor.agent.AgentStore;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Skill catalog, publish, and per-agent checks. */
@RestController
public class AdvisorSkillController {
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalidSkill(IllegalArgumentException error) {
        String code =
                ("invalid_skill_name".equals(error.getMessage())
                                || "invalid_skill_path".equals(error.getMessage()))
                        ? error.getMessage()
                        : "invalid_package";
        return ResponseEntity.badRequest()
                .body(
                        Map.of(
                                "code",
                                code,
                                "messageKey",
                                "skill.errors." + code,
                                "message",
                                error.getMessage() == null ? code : error.getMessage()));
    }

    private final SkillCatalog catalog;
    private final AgentStore agents;
    private final SkillSync sync;
    private final SkillDraftService drafts;
    private final io.deepscope.advisor.access.AccessGuard access;

    public AdvisorSkillController(
            SkillCatalog catalog,
            AgentStore agents,
            SkillSync sync,
            SkillDraftService drafts,
            io.deepscope.advisor.access.AccessGuard access) {
        this.catalog = catalog;
        this.agents = agents;
        this.sync = sync;
        this.drafts = drafts;
        this.access = access;
    }

    @GetMapping("/api/advisor/skills")
    public List<Map<String, Object>> list(HttpServletRequest request) throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        List<Map<String, Object>> view = new ArrayList<>();
        boolean selection = io.deepscope.advisor.access.WorkspaceAuthorization.selection(request);
        for (SkillCatalog.SkillRow skill :
                catalog.list(
                        io.deepscope.advisor.access.WorkspaceAuthorization.owner(
                                request, account.userId()),
                        !selection
                                && access.manage(
                                        account,
                                        io.deepscope.advisor.access.AccessPoints.SKILL_MANAGE),
                        request.getParameter("q"))) {
            view.add(
                    summary(
                            skill,
                            account.userId(),
                            access.manage(
                                    account,
                                    io.deepscope.advisor.access.AccessPoints.SKILL_MANAGE)));
        }
        if (selection) view.forEach(item -> item.put("canEdit", false));
        return view;
    }

    @GetMapping(value = "/api/advisor/skills", params = "page")
    public io.deepscope.advisor.db.PageResult<Map<String, Object>> page(HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        boolean selection = io.deepscope.advisor.access.WorkspaceAuthorization.selection(request);
        boolean admin =
                !selection
                        && access.manage(
                                account, io.deepscope.advisor.access.AccessPoints.SKILL_MANAGE);
        var rows =
                catalog.page(
                        io.deepscope.advisor.access.WorkspaceAuthorization.owner(
                                request, account.userId()),
                        admin,
                        request.getParameter("q"),
                        selection ? SkillCatalog.PUBLISHED : request.getParameter("status"),
                        io.deepscope.advisor.db.JdbcPage.page(request),
                        io.deepscope.advisor.db.JdbcPage.size(request));
        var items = new ArrayList<Map<String, Object>>();
        for (var skill : rows.items()) items.add(summary(skill, account.userId(), admin));
        if (selection) items.forEach(item -> item.put("canEdit", false));
        return new io.deepscope.advisor.db.PageResult<>(
                items, rows.page(), rows.size(), rows.total(), rows.totalPages());
    }

    @PostMapping(value = "/api/advisor/skills", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(
            @RequestParam("file") MultipartFile file, HttpServletRequest request)
            throws SQLException, IOException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "empty_file", "message", "上传文件不能为空"));
        }
        try {
            List<SkillPackage.Loaded> loadedList =
                    SkillPackage.loadAll(file.getOriginalFilename(), file.getBytes());
            if (loadedList.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("code", "no_skills_found", "message", "未在文件中找到有效的技能定义"));
            }

            List<Map<String, Object>> createdSummaries = new ArrayList<>();
            Set<String> usedNames = new HashSet<>();
            for (SkillCatalog.SkillRow s : catalog.list(account.userId(), false)) {
                usedNames.add(s.name());
            }
            List<SkillPackage.Loaded> prepared = new ArrayList<>();
            for (SkillPackage.Loaded loaded : loadedList) {
                String safeName = ensureUniqueSkillName(usedNames, loaded.name());
                usedNames.add(safeName);
                prepared.add(
                        new SkillPackage.Loaded(
                                safeName,
                                loaded.description(),
                                loaded.markdown(),
                                loaded.resources()));
            }
            for (SkillCatalog.SkillRow skill : catalog.createDrafts(account.userId(), prepared)) {
                createdSummaries.add(
                        summary(
                                skill,
                                account.userId(),
                                access.manage(
                                        account,
                                        io.deepscope.advisor.access.AccessPoints.SKILL_MANAGE)));
            }

            if (createdSummaries.size() == 1) {
                return ResponseEntity.status(HttpStatus.CREATED).body(createdSummaries.get(0));
            } else {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("batch", true);
                resp.put("count", createdSummaries.size());
                resp.put("skills", createdSummaries);
                return ResponseEntity.status(HttpStatus.CREATED).body(resp);
            }
        } catch (IllegalArgumentException error) {
            if (error instanceof SkillPackage.ArchiveLimitException)
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                        .body(
                                Map.of(
                                        "code",
                                        "skill_archive_limit_exceeded",
                                        "messageKey",
                                        "skill.errors.skill_archive_limit_exceeded"));
            String msg = error.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = "无效的技能包";
            }
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "invalid_package", "message", msg));
        }
    }

    @PostMapping(
            value = "/api/advisor/skills/parse",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> parse(
            @RequestParam("file") MultipartFile file, HttpServletRequest request)
            throws IOException {
        ApiAuthenticationFilter.account(request).orElseThrow();
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "empty_file", "message", "上传文件不能为空"));
        }
        try {
            List<SkillPackage.Loaded> loadedList =
                    SkillPackage.loadAll(file.getOriginalFilename(), file.getBytes());
            if (loadedList.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("code", "no_skills_found", "message", "未在文件中找到有效的技能定义"));
            }
            List<Map<String, Object>> parsed = new ArrayList<>();
            for (SkillPackage.Loaded loaded : loadedList) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("name", loaded.name());
                map.put("description", loaded.description());
                map.put("markdown", loaded.markdown());
                map.put(
                        "resourceCount",
                        loaded.resources() != null ? loaded.resources().size() : 0);
                parsed.add(map);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("batch", parsed.size() > 1);
            resp.put("count", parsed.size());
            resp.put("skills", parsed);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException error) {
            if (error instanceof SkillPackage.ArchiveLimitException)
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                        .body(
                                Map.of(
                                        "code",
                                        "skill_archive_limit_exceeded",
                                        "messageKey",
                                        "skill.errors.skill_archive_limit_exceeded"));
            String msg = error.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = "解析技能包失败";
            }
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "invalid_package", "message", msg));
        }
    }

    @PostMapping(value = "/api/advisor/skills", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generate(
            @RequestBody Map<String, Object> body, HttpServletRequest request)
            throws SQLException, IOException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        if (body != null && body.get("skills") instanceof List<?> list && !list.isEmpty()) {
            List<Map<String, Object>> createdSummaries = new ArrayList<>();
            Set<String> usedNames = new HashSet<>();
            for (SkillCatalog.SkillRow s : catalog.list(account.userId(), false)) {
                usedNames.add(s.name());
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> skillMap) {
                    String sName = (String) skillMap.get("name");
                    String sDesc = (String) skillMap.get("description");
                    String sMd = (String) skillMap.get("markdown");
                    if (sMd == null) {
                        sMd = (String) skillMap.get("content");
                    }
                    if (sMd == null || sMd.isBlank()) {
                        sMd =
                                "---\nname: "
                                        + quote(sName)
                                        + "\ndescription: "
                                        + quote(sDesc)
                                        + "\n---\n\n# "
                                        + (sName != null ? sName : "Skill")
                                        + "\n\n"
                                        + (sDesc != null ? sDesc : "")
                                        + "\n";
                    }
                    String safeName = ensureUniqueSkillName(usedNames, sName);
                    usedNames.add(safeName);
                    SkillCatalog.SkillRow skill =
                            catalog.createDraft(
                                    account.userId(),
                                    safeName,
                                    sDesc != null ? sDesc : "",
                                    sMd,
                                    Map.of());
                    createdSummaries.add(
                            summary(
                                    skill,
                                    account.userId(),
                                    access.manage(
                                            account,
                                            io.deepscope.advisor.access.AccessPoints
                                                    .SKILL_MANAGE)));
                }
            }
            if (createdSummaries.size() == 1) {
                return ResponseEntity.status(HttpStatus.CREATED).body(createdSummaries.get(0));
            } else {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("batch", true);
                resp.put("count", createdSummaries.size());
                resp.put("skills", createdSummaries);
                return ResponseEntity.status(HttpStatus.CREATED).body(resp);
            }
        }
        String name = text(body, "name");
        String brief = text(body, "brief");
        if (brief == null) {
            brief = text(body, "description");
        }
        String content = text(body, "content");
        String markdown;
        if (content != null && !content.isBlank()) {
            if (content.stripLeading().startsWith("---")) {
                markdown = content;
            } else {
                if (name == null || name.isBlank()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("code", "missing_name", "message", "请提供技能名称"));
                }
                if (brief == null || brief.isBlank()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("code", "missing_description", "message", "请提供技能用途描述"));
                }
                markdown =
                        "---\nname: "
                                + quote(name)
                                + "\ndescription: "
                                + quote(brief)
                                + "\n---\n\n"
                                + content.strip()
                                + "\n";
            }
        } else {
            if (brief == null || brief.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("code", "missing_description", "message", "请提供技能用途描述"));
            }
            markdown = drafts.draft(name, brief);
            if (markdown == null || markdown.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("code", "draft_failed", "message", "模型没有写出技能文件"));
            }
        }
        try {
            SkillPackage.Loaded loaded =
                    SkillPackage.load("SKILL.md", markdown.getBytes(StandardCharsets.UTF_8));
            String safeName = ensureUniqueSkillName(account.userId(), loaded.name());
            SkillCatalog.SkillRow skill =
                    catalog.createDraft(
                            account.userId(),
                            safeName,
                            loaded.description(),
                            loaded.markdown(),
                            loaded.resources());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(
                            summary(
                                    skill,
                                    account.userId(),
                                    access.manage(
                                            account,
                                            io.deepscope.advisor.access.AccessPoints
                                                    .SKILL_MANAGE)));
        } catch (IllegalArgumentException error) {
            String msg = error.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = "无效的技能定义";
            }
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "invalid_package", "message", msg));
        }
    }

    @PostMapping(
            value = "/api/advisor/skills/draft-content",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> draftContent(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        ApiAuthenticationFilter.account(request).orElseThrow();
        String name = text(body, "name");
        String brief = text(body, "brief");
        if (brief == null) {
            brief = text(body, "description");
        }
        if (brief == null || brief.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "missing_description", "message", "请提供技能用途描述"));
        }
        String markdown = drafts.draft(name, brief);
        if (markdown == null || markdown.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("code", "draft_failed", "message", "模型没有写出技能文件"));
        }
        String instructions = extractInstructions(markdown);
        Map<String, Object> response = new HashMap<>();
        response.put("content", markdown);
        response.put("instructions", instructions);
        try {
            SkillPackage.Loaded loaded =
                    SkillPackage.load("SKILL.md", markdown.getBytes(StandardCharsets.UTF_8));
            response.put("name", loaded.name());
            response.put("description", loaded.description());
        } catch (Exception ignored) {
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/api/advisor/skills/suggest", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> suggest(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        ApiAuthenticationFilter.account(request).orElseThrow();
        String brief = text(body, "brief");
        if (brief == null) {
            return ResponseEntity.badRequest().build();
        }
        String name = drafts.suggestName(brief);
        if (name.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("message", "模型没有补上名称"));
        }
        return ResponseEntity.ok(Map.of("name", name));
    }

    @PostMapping("/api/advisor/skills/{skillId}/publish")
    public ResponseEntity<Void> publish(@PathVariable String skillId, HttpServletRequest request)
            throws SQLException, IOException {
        var skill = editable(skillId, request);
        if (skill == null) {
            return missing(skillId, request);
        }
        catalog.publish(skill);
        refresh(skill.skillId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/advisor/skills/{skillId}/unpublish")
    public ResponseEntity<Void> unpublish(@PathVariable String skillId, HttpServletRequest request)
            throws SQLException, IOException {
        var skill = editable(skillId, request);
        if (skill == null) {
            return missing(skillId, request);
        }
        catalog.unpublish(skill);
        refresh(skill.skillId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/advisor/skills/{skillId}")
    public ResponseEntity<?> detail(@PathVariable String skillId, HttpServletRequest request)
            throws SQLException, IOException {
        var skill = viewable(skillId, request);
        if (skill == null) {
            return missing(skillId, request);
        }
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        Map<String, Object> view =
                summary(
                        skill,
                        account.userId(),
                        access.manage(
                                account, io.deepscope.advisor.access.AccessPoints.SKILL_MANAGE));
        view.put("markdown", catalog.readMarkdown(skill));
        return ResponseEntity.ok(view);
    }

    @PutMapping(
            value = "/api/advisor/skills/{skillId}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(
            @PathVariable String skillId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException, IOException {
        var skill = editable(skillId, request);
        if (skill == null) {
            return missing(skillId, request);
        }
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        String name = text(body, "name");
        String brief = text(body, "brief");
        if (brief == null) {
            brief = text(body, "description");
        }
        String content = text(body, "content");
        if (content == null) {
            content = text(body, "markdown");
        }
        String markdown;
        if (content != null && !content.isBlank()) {
            if (content.stripLeading().startsWith("---")) {
                markdown = content;
            } else {
                markdown =
                        "---\nname: "
                                + quote(name != null ? name : skill.name())
                                + "\ndescription: "
                                + quote(brief != null ? brief : skill.description())
                                + "\n---\n\n"
                                + content.strip()
                                + "\n";
            }
        } else {
            markdown = catalog.readMarkdown(skill);
        }
        try {
            SkillCatalog.SkillRow updated = catalog.update(skill, name, brief, markdown);
            refresh(skillId);
            return ResponseEntity.ok(
                    summary(
                            updated,
                            account.userId(),
                            access.manage(
                                    account,
                                    io.deepscope.advisor.access.AccessPoints.SKILL_MANAGE)));
        } catch (IllegalArgumentException error) {
            String msg = error.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = "更新技能失败";
            }
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "invalid_update", "message", msg));
        }
    }

    @DeleteMapping("/api/advisor/skills/{skillId}")
    public ResponseEntity<Void> delete(@PathVariable String skillId, HttpServletRequest request)
            throws SQLException, IOException {
        var skill = editable(skillId, request);
        if (skill == null) {
            return missing(skillId, request);
        }
        if (!catalog.deleteDraft(skill)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/advisor/agents/{agentId}/skill-selection")
    public ResponseEntity<Map<String, Object>> selection(
            @PathVariable String agentId, HttpServletRequest request) throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        if (agents.find(agentId)
                .filter(
                        agent ->
                                AgentStore.canView(
                                        agent,
                                        account.userId(),
                                        access.manage(
                                                account,
                                                io.deepscope.advisor.access.AccessPoints
                                                        .AGENT_MANAGE)))
                .isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("skillIds", catalog.selection(agentId)));
    }

    @PutMapping("/api/advisor/agents/{agentId}/skill-selection")
    public ResponseEntity<Void> replaceSelection(
            @PathVariable String agentId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException, IOException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        var agent =
                agents.find(agentId)
                        .filter(
                                row ->
                                        AgentStore.canView(
                                                row,
                                                account.userId(),
                                                access.manage(
                                                        account,
                                                        io.deepscope.advisor.access.AccessPoints
                                                                .AGENT_MANAGE)))
                        .orElse(null);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }
        if (!AgentStore.canEdit(
                agent,
                account.userId(),
                access.manage(account, io.deepscope.advisor.access.AccessPoints.AGENT_MANAGE))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<String> skillIds = new ArrayList<>();
        if (body.get("skillIds") instanceof List<?> items) {
            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                SkillCatalog.SkillRow skill = catalog.find(item.toString()).orElse(null);
                if (skill == null || !SkillCatalog.PUBLISHED.equals(skill.status())) {
                    return ResponseEntity.badRequest().build();
                }
                boolean visibleToAgent =
                        agent.ownerUserId() != null
                                && agent.ownerUserId().equals(skill.ownerUserId());
                if (!visibleToAgent) {
                    return ResponseEntity.badRequest().build();
                }
                skillIds.add(item.toString());
            }
        }
        catalog.replaceSelection(agentId, skillIds);
        sync.sync(agent);
        return ResponseEntity.noContent().build();
    }

    private void refresh(String skillId) throws SQLException, IOException {
        for (String agentId : catalog.agentsUsing(skillId)) {
            agents.find(agentId)
                    .ifPresent(
                            agent -> {
                                try {
                                    sync.sync(agent);
                                } catch (SQLException | IOException error) {
                                    throw new IllegalStateException(error);
                                }
                            });
        }
    }

    private SkillCatalog.SkillRow viewable(String skillId, HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        return catalog.find(skillId)
                .filter(
                        skill ->
                                SkillCatalog.canView(
                                        skill,
                                        account.userId(),
                                        access.manage(
                                                account,
                                                io.deepscope.advisor.access.AccessPoints
                                                        .SKILL_MANAGE)))
                .orElse(null);
    }

    private SkillCatalog.SkillRow editable(String skillId, HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        return catalog.find(skillId)
                .filter(
                        skill ->
                                SkillCatalog.canView(
                                        skill,
                                        account.userId(),
                                        access.manage(
                                                account,
                                                io.deepscope.advisor.access.AccessPoints
                                                        .SKILL_MANAGE)))
                .filter(
                        skill ->
                                SkillCatalog.canEdit(
                                        skill,
                                        account.userId(),
                                        access.manage(
                                                account,
                                                io.deepscope.advisor.access.AccessPoints
                                                        .SKILL_MANAGE)))
                .orElse(null);
    }

    private ResponseEntity<Void> missing(String skillId, HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        return catalog.find(skillId)
                        .filter(
                                skill ->
                                        SkillCatalog.canView(
                                                skill,
                                                account.userId(),
                                                access.manage(
                                                        account,
                                                        io.deepscope.advisor.access.AccessPoints
                                                                .SKILL_MANAGE)))
                        .isPresent()
                ? ResponseEntity.status(HttpStatus.FORBIDDEN).build()
                : ResponseEntity.notFound().build();
    }

    private Map<String, Object> summary(SkillCatalog.SkillRow skill, String userId, boolean admin)
            throws SQLException {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("skillId", skill.skillId());
        view.put("name", skill.name());
        view.put("description", skill.description());
        view.put("status", skill.status());
        view.put("origin", skill.origin());
        view.put("canEdit", SkillCatalog.canEdit(skill, userId, admin));
        List<String> agentIds = catalog.agentsUsing(skill.skillId());
        List<String> agentNames = new ArrayList<>(agentIds.size());
        for (String aid : agentIds) {
            String agentName =
                    agents.find(aid)
                            .map(AgentStore.AgentRow::name)
                            .filter(n -> !n.isBlank())
                            .orElse(aid);
            agentNames.add(agentName);
        }
        view.put("agents", agentNames);
        return view;
    }

    private static String extractInstructions(String markdown) {
        if (markdown == null) {
            return "";
        }
        String stripped = markdown.stripLeading();
        if (stripped.startsWith("---")) {
            int second = stripped.indexOf("---", 3);
            if (second > 0) {
                return stripped.substring(second + 3).strip();
            }
        }
        return stripped;
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value.replace("\"", "\\\"");
        return "\"" + safe + "\"";
    }

    private static String text(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        String value = body.get(key).toString();
        return value.isBlank() ? null : value;
    }

    private String ensureUniqueSkillName(String userId, String name) throws SQLException {
        Set<String> used = new HashSet<>();
        for (SkillCatalog.SkillRow s : catalog.list(userId, false)) {
            used.add(s.name());
        }
        return ensureUniqueSkillName(used, name);
    }

    private static String ensureUniqueSkillName(Set<String> usedNames, String name) {
        String baseName = name == null || name.isBlank() ? "custom_skill" : name.strip();
        if (!usedNames.contains(baseName)) {
            return baseName;
        }
        int count = 2;
        while (usedNames.contains(baseName + "_" + count)) {
            count++;
        }
        return baseName + "_" + count;
    }
}
