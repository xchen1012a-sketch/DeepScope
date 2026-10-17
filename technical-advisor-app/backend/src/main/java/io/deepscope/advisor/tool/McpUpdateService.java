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
package io.deepscope.advisor.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages MCP service synchronization lifecycle: refresh diff computation (added, changed, removed),
 * pending diff persistence, fingerprint verification, and atomic change application.
 */
public final class McpUpdateService {
    private final ToolCatalog catalog;
    private final McpToolSource mcp;
    private final ObjectMapper json;

    public McpUpdateService(
            ToolCatalog catalog, McpToolSource mcp, ObjectMapper advisorObjectMapper) {
        this.catalog = catalog;
        this.mcp = mcp;
        this.json = advisorObjectMapper;
    }

    public record ChangedTool(
            String toolId,
            String name,
            String oldDescription,
            String newDescription,
            String oldInputSchema,
            String newInputSchema,
            String permission,
            long revision) {}

    public record RemovedTool(
            String toolId,
            String name,
            String description,
            String inputSchema,
            int impactedAgentCount,
            Long revision) {
        public RemovedTool(
                String toolId,
                String name,
                String description,
                String inputSchema,
                int impactedAgentCount) {
            this(toolId, name, description, inputSchema, impactedAgentCount, null);
        }
    }

    public record McpDiff(
            String serverId,
            String fingerprint,
            List<ToolCatalog.DiscoveredTool> added,
            List<ChangedTool> changed,
            List<RemovedTool> removed,
            long generatedAt) {
        public boolean hasChanges() {
            return !added.isEmpty() || !changed.isEmpty() || !removed.isEmpty();
        }
    }

    public record AcceptedAdded(String name, String permission) {}

    public record AcceptedChanged(String toolId, String name, Boolean apply) {}

    public record AcceptedRemoved(String toolId, String name, Boolean confirm) {}

    public record AcceptedChanges(
            List<AcceptedAdded> added,
            List<AcceptedChanged> changed,
            List<AcceptedRemoved> removed) {}

    public record ApproveRequest(
            String expectedFingerprint,
            List<Map<String, Object>> tools,
            AcceptedChanges acceptedChanges) {}

    public McpDiff refresh(ToolCatalog.ServerRow server) throws Exception {
        McpEndpointParser.McpEndpoint endpoint = catalog.endpointOf(server);
        List<ToolCatalog.DiscoveredTool> live;
        try {
            live = mcp.list(endpoint);
            catalog.markServer(server.serverId(), "connected", "");
        } catch (Exception error) {
            catalog.markServer(server.serverId(), "error", error.getMessage());
            throw error;
        }

        String fingerprint = computeFingerprint(live);
        List<ToolCatalog.ToolRow> existing = catalog.toolsOfServer(server.serverId());
        Map<String, ToolCatalog.ToolRow> existingByName = new LinkedHashMap<>();
        for (ToolCatalog.ToolRow t : existing) {
            existingByName.put(t.name(), t);
        }
        Map<String, ToolCatalog.DiscoveredTool> liveByName = new LinkedHashMap<>();
        for (ToolCatalog.DiscoveredTool t : live) {
            liveByName.put(t.name(), t);
        }

        List<ToolCatalog.DiscoveredTool> added = new ArrayList<>();
        for (ToolCatalog.DiscoveredTool t : live) {
            if (!existingByName.containsKey(t.name())) {
                added.add(t);
            }
        }

        List<ChangedTool> changed = new ArrayList<>();
        for (ToolCatalog.DiscoveredTool t : live) {
            if (existingByName.containsKey(t.name())) {
                ToolCatalog.ToolRow oldTool = existingByName.get(t.name());
                boolean descChanged =
                        !Objects.equals(
                                oldTool.description() == null ? "" : oldTool.description().strip(),
                                t.description() == null ? "" : t.description().strip());
                boolean schemaChanged =
                        !Objects.equals(
                                normalizeJson(oldTool.inputSchema()),
                                normalizeJson(t.inputSchema()));
                if (descChanged || schemaChanged) {
                    changed.add(
                            new ChangedTool(
                                    oldTool.toolId(),
                                    t.name(),
                                    oldTool.description(),
                                    t.description(),
                                    oldTool.inputSchema(),
                                    t.inputSchema(),
                                    oldTool.permission(),
                                    oldTool.revision()));
                }
            }
        }

        List<RemovedTool> removed = new ArrayList<>();
        for (ToolCatalog.ToolRow oldTool : existing) {
            if (!liveByName.containsKey(oldTool.name())) {
                int impactedCount = catalog.impactedAgentCountForTool(oldTool.toolId());
                removed.add(
                        new RemovedTool(
                                oldTool.toolId(),
                                oldTool.name(),
                                oldTool.description(),
                                oldTool.inputSchema(),
                                impactedCount,
                                oldTool.revision()));
            }
        }

        McpDiff diff =
                new McpDiff(
                        server.serverId(),
                        fingerprint,
                        added,
                        changed,
                        removed,
                        System.currentTimeMillis());

        try {
            catalog.updateServerPendingJson(server.serverId(), json.writeValueAsString(diff));
        } catch (SQLException | JsonProcessingException error) {
            // Log or propagate
        }
        return diff;
    }

