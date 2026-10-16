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
package io.agentscope.advisor.model;

import io.agentscope.advisor.auth.ApiAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Model settings; ordinary responses mask secrets, explicit copying requires separate permission. */
@RestController
public class AdvisorModelController {

    private final ModelCatalog catalog;
    private final ModelProbe probe;
    private final DiscoveredModelCache discoveredModels =
            new DiscoveredModelCache(java.time.Clock.systemUTC());
    private final io.agentscope.advisor.access.AccessGuard access;

    public AdvisorModelController(
            ModelCatalog catalog,
            ModelProbe probe,
            io.agentscope.advisor.access.AccessGuard access) {
        this.catalog = catalog;
        this.probe = probe;
        this.access = access;
    }

    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void refreshCurrentContextWindow() throws SQLException {
        var current = catalog.current(ModelCatalog.CHAT);
        if (current.isPresent()) {
            refreshContextWindow(current.get());
        }
    }

    private void refreshContextWindow(ModelCatalog.Endpoint endpoint) throws SQLException {
        var window =
                probe.contextWindow(
                        new ModelCatalog.EndpointDraft(
                                endpoint.kind(),
                                endpoint.provider(),
                                endpoint.modelName(),
                                endpoint.baseUrl(),
                                endpoint.secret()));
        int val =
                window.orElseGet(
                        () -> {
                            int inferred =
                                    io.agentscope.core.model.ModelContextWindows.infer(
                                            endpoint.modelName());
                            return inferred > 0
                                    ? inferred
                                    : io.agentscope.core.model.ModelContextWindows
                                            .DEFAULT_FALLBACK_WINDOW;
                        });
        catalog.saveContextWindow(endpoint.endpointId(), val);
    }

