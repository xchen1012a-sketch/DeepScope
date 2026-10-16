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

import io.agentscope.advisor.access.AccessGuard;
import io.agentscope.advisor.access.AccessPoints;
import io.agentscope.advisor.auth.ApiAuthenticationFilter;
import io.agentscope.advisor.db.JdbcPage;
import io.agentscope.advisor.db.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class AgentOverviewController {
    private final AgentOverviewStore store;
    private final AccessGuard access;
    private final AgentCreationService creation;

    public AgentOverviewController(
            AgentOverviewStore store, AccessGuard access, AgentCreationService creation) {
        this.store = store;
        this.access = access;
        this.creation = creation;
    }

    @GetMapping("/api/advisor/agent-overview")
    public PageResult<java.util.Map<String, Object>> page(HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        boolean allAgents = access.seesAll(account, AccessPoints.AGENT_MANAGE);
        boolean allDrafts = creation.seesAll(account);
        boolean manage = access.allowed(account, AccessPoints.AGENT_MANAGE);
        boolean edit = manage || access.allowed(account, "agent.edit");
        boolean delete = manage || access.allowed(account, "agent.delete");
        boolean create = manage || access.allowed(account, "agent.create");
        return store.page(
                        account.userId(),
                        allAgents,
                        allDrafts,
                        request.getParameter("q"),
                        JdbcPage.page(request),
                        JdbcPage.size(request))
                .map(
                        item -> {
                            boolean owner =
                                    account.userId().equals(item.ownerUserId())
                                            || (item.draft() ? allDrafts : allAgents);
                            return java.util.Map.<String, Object>of(
                                    "id",
                                    item.id(),
                                    "name",
                                    item.name(),
                                    "description",
                                    item.description(),
                                    "draft",
                                    item.draft(),
                                    "enabled",
                                    item.enabled(),
                                    "canEdit",
                                    owner && (item.draft() ? create : edit),
                                    "canDelete",
                                    owner && (item.draft() ? create : delete));
                        });
    }
}
