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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Normalizes supported OpenAPI/Schema input; execution availability is checked before saving. */
public final class ToolImport {

    /** The rule behind {@code invalid_name}, stated once so both call sites explain it. */
    private static final String NAME_RULE =
            "A tool name may only contain letters, digits, underscores and hyphens";

    private final ObjectMapper json;

    public ToolImport(ObjectMapper json) {
        this.json = json;
    }

    public List<ImportedTool> parseOpenApi(String content, String addressOverride) {
        JsonNode root = read(content);
        if (root.has("swagger")) throw problem("unsupported_version", "Use OpenAPI 3.0 or 3.1");
        String version = root.path("openapi").asText("");
        if (!version.isEmpty() && !version.startsWith("3.0") && !version.startsWith("3.1"))
            throw problem("unsupported_version", "Use OpenAPI 3.0 or 3.1");
        List<ImportedTool> tools = new ArrayList<>();
        JsonNode paths = root.path("paths");
        Iterator<String> pathNames = paths.fieldNames();
        while (pathNames.hasNext()) {
            String path = pathNames.next();
            JsonNode item = resolve(paths.get(path), root, new HashSet<>(), 0);
            Iterator<String> methods = item.fieldNames();
            while (methods.hasNext()) {
                String method = methods.next();
                if (!isHttpMethod(method)) continue;
                JsonNode operation = item.get(method);
                String base = normalizeBase(addressOverride);
                if (base == null)
                    base =
                            server(
                                    operation.has("servers")
                                            ? operation
                                            : item.has("servers") ? item : root);
                tools.add(operation(method, path, item.path("parameters"), operation, base, root));
            }
        }
        if (tools.isEmpty()) throw problem("no_operations", "No callable operations found");
        return tools;
    }

    public List<ImportedTool> parseSchema(String content, String address) {
        return parseSchema(content, address, null);
    }

    /**
     * Parses a bare input schema.
     *
     * <p>{@code nameOverride} is the name typed in the import form. It wins over a name carried by
     * the pasted document, so a user-typed name is enough to describe a tool whose document only
     * holds parameters.
     *
     * @param content the pasted schema document
     * @param address the execution address, or null for a document-only definition
     * @param nameOverride the name typed by the user, or null when none was typed
     * @return the single parsed tool
     */
    public List<ImportedTool> parseSchema(String content, String address, String nameOverride) {
        JsonNode root = read(content);
        String name = nameOverride;
        if (name == null) name = text(root, "name");
        if (name == null) name = text(root, "title");
        if (name == null) throw problem("name_required", "A tool name is required");
        name = normalizeName(name);
        JsonNode schema =
                resolve(
                        root.has("inputSchema") ? root.get("inputSchema") : root,
                        root,
                        new HashSet<>(),
                        0);
        if (!schema.isObject()) throw problem("invalid_schema", "Input schema must be an object");
        String base = normalizeBase(address);
        String description = text(root, "description");
        return List.of(
                new ImportedTool(
                        name,
                        description == null ? "" : description,
                        schema.toString(),
                        base == null ? "declared" : "http",
                        base == null ? null : "POST",
                        base));
    }

