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
package io.deepscope.advisor.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Bounded model enumeration shared by OpenAI-compatible and Anthropic connections. */
public final class ModelDiscoveryClient {
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_IDS = 2000;
    private static final int MAX_PAGES = 20;
    private static final long PAGE_NANOS = Duration.ofSeconds(20).toNanos();
    private static final Pattern NEXT_REL =
            Pattern.compile(
                    "(?i)(?:^|;)\\s*rel\\s*=\\s*(?:\"[^\"]*\\bnext\\b[^\"]*\"|next)(?:\\s*;|\\s*$)");
    private final ObjectMapper mapper =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public ModelProbe.DiscoveryResult discover(ModelCatalog.EndpointDraft draft) {
        URI initial = initialUri(draft);
        URI current = initial;
        var ids = new LinkedHashSet<String>();
        var embeddingIds = new HashSet<String>();
        var visited = new HashSet<URI>();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        int advertisedTotal = -1;
        ExecutorService reader =
                Executors.newSingleThreadExecutor(
                        task -> {
                            Thread thread = new Thread(task, "model-discovery-body");
                            thread.setDaemon(true);
                            return thread;
                        });
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                if (Thread.currentThread().isInterrupted()) throw failure("interrupted");
                if (!visited.add(current)) throw failure("invalid_pagination");
                long pageDeadline = Math.min(deadline, System.nanoTime() + PAGE_NANOS);
                Page response = fetch(current, draft, pageDeadline, reader);
                JsonNode total =
                        response.body().has("total")
                                ? response.body().get("total")
                                : response.body().get("total_count");
                if (total != null) {
                    if (!total.isIntegralNumber() || total.intValue() < 0)
                        throw failure("invalid_response");
                    advertisedTotal = Math.max(advertisedTotal, total.intValue());
                }
                JsonNode items = items(response.body());
                for (JsonNode item : items) {
                    JsonNode value =
                            item.isTextual() ? item : item.isObject() ? item.get("id") : null;
                    if (value == null
                            || !value.isTextual()
                            || value.textValue().isBlank()
                            || value.textValue().length() > 1024) throw failure("invalid_response");
                    ids.add(value.textValue());
                    if (ModelCatalog.EMBEDDING.equals(draft.kind()) && embeddingCapability(item))
                        embeddingIds.add(value.textValue());
                    if (ids.size() >= MAX_IDS) return result(ids, false, embeddingIds);
                }
                URI next = next(initial, current, response, draft.provider());
                if (next == null)
                    return result(
                            ids,
                            advertisedTotal < 0 || ids.size() >= advertisedTotal,
                            embeddingIds);
                if (visited.contains(next)) throw failure("invalid_pagination");
                current = next;
                if (System.nanoTime() >= deadline) return result(ids, false, embeddingIds);
            }
            return result(ids, false, embeddingIds);
        } finally {
            reader.shutdownNow();
        }
    }

    private Page fetch(
            URI uri, ModelCatalog.EndpointDraft draft, long deadline, ExecutorService reader) {
        CompletableFuture<HttpResponse<InputStream>> pending = null;
        try {
            var request =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofNanos(remaining(deadline)))
                            .header("Accept", "application/json");
            if ("anthropic".equals(draft.provider()))
                request.header("x-api-key", draft.secret())
                        .header("anthropic-version", "2023-06-01");
            else request.header("Authorization", "Bearer " + draft.secret());
            pending =
                    http.sendAsync(
                            request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            var response = pending.get(remaining(deadline), TimeUnit.NANOSECONDS);
            try (InputStream input = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw failure(statusCode(response.statusCode()));
                var body = reader.submit(() -> readBounded(input));
                try {
                    byte[] bytes = body.get(remaining(deadline), TimeUnit.NANOSECONDS);
                    JsonNode json;
                    try {
                        json = mapper.readTree(bytes);
                    } catch (IOException malformed) {
                        throw failure("invalid_response");
                    }
                    if (json == null) throw failure("invalid_response");
                    return new Page(json, response.headers().allValues("Link"));
                } finally {
                    body.cancel(true);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure("interrupted");
        } catch (TimeoutException timeout) {
            throw failure("timeout");
        } catch (ExecutionException unavailable) {
            if (unavailable.getCause() instanceof ModelProbe.DiscoveryException classified)
                throw classified;
            if (unavailable.getCause() instanceof HttpTimeoutException) throw failure("timeout");
            throw failure("upstream");
        } catch (ModelProbe.DiscoveryException classified) {
            throw classified;
        } catch (IOException unavailable) {
            throw failure("upstream");
        } catch (IllegalArgumentException invalid) {
            throw failure("invalid_address");
        } finally {
            if (pending != null) pending.cancel(true);
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1; ) {
            if (output.size() + count > MAX_BODY_BYTES) throw failure("invalid_response");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static long remaining(long deadline) throws TimeoutException {
        long left = deadline - System.nanoTime();
        if (left <= 0) throw new TimeoutException();
        return left;
    }

    private static JsonNode items(JsonNode root) {
        JsonNode values =
                root.isArray() ? root : root.has("data") ? root.get("data") : root.get("models");
        if (values == null || !values.isArray()) throw failure("invalid_response");
        return values;
    }

    private static URI next(URI initial, URI current, Page page, String provider) {
        JsonNode root = page.body();
        for (String field : List.of("next_page_token", "nextPageToken", "next_cursor"))
            if (root.has(field) && !root.get(field).isNull()) throw failure("invalid_pagination");
        JsonNode pagination = root.get("pagination");
        if (pagination != null && !pagination.isNull()) {
            if (!pagination.isObject()) throw failure("invalid_pagination");
            JsonNode nestedMore = pagination.get("has_more");
            JsonNode nestedNext = pagination.get("next");
            if (nestedMore != null
                    && (!nestedMore.isBoolean()
                            || nestedMore.booleanValue()
                            || (nestedNext != null && !nestedNext.isNull())))
                throw failure("invalid_pagination");
            if (nestedNext != null && !nestedNext.isNull()) throw failure("invalid_pagination");
        }
        String link = null;
        for (String field : List.of("next", "next_page_url"))
            link = mergeLink(link, nextValue(root.get(field)));
        JsonNode links = root.get("links");
        if (links != null && !links.isNull()) {
            if (!links.isObject()) throw failure("invalid_pagination");
            link = mergeLink(link, nextValue(links.get("next")));
        }
        for (String header : page.links()) {
            // Commas inside URI references are legal, so split only between link entries.
            for (String entry : header.split(",\\s*(?=<)")) {
                int end = entry.indexOf('>');
                if (end < 0 && NEXT_REL.matcher(entry).find()) throw failure("invalid_pagination");
                if (end >= 0 && NEXT_REL.matcher(entry.substring(end + 1)).find()) {
                    String value = entry.strip();
                    if (!value.startsWith("<")) throw failure("invalid_pagination");
                    link = mergeLink(link, value.substring(1, value.indexOf('>')));
                }
            }
        }
        JsonNode more = root.get("has_more");
        if (more != null) {
            if (!more.isBoolean()) throw failure("invalid_pagination");
            if (more.booleanValue() && link == null) {
                JsonNode last = root.get("last_id");
                if (!"anthropic".equals(provider)
                        || last == null
                        || !last.isTextual()
                        || last.textValue().isBlank()) throw failure("invalid_pagination");
                link = "?after_id=" + URLEncoder.encode(last.textValue(), StandardCharsets.UTF_8);
            }
            if (!more.booleanValue() && link != null) throw failure("invalid_pagination");
        }
        if (link == null) return null;
        try {
            // Only links resolving to this exact resource are valid; opaque cursors fail this
            // check.
            URI target =
                    link.startsWith("?")
                            ? URI.create(initial.toString() + link)
                            : current.resolve(link);
            if (!Objects.equals(initial.getScheme(), target.getScheme())
                    || !Objects.equals(initial.getHost(), target.getHost())
                    || port(initial) != port(target)
                    || !Objects.equals(initial.getRawPath(), target.getRawPath())
                    || target.getRawUserInfo() != null
                    || target.getRawFragment() != null) throw failure("invalid_pagination");
            return target;
        } catch (IllegalArgumentException invalid) {
            throw failure("invalid_pagination");
        }
    }

    private static String nextValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw failure("invalid_pagination");
        return value.textValue().isBlank() ? null : value.textValue();
    }

    private static String mergeLink(String previous, String next) {
        if (previous != null && next != null && !previous.equals(next))
            throw failure("invalid_pagination");
        return next == null ? previous : next;
    }

    private static URI initialUri(ModelCatalog.EndpointDraft draft) {
        try {
            URI uri = URI.create(OpenAiEndpointClient.base(draft) + "/models");
            if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) throw failure("invalid_address");
            return uri;
        } catch (RuntimeException invalid) {
            throw failure("invalid_address");
        }
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
    }

    private static String statusCode(int status) {
        return switch (status) {
            case 401, 403 -> "auth";
            case 404 -> "endpoint_not_found";
            case 405, 501 -> "unsupported";
            case 429 -> "rate_limited";
            default -> "upstream";
        };
    }

    private static boolean embeddingCapability(JsonNode item) {
        if (item.path("capabilities").path("embedding").asBoolean(false)
                || "embedding".equals(item.path("type").asText())
                || "embeddings".equals(item.path("type").asText())) return true;
        for (JsonNode endpoint : item.path("supported_endpoints"))
            if ("embeddings".equals(endpoint.asText()) || "/embeddings".equals(endpoint.asText()))
                return true;
        return false;
    }

    private static ModelProbe.DiscoveryResult result(
            LinkedHashSet<String> ids, boolean complete, java.util.Set<String> embeddingIds) {
        return new ModelProbe.DiscoveryResult(new ArrayList<>(ids), complete, embeddingIds);
    }

    private static ModelProbe.DiscoveryException failure(String code) {
        return new ModelProbe.DiscoveryException(code);
    }

    private record Page(JsonNode body, List<String> links) {}
}
