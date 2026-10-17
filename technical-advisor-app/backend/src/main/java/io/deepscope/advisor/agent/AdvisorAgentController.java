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
package io.deepscope.advisor.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import io.deepscope.advisor.session.SessionStore;
import io.deepscope.advisor.session.TimelineStore;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.MsgRole;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.model.Model;
import io.deepscope.harness.agent.context.ContextModelCalls;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Agent management API (stage 4). Personal agents are visible to their owner (admins see all);
 * another visitor's personal agent is hidden on every operation (404), and a visible but
 * non-editable agent is rejected with 403.
 *
 * <p>Session routing lives here too: a session bound to an agent (lock mode) returns it directly,
 * an unbound session (free mode) is routed by a small model call over the visible agent catalog
 * for each turn; routing never changes a free session into a locked session.
 */
@RestController
public class AdvisorAgentController {

    private static final Logger log = LoggerFactory.getLogger(AdvisorAgentController.class);

    /** Upper bound for the free-mode routing call, far below the 10-minute run budget (NF1). */
    private static final Duration ROUTE_TIMEOUT = Duration.ofSeconds(30);

    private final AgentStore agents;
    private final AgentProvisioner provisioner;
    private final SessionStore sessions;
    private final TimelineStore timeline;
    private final Model model;
    private final ObjectMapper json;
    private final io.deepscope.advisor.access.AccessGuard access;
    private final AgentWorkspace workspace = new AgentWorkspace();
    private final SubagentDraftService subagentDrafts;
    private final io.deepscope.spring.boot.agui.mvc.AguiMvcController runtime;

    public AdvisorAgentController(
            AgentStore agents,
            AgentProvisioner provisioner,
            SessionStore sessions,
            TimelineStore timeline,
            Model model,
            ObjectMapper json,
            io.deepscope.advisor.access.AccessGuard access) {
        this(agents, provisioner, sessions, timeline, model, json, access, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdvisorAgentController(
            AgentStore agents,
            AgentProvisioner provisioner,
            SessionStore sessions,
            TimelineStore timeline,
            Model model,
            ObjectMapper json,
            io.deepscope.advisor.access.AccessGuard access,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
                    io.deepscope.spring.boot.agui.mvc.AguiMvcController runtime) {
        this.agents = agents;
        this.provisioner = provisioner;
        this.sessions = sessions;
        this.timeline = timeline;
        this.model = model;
        this.json = json;
        this.access = access;
        this.subagentDrafts = new SubagentDraftService(model);
        this.runtime = runtime;
    }

    @GetMapping("/api/advisor/agents")
    public List<Map<String, Object>> list(HttpServletRequest request)
            throws SQLException, IOException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentStore.AgentRow agent :
                agents.listVisible(
                        account.userId(),
                        access.seesAll(
                                account, io.deepscope.advisor.access.AccessPoints.AGENT_MANAGE),
                        request.getParameter("q"))) {
            out.add(agentView(agent, account));
        }
        return out;
    }

    @GetMapping("/api/advisor/agents/available")
    public List<Map<String, Object>> available(HttpServletRequest request) throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        if (request.getParameter("agentId") != null) {
            var selected = visibleAgent(request.getParameter("agentId"), account);
            return selected == null
                            || !selected.enabled()
                            || !account.userId().equals(selected.ownerUserId())
                    ? List.of()
                    : List.of(availableView(selected));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (var agent : agents.listAvailable(account.userId(), request.getParameter("q"))) {
            result.add(availableView(agent));
        }
        return result;
    }

