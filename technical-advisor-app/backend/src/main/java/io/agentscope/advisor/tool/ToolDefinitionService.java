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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Coordinates import selection, duplicate decisions, connection checks and atomic replay. */
public final class ToolDefinitionService {
    private final ToolCatalog catalog;
    private final ToolImport importer;
    private final ObjectMapper json;
    private final ToolCredentialStore credentials;
    private final McpToolSource mcp;
    private final ToolImportRequestStore requests;
    private final ToolConnectionProbe probe = new ToolConnectionProbe();

    public ToolDefinitionService(
            ToolCatalog catalog,
            ObjectMapper advisorObjectMapper,
            ToolCredentialStore credentials,
            McpToolSource mcp) {
        this.catalog = catalog;
        json = advisorObjectMapper;
        importer = new ToolImport(json);
        this.credentials = credentials;
        this.mcp = mcp;
        requests = new ToolImportRequestStore(catalog, json);
    }

    record Candidate(ToolImport.ImportedTool definition, String sourceId, String operationId) {}

    record Decision(
            Candidate candidate,
            String action,
            String toolId,
            Long expectedRevision,
            String name) {}

    public List<Map<String, Object>> preview(String owner, boolean admin, Map<String, Object> body)
            throws SQLException {
        List<Candidate> candidates = parse(owner, admin, body);
        List<ToolCatalog.ToolRow> existing = catalog.listTools(owner, admin);
        String hash = definitionHash(candidates);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            var definition = candidate.definition();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", definition.name());
            item.put("description", definition.description());
            item.put("inputSchema", definition.inputSchema());
            item.put("origin", definition.origin());
            item.put("httpMethod", definition.httpMethod());
            item.put("httpUrl", definition.httpUrl());
            item.put("sourceId", candidate.sourceId());
            item.put("operationId", candidate.operationId());
            item.put("definitionHash", hash);
            item.put(
                    "duplicates",
                    duplicates(candidate, existing).stream()
                            .map(
                                    row ->
                                            Map.of(
                                                    "toolId",
                                                    row.toolId(),
                                                    "name",
                                                    row.name(),
                                                    "revision",
                                                    row.revision(),
                                                    "sameOperation",
                                                    sameOperation(candidate, row),
                                                    "canEdit",
                                                    ToolCatalog.canEdit(
                                                            row.ownerUserId(),
                                                            row.origin(),
                                                            owner,
                                                            admin)))
                            .toList());
            result.add(item);
        }
        return result;
    }

    public List<ToolCatalog.ToolRow> importTools(
            String owner, boolean admin, Map<String, Object> body, String key) throws SQLException {
        if (key != null && (key.isBlank() || key.length() > 200))
            throw invalid(
                    "invalid_idempotency_key", "Use a non-empty key of at most 200 characters");
        String digest = hash(canonical(json.valueToTree(body)).toString());
        var replay = requests.find(owner, key, digest);
        if (replay.isPresent()) return replay.get();
        List<Candidate> parsed = parse(owner, admin, body);
        if (body.containsKey("definitionHash")
                && !definitionHash(parsed).equals(text(body, "definitionHash")))
            throw new ToolProblem(
                    409, "definition_changed", "Preview this definition again before saving");
        List<Decision> decisions = select(parsed, body);
        try {
            validate(decisions, catalog.listTools(owner, admin), owner, admin);
        } catch (ToolProblem error) {
            if (key != null) {
                var committedReplay = requests.findUnderLock(owner, key, digest);
                if (committedReplay.isPresent()) return committedReplay.get();
            }
            throw error;
        }
        String permission =
                text(body, "permission") == null
                        ? ToolCatalog.PERMISSION_ASK
                        : text(body, "permission");
        if (!Set.of("allow", "ask", "deny").contains(permission))
            throw invalid("invalid_permission", "Use allow, ask or deny");
        Object rawAuth = body.get("auth");
        if (rawAuth != null && !(rawAuth instanceof Map<?, ?>)) {
            throw invalid("invalid_auth", "The authentication details must be an object");
        }
        Map<?, ?> authMap = rawAuth instanceof Map<?, ?> map ? map : null;
        String authAction = authMap == null ? null : text(authMap, "action");
        if (authAction != null && !Set.of("keep", "replace", "clear").contains(authAction)) {
            throw invalid("invalid_auth", "Use keep, replace or clear for auth action");
        }
        boolean authProvided;
        Map<String, Object> secret;
        if (authMap == null || "keep".equals(authAction)) {
            authProvided = false;
            secret = null;
        } else if ("clear".equals(authAction)) {
            authProvided = true;
            secret = null;
        } else {
            if ("replace".equals(authAction)) {
                String type = text(authMap, "type");
                if (type == null || "none".equals(type)) {
                    throw invalid("invalid_auth", "Enter an auth type to replace credentials");
                }
            }
            authProvided = true;
            secret = ToolAuth.secret(authMap);
        }
        Map<String, ToolCredentialStore.Pending> pending = new LinkedHashMap<>();
        if (secret != null) {
            for (Decision decision : decisions) {
                if ("skip".equals(decision.action())) continue;
                String credentialOwner =
                        decision.toolId() == null
                                ? owner
                                : catalog.findTool(decision.toolId()).orElseThrow().ownerUserId();
                if (!pending.containsKey(credentialOwner))
                    pending.put(credentialOwner, credentials.prepare(credentialOwner, secret));
            }
        }
        // Network and decryption stay outside the database transaction.
        for (Decision decision : decisions) {
            if ("skip".equals(decision.action())) continue;
            ToolCatalog.ToolRow old =
                    decision.toolId() == null
                            ? null
                            : catalog.findTool(decision.toolId()).orElse(null);
            if (ToolCatalog.ORIGIN_MCP.equals(decision.candidate().definition().origin())) {
                var server =
                        catalog.findServer(decision.candidate().definition().mcpServerId())
                                .orElseThrow(
                                        () ->
                                                invalid(
                                                        "bound_tool_not_found",
                                                        "The MCP server was not found"));
                try {
                    mcp.list(catalog.endpointOf(server));
                } catch (Exception error) {
                    throw new ToolProblem(
                            502, "connection_failed", "The MCP service could not be reached");
                }
            } else {
                Map<String, Object> auth = secret;
                if (!authProvided && old != null)
                    auth =
                            ToolAuth.credentialId(old.configJson()) == null
                                    ? null
                                    : credentials.read(
                                            old.ownerUserId(),
                                            ToolAuth.credentialId(old.configJson()));
                probe.check(
                        ToolAuth.address(decision.candidate().definition().httpUrl(), auth),
                        ToolAuth.headers(auth));
            }
        }
        return requests.commit(
                owner,
                key,
                digest,
                connection -> {
                    List<ToolCatalog.ToolRow> existing =
                            catalog.listTools(connection, owner, admin);
                    validate(decisions, existing, owner, admin);
                    for (ToolCredentialStore.Pending credential : pending.values())
                        ToolCredentialStore.insert(connection, credential);
                    List<ToolCatalog.ToolRow> result = new ArrayList<>();
                    for (Decision decision : decisions) {
                        if ("skip".equals(decision.action())) continue;
                        ToolCatalog.ToolRow old =
                                decision.toolId() == null
                                        ? null
                                        : catalog.findTool(connection, decision.toolId());
                        ToolCredentialStore.Pending credential =
                                pending.get(old == null ? owner : old.ownerUserId());
                        String reference =
                                ToolAuth.reference(
                                        secret, credential == null ? null : credential.id());
                        var definition = configured(decision, old, authProvided, reference);
                        String id;
                        if ("update".equals(decision.action())) {
                            id = old.toolId();
                            if (!catalog.updateDefinition(
                                    connection,
                                    id,
                                    definition,
                                    old.permission(),
                                    decision.expectedRevision())) throw revisionConflict(old);
                        } else {
                            id =
                                    catalog.insertTool(
                                            connection,
                                            definition,
                                            owner,
                                            permission,
                                            definition.mcpServerId());
                        }
                        result.add(catalog.findTool(connection, id));
                    }
                    return result;
                });
    }

    private List<Candidate> parse(String owner, boolean admin, Map<String, Object> body)
            throws SQLException {
        String content = text(body, "content");
        if (content == null) throw invalid("invalid_document", "Paste a definition first");
        String kind = "schema".equals(text(body, "kind")) ? "schema" : "openapi";
        String address =
                body.containsKey("addressOverride")
                        ? text(body, "addressOverride")
                        : text(body, "address");
        List<ToolImport.ImportedTool> definitions =
                "schema".equals(kind)
                        ? importer.parseSchema(content, address)
                        : importer.parseOpenApi(content, address);
        String boundId =
                body.containsKey("boundToolId")
                        ? text(body, "boundToolId")
                        : text(body, "mcpToolId");
        ToolCatalog.ToolRow bound = null;
        if (boundId != null) {
            bound = catalog.findTool(boundId).orElse(null);
            if (bound == null
                    || !ToolCatalog.canView(bound.ownerUserId(), bound.origin(), owner, admin))
                throw invalid("bound_tool_not_found", "The specified MCP tool was not found");
            if (!ToolCatalog.ORIGIN_MCP.equals(bound.origin()) || bound.mcpServerId() == null)
                throw invalid("invalid_binding", "Can only bind to an MCP tool");
            if (definitions.size() != 1)
                throw invalid("invalid_binding", "Choose one definition for a bound MCP tool");
        }
        List<Candidate> candidates = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (var definition : definitions) {
            String sourceId;
            String operationId;
            if (bound != null) {
                sourceId = "mcp:" + bound.mcpServerId();
                operationId = "mcp:" + bound.toolId();
                definition =
                        new ToolImport.ImportedTool(
                                definition.name(),
                                definition.description().isBlank()
                                        ? bound.description()
                                        : definition.description(),
                                definition.inputSchema(),
                                ToolCatalog.ORIGIN_MCP,
                                null,
                                null,
                                "{}",
                                bound.mcpServerId());
            } else if (definition.httpUrl() != null) {
                URI uri = URI.create(definition.httpUrl().replace("{", "%7B").replace("}", "%7D"));
                int port =
                        uri.getPort() < 0
                                ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80)
                                : uri.getPort();
                sourceId =
                        "http:"
                                + uri.getScheme().toLowerCase(java.util.Locale.ROOT)
                                + "://"
                                + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                                + ":"
                                + port;
                operationId =
                        definition.httpMethod()
                                + ":"
                                + uri.getRawPath().replace("%7B", "{").replace("%7D", "}")
                                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            } else {
                sourceId = kind;
                operationId = definition.name();
            }
            if (!ids.add(operationId))
                throw invalid("invalid_selection", "Operation identifiers must be unique");
            candidates.add(new Candidate(definition, sourceId, operationId));
        }
        return candidates;
    }

    private List<Decision> select(List<Candidate> candidates, Map<String, Object> body) {
        Set<String> selected = new HashSet<>();
        if (body.containsKey("chosenOperations")) {
            if (!(body.get("chosenOperations") instanceof List<?> values) || values.isEmpty())
                throw invalid("invalid_selection", "Choose at least one operation");
            for (Object value : values) {
                if (!(value instanceof String id) || !selected.add(id))
                    throw invalid("invalid_selection", "Choose each operation only once");
            }
        } else candidates.forEach(c -> selected.add(c.operationId()));
        Set<String> available = new HashSet<>();
        candidates.forEach(c -> available.add(c.operationId()));
        if (!available.containsAll(selected))
            throw invalid("invalid_selection", "An operation is not in this definition");
        Object rawActions = body.get("duplicateActions");
        if (rawActions != null && !(rawActions instanceof Map<?, ?>))
            throw invalid("invalid_selection", "Duplicate actions must be keyed by operationId");
        Map<?, ?> actions = rawActions instanceof Map<?, ?> map ? map : Map.of();
        if (!selected.containsAll(actions.keySet()))
            throw invalid("invalid_selection", "An action refers to an unselected operation");
        List<Decision> decisions = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!selected.contains(candidate.operationId())) continue;
            Object raw = actions.get(candidate.operationId());
            if (raw != null && !(raw instanceof Map<?, ?>))
                throw invalid("invalid_selection", "Each action must be an object");
            Map<?, ?> action = raw instanceof Map<?, ?> map ? map : Map.of();
            String type = text(action, "action");
            if (type != null && !Set.of("create", "update", "skip").contains(type))
                throw invalid("invalid_selection", "Use create, update or skip");
            Long revision = null;
            if (action.containsKey("expectedRevision")) {
                Object value = action.get("expectedRevision");
                if (!(value instanceof Number number)
                        || number.longValue() < 1
                        || number.doubleValue() != number.longValue())
                    throw invalid(
                            "invalid_revision", "expectedRevision must be a positive revision");
                revision = number.longValue();
            }
            decisions.add(
                    new Decision(
                            candidate,
                            type,
                            text(action, "toolId"),
                            revision,
                            text(action, "name") == null
                                    ? candidate.definition().name()
                                    : ToolImport.normalizeName(text(action, "name"))));
        }
        return decisions;
    }

    private void validate(
            List<Decision> decisions,
            List<ToolCatalog.ToolRow> existing,
            String owner,
            boolean admin) {
        Set<String> names = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (Decision decision : decisions) {
            List<ToolCatalog.ToolRow> duplicates = duplicates(decision.candidate(), existing);
            boolean originalMcpAlias =
                    ToolCatalog.ORIGIN_MCP.equals(decision.candidate().definition().origin())
                            && duplicates.size() == 1
                            && decision.candidate()
                                    .operationId()
                                    .equals("mcp:" + duplicates.get(0).toolId())
                            && !duplicates.get(0).name().equals(decision.name());
            if (decision.action() == null && !duplicates.isEmpty() && !originalMcpAlias)
                throw new ToolProblem(
                        409,
                        "duplicate_confirmation_required",
                        "Choose create, update or skip for existing candidates");
            if ("skip".equals(decision.action())) continue;
            if (!names.add(decision.name()))
                throw new ToolProblem(
                        409, "tool_name_conflict", "Selected tools need different names");
            if ("update".equals(decision.action())) {
                var target =
                        existing.stream()
                                .filter(row -> row.toolId().equals(decision.toolId()))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new ToolProblem(
                                                        404,
                                                        "tool_not_found",
                                                        "The update target was not found"));
                if (!ToolCatalog.canEdit(target.ownerUserId(), target.origin(), owner, admin))
                    throw new ToolProblem(403, "tool_edit_forbidden", "You cannot edit this tool");
                if (!sameOperation(decision.candidate(), target))
                    throw invalid(
                            "invalid_binding",
                            "The update target belongs to a different source or operation");
                if (decision.expectedRevision() == null)
                    throw invalid("invalid_revision", "Updates require expectedRevision");
                if (decision.expectedRevision() != target.revision())
                    throw revisionConflict(target);
                if (!targets.add(target.toolId()))
                    throw invalid("invalid_selection", "Choose each update target only once");
            } else if (decision.toolId() != null || decision.expectedRevision() != null) {
                throw invalid(
                        "invalid_selection", "Only update actions accept a target and revision");
            }
            for (var row : existing)
                if (row.name().equals(decision.name())
                        && !row.toolId()
                                .equals(
                                        "update".equals(decision.action())
                                                ? decision.toolId()
                                                : null))
                    throw new ToolProblem(
                            409, "tool_name_conflict", "Choose a new local tool name");
        }
    }

    private List<ToolCatalog.ToolRow> duplicates(
            Candidate candidate, List<ToolCatalog.ToolRow> existing) {
        return existing.stream()
                .filter(
                        row ->
                                sameOperation(candidate, row)
                                        || row.name().equals(candidate.definition().name()))
                .toList();
    }

    private boolean sameOperation(Candidate candidate, ToolCatalog.ToolRow row) {
        JsonNode source = config(row.configJson()).path("source");
        if (source.has("id") && source.has("operationId"))
            return candidate.sourceId().equals(source.path("id").asText())
                    && candidate.operationId().equals(source.path("operationId").asText());
        if (ToolCatalog.ORIGIN_MCP.equals(row.origin()))
            return candidate.operationId().equals("mcp:" + row.toolId());
        return java.util.Objects.equals(candidate.definition().httpUrl(), row.httpUrl())
                && java.util.Objects.equals(candidate.definition().httpMethod(), row.httpMethod());
    }

    private ToolImport.ImportedTool configured(
            Decision decision, ToolCatalog.ToolRow old, boolean authProvided, String reference) {
        var definition = decision.candidate().definition();
        ObjectNode config =
                old == null ? config(definition.configJson()) : config(old.configJson());
        ObjectNode source = config.putObject("source");
        source.put("id", decision.candidate().sourceId());
        source.put("operationId", decision.candidate().operationId());
        source.put("kind", ToolCatalog.ORIGIN_MCP.equals(definition.origin()) ? "mcp" : "http");
        if (old == null || authProvided) {
            config.remove(List.of("authType", "credentialId"));
            config.setAll(config(reference));
        }
        return new ToolImport.ImportedTool(
                decision.name(),
                definition.description(),
                definition.inputSchema(),
                definition.origin(),
                definition.httpMethod(),
                definition.httpUrl(),
                config.toString(),
                definition.mcpServerId());
    }

    private ObjectNode config(String value) {
        try {
            return value == null ? json.createObjectNode() : (ObjectNode) json.readTree(value);
        } catch (Exception error) {
            throw invalid("invalid_definition", "The stored configuration is invalid");
        }
    }

    private String definitionHash(List<Candidate> candidates) {
        return hash(canonical(json.valueToTree(candidates)).toString());
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = json.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(Comparator.naturalOrder());
            keys.forEach(key -> sorted.set(key, canonical(node.get(key))));
            return sorted;
        }
        if (node.isArray()) {
            var array = json.createArrayNode();
            node.forEach(value -> array.add(canonical(value)));
            return array;
        }
        return node;
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static ToolProblem revisionConflict(ToolCatalog.ToolRow row) {
        return new ToolProblem(
                409,
                "revision_conflict",
                "This tool changed; load its current version",
                Map.of("currentRevision", row.revision()));
    }

    private static ToolProblem invalid(String code, String message) {
        return new ToolProblem(422, code, message);
    }

    private static String text(Map<?, ?> body, String key) {
        Object value = body.get(key);
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }
}