    private ImportedTool operation(
            String method,
            String path,
            JsonNode inherited,
            JsonNode operation,
            String base,
            JsonNode root) {
        String name = text(operation, "operationId");
        if (name == null) name = method.toLowerCase(Locale.ROOT) + "_" + path;
        name = normalizeName(name);
        String description = text(operation, "summary");
        if (description == null) description = text(operation, "description");
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        Map<String, JsonNode> parameters = new LinkedHashMap<>();
        collectParameters(parameters, inherited);
        collectParameters(parameters, operation.path("parameters"));
        for (JsonNode parameter : parameters.values()) {
            String original = text(parameter, "name");
            String where = text(parameter, "in");
            if (original == null
                    || where == null
                    || !Set.of("path", "query", "header", "cookie").contains(where))
                throw problem("invalid_parameter", "Parameter name and location are required");
            if ("header".equals(where)
                    && Set.of("authorization", "content-type", "accept")
                            .contains(original.toLowerCase(Locale.ROOT))) continue;
            String field = properties.has(original) ? where + "_" + original : original;
            ObjectNode definition =
                    parameter.path("schema").isObject()
                            ? ((ObjectNode) parameter.get("schema")).deepCopy()
                            : json.createObjectNode();
            if (parameter.has("description"))
                definition.set("description", parameter.get("description"));
            definition.put("x-parameter-in", where);
            definition.put("x-parameter-name", original);
            if (parameter.has("style")) definition.set("x-style", parameter.get("style"));
            if (parameter.has("explode")) definition.set("x-explode", parameter.get("explode"));
            properties.set(field, definition);
            if (parameter.path("required").asBoolean(false) || "path".equals(where))
                required.add(field);
        }
        JsonNode content = operation.path("requestBody").path("content");
        if (content.isObject() && !content.isEmpty()) {
            String media =
                    content.has("application/json")
                            ? "application/json"
                            : content.has("application/x-www-form-urlencoded")
                                    ? "application/x-www-form-urlencoded"
                                    : content.has("multipart/form-data")
                                            ? "multipart/form-data"
                                            : null;
            if (media == null)
                throw problem("unsupported_media", "Unsupported request body format");
            schema.put("x-body-content-type", media);
            JsonNode body = content.path(media).path("schema");
            if (!body.isObject()) throw problem("invalid_schema", "Request body schema is missing");
            if (body.path("properties").isObject()) {
                Set<String> mandatory = new HashSet<>();
                body.path("required").forEach(value -> mandatory.add(value.asText()));
                body.path("properties")
                        .fields()
                        .forEachRemaining(
                                entry -> {
                                    String original = entry.getKey();
                                    String field =
                                            properties.has(original)
                                                    ? "body_" + original
                                                    : original;
                                    ObjectNode definition =
                                            entry.getValue().isObject()
                                                    ? ((ObjectNode) entry.getValue()).deepCopy()
                                                    : json.createObjectNode();
                                    definition.put("x-parameter-in", "body");
                                    definition.put("x-parameter-name", original);
                                    properties.set(field, definition);
                                    if (mandatory.contains(original)) required.add(field);
                                });
            } else {
                schema.put("x-body-wrapper", "body");
                properties.set("body", body);
                if (operation.path("requestBody").path("required").asBoolean(false))
                    required.add("body");
            }
        }
        JsonNode security =
                operation.has("security") ? operation.get("security") : root.path("security");
        if (security.isArray() && !security.isEmpty()) {
            ObjectNode auth = schema.putObject("x-auth");
            auth.set("requirements", security);
            auth.set("schemes", root.path("components").path("securitySchemes"));
            for (JsonNode requirement : security)
                for (Iterator<String> names = requirement.fieldNames(); names.hasNext(); ) {
                    String scheme =
                            root.path("components")
                                    .path("securitySchemes")
                                    .path(names.next())
                                    .path("type")
                                    .asText();
                    if ("oauth2".equals(scheme) || "openIdConnect".equals(scheme))
                        throw problem(
                                "unsupported_auth",
                                "OAuth/OpenID authorization flows are not supported");
                }
        }
        return new ImportedTool(
                name,
                description == null ? "" : description,
                schema.toString(),
                base == null ? "declared" : "http",
                base == null ? null : method.toUpperCase(Locale.ROOT),
                base == null ? null : base + (path.startsWith("/") ? path : "/" + path));
    }

    private void collectParameters(Map<String, JsonNode> target, JsonNode values) {
        if (!values.isMissingNode() && !values.isArray())
            throw problem("invalid_parameter", "Parameters must be an array");
        values.forEach(
                value ->
                        target.put(
                                value.path("in").asText() + ":" + value.path("name").asText(),
                                value));
    }

    private String server(JsonNode node) {
        JsonNode servers = node.path("servers");
        if (!servers.isArray() || servers.isEmpty()) return null;
        if (servers.size() > 1)
            throw problem("multiple_servers", "Choose a server address before importing");
        JsonNode selected = servers.get(0);
        String url = text(selected, "url");
        if (url == null) return null;
        Iterator<String> variables = selected.path("variables").fieldNames();
        while (variables.hasNext()) {
            String key = variables.next();
            String value = text(selected.path("variables").path(key), "default");
            if (value == null)
                throw problem("server_variable_required", "Server variable has no default: " + key);
            url = url.replace("{" + key + "}", value);
        }
        if (url.contains("{"))
            throw problem("server_variable_required", "Unresolved server variable");
        return normalizeBase(url);
    }

