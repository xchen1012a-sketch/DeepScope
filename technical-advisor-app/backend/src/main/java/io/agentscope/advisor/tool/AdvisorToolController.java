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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.advisor.agent.AgentStore;
import io.agentscope.advisor.auth.ApiAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tool catalog and MCP services. Creating a tool and connecting an MCP service are separate
 * entries. An agent later selects tools; it does not bind a protocol a second time.
 */
@RestController
public class AdvisorToolController {

    private static final Logger log = LoggerFactory.getLogger(AdvisorToolController.class);

    private final ToolCatalog catalog;
    private final McpToolSource mcp;
    private final AgentStore agents;
    private final io.agentscope.advisor.agent.AgentProvisioner provisioner;
    private final ToolImport importer;
    private final ToolConnectionProbe probe = new ToolConnectionProbe();
    private final ToolCredentialStore credentials;
    private final McpEndpointParser endpoints;
    private final io.agentscope.advisor.access.AccessGuard access;
    private final ObjectMapper json;
    private final ToolTestService tests;
    private final ToolDefinitionService definitions;
    private final McpUpdateService updates;
    private final ToolRuntimeCoordinator coordinator;
    private ToolTestInputService testInputs;

    @Autowired(required = false)
    public void setTestInputService(ToolTestInputService service) {
        this.testInputs = service;
    }

    public AdvisorToolController(
            ToolCatalog catalog,
            McpToolSource mcp,
            AgentStore agents,
            io.agentscope.advisor.agent.AgentProvisioner provisioner,
            ObjectMapper advisorObjectMapper,
            io.agentscope.advisor.access.AccessGuard access,
            ToolCredentialStore credentials) {
        this(
                catalog,
                mcp,
                agents,
                provisioner,
                advisorObjectMapper,
                access,
                credentials,
                new McpUpdateService(catalog, mcp, advisorObjectMapper),
                null);
    }

    public AdvisorToolController(
            ToolCatalog catalog,
            McpToolSource mcp,
            AgentStore agents,
            io.agentscope.advisor.agent.AgentProvisioner provisioner,
            ObjectMapper advisorObjectMapper,
            io.agentscope.advisor.access.AccessGuard access,
            ToolCredentialStore credentials,
            McpUpdateService updates) {
        this(
                catalog,
                mcp,
                agents,
                provisioner,
                advisorObjectMapper,
                access,
                credentials,
                updates,
                null);
    }

    @Autowired
    public AdvisorToolController(
            ToolCatalog catalog,
            McpToolSource mcp,
            AgentStore agents,
            io.agentscope.advisor.agent.AgentProvisioner provisioner,
            ObjectMapper advisorObjectMapper,
            io.agentscope.advisor.access.AccessGuard access,
            ToolCredentialStore credentials,
            McpUpdateService updates,
            @Autowired(required = false) ToolRuntimeCoordinator coordinator) {
        this.catalog = catalog;
        this.mcp = mcp;
        this.agents = agents;
        this.provisioner = provisioner;
        this.importer = new ToolImport(advisorObjectMapper);
        this.endpoints = new McpEndpointParser(advisorObjectMapper);
        this.access = access;
        this.credentials = credentials;
        this.json = advisorObjectMapper;
        this.definitions =
                new ToolDefinitionService(catalog, advisorObjectMapper, credentials, mcp);
        this.tests =
                new ToolTestService(catalog, json, new ToolHttpExecutor(json), credentials, mcp);
        this.updates = updates;
        this.coordinator = coordinator;
    }

    @GetMapping("/api/advisor/tools")
    public List<Map<String, Object>> list(HttpServletRequest request) throws SQLException {
        var account = account(request);
        List<Map<String, Object>> view = new ArrayList<>();
        boolean selection = io.agentscope.advisor.access.WorkspaceAuthorization.selection(request);
        for (ToolCatalog.ToolRow tool :
                catalog.listTools(
                        io.agentscope.advisor.access.WorkspaceAuthorization.owner(
                                request, account.userId()),
                        !selection
                                && access.manage(
                                        account,
                                        io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE),
                        request.getParameter("q"))) {
            view.add(
                    toolView(
                            tool,
                            account.userId(),
                            access.manage(
                                    account,
                                    io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE)));
        }
        if (selection) view.forEach(item -> item.put("canEdit", false));
        return view;
    }

    @GetMapping(value = "/api/advisor/tools", params = "page")
    public io.agentscope.advisor.db.PageResult<Map<String, Object>> page(HttpServletRequest request)
            throws SQLException {
        var account = account(request);
        boolean selection = io.agentscope.advisor.access.WorkspaceAuthorization.selection(request);
        boolean admin =
                !selection
                        && access.manage(
                                account, io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE);
        var rows =
                catalog.pageTools(
                                io.agentscope.advisor.access.WorkspaceAuthorization.owner(
                                        request, account.userId()),
                                admin,
                                request.getParameter("q"),
                                request.getParameter("permission"),
                                selection ? requestedOrigins(request.getParameter("origin")) : null,
                                io.agentscope.advisor.db.JdbcPage.page(request),
                                io.agentscope.advisor.db.JdbcPage.size(request))
                        .map(tool -> toolView(tool, account.userId(), admin));
        if (selection) rows.items().forEach(item -> item.put("canEdit", false));
        return rows;
    }

