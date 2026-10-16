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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.advisor.auth.AccountStore;
import io.agentscope.advisor.session.SessionStore;
import io.agentscope.advisor.session.TimelineStore;
import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Permission-checked, FIFO resolution of visitor-approved subagent definitions. */
final class ProposalResolver {
    private final AgentStore agents;
    private final SessionStore sessions;
    private final TimelineStore timeline;
    private final ObjectMapper json;
    private final AgentWorkspace workspace;

    ProposalResolver(
            AgentStore agents,
            SessionStore sessions,
            TimelineStore timeline,
            ObjectMapper json,
            AgentWorkspace workspace) {
        this.agents = agents;
        this.sessions = sessions;
        this.timeline = timeline;
        this.json = json;
        this.workspace = workspace;
    }

    Map<String, Object> resolve(
            String sessionId, int seq, Map<String, Object> body, AccountStore.Account account)
            throws SQLException, IOException {
        synchronized (timeline) {
            var session =
                    sessions.find(sessionId)
                            .filter(row -> account.userId().equals(row.ownerUserId()))
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (session.agentId() == null)
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Free sessions cannot resolve proposals");
            var agent =
                    agents.find(session.agentId())
                            .filter(
                                    row ->
                                            AgentStore.canView(
                                                    row, account.userId(), account.isAdmin()))
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (!(body.get("approved") instanceof Boolean))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            boolean approved = Boolean.TRUE.equals(body.get("approved"));
            if (approved && !AgentStore.canEdit(agent, account.userId(), account.isAdmin()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            List<TimelineStore.TimelineEntry> pending = timeline.pendingProposals(sessionId);
            var proposal =
                    pending.stream()
                            .filter(entry -> entry.seq() == seq)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.CONFLICT,
                                                    "Proposal already resolved or missing"));
            Map<String, Object> payload =
                    json.readValue(proposal.payload(), new TypeReference<Map<String, Object>>() {});
            String name = text(payload, "name");
            for (var entry : pending) {
                if (entry.seq() >= seq) break;
                Map<String, Object> earlier =
                        json.readValue(
                                entry.payload(), new TypeReference<Map<String, Object>>() {});
                if (java.util.Objects.equals(name, text(earlier, "name")))
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Resolve the earlier proposal first");
            }
            Map<String, Object> resolution = new LinkedHashMap<>();
            resolution.put("name", name);
            resolution.put("approved", approved);
            if (approved) {
                String slug =
                        name == null
                                ? "subagent"
                                : name.toLowerCase(java.util.Locale.ROOT)
                                        .replaceAll("[^a-z0-9-]", "-")
                                        .replaceAll("^-+|-+$", "");
                if (slug.isBlank()) slug = "subagent";
                String file =
                        "subagents/"
                                + slug
                                + "-"
                                + UUID.randomUUID().toString().substring(0, 8)
                                + ".md";
                String content =
                        body.containsKey("content")
                                ? text(body, "content")
                                : text(payload, "content");
                workspace.write(agent, file, content, true);
                resolution.put("file", file);
                try {
                    timeline.append(sessionId, TimelineStore.PROPOSAL_RESOLVED, resolution);
                } catch (SQLException | RuntimeException error) {
                    workspace.delete(agent, file);
                    throw error;
                }
            } else {
                timeline.append(sessionId, TimelineStore.PROPOSAL_RESOLVED, resolution);
            }
            sessions.updateStatus(
                    sessionId,
                    pending.size() > 1
                            ? SessionStore.STATUS_AWAITING_VISITOR
                            : SessionStore.STATUS_IDLE);
            return resolution;
        }
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : value.toString();
    }
}
