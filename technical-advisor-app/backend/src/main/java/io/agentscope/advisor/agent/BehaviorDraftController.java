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

import io.agentscope.advisor.auth.ApiAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Writes a model-drafted behavior document for an agent the caller may edit. */
@RestController
public class BehaviorDraftController {

    private final AgentStore agents;
    private final BehaviorDraftService drafts;
    private final AgentWorkspace workspace = new AgentWorkspace();

    public BehaviorDraftController(AgentStore agents, BehaviorDraftService drafts) {
        this.agents = agents;
        this.drafts = drafts;
    }

    @PostMapping("/api/advisor/agents/{agentId}/behavior/draft")
    public ResponseEntity<Map<String, String>> draft(
            @PathVariable String agentId, HttpServletRequest request)
            throws SQLException, IOException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        var agent =
                agents.find(agentId)
                        .filter(row -> AgentStore.canView(row, account.userId(), account.isAdmin()))
                        .orElse(null);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }
        if (!AgentStore.canEdit(agent, account.userId(), account.isAdmin())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String content = drafts.draft(agent.name(), agent.description());
        if (content.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        workspace.write(agent, "AGENTS.md", content, false);
        return ResponseEntity.ok(Map.of("content", content));
    }
}