    /** Selectors narrow the catalog themselves; unknown origins never reach SQL. */
    private static List<String> requestedOrigins(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> origins = new ArrayList<>();
        for (String part : value.split(",")) {
            String origin = part.strip();
            if (!List.of(
                            ToolCatalog.ORIGIN_HTTP,
                            ToolCatalog.ORIGIN_DECLARED,
                            ToolCatalog.ORIGIN_MCP)
                    .contains(origin)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid origin");
            }
            origins.add(origin);
        }
        return origins;
    }

    @PostMapping({"/api/advisor/tools/preview", "/api/advisor/tools/import/preview"})
    public ResponseEntity<?> preview(
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws SQLException {
        var account = account(request);
        try {
            return ResponseEntity.ok(
                    definitions.preview(
                            account.userId(),
                            access.manage(
                                    account, io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE),
                            body));
        } catch (ToolProblem error) {
            return problem(error);
        }
    }

    @PostMapping("/api/advisor/tools/import")
    public ResponseEntity<?> importTools(
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws SQLException {
        var account = account(request);
        boolean admin =
                access.manage(account, io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE);
        try {
            List<Map<String, Object>> view =
                    definitions
                            .importTools(
                                    account.userId(),
                                    admin,
                                    body,
                                    request.getHeader("Idempotency-Key"))
                            .stream()
                            .map(tool -> toolView(tool, account.userId(), admin))
                            .toList();
            return ResponseEntity.status(HttpStatus.CREATED).body(view);
        } catch (ToolProblem error) {
            return problem(error);
        }
    }

    @PatchMapping("/api/advisor/tools/{toolId}")
    public ResponseEntity<?> updatePermission(
            @PathVariable String toolId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException, IOException {
        var account = account(request);
        ToolCatalog.ToolRow tool = visible(toolId, account);
        if (tool == null) {
            return ResponseEntity.notFound().build();
        }
        if (!ToolCatalog.canEdit(
                tool.ownerUserId(),
                tool.origin(),
                account.userId(),
                access.manage(account, io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String ifMatch = request.getHeader("If-Match");
        Long expectedRevision = null;
        if (ifMatch != null && !ifMatch.isBlank()) {
            try {
                expectedRevision = Long.parseLong(ifMatch.strip());
            } catch (NumberFormatException error) {
                return problem(
                        new ToolProblem(422, "invalid_revision", "If-Match must be a revision"));
            }
        }
        if (expectedRevision != null && expectedRevision != tool.revision()) {
            return problem(conflict());
        }
        String boundId =
                body.containsKey("boundToolId")
                        ? text(body, "boundToolId")
                        : text(body, "mcpToolId");
        boolean editingDefinition =
                boundId != null
                        || body.containsKey("name")
                        || body.containsKey("description")
                        || body.containsKey("httpUrl")
                        || body.containsKey("httpMethod")
                        || body.containsKey("inputSchema")
                        || body.containsKey("auth");
        if (editingDefinition) {
            if (boundId != null) {
                ToolCatalog.ToolRow boundTool = visible(boundId, account);
                if (boundTool == null) {
                    return problem(
                            new ToolProblem(
                                    422,
                                    "bound_tool_not_found",
                                    "The specified MCP tool was not found"));
                }
                if (!ToolCatalog.ORIGIN_MCP.equals(boundTool.origin())
                        || boundTool.mcpServerId() == null) {
                    return problem(
                            new ToolProblem(
                                    422, "invalid_binding", "Can only bind to an MCP tool"));
                }
                ToolCatalog.ServerRow server = visibleServer(boundTool.mcpServerId(), account);
                if (server == null) {
                    return problem(
                            new ToolProblem(
                                    422,
                                    "bound_tool_not_found",
                                    "The server for the MCP tool was not found"));
                }
                try {
                    mcp.list(catalog.endpointOf(server));
                } catch (Exception error) {
                    log.error(
                            "MCP service '{}' could not be reached: {}",
                            server.name(),
                            error.toString());
                    String message =
                            error.getMessage() == null
                                    ? "MCP connection failed"
                                    : error.getMessage();
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .body(Map.of("code", "connection_failed", "message", message));
                }
                String name = text(body, "name");
                if (name == null) name = tool.name();
                String description =
                        body.containsKey("description")
                                ? text(body, "description")
                                : (tool.description().isBlank()
                                        ? boundTool.description()
                                        : tool.description());
                String schema =
                        body.containsKey("inputSchema")
                                ? text(body, "inputSchema")
                                : tool.inputSchema();
                if (!catalog.updateDefinition(
                        toolId,
                        new ToolImport.ImportedTool(
                                name,
                                description == null ? "" : description,
                                schema,
                                ToolCatalog.ORIGIN_MCP,
                                null,
                                null,
                                tool.configJson() == null ? "{}" : tool.configJson(),
                                boundTool.mcpServerId()),
                        text(body, "permission") == null
                                ? tool.permission()
                                : text(body, "permission"),
                        tool.revision())) return problem(conflict());
                applyToolChanges(account.userId(), List.of(toolId), List.of());
                return ResponseEntity.ok(
                        toolView(
                                catalog.findTool(toolId).orElseThrow(),
                                account.userId(),
                                access.manage(
                                        account,
                                        io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE)));
            }
            if (ToolCatalog.ORIGIN_MCP.equals(tool.origin()))
                return problem(
                        new ToolProblem(
                                422,
                                "remote_definition",
                                "A remote definition is edited by syncing, not here"));
            String name = text(body, "name");
            if (name == null) name = tool.name();
            String description =
                    body.containsKey("description")
                            ? text(body, "description")
                            : tool.description();
            String address = body.containsKey("httpUrl") ? text(body, "httpUrl") : tool.httpUrl();
            String method =
                    body.containsKey("httpMethod") ? text(body, "httpMethod") : tool.httpMethod();
            String schema =
                    body.containsKey("inputSchema")
                            ? text(body, "inputSchema")
                            : tool.inputSchema();
            boolean executionChanged =
                    body.containsKey("httpUrl")
                            || body.containsKey("httpMethod")
                            || body.containsKey("inputSchema")
                            || body.containsKey("auth");
            boolean authGiven = body.containsKey("auth");
            Map<String, Object> secret = null;
            ToolCredentialStore.Pending replacement = null;
            com.fasterxml.jackson.databind.node.ObjectNode configNode;
            try {
                var existingTree =
                        tool.configJson() == null || tool.configJson().isBlank()
                                ? json.createObjectNode()
                                : json.readTree(tool.configJson());
                configNode =
                        existingTree instanceof com.fasterxml.jackson.databind.node.ObjectNode on
                                ? on.deepCopy()
                                : json.createObjectNode();
            } catch (Exception e) {
                configNode = json.createObjectNode();
            }
            try {
                if (authGiven) {
                    Object rawAuth = body.get("auth");
                    if (rawAuth instanceof Map<?, ?> authMap) {
                        String action = text(authMap, "action");
                        if (action != null) {
                            if (!java.util.Set.of("keep", "replace", "clear").contains(action)) {
                                throw new ToolProblem(
                                        422,
                                        "invalid_auth",
                                        "Supported auth actions are keep, replace, or clear");
                            }
                            if ("keep".equals(action)) {
                                String stored = ToolAuth.credentialId(tool.configJson());
                                secret =
                                        stored == null
                                                ? null
                                                : credentials.read(tool.ownerUserId(), stored);
                            } else if ("clear".equals(action)) {
                                configNode.remove("authType");
                                configNode.remove("credentialId");
                                secret = null;
                            } else {
                                String type = text(authMap, "type");
                                if (type == null || "none".equals(type)) {
                                    throw new ToolProblem(
                                            422,
                                            "invalid_auth",
                                            "Auth replace requires a valid auth type");
                                }
                                secret = ToolAuth.secret(authMap);
                                replacement = credentials.prepare(tool.ownerUserId(), secret);
                                configNode.put("authType", String.valueOf(secret.get("type")));
                                configNode.put("credentialId", replacement.id());
                            }
                        } else {
                            String type = text(authMap, "type");
                            if (type == null || "none".equals(type)) {
                                configNode.remove("authType");
                                configNode.remove("credentialId");
                                secret = null;
                            } else {
                                secret = ToolAuth.secret(authMap);
                                replacement = credentials.prepare(tool.ownerUserId(), secret);
                                configNode.put("authType", String.valueOf(secret.get("type")));
                                configNode.put("credentialId", replacement.id());
                            }
                        }
                    } else if (rawAuth == null) {
                        configNode.remove("authType");
                        configNode.remove("credentialId");
                        secret = null;
                    }
                } else {
                    String stored = ToolAuth.credentialId(tool.configJson());
                    secret = stored == null ? null : credentials.read(tool.ownerUserId(), stored);
                }
            } catch (ToolProblem error) {
                return problem(error);
            }
            String reference = configNode.toString();
            try {
                var parsed =
                        importer.parseSchema(
                                        "{\"name\":"
                                                + importerName(name)
                                                + ",\"inputSchema\":"
                                                + schema
                                                + "}",
                                        address)
                                .get(0);
                if (parsed.httpUrl() != null
                        && (method == null
                                || !java.util.Set.of(
                                                "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD",
                                                "OPTIONS")
                                        .contains(method.toUpperCase(java.util.Locale.ROOT))))
                    throw new ToolProblem(422, "invalid_method", "Pick a supported HTTP method");
                if (executionChanged && parsed.httpUrl() != null)
                    probe.check(ToolAuth.address(address, secret), ToolAuth.headers(secret));
                try (Connection connection = catalog.dataSource().getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        if (replacement != null) {
                            ToolCredentialStore.insert(connection, replacement);
                        }
                        if (!catalog.updateDefinition(
                                connection,
                                toolId,
                                new ToolImport.ImportedTool(
                                        parsed.name(),
                                        description == null ? "" : description,
                                        parsed.inputSchema(),
                                        parsed.origin(),
                                        parsed.httpUrl() == null
                                                ? null
                                                : method.toUpperCase(java.util.Locale.ROOT),
                                        parsed.httpUrl(),
                                        reference),
                                text(body, "permission") == null
                                        ? tool.permission()
                                        : text(body, "permission"),
                                tool.revision())) {
                            connection.rollback();
                            return problem(conflict());
                        }
                        connection.commit();
                    } catch (Exception error) {
                        connection.rollback();
                        throw error;
                    }
                }
                applyToolChanges(account.userId(), List.of(toolId), List.of());
                return ResponseEntity.ok(
                        toolView(
                                catalog.findTool(toolId).orElseThrow(),
                                account.userId(),
                                access.manage(
                                        account,
                                        io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE)));
            } catch (ToolProblem error) {
                return problem(error);
            } catch (IllegalArgumentException error) {
                return problem(
                        new ToolProblem(
                                422,
                                "invalid_definition",
                                "The tool definition is not usable as written"));
            }
        }
        try {
            catalog.updatePermission(toolId, text(body, "permission"));
        } catch (IllegalArgumentException error) {
            return problem(
                    new ToolProblem(422, "invalid_permission", "Pick a supported permission"));
        }
        applyToolChanges(account.userId(), List.of(toolId), List.of());
        return ResponseEntity.ok(
                toolView(
                        catalog.findTool(toolId).orElseThrow(),
                        account.userId(),
                        access.manage(
                                account, io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE)));
    }

    @PostMapping("/api/advisor/tools/{toolId}/test")
    public ResponseEntity<?> testTool(
            @PathVariable String toolId,
            @RequestBody ToolTestService.Request body,
            HttpServletRequest request)
            throws SQLException {
        var account = account(request);
        var tool = visible(toolId, account);
        if (tool == null)
            return problem(new ToolProblem(404, "tool_not_found", "The tool is not available"));
        try {
            boolean canEdit =
                    ToolCatalog.canEdit(
                            tool.ownerUserId(),
                            tool.origin(),
                            account.userId(),
                            access.manage(
                                    account,
                                    io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE));
            return ResponseEntity.ok(tests.test(account.userId(), tool, canEdit, body));
        } catch (ToolProblem error) {
            return problem(error);
        }
    }

    @PostMapping("/api/advisor/tools/{toolId}/test-input")
    public ResponseEntity<?> suggestTestInput(
            @PathVariable String toolId, HttpServletRequest request) throws SQLException {
        var tool = visible(toolId, account(request));
        if (tool == null)
            return problem(new ToolProblem(404, "tool_not_found", "The tool is not available"));
        try {
            new CatalogExecutionGuard(catalog).check(toolId);
            if (testInputs == null)
                throw new ToolProblem(503, "ai_input_failed", "AI input generation is unavailable");
            return ResponseEntity.ok(Map.of("input", testInputs.suggest(tool)));
        } catch (ToolProblem error) {
            return problem(error);
        }
    }

    @PostMapping("/api/advisor/tools/tests/{runId}/stop")
    public ResponseEntity<?> stopTest(@PathVariable String runId, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(tests.stop(account(request).userId(), runId));
        } catch (ToolProblem error) {
            return problem(error);
        }
    }

    @jakarta.annotation.PreDestroy
    public void closeTests() {
        tests.close();
    }

    @GetMapping("/api/advisor/tools/{toolId}/impact")
    public ResponseEntity<?> toolImpact(@PathVariable String toolId, HttpServletRequest request)
            throws SQLException {
        var account = account(request);
        ToolCatalog.ToolRow tool = visible(toolId, account);
        if (tool == null) {
            return problem(new ToolProblem(404, "tool_not_found", "The tool is not available"));
        }
        boolean canManage =
                access.manage(account, io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE);
        List<String> agentIds = catalog.impactedAgentIdsForTool(toolId);
        List<Map<String, Object>> impactedAgents = new ArrayList<>();
        for (String agentId : agentIds) {
            agents.find(agentId)
                    .filter(a -> AgentStore.canView(a, account.userId(), canManage))
                    .ifPresent(
                            a ->
                                    impactedAgents.add(
                                            Map.of("agentId", a.agentId(), "name", a.name())));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("toolId", tool.toolId());
        response.put("name", tool.name());
        response.put("revision", tool.revision());
        response.put("mcpServerId", tool.mcpServerId());
        response.put("impactedAgents", impactedAgents);
        response.put("impactedCount", impactedAgents.size());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/api/advisor/tools/{toolId}")
    public ResponseEntity<?> delete(
            @PathVariable String toolId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest request)
            throws SQLException, IOException {
        var account = account(request);
        ToolCatalog.ToolRow tool = visible(toolId, account);
        if (tool == null) {
            return ResponseEntity.notFound().build();
        }
        if (!ToolCatalog.canEdit(
                tool.ownerUserId(),
                tool.origin(),
                account.userId(),
                access.manage(account, io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Long expectedRevision = parseRevisionHeader(ifMatch);
        if (expectedRevision != null && expectedRevision != tool.revision()) {
            return problem(conflict());
        }
        if (!catalog.deleteTool(toolId, expectedRevision)) {
            return problem(conflict());
        }
        applyToolChanges(account.userId(), List.of(toolId), List.of());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/advisor/mcp-servers")
    public List<Map<String, Object>> servers(HttpServletRequest request) throws SQLException {
        var account = account(request);
        List<Map<String, Object>> view = new ArrayList<>();
        boolean selection = io.agentscope.advisor.access.WorkspaceAuthorization.selection(request);
        for (ToolCatalog.ServerRow server :
                catalog.listServers(
                        io.agentscope.advisor.access.WorkspaceAuthorization.owner(
                                request, account.userId()),
                        !selection
                                && access.manage(
                                        account,
                                        io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE),
                        request.getParameter("q"))) {
            view.add(serverView(server, catalog.toolsOfServer(server.serverId())));
        }
        return view;
    }

    @GetMapping(value = "/api/advisor/mcp-servers", params = "page")
    public io.agentscope.advisor.db.PageResult<Map<String, Object>> serverPage(
            HttpServletRequest request) throws SQLException {
        var account = account(request);
        boolean selection = io.agentscope.advisor.access.WorkspaceAuthorization.selection(request);
        var rows =
                catalog.pageServers(
                        io.agentscope.advisor.access.WorkspaceAuthorization.owner(
                                request, account.userId()),
                        !selection
                                && access.manage(
                                        account,
                                        io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE),
                        request.getParameter("q"),
                        request.getParameter("status"),
                        io.agentscope.advisor.db.JdbcPage.page(request),
                        io.agentscope.advisor.db.JdbcPage.size(request));
        var items = new ArrayList<Map<String, Object>>();
        for (var server : rows.items())
            items.add(serverView(server, catalog.toolsOfServer(server.serverId()), selection));
        return new io.agentscope.advisor.db.PageResult<>(
                items, rows.page(), rows.size(), rows.total(), rows.totalPages());
    }

    @PostMapping("/api/advisor/mcp-servers")
    public ResponseEntity<?> connect(
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws SQLException {
        var account = account(request);
        List<McpEndpointParser.McpEndpoint> parsed;
        try {
            parsed = endpoints.parse(text(body, "text"));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
        List<List<ToolCatalog.DiscoveredTool>> probed = new ArrayList<>();
        for (McpEndpointParser.McpEndpoint endpoint : parsed) {
            try {
                probed.add(mcp.list(endpoint));
            } catch (Exception error) {
                log.error(
                        "MCP service '{}' could not be reached, so it was not connected: {}",
                        endpoint.name(),
                        error.toString());
                String message =
                        error.getMessage() == null ? "MCP connection failed" : error.getMessage();
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("message", message));
            }
        }
        List<Map<String, Object>> view = new ArrayList<>();
        for (int index = 0; index < parsed.size(); index++) {
            view.add(connectOne(parsed.get(index), probed.get(index), account.userId()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PostMapping("/api/advisor/mcp-servers/{serverId}/approve")
    public ResponseEntity<?> approve(
            @PathVariable String serverId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws Exception {
        var account = account(request);
        ToolCatalog.ServerRow server = visibleServer(serverId, account);
        if (server == null) {
            return ResponseEntity.notFound().build();
        }
        String expectedFingerprint = text(body, "expectedFingerprint");
        McpUpdateService.AcceptedChanges acceptedChanges = null;
        if (body.get("acceptedChanges") instanceof Map<?, ?> acMap) {
            try {
                acceptedChanges = json.convertValue(acMap, McpUpdateService.AcceptedChanges.class);
            } catch (Exception ignored) {
            }
        }
        List<Map<String, Object>> tools = null;
        if (body.get("tools") instanceof List<?> list) {
            tools = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    tools.add(copy);
                }
            }
        }
        try {
            updates.apply(
                    server,
                    new McpUpdateService.ApproveRequest(
                            expectedFingerprint, tools, acceptedChanges));
        } catch (ToolProblem problem) {
            return problem(problem);
        } catch (Exception error) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(
                            Map.of(
                                    "code",
                                    "connection_failed",
                                    "message",
                                    error.getMessage() == null
                                            ? "Approval failed"
                                            : error.getMessage()));
        }
        applyToolChanges(account.userId(), List.of(), List.of(serverId));
        return ResponseEntity.ok(
                serverView(
                        catalog.findServer(serverId).orElseThrow(),
                        catalog.toolsOfServer(serverId)));
    }

    @PostMapping("/api/advisor/mcp-servers/{serverId}/refresh")
    public ResponseEntity<?> refresh(@PathVariable String serverId, HttpServletRequest request)
            throws Exception {
        var account = account(request);
        ToolCatalog.ServerRow server = visibleServer(serverId, account);
        if (server == null) {
            return ResponseEntity.notFound().build();
        }
        McpUpdateService.McpDiff diff;
        try {
            diff = updates.refresh(server);
        } catch (Exception error) {
            log.error("MCP refresh failed for server '{}': {}", serverId, error.toString());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(
                            Map.of(
                                    "code",
                                    "connection_failed",
                                    "message",
                                    error.getMessage() == null
                                            ? "Connection failed"
                                            : error.getMessage()));
        }
        applyToolChanges(account.userId(), List.of(), List.of(serverId));
        Map<String, Object> view =
                serverView(
                        catalog.findServer(serverId).orElseThrow(),
                        catalog.toolsOfServer(serverId));
        view.put("diff", diff);
        view.put(
                "pending",
                diff.added().stream()
                        .map(
                                t ->
                                        Map.of(
                                                "name",
                                                t.name(),
                                                "description",
                                                t.description(),
                                                "inputSchema",
                                                t.inputSchema()))
                        .toList());
        return ResponseEntity.ok(view);
    }

    @PatchMapping("/api/advisor/mcp-servers/{serverId}")
    public ResponseEntity<?> updateServer(
            @PathVariable String serverId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException, IOException {
        var account = account(request);
        ToolCatalog.ServerRow server = visibleServer(serverId, account);
        if (server == null) {
            return ResponseEntity.notFound().build();
        }
        if (!access.manage(account, io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE)
                && !account.userId().equals(server.ownerUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String ifMatch = request.getHeader("If-Match");
        Long expectedRevision = null;
        if (ifMatch != null && !ifMatch.isBlank()) {
            try {
                expectedRevision = Long.parseLong(ifMatch.strip());
            } catch (NumberFormatException error) {
                return problem(
                        new ToolProblem(422, "invalid_revision", "If-Match must be a revision"));
            }
        } else if (body.containsKey("revision") && body.get("revision") instanceof Number num) {
            expectedRevision = num.longValue();
        }
        if (expectedRevision != null && expectedRevision != server.revision()) {
            return problem(conflict());
        }
        McpEndpointParser.McpEndpoint endpoint;
        try {
            if (body.containsKey("text")) {
                List<McpEndpointParser.McpEndpoint> parsed = endpoints.parse(text(body, "text"));
                if (parsed.isEmpty()) {
                    return problem(
                            new ToolProblem(422, "invalid_mcp_config", "No MCP endpoint found"));
                }
                endpoint = parsed.get(0);
            } else {
                List<McpEndpointParser.McpEndpoint> parsed =
                        endpoints.parse(json.writeValueAsString(body));
                if (parsed.isEmpty()) {
                    return problem(
                            new ToolProblem(422, "invalid_mcp_config", "No MCP endpoint found"));
                }
                endpoint = parsed.get(0);
            }
        } catch (Exception error) {
            return problem(
                    new ToolProblem(
                            422,
                            "invalid_mcp_config",
                            error.getMessage() == null
                                    ? "Invalid MCP config"
                                    : error.getMessage()));
        }
        try {
            mcp.list(endpoint);
        } catch (Exception error) {
            log.error(
                    "MCP service '{}' could not be reached on edit: {}",
                    endpoint.name(),
                    error.toString());
            String message =
                    error.getMessage() == null ? "MCP connection failed" : error.getMessage();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("code", "connection_failed", "message", message));
        }
        if (!catalog.updateServer(serverId, endpoint, server.revision())) {
            return problem(conflict());
        }
        applyToolChanges(account.userId(), List.of(), List.of(serverId));
        return ResponseEntity.ok(
                serverView(
                        catalog.findServer(serverId).orElseThrow(),
                        catalog.toolsOfServer(serverId)));
    }

    @GetMapping("/api/advisor/mcp-servers/{serverId}/impact")
    public ResponseEntity<?> serverImpact(@PathVariable String serverId, HttpServletRequest request)
            throws SQLException {
        var account = account(request);
        ToolCatalog.ServerRow server = visibleServer(serverId, account);
        if (server == null) {
            return problem(
                    new ToolProblem(404, "server_not_found", "The MCP server is not available"));
        }
        boolean canManage =
                access.manage(account, io.agentscope.advisor.access.AccessPoints.TOOL_MANAGE);
        List<ToolCatalog.ToolRow> serverTools = catalog.toolsOfServer(serverId);
        List<Map<String, Object>> toolsView = new ArrayList<>();
        for (ToolCatalog.ToolRow t : serverTools) {
            toolsView.add(
                    Map.of(
                            "toolId", t.toolId(),
                            "name", t.name(),
                            "description", t.description() == null ? "" : t.description()));
        }

        List<String> agentIds = catalog.impactedAgentIdsForServer(serverId);
        List<Map<String, Object>> impactedAgents = new ArrayList<>();
        for (String agentId : agentIds) {
            agents.find(agentId)
                    .filter(a -> AgentStore.canView(a, account.userId(), canManage))
                    .ifPresent(
                            a ->
                                    impactedAgents.add(
                                            Map.of("agentId", a.agentId(), "name", a.name())));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("serverId", server.serverId());
        response.put("name", server.name());
        response.put("revision", server.revision());
        response.put("tools", toolsView);
        response.put("impactedAgents", impactedAgents);
        response.put("impactedCount", impactedAgents.size());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/api/advisor/mcp-servers/{serverId}")
    public ResponseEntity<?> deleteServer(
            @PathVariable String serverId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest request)
            throws SQLException, IOException {
        var account = account(request);
        ToolCatalog.ServerRow server = visibleServer(serverId, account);
        if (server == null) {
            return ResponseEntity.notFound().build();
        }
        Long expectedRevision = parseRevisionHeader(ifMatch);
        if (expectedRevision != null && expectedRevision != server.revision()) {
            return problem(conflict());
        }
        if (!catalog.deleteServer(serverId, expectedRevision)) {
            return problem(conflict());
        }
        applyToolChanges(account.userId(), List.of(), List.of(serverId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/advisor/agents/{agentId}/tool-selection")
    public ResponseEntity<Map<String, Object>> selection(
            @PathVariable String agentId, HttpServletRequest request) throws SQLException {
        var account = account(request);
        if (agents.find(agentId)
                .filter(
                        agent ->
                                AgentStore.canView(
                                        agent,
                                        account.userId(),
                                        access.manage(
                                                account,
                                                io.agentscope.advisor.access.AccessPoints
                                                        .AGENT_MANAGE)))
                .isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ToolCatalog.Selection selection = catalog.selection(agentId);
        return ResponseEntity.ok(
                Map.of("toolIds", selection.toolIds(), "serverIds", selection.serverIds()));
    }

    @PutMapping("/api/advisor/agents/{agentId}/tool-selection")
    public ResponseEntity<Void> replaceSelection(
            @PathVariable String agentId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException, IOException {
        var account = account(request);
        var agent =
                agents.find(agentId)
                        .filter(
                                row ->
                                        AgentStore.canView(
                                                row,
                                                account.userId(),
                                                access.manage(
                                                        account,
                                                        io.agentscope.advisor.access.AccessPoints
                                                                .AGENT_MANAGE)))
                        .orElse(null);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }
        if (!AgentStore.canEdit(
                agent,
                account.userId(),
                access.manage(account, io.agentscope.advisor.access.AccessPoints.AGENT_MANAGE))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<String> toolIds = strings(body.get("toolIds"));
        List<String> serverIds = strings(body.get("serverIds"));
        for (String toolId : toolIds) {
            ToolCatalog.ToolRow tool =
                    catalog.findTool(toolId)
                            .filter(
                                    row ->
                                            java.util.Objects.equals(
                                                    row.ownerUserId(), agent.ownerUserId()))
                            .orElse(null);
            if (tool == null
                    || !tool.approved()
                    || ToolCatalog.ORIGIN_DECLARED.equals(tool.origin())) {
                return ResponseEntity.badRequest().build();
            }
        }
        for (String serverId : serverIds) {
            if (catalog.findServer(serverId)
                    .filter(row -> java.util.Objects.equals(row.ownerUserId(), agent.ownerUserId()))
                    .isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
        }
        catalog.replaceSelection(agentId, toolIds, serverIds);
        if (agent.enabled()) provisioner.register(agent);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> connectOne(
            McpEndpointParser.McpEndpoint endpoint,
            List<ToolCatalog.DiscoveredTool> live,
            String ownerUserId)
            throws SQLException {
        ToolCatalog.ServerRow server = catalog.insertServer(endpoint, ownerUserId, "connected", "");
        List<Map<String, Object>> discovered = new ArrayList<>();
        for (ToolCatalog.DiscoveredTool tool : live) {
            discovered.add(
                    Map.of(
                            "name",
                            tool.name(),
                            "description",
                            tool.description(),
                            "inputSchema",
                            tool.inputSchema()));
        }
        Map<String, Object> view = serverView(server, List.of());
        view.put("discovered", discovered);
        return view;
    }

    private static ToolImport.ImportedTool withConfig(
            ToolImport.ImportedTool tool, String configJson) {
        return new ToolImport.ImportedTool(
                tool.name(),
                tool.description(),
                tool.inputSchema(),
                tool.origin(),
                tool.httpMethod(),
                tool.httpUrl(),
                configJson,
                tool.mcpServerId());
    }

    private static ResponseEntity<Map<String, Object>> problem(ToolProblem error) {
        return ResponseEntity.status(error.status()).body(error.body());
    }

    private static ToolProblem conflict() {
        return new ToolProblem(409, "revision_conflict", "Someone else saved this tool first");
    }

    private static Long parseRevisionHeader(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) return null;
        try {
            return Long.parseLong(ifMatch.strip());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private void applyToolChanges(String ownerUserId, List<String> toolIds, List<String> serverIds)
            throws SQLException, IOException {
        if (coordinator != null) {
            coordinator.applyChanges(ownerUserId, null, toolIds, serverIds);
        } else if (provisioner != null) {
            for (AgentStore.AgentRow agent : agents.list()) {
                provisioner.register(agent);
            }
        }
    }

    private void refreshAgents() throws SQLException, IOException {
        if (provisioner == null) {
            return;
        }
        for (AgentStore.AgentRow agent : agents.list()) {
            provisioner.register(agent);
        }
    }

    @PostMapping("/api/advisor/tool-changes/{requestId}/retry-apply")
    public ResponseEntity<?> retryApply(@PathVariable String requestId, HttpServletRequest request)
            throws Exception {
        var account = account(request);
        if (coordinator == null) {
            return ResponseEntity.ok(
                    Map.of(
                            "requestId",
                            requestId,
                            "status",
                            "applied",
                            "impactedAgents",
                            List.of()));
        }
        try {
            ToolRuntimeCoordinator.ApplyResult result =
                    coordinator.retryApply(account.userId(), requestId);
            if ("apply_failed".equals(result.status())) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(
                                Map.of(
                                        "requestId",
                                        requestId,
                                        "status",
                                        result.status(),
                                        "errorMessage",
                                        result.errorMessage() == null ? "" : result.errorMessage(),
                                        "impactedAgents",
                                        result.impactedAgentIds()));
            }
            return ResponseEntity.ok(
                    Map.of(
                            "requestId",
                            requestId,
                            "status",
                            result.status(),
                            "impactedAgents",
                            result.impactedAgentIds()));
        } catch (ToolProblem problem) {
            return problem(problem);
        }
    }

    private ToolCatalog.ToolRow visible(
            String toolId, io.agentscope.advisor.auth.AccountStore.Account account)
            throws SQLException {
        return catalog.findTool(toolId)
                .filter(
                        tool ->
                                ToolCatalog.canView(
                                        tool.ownerUserId(),
                                        tool.origin(),
                                        account.userId(),
                                        access.manage(
                                                account,
                                                io.agentscope.advisor.access.AccessPoints
                                                        .TOOL_MANAGE)))
                .orElse(null);
    }

    private ToolCatalog.ServerRow visibleServer(
            String serverId, io.agentscope.advisor.auth.AccountStore.Account account)
            throws SQLException {
        return catalog.findServer(serverId)
                .filter(server -> account.userId().equals(server.ownerUserId()))
                .orElse(null);
    }

    private static io.agentscope.advisor.auth.AccountStore.Account account(
            HttpServletRequest request) {
        return ApiAuthenticationFilter.account(request).orElseThrow();
    }

    private Map<String, Object> toolView(ToolCatalog.ToolRow tool, String userId, boolean admin) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("toolId", tool.toolId());
        view.put("name", tool.name());
        view.put("description", tool.description());
        view.put("inputSchema", tool.inputSchema());
        view.put("origin", tool.origin());
        view.put("permission", tool.permission());
        view.put("ownerUserId", tool.ownerUserId());
        view.put("mcpServerId", tool.mcpServerId());
        view.put("httpMethod", tool.httpMethod());
        view.put("httpUrl", tool.httpUrl());
        view.put("approved", tool.approved());
        view.put("revision", tool.revision());
        view.put("hasCredential", ToolAuth.credentialId(tool.configJson()) != null);
        view.put("canEdit", ToolCatalog.canEdit(tool.ownerUserId(), tool.origin(), userId, admin));
        return view;
    }

    private Map<String, Object> serverView(
            ToolCatalog.ServerRow server, List<ToolCatalog.ToolRow> tools) {
        return serverView(server, tools, false);
    }

    /** Selector pages carry each server's tools so a page is self-contained; lists stay lean. */
    private Map<String, Object> serverView(
            ToolCatalog.ServerRow server,
            List<ToolCatalog.ToolRow> tools,
            boolean withToolSummaries) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("serverId", server.serverId());
        view.put("name", server.name());
        view.put("transport", server.transport());
        view.put("command", server.command());
        view.put("url", server.url());
        view.put("status", server.status());
        view.put("statusMessage", server.statusMessage());
        view.put("ownerUserId", server.ownerUserId());
        view.put("hasSecrets", hasSecrets(server));
        view.put("toolCount", tools.size());
        view.put("tools", tools.stream().map(ToolCatalog.ToolRow::name).toList());
        view.put("revision", server.revision());
        if (withToolSummaries) {
            view.put("toolSummaries", tools.stream().map(this::toolSummary).toList());
        }
        if (server.pendingJson() != null
                && !server.pendingJson().isBlank()
                && !"[]".equals(server.pendingJson().strip())) {
            try {
                McpUpdateService.McpDiff diff =
                        json.readValue(server.pendingJson(), McpUpdateService.McpDiff.class);
                view.put("pendingDiff", diff);
                view.put(
                        "pending",
                        diff.added().stream()
                                .map(
                                        t ->
                                                Map.of(
                                                        "name",
                                                        t.name(),
                                                        "description",
                                                        t.description(),
                                                        "inputSchema",
                                                        t.inputSchema()))
                                .toList());
            } catch (Exception ignored) {
            }
        }
        return view;
    }

    private Map<String, Object> toolSummary(ToolCatalog.ToolRow tool) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("toolId", tool.toolId());
        summary.put("name", tool.name());
        summary.put("description", tool.description());
        summary.put("approved", tool.approved());
        summary.put("permission", tool.permission());
        return summary;
    }

    private boolean hasSecrets(ToolCatalog.ServerRow server) {
        McpEndpointParser.McpEndpoint endpoint = catalog.endpointOf(server);
        return !endpoint.env().isEmpty() || !endpoint.headers().isEmpty();
    }

    private static String importerName(String name) {
        try {
            return new ObjectMapper().writeValueAsString(name);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid tool name", error);
        }
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : items) {
            if (item != null) {
                ids.add(item.toString());
            }
        }
        return ids;
    }

    private static String text(Map<?, ?> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        String value = body.get(key).toString();
        return value.isBlank() ? null : value;
    }
}
