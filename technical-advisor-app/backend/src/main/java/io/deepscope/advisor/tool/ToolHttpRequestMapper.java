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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Maps the importer's normalized parameter metadata for both tests and real calls. */
final class ToolHttpRequestMapper {
    private final ObjectMapper json;

    ToolHttpRequestMapper(ObjectMapper json) {
        this.json = json;
    }

    record Request(String method, URI uri, Map<String, String> headers, byte[] body) {
        HttpRequest http(URI target, Map<String, String> sent, Duration budget) {
            var builder = HttpRequest.newBuilder(target).timeout(budget);
            sent.forEach(builder::header);
            return builder.method(
                            method,
                            body.length == 0
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        }
    }

    Request map(
            String method,
            String address,
            Map<String, Object> schema,
            Map<String, Object> input,
            Map<String, Object> credential) {
        validate(schema, input, "input");
        ToolConnectionProbe.target(address);
        String verb = method == null ? "POST" : method.toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").contains(verb))
            throw new ToolProblem(422, "unsupported_method", "Unsupported HTTP method");
        var headers = new LinkedHashMap<String, String>();
        var query = new ArrayList<String>();
        var cookies = new ArrayList<String>();
        var body = new LinkedHashMap<String, Object>();
        var bodyDefinitions = new LinkedHashMap<String, Map<String, Object>>();
        String url = address;
        Map<String, Object> properties = object(schema.get("properties"));
        for (var entry : input.entrySet()) {
            var definition = object(properties.get(entry.getKey()));
            String name =
                    String.valueOf(definition.getOrDefault("x-parameter-name", entry.getKey()));
            String location =
                    String.valueOf(
                            definition.getOrDefault(
                                    "x-parameter-in",
                                    url.contains("{" + name + "}") ? "path" : "body"));
            Object value = entry.getValue();
            switch (location) {
                case "path" -> {
                    var mapping = new LinkedHashMap<>(definition);
                    mapping.put("x-parameter-name", name);
                    url = url.replace("{" + name + "}", path(value, mapping));
                }
                case "query" -> query.addAll(pairs(name, value, definition, false));
                case "header" -> headers.put(name, simple(value, definition));
                case "cookie" -> cookies.addAll(pairs(name, value, definition, true));
                default -> {
                    body.put(name, value);
                    bodyDefinitions.put(name, definition);
                }
            }
        }
        if (url.contains("{") || url.contains("}"))
            invalid("input", "Fill in every path parameter");
        if (Set.of("GET", "HEAD", "OPTIONS").contains(verb)) {
            body.forEach((name, value) -> query.addAll(pairs(name, value, Map.of(), false)));
            body.clear();
        }
        if (!query.isEmpty()) url += (url.contains("?") ? "&" : "?") + String.join("&", query);
        if (!cookies.isEmpty()) headers.put("Cookie", String.join("; ", cookies));
        headers.putAll(ToolAuth.headers(credential));
        url = ToolAuth.address(url, credential);
        byte[] bytes = new byte[0];
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(verb)) {
            String media =
                    String.valueOf(schema.getOrDefault("x-body-content-type", "application/json"));
            try {
                switch (media) {
                    case "application/json" -> {
                        Object payload =
                                schema.containsKey("x-body-wrapper")
                                        ? input.get(String.valueOf(schema.get("x-body-wrapper")))
                                        : body;
                        bytes = json.writeValueAsBytes(payload);
                        headers.put("Content-Type", media);
                    }
                    case "application/x-www-form-urlencoded" -> {
                        var fields = new ArrayList<String>();
                        body.forEach(
                                (name, value) ->
                                        fields.addAll(
                                                pairs(
                                                        name,
                                                        value,
                                                        bodyDefinitions.getOrDefault(
                                                                name, Map.of()),
                                                        false)));
                        bytes = String.join("&", fields).getBytes(StandardCharsets.UTF_8);
                        headers.put("Content-Type", media);
                    }
                    case "multipart/form-data" -> {
                        String boundary = "advisor-" + UUID.randomUUID();
                        var data = new java.io.ByteArrayOutputStream();
                        for (var field : body.entrySet()) {
                            List<?> values =
                                    field.getValue() instanceof List<?> list
                                            ? list
                                            : java.util.Collections.singletonList(field.getValue());
                            for (Object value : values) {
                                String name = field.getKey();
                                if (name.contains("\r")
                                        || name.contains("\n")
                                        || name.contains("\""))
                                    invalid(name, "Invalid multipart field name");
                                data.write(
                                        ("--"
                                                        + boundary
                                                        + "\r\n"
                                                        + "Content-Disposition: form-data; name=\""
                                                        + name
                                                        + "\"\r\n\r\n")
                                                .getBytes(StandardCharsets.UTF_8));
                                data.write(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                                data.write("\r\n".getBytes(StandardCharsets.UTF_8));
                            }
                        }
                        data.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                        bytes = data.toByteArray();
                        headers.put("Content-Type", media + "; boundary=" + boundary);
                    }
                    default ->
                            throw new ToolProblem(
                                    422, "unsupported_media", "Unsupported request body format");
                }
            } catch (ToolProblem error) {
                throw error;
            } catch (Exception error) {
                throw new ToolProblem(
                        422, "invalid_parameters", "Cannot encode request parameters");
            }
        }
        URI target = ToolConnectionProbe.target(url);
        try {
            Request mapped = new Request(verb, target, Map.copyOf(headers), bytes);
            mapped.http(target, headers, Duration.ofSeconds(30));
            return mapped;
        } catch (IllegalArgumentException error) {
            throw new ToolProblem(422, "invalid_parameters", "Invalid request header or parameter");
        }
    }