    /**
     * Public active chat model capability query. Returns model name and context window
     * for all users without requiring admin management permissions.
     */
    @GetMapping("/api/advisor/models/active")
    public ResponseEntity<Map<String, Object>> active() throws SQLException {
        var current = catalog.current(ModelCatalog.CHAT);
        if (current.isEmpty()) {
            return ResponseEntity.ok(
                    Map.of(
                            "modelName",
                            "none",
                            "contextWindow",
                            io.agentscope.core.model.ModelContextWindows.DEFAULT_FALLBACK_WINDOW));
        }
        ModelCatalog.Endpoint endpoint = current.get();
        int window =
                catalog.contextWindow(endpoint.endpointId())
                        .orElseGet(
                                () -> {
                                    int inferred =
                                            io.agentscope.core.model.ModelContextWindows.infer(
                                                    endpoint.modelName());
                                    return inferred > 0
                                            ? inferred
                                            : io.agentscope.core.model.ModelContextWindows
                                                    .DEFAULT_FALLBACK_WINDOW;
                                });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endpointId", endpoint.endpointId());
        body.put("provider", endpoint.provider());
        body.put("modelName", endpoint.modelName());
        body.put("contextWindow", window);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/advisor/models")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request)
            throws SQLException {
        if (forbidden(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var current = catalog.current(ModelCatalog.CHAT);
        if (current.isPresent() && catalog.contextWindow(current.get().endpointId()).isEmpty()) {
            refreshContextWindow(current.get());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "defaults",
                Map.of(
                        "chat",
                        views(catalog.current(ModelCatalog.CHAT).stream().toList()),
                        "embedding",
                        views(catalog.current(ModelCatalog.EMBEDDING).stream().toList())));
        body.put("chat", views(catalog.list(ModelCatalog.CHAT, request.getParameter("q"))));
        body.put(
                "embedding",
                views(catalog.list(ModelCatalog.EMBEDDING, request.getParameter("q"))));
        ModelCatalog.UsagePolicy usage = catalog.usage();
        body.put(
                "usage",
                Map.of(
                        "turnTokenLimit", usage.turnTokenLimit(),
                        "dailyTaskLimit", usage.dailyTaskLimit(),
                        "ratePerMinute", usage.ratePerMinute()));
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/api/advisor/models", params = "page")
    public ResponseEntity<Map<String, Object>> page(HttpServletRequest request)
            throws SQLException {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String kind = request.getParameter("kind");
        if (kind == null) kind = ModelCatalog.CHAT;
        if (!ModelCatalog.CHAT.equals(kind) && !ModelCatalog.EMBEDDING.equals(kind))
            return ResponseEntity.badRequest().build();
        var rows =
                catalog.page(
                        kind,
                        request.getParameter("provider"),
                        request.getParameter("q"),
                        io.agentscope.advisor.db.JdbcPage.page(request),
                        io.agentscope.advisor.db.JdbcPage.size(request),
                        request.getParameter("presetId"));
        var body = new LinkedHashMap<String, Object>();
        body.put("items", views(rows.items()));
        body.put("page", rows.page());
        body.put("size", rows.size());
        body.put("total", rows.total());
        body.put("totalPages", rows.totalPages());
        var defaults = views(catalog.current(kind).stream().toList());
        body.put("defaultModel", defaults.isEmpty() ? null : defaults.get(0));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/advisor/models")
    public ResponseEntity<?> connect(
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws SQLException {
        return save(null, body, request);
    }

    @PutMapping("/api/advisor/models/{endpointId}")
    public ResponseEntity<?> update(
            @PathVariable String endpointId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        return save(endpointId, body, request);
    }

    @PostMapping("/api/advisor/models/{endpointId}/copy-config")
    public ResponseEntity<?> copyConfig(@PathVariable String endpointId, HttpServletRequest request)
            throws SQLException {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        var endpoint = catalog.find(endpointId).orElse(null);
        if (endpoint == null) return ResponseEntity.notFound().build();
        var preset = ModelProviderPresets.identify(endpoint.provider(), endpoint.baseUrl());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("provider", endpoint.provider());
        config.put("kind", endpoint.kind());
        config.put("displayName", catalog.metadata(endpointId).get("displayName"));
        config.put(
                "providerName",
                preset.map(ModelProviderPresets.ProviderView::name).orElse(endpoint.provider()));
        config.put("baseUrl", endpoint.baseUrl());
        config.put("modelName", endpoint.modelName());
        config.put("secret", endpoint.secret());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(config);
    }

    @PostMapping("/api/advisor/models/{endpointId}/copy")
    public ResponseEntity<?> copyModel(
            @PathVariable String endpointId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        var source = catalog.find(endpointId).orElse(null);
        if (source == null) return ResponseEntity.notFound().build();
        String name = text(body, "displayName");
        if (name == null) name = catalog.metadata(endpointId).get("displayName") + " 副本";
        var copy = catalog.insert(draft(Map.of(), source), source.dimensions(), name, false);
        var contextWindow = catalog.contextWindow(endpointId);
        if (contextWindow.isPresent())
            catalog.saveContextWindow(copy.endpointId(), contextWindow.get());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(copy));
    }

    private ResponseEntity<?> save(String id, Map<String, Object> body, HttpServletRequest request)
            throws SQLException {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        var original = id == null ? null : catalog.find(id).orElse(null);
        if (id != null && original == null) return ResponseEntity.notFound().build();
        if (unsafeSecretReuse(body, original))
            return problem(HttpStatus.BAD_REQUEST, "更改连接地址或协议后请重新填写密钥");
        if (original != null
                && ((text(body, "provider") != null
                                && !original.provider().equals(text(body, "provider"))
                                && !("custom".equals(text(body, "provider"))
                                        && !"anthropic".equals(original.provider())))
                        || (text(body, "kind") != null
                                && !original.kind().equals(text(body, "kind"))))) {
            return problem(HttpStatus.BAD_REQUEST, "供应商和模型类型不可修改");
        }
        var draft = draft(body, original);
        if (!valid(draft)) return problem(HttpStatus.BAD_REQUEST, "请填写供应商、模型和密钥");
        Boolean knowledgePdf =
                body.containsKey("knowledgePdf")
                        ? Boolean.TRUE.equals(body.get("knowledgePdf"))
                        : null;
        boolean assignedPdf =
                original != null
                        && catalog.knowledgePdf()
                                .map(value -> value.endpointId().equals(original.endpointId()))
                                .orElse(false);
        if ((Boolean.TRUE.equals(knowledgePdf) || knowledgePdf == null && assignedPdf)
                && (!ModelCatalog.CHAT.equals(draft.kind()) || !probe.supportsImages(draft)))
            return problem(HttpStatus.BAD_REQUEST, "该模型未通过图片识别验证，不能用于知识库 PDF 解析");
        if (!ModelProviderPresets.supportsConnection(draft))
            return problem(HttpStatus.BAD_REQUEST, "服务商不支持向量模型");
        long started = System.nanoTime();
        var result = probe.check(draft);
        long elapsed = elapsed(started);
        if (!result.ok()) return problem(HttpStatus.BAD_GATEWAY, safeFailure(result, draft));
        if (ModelCatalog.EMBEDDING.equals(draft.kind())
                && (result.dimensions() == null || result.dimensions() < 1))
            return problem(HttpStatus.BAD_GATEWAY, "向量响应无效");
        boolean current = Boolean.TRUE.equals(body.get("makeCurrent"));
        String name = text(body, "displayName");
        if (name == null)
            name =
                    original == null
                            ? draft.modelName()
                            : (String) catalog.metadata(id).get("displayName");
        try {
            var endpoint =
                    original == null
                            ? catalog.insert(
                                    draft, result.dimensions(), name, current, knowledgePdf)
                            : catalog.update(
                                    id, draft, result.dimensions(), name, current, knowledgePdf);
            catalog.recordTest(endpoint.endpointId(), result, elapsed);
            if (ModelCatalog.CHAT.equals(endpoint.kind())) refreshContextWindow(endpoint);
            return ResponseEntity.status(original == null ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(view(endpoint));
        } catch (IllegalArgumentException conflict) {
            return problem(HttpStatus.CONFLICT, conflict.getMessage());
        }
    }

    @PostMapping("/api/advisor/models/test")
    public ResponseEntity<?> testDraft(
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws SQLException {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String id = text(body, "endpointId");
        var original = id == null ? null : catalog.find(id).orElse(null);
        if (id != null && original == null) return ResponseEntity.notFound().build();
        if (unsafeSecretReuse(body, original))
            return problem(HttpStatus.BAD_REQUEST, "更改连接地址或协议后请重新填写密钥");
        var draft = draft(body, original);
        if (!valid(draft)) return problem(HttpStatus.BAD_REQUEST, "请填写供应商、模型和密钥");
        long started = System.nanoTime();
        var result = probe.check(draft);
        return ResponseEntity.ok(testView(result, elapsed(started), draft));
    }

    @PostMapping("/api/advisor/models/{endpointId}/test")
    public ResponseEntity<?> testSaved(@PathVariable String endpointId, HttpServletRequest request)
            throws SQLException {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        var endpoint = catalog.find(endpointId).orElse(null);
        if (endpoint == null) return ResponseEntity.notFound().build();
        var draft = draft(Map.of(), endpoint);
        long started = System.nanoTime();
        var raw = probe.check(draft);
        var result = raw.ok() ? raw : ModelProbe.Result.failure(safeFailure(raw, draft));
        long elapsed = elapsed(started);
        catalog.recordTest(endpointId, result, elapsed);
        return ResponseEntity.ok(testView(result, elapsed, draft));
    }

    @PostMapping("/api/advisor/models/discover")
    public ResponseEntity<?> discover(
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws SQLException {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String id = text(body, "endpointId");
        var original = id == null ? null : catalog.find(id).orElse(null);
        if (id != null && original == null) return ResponseEntity.notFound().build();
        if (unsafeSecretReuse(body, original))
            return problem(HttpStatus.BAD_REQUEST, "更改连接地址或协议后请重新填写密钥");
        var draft = draft(body, original);
        if (!ModelProviderPresets.validKind(draft.kind())
                || draft.provider() == null
                || draft.secret() == null) return problem(HttpStatus.BAD_REQUEST, "请填写供应商和密钥");
        discoveredModels.invalidate(draft);
        try {
            var result = discoveredModels.putResult(draft, probe.discoverResult(draft));
            return ResponseEntity.ok(
                    Boolean.TRUE.equals(body.get("detailed"))
                            ? Map.of(
                                    "items",
                                    result.items(),
                                    "total",
                                    result.items().size(),
                                    "complete",
                                    result.complete(),
                                    "status",
                                    result.items().isEmpty() ? "empty" : "discovered")
                            : result.items());
        } catch (ModelProbe.DiscoveryException failure) {
            return discoveryFailure(failure.code());
        } catch (IllegalArgumentException unavailable) {
            return discoveryFailure("upstream");
        }
    }

    private static ResponseEntity<?> discoveryFailure(String code) {
        String known =
                switch (code) {
                    case "unsupported",
                            "endpoint_not_found",
                            "auth",
                            "rate_limited",
                            "timeout",
                            "invalid_response",
                            "invalid_pagination",
                            "invalid_address",
                            "interrupted" ->
                            code;
                    default -> "upstream";
                };
        HttpStatus status =
                "unsupported".equals(known)
                        ? HttpStatus.UNPROCESSABLE_ENTITY
                        : "invalid_address".equals(known)
                                ? HttpStatus.BAD_REQUEST
                                : "timeout".equals(known)
                                        ? HttpStatus.GATEWAY_TIMEOUT
                                        : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status)
                .body(
                        Map.of(
                                "error",
                                "model_discovery_" + known,
                                "message",
                                "模型列表获取未完成，请检查当前连接或手动输入模型 ID"));
    }

    @GetMapping("/api/advisor/models/providers")
    public ResponseEntity<?> providers(HttpServletRequest request) {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String kind = request.getParameter("kind");
        if (kind == null) kind = ModelCatalog.CHAT;
        if (!ModelProviderPresets.validKind(kind)) return problem(HttpStatus.BAD_REQUEST, "模型类型无效");
        try {
            return ResponseEntity.ok(
                    ModelProviderPresets.search(
                            kind,
                            request.getParameter("q"),
                            ModelProviderPresets.limit(request.getParameter("limit"))));
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @PostMapping("/api/advisor/models/options")
    public ResponseEntity<?> options(
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws SQLException {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String id = text(body, "endpointId");
        var original = id == null ? null : catalog.find(id).orElse(null);
        if (id != null && original == null) return ResponseEntity.notFound().build();
        if (unsafeSecretReuse(body, original))
            return problem(HttpStatus.BAD_REQUEST, "更改连接地址或协议后请重新填写密钥");
        var draft = draft(body, original);
        if (!ModelProviderPresets.validKind(draft.kind()) || draft.provider() == null)
            return problem(HttpStatus.BAD_REQUEST, "请填写模型类型和供应商");
        try {
            int limit = ModelProviderPresets.limit(body.get("limit"));
            int page = 1;
            if (body.get("page") != null) {
                try {
                    page = Integer.parseInt(body.get("page").toString());
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("page 必须为正整数");
                }
                if (page < 1 || page > 100000) throw new IllegalArgumentException("page 超出范围");
            }
            var cacheEntry = discoveredModels.getSnapshot(draft);
            String snapshot = cacheEntry.map(DiscoveredModelCache.Snapshot::id).orElse("preset");
            String requestedSnapshot = text(body, "snapshot");
            if (requestedSnapshot != null && !requestedSnapshot.equals(snapshot))
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "model_options_changed", "message", "模型列表已更新，请重新加载"));
            var cached = cacheEntry.map(DiscoveredModelCache.Snapshot::models);
            var models =
                    cached.map(ModelProbe.DiscoveryResult::items)
                            .orElseGet(
                                    () ->
                                            ModelProviderPresets.models(
                                                    draft, text(body, "presetId")));
            String query = ModelProviderPresets.keyword(text(body, "q"));
            var matches =
                    models.stream()
                            .filter(model -> ModelProviderPresets.keyword(model).contains(query))
                            .toList();
            long offset = (long) (page - 1) * limit;
            var items = matches.stream().skip(offset).limit(limit).toList();
            return ResponseEntity.ok(
                    Map.of(
                            "items",
                            items,
                            "total",
                            matches.size(),
                            "page",
                            page,
                            "snapshot",
                            snapshot,
                            "limit",
                            limit,
                            "hasMore",
                            offset + items.size() < matches.size(),
                            "source",
                            cached.isPresent() ? "discovered" : "preset",
                            "complete",
                            cached.map(ModelProbe.DiscoveryResult::complete).orElse(false)));
        } catch (IllegalArgumentException invalid) {
            return problem(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @PostMapping("/api/advisor/models/{endpointId}/current")
    public ResponseEntity<?> makeCurrent(
            @PathVariable String endpointId, HttpServletRequest request) throws SQLException {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        var endpoint = catalog.find(endpointId).orElse(null);
        if (endpoint == null) return ResponseEntity.notFound().build();
        var draft = draft(Map.of(), endpoint);
        long started = System.nanoTime();
        var raw = probe.check(draft);
        var result = raw.ok() ? raw : ModelProbe.Result.failure(safeFailure(raw, draft));
        catalog.recordTest(endpointId, result, elapsed(started));
        if (!result.ok()) return problem(HttpStatus.BAD_GATEWAY, result.failure());
        try {
            catalog.makeCurrent(endpointId);
        } catch (IllegalArgumentException conflict) {
            return problem(HttpStatus.CONFLICT, conflict.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/advisor/models/{endpointId}")
    public ResponseEntity<?> delete(@PathVariable String endpointId, HttpServletRequest request)
            throws SQLException {
        if (forbidden(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (catalog.find(endpointId).isEmpty()) return ResponseEntity.notFound().build();
        try {
            catalog.delete(endpointId);
        } catch (IllegalArgumentException conflict) {
            return problem(HttpStatus.CONFLICT, conflict.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    private static ModelCatalog.EndpointDraft draft(
            Map<String, Object> body, ModelCatalog.Endpoint original) {
        return new ModelCatalog.EndpointDraft(
                value(body, "kind", original == null ? null : original.kind()),
                value(body, "provider", original == null ? null : original.provider()),
                value(body, "modelName", original == null ? null : original.modelName()),
                body.containsKey("baseUrl")
                        ? text(body, "baseUrl")
                        : original == null ? null : original.baseUrl(),
                value(body, "secret", original == null ? null : original.secret()));
    }

    private static boolean unsafeSecretReuse(
            Map<String, Object> body, ModelCatalog.Endpoint original) {
        if (original == null || text(body, "secret") != null) return false;
        var connection = draft(body, original);
        return !ModelProviderPresets.connectionUrl(original.provider(), original.baseUrl())
                        .equals(
                                ModelProviderPresets.connectionUrl(
                                        connection.provider(), connection.baseUrl()))
                || "anthropic".equals(original.provider())
                        != "anthropic".equals(connection.provider());
    }

    private static String value(Map<String, Object> body, String key, String fallback) {
        return text(body, key) == null ? fallback : text(body, key);
    }

    private static boolean valid(ModelCatalog.EndpointDraft draft) {
        return (ModelCatalog.CHAT.equals(draft.kind())
                        || ModelCatalog.EMBEDDING.equals(draft.kind()))
                && draft.provider() != null
                && draft.modelName() != null
                && draft.secret() != null;
    }

    private static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static String safeFailure(ModelProbe.Result result, ModelCatalog.EndpointDraft draft) {
        String message = result.failure();
        if (message == null || (draft.secret() != null && message.contains(draft.secret())))
            return "连接失败，请检查配置";
        return message;
    }

    private static Map<String, Object> testView(
            ModelProbe.Result result, long elapsed, ModelCatalog.EndpointDraft draft) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", result.ok());
        response.put("message", result.ok() ? "连接成功" : safeFailure(result, draft));
        response.put("elapsedMs", elapsed);
        response.put("dimensions", result.dimensions());
        return response;
    }

    private static ResponseEntity<?> problem(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }

    @PutMapping("/api/advisor/models/usage")
    public ResponseEntity<Void> usage(
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws SQLException {
        if (forbidden(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        catalog.saveUsage(
                new ModelCatalog.UsagePolicy(
                        number(body, "turnTokenLimit"),
                        number(body, "dailyTaskLimit"),
                        number(body, "ratePerMinute")));
        return ResponseEntity.noContent().build();
    }

    private boolean forbidden(HttpServletRequest request) {
        return ApiAuthenticationFilter.account(request)
                .filter(
                        account -> {
                            try {
                                return access.allowed(
                                        account,
                                        io.agentscope.advisor.access.AccessPoints.operation(
                                                request, "model"));
                            } catch (java.sql.SQLException error) {
                                return false;
                            }
                        })
                .isEmpty();
    }

    private List<Map<String, Object>> views(List<ModelCatalog.Endpoint> endpoints)
            throws SQLException {
        List<Map<String, Object>> views = new ArrayList<>();
        for (ModelCatalog.Endpoint endpoint : endpoints) {
            views.add(view(endpoint));
        }
        return views;
    }

    private Map<String, Object> view(ModelCatalog.Endpoint endpoint) throws SQLException {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("endpointId", endpoint.endpointId());
        view.put("kind", endpoint.kind());
        view.put("provider", endpoint.provider());
        var preset = ModelProviderPresets.identify(endpoint.provider(), endpoint.baseUrl());
        view.put("presetId", preset.map(ModelProviderPresets.ProviderView::id).orElse("custom"));
        view.put(
                "providerKeysUrl",
                preset.map(ModelProviderPresets.ProviderView::keysUrl).orElse(null));
        view.put(
                "providerName",
                preset.map(ModelProviderPresets.ProviderView::name)
                        .orElse(
                                "anthropic".equals(endpoint.provider())
                                        ? "Anthropic 兼容服务"
                                        : "OpenAI 兼容服务"));
        view.put("modelName", endpoint.modelName());
        view.put("baseUrl", endpoint.baseUrl());
        view.put("secretMask", ModelCatalog.mask(endpoint.secret()));
        view.put("current", endpoint.current());
        view.put(
                "knowledgePdf",
                catalog.knowledgePdf()
                        .map(value -> value.endpointId().equals(endpoint.endpointId()))
                        .orElse(false));
        view.putAll(catalog.metadata(endpoint.endpointId()));
        catalog.contextWindow(endpoint.endpointId())
                .ifPresent(window -> view.put("contextWindow", window));
        view.put("dimensions", endpoint.dimensions());
        return view;
    }

    private static String text(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        String value = body.get(key).toString().strip();
        return value.isBlank() ? null : value;
    }

    private static int number(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        return 0;
    }
}
