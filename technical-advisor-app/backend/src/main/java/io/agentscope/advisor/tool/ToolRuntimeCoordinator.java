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
package io.agentscope.advisor.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.advisor.agent.AgentProvisioner;
import io.agentscope.advisor.agent.AgentStore;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates targeted agent updates upon tool/MCP lifecycle changes, runtime leases for active
 * sessions, and safe retirement of old connections once all leases drop to zero.
 */
public class ToolRuntimeCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ToolRuntimeCoordinator.class);

    private final ToolCatalog catalog;
    private final AgentStore agents;
    private final AgentProvisioner provisioner;
    private final DataSource dataSource;
    private final ObjectMapper json;

    private record RuntimeLease(String agentId, String runId) {}

    private final ConcurrentHashMap<String, RuntimeLease> activeLeases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> admittedRuns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> agentActiveSessions =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<McpClientWrapper>> retiringConnections =
            new ConcurrentHashMap<>();

    public record ApplyResult(
            String requestId, String status, List<String> impactedAgentIds, String errorMessage) {
        public static ApplyResult success(String requestId, List<String> impactedAgentIds) {
            return new ApplyResult(requestId, "applied", impactedAgentIds, null);
        }

        public static ApplyResult failed(
                String requestId, List<String> impactedAgentIds, String errorMessage) {
            return new ApplyResult(requestId, "apply_failed", impactedAgentIds, errorMessage);
        }
    }

    public ToolRuntimeCoordinator(
            ToolCatalog catalog,
            AgentStore agents,
            AgentProvisioner provisioner,
            DataSource dataSource,
            ObjectMapper json) {
        this.catalog = catalog;
        this.agents = agents;
        this.provisioner = provisioner;
        this.dataSource = dataSource;
        this.json = json != null ? json : new ObjectMapper();
        if (provisioner != null) {
            provisioner.setCoordinator(this);
        }
    }

    public Set<String> findImpactedAgentIds(
            Collection<String> toolIds, Collection<String> serverIds) throws SQLException {
        Set<String> set = new LinkedHashSet<>();
        if (toolIds != null) {
            for (String toolId : toolIds) {
                set.addAll(catalog.impactedAgentIdsForTool(toolId));
            }
        }
        if (serverIds != null) {
            for (String serverId : serverIds) {
                set.addAll(catalog.impactedAgentIdsForServer(serverId));
            }
        }
        return set;
    }

    public void acquireLease(String sessionId, String agentId) {
        acquireLease(sessionId, agentId, null);
    }

    public synchronized void acquireLease(String sessionId, String agentId, String runId) {
        if (sessionId == null || agentId == null) return;
        if (runId != null && !runId.equals(admittedRuns.get(sessionId)))
            throw new IllegalStateException("Run is no longer admitted");
        RuntimeLease previous = activeLeases.get(sessionId);
        if (previous != null && !previous.agentId().equals(agentId)) releaseLease(sessionId);
        activeLeases.put(sessionId, new RuntimeLease(agentId, runId));
        agentActiveSessions
                .computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet())
                .add(sessionId);
        log.debug("Acquired runtime lease for session {} on agent {}", sessionId, agentId);
    }

    public synchronized void claimRun(String sessionId, String runId) {
        if (sessionId == null || runId == null) return;
        RuntimeLease active = activeLeases.get(sessionId);
        if (active != null && !java.util.Objects.equals(active.runId(), runId))
            throw new IllegalStateException("Thread already has an active run");
        admittedRuns.put(sessionId, runId);
    }

    public synchronized void releaseLease(String sessionId, String runId) {
        if (sessionId == null) return;
        RuntimeLease lease = activeLeases.get(sessionId);
        if (lease != null && java.util.Objects.equals(lease.runId(), runId))
            releaseLease(sessionId);
    }

    public synchronized void releaseLease(String sessionId) {
        if (sessionId == null) return;
        RuntimeLease lease = activeLeases.remove(sessionId);
        if (lease != null) {
            String agentId = lease.agentId();
            Set<String> sessions = agentActiveSessions.get(agentId);
            if (sessions != null) {
                sessions.remove(sessionId);
                if (sessions.isEmpty()) {
                    agentActiveSessions.remove(agentId);
                    closeRetiringConnections(agentId);
                }
            }
            log.debug("Released runtime lease for session {} on agent {}", sessionId, agentId);
        }
    }

    public int activeLeaseCount(String agentId) {
        Set<String> sessions = agentActiveSessions.get(agentId);
        return sessions == null ? 0 : sessions.size();
    }

    public boolean hasActiveLeases(String agentId) {
        return activeLeaseCount(agentId) > 0;
    }

    public void retireOrClose(String agentId, List<McpClientWrapper> connections) {
        if (connections == null || connections.isEmpty()) return;
        if (hasActiveLeases(agentId)) {
            retiringConnections
                    .computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>())
                    .addAll(connections);
            log.info(
                    "Agent '{}' has active lease(s); queued {} connection(s) for retirement",
                    agentId,
                    connections.size());
        } else {
            closeAll(connections);
        }
    }

    public void closeRetiringConnections(String agentId) {
        List<McpClientWrapper> list = retiringConnections.remove(agentId);
        if (list != null && !list.isEmpty()) {
            log.info(
                    "Closing {} retired connection(s) for agent '{}' now that leases are 0",
                    list.size(),
                    agentId);
            closeAll(list);
        }
    }

    public ApplyResult applyChanges(
            String ownerUserId,
            String requestId,
            Collection<String> toolIds,
            Collection<String> serverIds)
            throws SQLException {
        Set<String> impacted = findImpactedAgentIds(toolIds, serverIds);
        List<String> impactedList = new ArrayList<>(impacted);

        if (requestId != null && dataSource != null) {
            recordChange(ownerUserId, requestId, "running", impactedList, null);
        }

        if (impactedList.isEmpty()) {
            if (requestId != null && dataSource != null) {
                recordChange(ownerUserId, requestId, "applied", impactedList, null);
            }
            return ApplyResult.success(requestId, impactedList);
        }

        for (String agentId : impactedList) {
            var agentOpt = agents.find(agentId);
            if (agentOpt.isEmpty() || !agentOpt.get().enabled()) continue;
            try {
                AgentProvisioner.PreparedAgent prepared = provisioner.prepare(agentOpt.get());
                List<McpClientWrapper> previous = provisioner.commit(agentId, prepared);
                retireOrClose(agentId, previous);
            } catch (Exception ex) {
                log.error("Failed to build runtime version for agent '{}'", agentId, ex);
                String errMsg =
                        "配置已保存，运行更新失败: "
                                + (ex.getMessage() == null
                                        ? ex.getClass().getSimpleName()
                                        : ex.getMessage());
                if (requestId != null && dataSource != null) {
                    recordChange(ownerUserId, requestId, "apply_failed", impactedList, errMsg);
                }
                return ApplyResult.failed(requestId, impactedList, errMsg);
            }
        }

        if (requestId != null && dataSource != null) {
            recordChange(ownerUserId, requestId, "applied", impactedList, null);
        }
        return ApplyResult.success(requestId, impactedList);
    }

    public ApplyResult retryApply(String ownerUserId, String requestId) throws Exception {
        if (dataSource == null) {
            return ApplyResult.success(requestId, List.of());
        }
        ChangeRecord record = findChangeRecord(ownerUserId, requestId);
        if (record == null) {
            throw new ToolProblem(
                    404, "request_not_found", "No tool change request found with id: " + requestId);
        }
        if ("applied".equals(record.status())) {
            return ApplyResult.success(requestId, record.impactedAgentIds());
        }

        List<String> impactedList = record.impactedAgentIds();
        for (String agentId : impactedList) {
            var agentOpt = agents.find(agentId);
            if (agentOpt.isEmpty() || !agentOpt.get().enabled()) continue;
            try {
                AgentProvisioner.PreparedAgent prepared = provisioner.prepare(agentOpt.get());
                List<McpClientWrapper> previous = provisioner.commit(agentId, prepared);
                retireOrClose(agentId, previous);
            } catch (Exception ex) {
                log.error("Retry build runtime version for agent '{}' failed", agentId, ex);
                String errMsg =
                        "配置已保存，运行更新失败: "
                                + (ex.getMessage() == null
                                        ? ex.getClass().getSimpleName()
                                        : ex.getMessage());
                recordChange(ownerUserId, requestId, "apply_failed", impactedList, errMsg);
                return ApplyResult.failed(requestId, impactedList, errMsg);
            }
        }

        recordChange(ownerUserId, requestId, "applied", impactedList, null);
        return ApplyResult.success(requestId, impactedList);
    }

    private void recordChange(
            String ownerUserId,
            String requestId,
            String status,
            List<String> impactedAgentIds,
            String errorMessage) {
        if (dataSource == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("status", status);
        data.put("impactedAgentIds", impactedAgentIds);
        if (errorMessage != null) data.put("errorMessage", errorMessage);
        try {
            String jsonStr = json.writeValueAsString(data);
            try (Connection conn = dataSource.getConnection()) {
                String key = "change:" + requestId;
                long now = System.currentTimeMillis();
                try (PreparedStatement update =
                        conn.prepareStatement(
                                "UPDATE advisor_tool_change_requests SET result_json=?,"
                                    + " created_at=? WHERE owner_user_id=? AND request_key=?")) {
                    update.setString(1, jsonStr);
                    update.setLong(2, now);
                    update.setString(3, ownerUserId);
                    update.setString(4, key);
                    int rows = update.executeUpdate();
                    if (rows == 0) {
                        try (PreparedStatement insert =
                                conn.prepareStatement(
                                        "INSERT INTO advisor_tool_change_requests (owner_user_id,"
                                                + " request_key, digest, result_json, created_at)"
                                                + " VALUES (?, ?, ?, ?, ?)")) {
                            insert.setString(1, ownerUserId);
                            insert.setString(2, key);
                            insert.setString(3, "change");
                            insert.setString(4, jsonStr);
                            insert.setLong(5, now);
                            insert.executeUpdate();
                        } catch (SQLException ex) {
                            if (!"23505".equals(ex.getSQLState())) {
                                log.warn("Failed to insert tool change record", ex);
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to record change in advisor_tool_change_requests", ex);
        }
    }

    private ChangeRecord findChangeRecord(String ownerUserId, String requestId) {
        if (dataSource == null) return null;
        String key = "change:" + requestId;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "SELECT result_json FROM advisor_tool_change_requests WHERE"
                                        + " owner_user_id=? AND request_key=?")) {
            ps.setString(1, ownerUserId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String jsonStr = rs.getString(1);
                    Map<String, Object> map = json.readValue(jsonStr, new TypeReference<>() {});
                    String status = (String) map.get("status");
                    @SuppressWarnings("unchecked")
                    List<String> impacted =
                            (List<String>) map.getOrDefault("impactedAgentIds", List.of());
                    String err = (String) map.get("errorMessage");
                    return new ChangeRecord(requestId, status, impacted, err);
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to query tool change record", ex);
        }
        return null;
    }

    private record ChangeRecord(
            String requestId, String status, List<String> impactedAgentIds, String errorMessage) {}

    private static void closeAll(List<McpClientWrapper> list) {
        for (McpClientWrapper client : list) {
            try {
                client.close();
            } catch (Exception ex) {
                log.warn("Error closing McpClientWrapper", ex);
            }
        }
    }
}