    private static String path(Object value, Map<String, Object> definition) {
        String style = String.valueOf(definition.getOrDefault("x-style", "simple"));
        boolean explode = Boolean.TRUE.equals(definition.get("x-explode"));
        String name = encode(String.valueOf(definition.get("x-parameter-name")));
        var parts = new ArrayList<String>();
        if (value instanceof List<?> list)
            list.forEach(item -> parts.add(encode(String.valueOf(item))));
        else if (value instanceof Map<?, ?> map)
            map.forEach(
                    (key, item) -> {
                        if (explode)
                            parts.add(
                                    encode(String.valueOf(key))
                                            + "="
                                            + encode(String.valueOf(item)));
                        else {
                            parts.add(encode(String.valueOf(key)));
                            parts.add(encode(String.valueOf(item)));
                        }
                    });
        else parts.add(encode(value == null ? "" : String.valueOf(value)));
        return switch (style) {
            case "simple" -> String.join(",", parts);
            case "label" -> "." + String.join(explode ? "." : ",", parts);
            case "matrix" ->
                    explode && value instanceof Map<?, ?>
                            ? ";" + String.join(";", parts)
                            : explode && value instanceof List<?>
                                    ? ";" + name + "=" + String.join(";" + name + "=", parts)
                                    : ";" + name + "=" + String.join(",", parts);
            default ->
                    throw new ToolProblem(
                            422, "invalid_parameters", "Unsupported path parameter style");
        };
    }