    public Optional<McpDiff> pendingDiff(ToolCatalog.ServerRow server) {
        if (server.pendingJson() == null
                || server.pendingJson().isBlank()
                || "[]".equals(server.pendingJson().strip())) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(server.pendingJson(), McpDiff.class));
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    public void apply(ToolCatalog.ServerRow server, ApproveRequest request) throws Exception {
        McpEndpointParser.McpEndpoint endpoint = catalog.endpointOf(server);
        List<ToolCatalog.DiscoveredTool> live;
        try {
            live = mcp.list(endpoint);
            catalog.markServer(server.serverId(), "connected", "");
        } catch (Exception error) {
            catalog.markServer(server.serverId(), "error", error.getMessage());
            throw error;
        }

        String liveFingerprint = computeFingerprint(live);
        if (request.expectedFingerprint() != null && !request.expectedFingerprint().isBlank()) {
            if (!request.expectedFingerprint().equals(liveFingerprint)) {
                throw new ToolProblem(
                        409,
                        "mcp_changed",
                        "Remote MCP service definitions have changed since refresh. Please review"
                                + " again.");
            }
        }

        Map<String, ToolCatalog.DiscoveredTool> liveByName = new LinkedHashMap<>();
        for (ToolCatalog.DiscoveredTool t : live) {
            liveByName.put(t.name(), t);
        }

        try (Connection connection = catalog.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                validateChoices(connection, server, request, liveFingerprint);
                if (request.acceptedChanges() != null) {
                    if (request.acceptedChanges().added() != null) {
                        for (AcceptedAdded addChoice : request.acceptedChanges().added()) {
                            ToolCatalog.DiscoveredTool discovered =
                                    liveByName.get(addChoice.name());
                            if (discovered != null) {
                                String perm =
                                        addChoice.permission() == null
                                                ? ToolCatalog.PERMISSION_ASK
                                                : addChoice.permission();
                                catalog.insertTool(
                                        connection,
                                        new ToolImport.ImportedTool(
                                                discovered.name(),
                                                discovered.description(),
                                                discovered.inputSchema(),
                                                ToolCatalog.ORIGIN_MCP,
                                                null,
                                                null),
                                        server.ownerUserId(),
                                        ToolCatalog.permissionOf(perm),
                                        server.serverId());
                            }
                        }
                    }

                    if (request.acceptedChanges().changed() != null) {
                        for (AcceptedChanged changeChoice : request.acceptedChanges().changed()) {
                            if (Boolean.TRUE.equals(changeChoice.apply())) {
                                ToolCatalog.DiscoveredTool discovered =
                                        liveByName.get(changeChoice.name());
                                if (discovered != null) {
                                    ToolCatalog.ToolRow oldTool =
                                            catalog.findTool(connection, changeChoice.toolId());
                                    if (oldTool != null) {
                                        if (!catalog.updateDefinition(
                                                connection,
                                                oldTool.toolId(),
                                                new ToolImport.ImportedTool(
                                                        discovered.name(),
                                                        discovered.description(),
                                                        discovered.inputSchema(),
                                                        ToolCatalog.ORIGIN_MCP,
                                                        null,
                                                        null,
                                                        oldTool.configJson(),
                                                        server.serverId()),
                                                oldTool.permission(),
                                                oldTool.revision()))
                                            throw new ToolProblem(
                                                    409,
                                                    "revision_conflict",
                                                    "Review this change again");
                                    }
                                }
                            }
                        }
                    }

                    if (request.acceptedChanges().removed() != null) {
                        for (AcceptedRemoved removeChoice : request.acceptedChanges().removed()) {
                            if (Boolean.TRUE.equals(removeChoice.confirm())) {
                                catalog.deleteTool(connection, removeChoice.toolId());
                            }
                        }
                    }
                } else if (request.tools() != null) {
                    for (Map<String, Object> choice : request.tools()) {
                        String name =
                                choice.get("name") == null ? null : choice.get("name").toString();
                        ToolCatalog.DiscoveredTool discovered = liveByName.get(name);
                        if (discovered != null) {
                            String perm =
                                    choice.get("permission") == null
                                            ? ToolCatalog.PERMISSION_ASK
                                            : choice.get("permission").toString();
                            catalog.approveMcpTool(connection, server, discovered, perm);
                        }
                    }
                }

                catalog.updateServerPendingJson(connection, server.serverId(), "[]");
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public static String computeFingerprint(List<ToolCatalog.DiscoveredTool> tools) {
        List<ToolCatalog.DiscoveredTool> sorted = new ArrayList<>(tools);
        sorted.sort(Comparator.comparing(ToolCatalog.DiscoveredTool::name));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (ToolCatalog.DiscoveredTool t : sorted) {
                md.update(t.name().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(
                        (t.description() == null ? "" : t.description().strip())
                                .getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(normalizeJsonStatic(t.inputSchema()).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 not available", error);
        }
    }

    private void validateChoices(
            Connection connection,
            ToolCatalog.ServerRow server,
            ApproveRequest request,
            String fingerprint)
            throws SQLException {
        if (request.acceptedChanges() == null) {
            try (var statement =
                    connection.prepareStatement(
                            "SELECT revision FROM advisor_mcp_servers WHERE server_id=? FOR"
                                    + " UPDATE")) {
                statement.setString(1, server.serverId());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getLong(1) != server.revision())
                        throw new ToolProblem(
                                409, "revision_conflict", "The MCP configuration changed");
                }
            }
            var seen = new java.util.HashSet<String>();
            if (request.tools() != null)
                for (var item : request.tools()) {
                    String name = Objects.toString(item.get("name"), "");
                    String permission = Objects.toString(item.get("permission"), "ask");
                    if (!seen.add(name) || !List.of("allow", "ask", "deny").contains(permission))
                        throw invalidSelection();
                }
            return;
        }
        McpDiff diff;
        try (var statement =
                connection.prepareStatement(
                        "SELECT pending_json, revision FROM advisor_mcp_servers WHERE server_id = ?"
                                + " FOR UPDATE")) {
            statement.setString(1, server.serverId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong("revision") != server.revision())
                    throw new ToolProblem(
                            409, "revision_conflict", "The MCP configuration changed");
                try {
                    diff = json.readValue(rows.getString("pending_json"), McpDiff.class);
                } catch (JsonProcessingException error) {
                    throw new ToolProblem(409, "mcp_changed", "Refresh the MCP changes first");
                }
            }
        }
        if (!fingerprint.equals(diff.fingerprint())
                || !fingerprint.equals(request.expectedFingerprint()))
            throw new ToolProblem(409, "mcp_changed", "Refresh the MCP changes first");
        var seen = new java.util.HashSet<String>();
        var choices = request.acceptedChanges();
        if (choices.added() != null)
            for (var item : choices.added()) {
                if (!seen.add("name:" + item.name())
                        || diff.added().stream().noneMatch(t -> t.name().equals(item.name()))
                        || !List.of("allow", "ask", "deny")
                                .contains(item.permission() == null ? "ask" : item.permission()))
                    throw invalidSelection();
            }
        if (choices.changed() != null)
            for (var item : choices.changed()) {
                var expected =
                        diff.changed().stream()
                                .filter(
                                        t ->
                                                t.toolId().equals(item.toolId())
                                                        && t.name().equals(item.name()))
                                .findFirst()
                                .orElseThrow(McpUpdateService::invalidSelection);
                validateTool(connection, server, item.toolId(), item.name(), seen);
                if (catalog.findTool(connection, item.toolId()).revision() != expected.revision())
                    throw new ToolProblem(
                            409, "revision_conflict", "The tool changed after refresh");
            }
        if (choices.removed() != null)
            for (var item : choices.removed()) {
                if (diff.removed().stream()
                        .noneMatch(
                                t ->
                                        t.toolId().equals(item.toolId())
                                                && t.name().equals(item.name())))
                    throw invalidSelection();
                validateTool(connection, server, item.toolId(), item.name(), seen);
                var snapshot =
                        diff.removed().stream()
                                .filter(t -> t.toolId().equals(item.toolId()))
                                .findFirst()
                                .orElseThrow(McpUpdateService::invalidSelection);
                if (snapshot.revision() == null
                        || snapshot.revision()
                                != catalog.findTool(connection, item.toolId()).revision())
                    throw new ToolProblem(
                            409, "revision_conflict", "The tool changed after refresh");
            }
    }

    private void validateTool(
            Connection connection,
            ToolCatalog.ServerRow server,
            String id,
            String name,
            java.util.Set<String> seen)
            throws SQLException {
        try (var lock =
                connection.prepareStatement(
                        "SELECT tool_id FROM advisor_tools WHERE tool_id = ? FOR UPDATE")) {
            lock.setString(1, id);
            try (var rows = lock.executeQuery()) {
                if (!rows.next()) throw invalidSelection();
            }
        }
        var tool = catalog.findTool(connection, id);
        if (!seen.add("id:" + id)
                || tool == null
                || !Objects.equals(tool.mcpServerId(), server.serverId())
                || !Objects.equals(tool.ownerUserId(), server.ownerUserId())
                || !tool.name().equals(name)) throw invalidSelection();
    }

    private static ToolProblem invalidSelection() {
        return new ToolProblem(
                422, "invalid_mcp_selection", "The selection does not belong to these MCP changes");
    }

    private String normalizeJson(String raw) {
        return normalizeJsonStatic(raw);
    }

    private static String normalizeJsonStatic(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(raw);
            return node.toString();
        } catch (Exception error) {
            return raw.strip();
        }
    }
}
