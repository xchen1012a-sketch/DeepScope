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
package io.deepscope.advisor.access;

import io.deepscope.advisor.agent.AgentCreationDraftStore;
import io.deepscope.advisor.agent.AgentStore;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.SQLException;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Module authorization is independent of per-resource ownership checks in controllers. */
@Configuration
public class WorkspaceAuthorization implements WebMvcConfigurer, HandlerInterceptor {
    private static final String OWNER = WorkspaceAuthorization.class.getName() + ".owner";
    private final AccessGuard access;
    private final AgentStore agents;
    private final AgentCreationDraftStore drafts;

    public WorkspaceAuthorization(
            AccessGuard access, AgentStore agents, AgentCreationDraftStore drafts) {
        this.access = access;
        this.agents = agents;
        this.drafts = drafts;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/advisor/**");
    }

    public static boolean selection(HttpServletRequest request) {
        return request.getAttribute(OWNER) instanceof String;
    }

    public static String owner(HttpServletRequest request, String fallback) {
        return selection(request) ? (String) request.getAttribute(OWNER) : fallback;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws SQLException {
        String path = request.getServletPath();
        if (path.isEmpty())
            path = request.getRequestURI().substring(request.getContextPath().length());
        boolean read = "GET".equals(request.getMethod());
        List<String> required;
        if (path.equals("/api/advisor/agents/available")) {
            required = List.of(AccessPoints.CHAT);
        } else if (read
                && "selection".equals(request.getParameter("purpose"))
                && List.of(
                                "/api/advisor/tools",
                                "/api/advisor/mcp-servers",
                                "/api/advisor/skills",
                                "/api/advisor/knowledge")
                        .contains(path)) {
            require(request, List.of(AccessPoints.AGENT_MANAGE, "agent.create", "agent.edit"));
            selectionOwner(request);
            return true;
        } else if (path.equals("/api/advisor/agent-overview")
                || path.startsWith("/api/advisor/agents")
                || path.startsWith("/api/advisor/agent-creation-drafts")) {
            required =
                    read
                            ? List.of(AccessPoints.AGENT_VIEW, AccessPoints.AGENT_MANAGE)
                            : List.of(
                                    AccessPoints.AGENT_MANAGE,
                                    AccessPoints.operation(request, "agent"));
        } else if (path.startsWith("/api/advisor/tools")
                || path.startsWith("/api/advisor/mcp-servers")
                || path.startsWith("/api/advisor/tool-")) {
            required = List.of(AccessPoints.TOOL_MANAGE, AccessPoints.operation(request, "tool"));
        } else if (path.startsWith("/api/advisor/skills")) {
            required = List.of(AccessPoints.SKILL_MANAGE, AccessPoints.operation(request, "skill"));
        } else if (path.startsWith("/api/advisor/knowledge")) {
            required =
                    read
                                    || path.endsWith("/search")
                                    || path.matches(
                                            "/api/advisor/knowledge/[^/]+/documents/[^/]+/pages/[0-9]+/verify")
                            ? List.of(AccessPoints.KNOWLEDGE_VIEW, AccessPoints.KNOWLEDGE_MANAGE)
                            : List.of(
                                    AccessPoints.KNOWLEDGE_MANAGE,
                                    AccessPoints.operation(request, "knowledge"));
        } else {
            return true;
        }
        require(request, required);
        if (!read
                && path.startsWith("/api/advisor/agent-creation-drafts/")
                && !path.endsWith("/activations")) {
            String draftId =
                    path.substring("/api/advisor/agent-creation-drafts/".length()).split("/")[0];
            var account = ApiAuthenticationFilter.account(request).orElseThrow();
            drafts.findById(draftId)
                    .filter(
                            row ->
                                    AgentCreationDraftStore.canEdit(
                                            row,
                                            account.userId(),
                                            access.manage(account, AccessPoints.AGENT_MANAGE)))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            drafts.requireEditable(draftId);
        }
        return true;
    }

    private void require(HttpServletRequest request, List<String> permissions) throws SQLException {
        var account =
                ApiAuthenticationFilter.account(request)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        for (String permission : permissions) if (access.allowed(account, permission)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "permission_denied");
    }

    /** Selectors page through the same routes, so pagination parameters stay allowed here. */
    private void selectionOwner(HttpServletRequest request) throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        String agentId = request.getParameter("agentId");
        String draftId = request.getParameter("draftId");
        if (agentId != null && draftId != null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String owner = account.userId();
        boolean all = access.seesAll(account, AccessPoints.AGENT_MANAGE);
        if (agentId != null) {
            owner =
                    agents.find(agentId)
                            .filter(row -> AgentStore.canEdit(row, account.userId(), all))
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
                            .ownerUserId();
        } else if (draftId != null) {
            owner =
                    drafts.findById(draftId)
                            .filter(
                                    row ->
                                            AgentCreationDraftStore.canEdit(
                                                    row, account.userId(), all))
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
                            .ownerUserId();
        }
        if ((request.getRequestURI().endsWith("/tools")
                        || request.getRequestURI().endsWith("/mcp-servers")
                        || request.getRequestURI().endsWith("/skills"))
                && !account.userId().equals(owner)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        request.setAttribute(OWNER, owner);
    }
}
