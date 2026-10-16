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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates the auth block a user submits into the two halves that are stored separately: the
 * secret, which only the credential store keeps, and the reference, which stays on the tool row.
 *
 * <p>Authorization headers are produced here rather than asked of the model, so a token never
 * becomes a tool parameter the model has to fill in.
 */
public final class ToolAuth {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolAuth() {}

    /**
     * The secret half of an auth block, or null when the block asks for no credentials. An
     * unsupported flow is refused here so it never reaches storage as a tool that looks usable.
     */
    public static Map<String, Object> secret(Object auth) {
        if (!(auth instanceof Map<?, ?> raw)) return null;
        String type = text(raw, "type");
        if (type == null || "none".equals(type)) return null;
        Map<String, Object> secret = new LinkedHashMap<>();
        secret.put("type", type);
        switch (type) {
            case "bearer" -> secret.put("value", required(raw, "value", "Enter the token"));
            case "apiKey" -> {
                secret.put("value", required(raw, "value", "Enter the key"));
                secret.put("name", text(raw, "name") == null ? "X-API-Key" : text(raw, "name"));
                secret.put("in", "query".equals(text(raw, "in")) ? "query" : "header");
            }
            case "basic" -> {
                secret.put("username", required(raw, "username", "Enter the user name"));
                secret.put("password", required(raw, "password", "Enter the password"));
            }
            default ->
                    throw new ToolProblem(
                            422,
                            "unsupported_auth",
                            "Only API key, bearer and basic authentication are supported");
        }
        return secret;
    }

    /** The non-secret half kept on the tool row, pointing at the stored credential. */
    public static String reference(Map<String, Object> secret, String credentialId) {
        if (secret == null) return "{}";
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("authType", String.valueOf(secret.get("type")));
        node.put("credentialId", credentialId);
        return node.toString();
    }

    /** The credential a tool row was saved with, or null when it needs none. */
    public static String credentialId(String configJson) {
        if (configJson == null || configJson.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(configJson);
            String id = node.path("credentialId").asText("");
            return id.isBlank() ? null : id;
        } catch (Exception error) {
            return null;
        }
    }

    /** Headers an authenticated request carries; query placement is handled in {@link #address}. */
    public static Map<String, String> headers(Map<String, Object> credential) {
        if (credential == null) return Map.of();
        String type = String.valueOf(credential.get("type"));
        if ("bearer".equals(type))
            return Map.of("Authorization", "Bearer " + credential.get("value"));
        if ("basic".equals(type)) {
            String pair = credential.get("username") + ":" + credential.get("password");
            return Map.of(
                    "Authorization",
                    "Basic "
                            + Base64.getEncoder()
                                    .encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        }
        if ("apiKey".equals(type) && !"query".equals(credential.get("in")))
            return Map.of(
                    String.valueOf(credential.get("name")),
                    String.valueOf(credential.get("value")));
        return Map.of();
    }

    /** Appends a query-placed API key, which is the one placement that cannot travel in a header. */
    public static String address(String address, Map<String, Object> credential) {
        if (address == null
                || credential == null
                || !"apiKey".equals(credential.get("type"))
                || !"query".equals(credential.get("in"))) return address;
        String pair =
                encode(String.valueOf(credential.get("name")))
                        + "="
                        + encode(String.valueOf(credential.get("value")));
        return address + (address.contains("?") ? "&" : "?") + pair;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String required(Map<?, ?> auth, String key, String message) {
        String value = text(auth, key);
        if (value == null) throw new ToolProblem(422, "invalid_auth", message);
        return value;
    }

    private static String text(Map<?, ?> auth, String key) {
        Object value = auth.get(key);
        if (!(value instanceof String text) || text.isBlank()) return null;
        return text.strip();
    }
}
