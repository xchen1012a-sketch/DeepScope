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
package io.deepscope.advisor.session;

import io.deepscope.advisor.access.AccessGuard;
import io.deepscope.advisor.access.AccessPoints;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import io.deepscope.advisor.model.ModelCatalog;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-scoped conversation model selection; does not mutate the system default. */
@RestController
@RequestMapping("/api/advisor/sessions/{sessionId}/model")
public final class SessionModelController {
    private final SessionStore sessions;
    private final ModelCatalog catalog;
    private final AccessGuard access;

    public SessionModelController(SessionStore sessions, ModelCatalog catalog, AccessGuard access) {
        this.sessions = sessions;
        this.catalog = catalog;
        this.access = access;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable String sessionId, HttpServletRequest request) throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        var session =
                sessions.find(sessionId).filter(row -> row.ownerUserId().equals(account.userId()));
        if (session.isEmpty()) return ResponseEntity.notFound().build();
        var selected =
                session.get().modelEndpointId() == null
                        ? catalog.current(ModelCatalog.CHAT)
                        : catalog.find(session.get().modelEndpointId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("endpointId", session.get().modelEndpointId());
        if (selected.isPresent()) {
            var endpoint = selected.get();
            view.put("modelName", endpoint.modelName());
            view.put("displayName", catalog.metadata(endpoint.endpointId()).get("displayName"));
            view.put("contextWindow", catalog.contextWindow(endpoint.endpointId()).orElse(0));
        }
        view.put("choices", access.manage(account, AccessPoints.SETTINGS) ? choices() : List.of());
        return ResponseEntity.ok(view);
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> select(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws SQLException {
        var account = ApiAuthenticationFilter.account(request).orElseThrow();
        if (sessions.find(sessionId)
                .filter(row -> row.ownerUserId().equals(account.userId()))
                .isEmpty()) return ResponseEntity.notFound().build();
        if (!access.manage(account, AccessPoints.SETTINGS))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        Object value = body.get("endpointId");
        String endpointId = value instanceof String id && !id.isBlank() ? id : null;
        if (value != null && endpointId == null)
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid model"));
        if (endpointId != null
                && catalog.find(endpointId)
                        .filter(row -> ModelCatalog.CHAT.equals(row.kind()))
                        .isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "Chat model is unavailable"));
        sessions.selectModel(sessionId, account.userId(), endpointId);
        return get(sessionId, request);
    }

    private List<Map<String, Object>> choices() throws SQLException {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (var endpoint : catalog.list(ModelCatalog.CHAT)) {
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("endpointId", endpoint.endpointId());
            choice.put("modelName", endpoint.modelName());
            choice.put("displayName", catalog.metadata(endpoint.endpointId()).get("displayName"));
            choice.put("current", endpoint.current());
            result.add(choice);
        }
        return result;
    }
}