    private static String simple(Object value, Map<String, Object> definition) {
        if (value instanceof List<?> list)
            return String.join(",", list.stream().map(String::valueOf).toList());
        if (value instanceof Map<?, ?> map) {
            boolean explode = Boolean.TRUE.equals(definition.get("x-explode"));
            return String.join(
                    ",",
                    map.entrySet().stream()
                            .map(e -> e.getKey() + (explode ? "=" : ",") + e.getValue())
                            .toList());
        }
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> pairs(
            String name, Object value, Map<String, Object> definition, boolean cookie) {
        String style = String.valueOf(definition.getOrDefault("x-style", "form"));
        boolean explode =
                definition.containsKey("x-explode")
                        ? Boolean.TRUE.equals(definition.get("x-explode"))
                        : "form".equals(style);
        if (value instanceof List<?> list) {
            if (explode && "form".equals(style))
                return list.stream()
                        .map(item -> encode(name) + "=" + encode(String.valueOf(item)))
                        .toList();
            String separator =
                    "spaceDelimited".equals(style)
                            ? "%20"
                            : "pipeDelimited".equals(style) ? "%7C" : ",";
            return List.of(
                    encode(name)
                            + "="
                            + String.join(
                                    separator,
                                    list.stream()
                                            .map(item -> encode(String.valueOf(item)))
                                            .toList()));
        }
        if (value instanceof Map<?, ?> map) {
            if ("deepObject".equals(style))
                return map.entrySet().stream()
                        .map(
                                e ->
                                        encode(name + "[" + e.getKey() + "]")
                                                + "="
                                                + encode(String.valueOf(e.getValue())))
                        .toList();
            if (explode)
                return map.entrySet().stream()
                        .map(
                                e ->
                                        encode(String.valueOf(e.getKey()))
                                                + "="
                                                + encode(String.valueOf(e.getValue())))
                        .toList();
            var parts = new ArrayList<String>();
            map.forEach(
                    (key, item) -> {
                        parts.add(encode(String.valueOf(key)));
                        parts.add(encode(String.valueOf(item)));
                    });
            return List.of(encode(name) + "=" + String.join(",", parts));
        }
        return List.of(encode(name) + "=" + encode(value == null ? "" : String.valueOf(value)));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    static void validate(Map<String, Object> schema, Object value, String field) {
        if (value == null) {
            if (Boolean.TRUE.equals(schema.get("nullable"))
                    || schema.get("type") instanceof List<?> types && types.contains("null"))
                return;
            invalid(field, "A value is required");
        }
        Object type = schema.get("type");
        if (type instanceof List<?> types) {
            boolean matched = false;
            for (Object candidate : types) {
                var alternative = new LinkedHashMap<>(schema);
                alternative.put("type", candidate);
                try {
                    validate(alternative, value, field);
                    matched = true;
                    break;
                } catch (ToolProblem ignored) {
                    /* try the next permitted type */
                }
            }
            if (!matched) invalid(field, "The parameter type is incorrect");
            return;
        }
        if (type instanceof String text) {
            boolean valid =
                    switch (text) {
                        case "object" -> value instanceof Map<?, ?>;
                        case "array" -> value instanceof List<?>;
                        case "string" -> value instanceof String;
                        case "integer" ->
                                value instanceof Number n
                                        && Double.isFinite(n.doubleValue())
                                        && n.doubleValue() == Math.rint(n.doubleValue());
                        case "number" ->
                                value instanceof Number n && Double.isFinite(n.doubleValue());
                        case "boolean" -> value instanceof Boolean;
                        case "null" -> value == null;
                        default -> true;
                    };
            if (!valid) invalid(field, "The parameter type is incorrect");
        }
        if (schema.get("enum") instanceof List<?> values
                && values.stream()
                        .noneMatch(
                                item ->
                                        java.util.Objects.equals(item, value)
                                                || item instanceof Number a
                                                        && value instanceof Number b
                                                        && a.doubleValue() == b.doubleValue()))
            invalid(field, "Choose an allowed value");
        if (value instanceof Number n) {
            double number = n.doubleValue();
            if (schema.get("exclusiveMinimum") instanceof Number min && number <= min.doubleValue()
                    || schema.get("exclusiveMaximum") instanceof Number max
                            && number >= max.doubleValue()
                    || Boolean.TRUE.equals(schema.get("exclusiveMinimum"))
                            && schema.get("minimum") instanceof Number min
                            && number <= min.doubleValue()
                    || Boolean.TRUE.equals(schema.get("exclusiveMaximum"))
                            && schema.get("maximum") instanceof Number max
                            && number >= max.doubleValue())
                invalid(field, "The value is outside the allowed range");
            if (schema.get("minimum") instanceof Number min && number < min.doubleValue()
                    || schema.get("maximum") instanceof Number max && number > max.doubleValue())
                invalid(field, "The value is outside the allowed range");
        }
        if (value instanceof String text) {
            if (schema.get("minLength") instanceof Number min && text.length() < min.intValue()
                    || schema.get("maxLength") instanceof Number max
                            && text.length() > max.intValue())
                invalid(field, "The text length is outside the allowed range");
        }
        if (value instanceof Map<?, ?> map) {
            if (schema.get("required") instanceof List<?> required)
                for (Object name : required)
                    if (!map.containsKey(name))
                        invalid(field + "." + name, "A required parameter is missing");
            Map<String, Object> properties = object(schema.get("properties"));
            for (var entry : map.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (properties.containsKey(name))
                    validate(object(properties.get(name)), entry.getValue(), field + "." + name);
                else if (Boolean.FALSE.equals(schema.get("additionalProperties")))
                    invalid(field + "." + name, "Unknown parameter");
            }
        }
        if (value instanceof List<?> list) {
            if (schema.get("minItems") instanceof Number min && list.size() < min.intValue()
                    || schema.get("maxItems") instanceof Number max && list.size() > max.intValue())
                invalid(field, "The number of items is outside the allowed range");
            for (int i = 0; i < list.size(); i++)
                validate(object(schema.get("items")), list.get(i), field + "[" + i + "]");
        }
    }

    private static void invalid(String field, String message) {
        throw new ToolProblem(
                422, "invalid_parameters", message, Map.of("fieldErrors", Map.of(field, message)));
    }
}