    private JsonNode resolve(JsonNode value, JsonNode root, Set<String> trail, int depth) {
        if (depth > 32) throw problem("reference_depth", "Definition nesting is too deep");
        if (value.isObject()) {
            if (value.has("$ref")) {
                String reference = value.path("$ref").asText();
                if (!reference.startsWith("#/"))
                    throw problem("unsupported_reference", "Only local references are supported");
                if (!trail.add(reference))
                    throw problem("cyclic_reference", "Circular reference: " + reference);
                JsonNode referenced = root.at(reference.substring(1));
                if (referenced.isMissingNode())
                    throw problem("missing_reference", "Reference does not exist: " + reference);
                JsonNode result = resolve(referenced, root, trail, depth + 1);
                trail.remove(reference);
                return result;
            }
            ObjectNode result = json.createObjectNode();
            value.fields()
                    .forEachRemaining(
                            entry ->
                                    result.set(
                                            entry.getKey(),
                                            resolve(entry.getValue(), root, trail, depth + 1)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = json.createArrayNode();
            value.forEach(entry -> result.add(resolve(entry, root, trail, depth + 1)));
            return result;
        }
        return value.deepCopy();
    }

    private JsonNode read(String content) {
        if (content == null || content.isBlank() || content.length() > 2_097_152)
            throw problem("invalid_document", "Definition is empty or too large");
        try {
            ObjectMapper reader =
                    content.stripLeading().startsWith("{") || content.stripLeading().startsWith("[")
                            ? json
                            : new ObjectMapper(new YAMLFactory());
            JsonNode root = reader.readTree(content);
            if (root == null || !root.isObject())
                throw problem("invalid_document", "Definition must be an object");
            return root;
        } catch (ToolProblem error) {
            throw error;
        } catch (Exception error) {
            throw problem("invalid_document", "Tool description is not valid JSON or YAML");
        }
    }

    private static boolean isHttpMethod(String method) {
        return Set.of("get", "post", "put", "patch", "delete", "head", "options")
                .contains(method.toLowerCase(Locale.ROOT));
    }

    private static String normalizeBase(String address) {
        if (address == null || address.isBlank()) return null;
        String value = address.strip();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.isBlank() ? null : value;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    /**
     * A tool name reaches the model, so unusable characters fold to underscores. Derived names keep
     * a stable suffix instead of being truncated into collisions.
     */
    static String normalizeName(String raw) {
        String cleaned =
                raw.strip()
                        .replaceAll("[^A-Za-z0-9_-]+", "_")
                        .replaceAll("_{2,}", "_")
                        .replaceAll("^_+|_+$", "");
        if (cleaned.isEmpty()) throw problem("invalid_name", NAME_RULE);
        if (cleaned.length() <= 64) return cleaned;
        String digest = Integer.toHexString(cleaned.hashCode());
        return cleaned.substring(0, 64 - digest.length() - 1) + "_" + digest;
    }

    private static ToolProblem problem(String code, String message) {
        return new ToolProblem(422, code, message);
    }

    /**
     * One catalog row produced from a pasted description. {@code configJson} carries the
     * normalized request configuration, including which stored credential to authenticate with.
     */
    public record ImportedTool(
            String name,
            String description,
            String inputSchema,
            String origin,
            String httpMethod,
            String httpUrl,
            String configJson,
            String mcpServerId) {

        public ImportedTool(
                String name,
                String description,
                String inputSchema,
                String origin,
                String httpMethod,
                String httpUrl) {
            this(name, description, inputSchema, origin, httpMethod, httpUrl, "{}", null);
        }

        public ImportedTool(
                String name,
                String description,
                String inputSchema,
                String origin,
                String httpMethod,
                String httpUrl,
                String configJson) {
            this(name, description, inputSchema, origin, httpMethod, httpUrl, configJson, null);
        }
    }
}