    @GetMapping(value = "/api/advisor/agents/available", params = "page")
    public io.deepscope.advisor.db.PageResult<Map<String, Object>> availablePage(
            HttpServletRequest request) throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        return agents.pageAvailable(
                        account.userId(),
                        request.getParameter("q"),
                        io.deepscope.advisor.db.JdbcPage.page(request),
                        request.getParameter("size") == null
                                ? 5
                                : io.deepscope.advisor.db.JdbcPage.size(request))
                .map(AdvisorAgentController::availableView);
    }

    private static Map<String, Object> availableView(AgentStore.AgentRow agent) {
        return Map.of(
                "agentId",
                agent.agentId(),
                "name",
                agent.name(),
                "description",
                agent.description(),
                "origin",
                agent.origin());
    }

    @GetMapping("/api/advisor/agents/{agentId}")
    public ResponseEntity<Map<String, Object>> detail(
            @PathVariable String agentId, HttpServletRequest request)
            throws SQLException, IOException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        var agent = visibleAgent(agentId, account);
        return agent == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(agentView(agent, account));
    }

    @PostMapping("/api/advisor/agents")
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request)
            throws SQLException, IOException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        String name = text(body, "name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String copyId = text(body, "copyFromAgentId");
        AgentStore.AgentRow source = copyId == null ? null : visibleAgent(copyId, account);
        if (copyId != null && source == null) return ResponseEntity.notFound().build();
        AgentStore.AgentRow agent =
                agents.create(name, text(body, "description"), account.userId());
        try {
            if (source != null) workspace.copy(source, agent);
            provisioner.register(agent);
        } catch (IOException | RuntimeException error) {
            provisioner.unregister(agent.agentId());
            agents.delete(agent.agentId());
            throw error;
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(agentView(agent, account));
    }

    @PatchMapping("/api/advisor/agents/{agentId}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String agentId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException, IOException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        var agent = visibleAgent(agentId, account);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }
        if (!AgentStore.canEdit(
                agent,
                account.userId(),
                access.seesAll(account, io.deepscope.advisor.access.AccessPoints.AGENT_MANAGE))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String name = body != null && body.containsKey("name") ? text(body, "name") : agent.name();
        String description =
                body != null && body.containsKey("description")
                        ? text(body, "description")
                        : agent.description();
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (body != null
                && body.containsKey("enabled")
                && !(body.get("enabled") instanceof Boolean))
            return ResponseEntity.badRequest().build();
        boolean enabled =
                body != null && body.containsKey("enabled")
                        ? Boolean.TRUE.equals(body.get("enabled"))
                        : agent.enabled();
        agents.update(
                agentId, name.strip(), description == null ? "" : description.strip(), enabled);
        AgentStore.AgentRow updated = agents.find(agentId).orElseThrow();
        try {
            if (updated.enabled()) provisioner.register(updated);
            else provisioner.unregister(agentId);
        } catch (IOException | SQLException | RuntimeException error) {
            agents.update(agentId, agent.name(), agent.description(), agent.enabled());
            throw error;
        }
        return ResponseEntity.ok(agentView(updated, account));
    }

    @DeleteMapping("/api/advisor/agents/{agentId}")
    public ResponseEntity<Void> delete(@PathVariable String agentId, HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        var agent = visibleAgent(agentId, account);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }
        if (!AgentStore.canEdit(
                agent,
                account.userId(),
                access.seesAll(account, io.deepscope.advisor.access.AccessPoints.AGENT_MANAGE))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        provisioner.unregister(agentId);
        return agents.delete(agentId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/api/advisor/agents/{agentId}/tree")
    public Map<String, Object> tree(@PathVariable String agentId, HttpServletRequest request)
            throws SQLException, IOException {
        return Map.of("files", workspace.tree(requireAgent(agentId, request, false)));
    }

    @GetMapping("/api/advisor/agents/{agentId}/files")
    public Map<String, String> readFile(
            @PathVariable String agentId, @RequestParam String path, HttpServletRequest request)
            throws SQLException, IOException {
        return Map.of("content", workspace.read(requireAgent(agentId, request, false), path));
    }

    @GetMapping("/api/advisor/agents/{agentId}/subagents/template")
    public Map<String, String> subagentTemplate(
            @PathVariable String agentId, HttpServletRequest request) throws SQLException {
        requireAgent(agentId, request, false);
        return Map.of("content", AgentWorkspace.SUBAGENT_TEMPLATE);
    }

    /**
     * 用当前填写的名称（可选用途）生成子智能体定义供编辑器补齐；生成失败不写入任何文件。
     */
    @PostMapping("/api/advisor/agents/{agentId}/subagents/draft")
    public ResponseEntity<Map<String, String>> subagentDraft(
            @PathVariable String agentId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        requireAgent(agentId, request, true);
        String name = text(body, "name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "missing_name", "message", "请先填写子智能体名称"));
        }
        String content = subagentDrafts.draft(name, text(body, "purpose"));
        if (content.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("code", "draft_failed", "message", "模型没有写出子智能体定义"));
        }
        return ResponseEntity.ok(Map.of("content", content));
    }

    @PutMapping("/api/advisor/agents/{agentId}/files")
    public ResponseEntity<Void> saveFile(
            @PathVariable String agentId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException, IOException {
        var agent = requireAgent(agentId, request, true);
        workspace.write(agent, text(body, "path"), text(body, "content"), false);
        if (agent.enabled()) provisioner.register(agent);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/advisor/agents/{agentId}/files")
    public ResponseEntity<Void> createFile(
            @PathVariable String agentId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException, IOException {
        var agent = requireAgent(agentId, request, true);
        workspace.write(agent, text(body, "path"), text(body, "content"), true);
        if (agent.enabled()) provisioner.register(agent);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/api/advisor/agents/{agentId}/files")
    public ResponseEntity<Void> deleteFile(
            @PathVariable String agentId, @RequestParam String path, HttpServletRequest request)
            throws SQLException, IOException {
        var agent = requireAgent(agentId, request, true);
        workspace.delete(agent, path);
        if (agent.enabled()) provisioner.register(agent);
        return ResponseEntity.noContent().build();
    }

    private AgentStore.AgentRow requireAgent(String id, HttpServletRequest request, boolean edit)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        var agent = visibleAgent(id, account);
        if (agent == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (edit
                && !AgentStore.canEdit(
                        agent,
                        account.userId(),
                        access.seesAll(
                                account, io.deepscope.advisor.access.AccessPoints.AGENT_MANAGE)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return agent;
    }

    @PostMapping("/api/advisor/sessions/{sessionId}/proposals/{seq}/resolve")
    public Map<String, Object> resolveProposal(
            @PathVariable String sessionId,
            @PathVariable int seq,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException, IOException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        return new ProposalResolver(agents, sessions, timeline, json, workspace)
                .resolve(sessionId, seq, body, account);
    }

    private static Map<String, Object> routeView(AgentStore.AgentRow agent, String reason) {
        return Map.of(
                "mode",
                "agent",
                "agentId",
                agent.agentId(),
                "name",
                agent.name(),
                "isLocked",
                false,
                "reason",
                reason);
    }

    private static Map<String, Object> lockedRouteView(AgentStore.AgentRow agent, String reason) {
        var view = new LinkedHashMap<String, Object>(routeView(agent, reason));
        view.put("isLocked", true);
        return view;
    }

    /**
     * Routes a session to the agent that should answer it. Lock mode (session already bound)
     * returns the bound agent without any model call; free mode asks the model to pick from the
     * visitor's visible agents on every turn, recording the decision without binding the session.
     */
    @PostMapping("/api/advisor/sessions/{sessionId}/route")
    public ResponseEntity<Map<String, Object>> route(
            @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        var session =
                sessions.find(sessionId)
                        .filter(row -> account.userId().equals(row.ownerUserId()))
                        .orElse(null);
        if (session == null) {
            log.warn("Session routing rejected: session={}, reason=session_unavailable", sessionId);
            return ResponseEntity.notFound().build();
        }
        if (body != null && body.containsKey("selectedAgentId")) {
            Object choice = body.get("selectedAgentId");
            if (choice != null && (!(choice instanceof String id) || id.isBlank()))
                return ResponseEntity.badRequest().build();
            if (choice instanceof String selectedId) {
                var selected = visibleAgent(selectedId, account);
                if (selected == null
                        || !selected.enabled()
                        || !account.userId().equals(selected.ownerUserId()))
                    return ResponseEntity.notFound().build();
                bindWhenIdle(sessionId, session.agentId(), selected.agentId());
                var result = lockedRouteView(selected, "访客选择智能体，本轮仅使用其挂载能力");
                timeline.append(sessionId, "agent_route", result);
                return ResponseEntity.ok(result);
            }
            bindWhenIdle(sessionId, session.agentId(), null);
            session = sessions.find(sessionId).orElseThrow();
        }
        if (session.agentId() != null) {
            var bound = visibleAgent(session.agentId(), account);
            if (bound != null && bound.enabled() && account.userId().equals(bound.ownerUserId())) {
                return ResponseEntity.ok(lockedRouteView(bound, "会话已锁定该智能体"));
            }
            if (body != null && Boolean.TRUE.equals(body.get("allowBasic"))) {
                bindWhenIdle(sessionId, session.agentId(), null);
                return basicRoute(sessionId, "bound_agent_unavailable");
            }
            log.warn(
                    "Session routing paused: session={}, reason=bound_agent_unavailable",
                    sessionId);
            sessions.updateStatus(sessionId, SessionStore.STATUS_INTERRUPTED);
            return ResponseEntity.ok(
                    Map.of(
                            "mode",
                            "unavailable",
                            "agentId",
                            session.agentId(),
                            "name",
                            "",
                            "reason",
                            "当前智能体不可用",
                            "reasonCode",
                            "bound_agent_unavailable"));
        }
        List<AgentStore.AgentRow> candidates = agents.listAvailable(account.userId(), null);
        log.info("Session routing candidates: session={}, count={}", sessionId, candidates.size());
        if (candidates.isEmpty()) {
            return basicRoute(sessionId, "no_visible_agents");
        }
        AgentStore.AgentRow chosen = chooseAgent(candidates, routingQuestion(sessionId, body));
        Map<String, Object> result = routeView(chosen, "根据本轮问题与可见智能体的职责选择；选择失败时使用第一个候选");
        timeline.append(sessionId, "agent_route", result);
        return ResponseEntity.ok(result);
    }

    private void bindWhenIdle(String sessionId, String current, String selected)
            throws SQLException {
        if (java.util.Objects.equals(current, selected)) return;
        if (runtime == null) {
            sessions.bindAgent(sessionId, selected);
            return;
        }
        var failure = new java.util.concurrent.atomic.AtomicReference<SQLException>();
        boolean applied =
                runtime.withIdleThread(
                        sessionId,
                        () -> {
                            try {
                                sessions.bindAgent(sessionId, selected);
                            } catch (SQLException error) {
                                failure.set(error);
                            }
                        });
        if (failure.get() != null) throw failure.get();
        if (!applied)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Agent changes apply after the active run or confirmation has finished");
    }

    private ResponseEntity<Map<String, Object>> basicRoute(String sessionId, String reason)
            throws SQLException {
        var result =
                Map.<String, Object>of(
                        "mode",
                        "basic",
                        "agentId",
                        BasicChatRuntime.AGENT_ID,
                        "name",
                        "基础对话",
                        "reason",
                        "使用基础对话",
                        "reasonCode",
                        reason);
        timeline.append(sessionId, "agent_route", result);
        log.info("Session routing degraded: session={}, reason={}", sessionId, reason);
        return ResponseEntity.ok(result);
    }

    /**
     * Free-mode routing: a budget-only model call turns the latest visitor input into one agent
     * id from the catalog. Any failure falls back to the first candidate so routing stays total.
     */
    private AgentStore.AgentRow chooseAgent(List<AgentStore.AgentRow> candidates, String question) {
        if (candidates.size() == 1 || question == null || question.isBlank()) {
            return builtinFirst(candidates);
        }
        try {
            String answer =
                    ContextModelCalls.auxiliary(model, chooserMessages(candidates, question))
                            .reduce(
                                    new StringBuilder(),
                                    (buffer, response) -> {
                                        List<ContentBlock> blocks = response.getContent();
                                        if (blocks != null) {
                                            for (ContentBlock block : blocks) {
                                                if (block instanceof TextBlock text
                                                        && text.getText() != null) {
                                                    buffer.append(text.getText());
                                                }
                                            }
                                        }
                                        return buffer;
                                    })
                            .map(StringBuilder::toString)
                            .block(ROUTE_TIMEOUT);
            if (answer != null) {
                for (AgentStore.AgentRow candidate : candidates) {
                    if (answer.strip().equals(candidate.agentId())) {
                        return candidate;
                    }
                }
            }
        } catch (RuntimeException error) {
            log.warn("Free-mode agent routing fell back to the first candidate: {}", error);
        }
        return builtinFirst(candidates);
    }

    private static List<Msg> chooserMessages(
            List<AgentStore.AgentRow> candidates, String question) {
        StringBuilder catalog = new StringBuilder();
        for (AgentStore.AgentRow candidate : candidates) {
            catalog.append("\n- ")
                    .append(candidate.agentId())
                    .append("：")
                    .append(candidate.name())
                    .append("——")
                    .append(candidate.description());
        }
        return List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .content(
                                TextBlock.builder()
                                        .text(
                                                "你是会话路由器。根据访客问题从下列智能体中选出最合适的一个，"
                                                        + "只输出该智能体的 id，不要任何解释。\n候选智能体："
                                                        + catalog)
                                        .build())
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(question).build())
                        .build());
    }

    /**
     * The routing question: the message posted with the request, else the latest visitor message
     * already in the timeline.
     */
    private String routingQuestion(String sessionId, Map<String, Object> body) throws SQLException {
        String posted = text(body, "text");
        if (posted != null && !posted.isBlank()) {
            return posted;
        }
        String latest = null;
        for (TimelineStore.TimelineEntry entry : timeline.listEntries(sessionId)) {
            if (TimelineStore.USER_MESSAGE.equals(entry.kind())) {
                latest = entryText(entry.payload());
            }
        }
        return latest;
    }

    private String entryText(String payload) {
        try {
            Object value =
                    json.readValue(payload, new TypeReference<Map<String, Object>>() {})
                            .get("text");
            return value == null ? null : value.toString();
        } catch (IOException error) {
            return null;
        }
    }

    /** Falls back to the first candidate when the model call fails or answers off-catalog. */
    private static AgentStore.AgentRow builtinFirst(List<AgentStore.AgentRow> candidates) {
        return candidates.get(0);
    }

    /** Finds an agent the account may see; invisible ones behave like missing ones. */
    private AgentStore.AgentRow visibleAgent(String agentId, AccountStore.Account account)
            throws SQLException {
        boolean manage =
                access.seesAll(account, io.deepscope.advisor.access.AccessPoints.AGENT_MANAGE);
        return agents.find(agentId)
                .filter(agent -> AgentStore.canView(agent, account.userId(), manage))
                .orElse(null);
    }

    private static String text(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value == null ? null : value.toString();
    }

    private Map<String, Object> agentView(AgentStore.AgentRow agent, AccountStore.Account account)
            throws IOException, SQLException {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("agentId", agent.agentId());
        view.put("name", agent.name());
        view.put("description", agent.description());
        view.put("origin", agent.origin());
        view.put("enabled", agent.enabled());
        view.put("ownerUserId", agent.ownerUserId());
        view.put("createdAt", agent.createdAt());
        view.put("updatedAt", agent.updatedAt());
        view.put("mine", account.userId().equals(agent.ownerUserId()));
        view.put(
                "canEdit",
                access.allowed(account, "agent.edit")
                        && AgentStore.canEdit(
                                agent,
                                account.userId(),
                                access.seesAll(
                                        account,
                                        io.deepscope.advisor.access.AccessPoints.AGENT_MANAGE)));
        Map<String, Object> sections = workspace.sections(agent, provisioner);
        view.put("sections", sections);
        view.put(
                "counts",
                Map.of(
                        "skills",
                        ((List<?>) sections.get("skills")).size(),
                        "subagents",
                        ((List<?>) sections.get("subagents")).size()));
        return view;
    }
}
