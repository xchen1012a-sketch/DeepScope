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
package io.deepscope.advisor.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.AdvisorProperties;
import io.deepscope.advisor.access.AccessGuard;
import io.deepscope.advisor.access.AccessPoints;
import io.deepscope.advisor.agent.AgentStore;
import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import io.deepscope.advisor.auth.AuthController;
import io.deepscope.advisor.knowledge.KnowledgeCatalog;
import io.deepscope.advisor.model.ModelCatalog;
import io.deepscope.advisor.session.SessionStore;
import io.deepscope.advisor.skill.SkillCatalog;
import io.deepscope.advisor.tool.ToolAuth;
import io.deepscope.advisor.tool.ToolCatalog;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Logged-in visitors manage only their own account, without a management permission grant. */
@RestController
@RequestMapping("/api/me")
public final class AccountSelfServiceController {
    private final AccountStore accounts;
    private final AuthController auth;
    private final AccessGuard access;
    private final AgentStore agents;
    private final SkillCatalog skills;
    private final KnowledgeCatalog knowledge;
    private final ToolCatalog tools;
    private final ModelCatalog models;
    private final AvatarStore avatars;
    private final AccountPreferencesStore preferences;
    private final SessionStore sessions;
    private final ObjectMapper json = new ObjectMapper();

    public AccountSelfServiceController(
            AccountStore accounts,
            AuthController auth,
            AccessGuard access,
            AgentStore agents,
            SkillCatalog skills,
            KnowledgeCatalog knowledge,
            ToolCatalog tools,
            ModelCatalog models,
            DataSource dataSource,
            SessionStore sessions,
            AdvisorProperties properties) {
        this.accounts = accounts;
        this.auth = auth;
        this.access = access;
        this.agents = agents;
        this.skills = skills;
        this.knowledge = knowledge;
        this.tools = tools;
        this.models = models;
        this.avatars = new AvatarStore(properties.dataDir(), accounts);
        this.preferences = new AccountPreferencesStore(dataSource);
        this.sessions = sessions;
    }

    @PutMapping("/profile")
    public ResponseEntity<AuthController.AccountResponse> profile(
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws SQLException {
        String nickname = text(body, "nickname", "invalid_nickname").strip();
        if (nickname.codePointCount(0, nickname.length()) > 40)
            throw problem("invalid_nickname", "Nickname must be at most 40 characters");
        accounts.setNickname(account(request).userId(), nickname);
        return auth.me(request);
    }

    public record PasswordChange(String currentPassword, String newPassword) {
        @Override
        public String toString() {
            return "PasswordChange[redacted]";
        }
    }

    @PutMapping("/password")
    public ResponseEntity<Void> password(
            @RequestBody PasswordChange body, HttpServletRequest request) throws SQLException {
        String replacement = body.newPassword();
        if (replacement == null
                || replacement.isBlank()
                || replacement.length() < 8
                || replacement.length() > 256)
            throw problem("weak_password", "New password must contain 8 to 256 characters");
        if (!accounts.changePassword(
                account(request).userId(),
                body.currentPassword(),
                replacement,
                ApiAuthenticationFilter.bearerToken(request)))
            throw problem("current_password_mismatch", "Current password is incorrect");
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping("/sessions/revoke-others")
    public ResponseEntity<Void> revokeOthers(HttpServletRequest request) throws SQLException {
        accounts.revokeOthers(
                account(request).userId(), ApiAuthenticationFilter.bearerToken(request));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<?> deleteIdleSession(
            @PathVariable String sessionId, HttpServletRequest request) throws SQLException {
        var owner = account(request);
        if (!access.allowed(owner, AccessPoints.CHAT)) return ResponseEntity.status(403).build();
        if (sessions.deleteIfNotRunning(sessionId, owner.userId()))
            return ResponseEntity.noContent().build();
        var remaining =
                sessions.find(sessionId).filter(row -> owner.userId().equals(row.ownerUserId()));
        if (remaining.isPresent() && "running".equals(remaining.get().status()))
            return ResponseEntity.status(409)
                    .body(
                            Map.of(
                                    "code",
                                    "sessions_running",
                                    "message",
                                    "Stop the running conversation before deleting it"));
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/avatar")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file, HttpServletRequest request)
            throws SQLException, IOException {
        try (var input = file.getInputStream()) {
            var profile = avatars.replace(account(request).userId(), file.getContentType(), input);
            return noStore(
                    Map.of(
                            "avatarUrl",
                            "/api/me/avatar?v=" + profile.avatarUpdatedAt(),
                            "avatarUpdatedAt",
                            profile.avatarUpdatedAt()));
        } catch (IllegalArgumentException error) {
            throw problem(
                    "invalid_avatar",
                    "Use PNG, JPEG or WebP up to 10 MiB and 8192 pixels per side");
        }
    }

    @GetMapping("/avatar")
    public ResponseEntity<byte[]> avatar(HttpServletRequest request)
            throws SQLException, IOException {
        var avatar = avatars.read(account(request).userId());
        if (avatar.isEmpty())
            return ResponseEntity.notFound().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, avatar.get().contentType())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .varyBy(HttpHeaders.AUTHORIZATION)
                .header("X-Content-Type-Options", "nosniff")
                .body(avatar.get().bytes());
    }

    @DeleteMapping("/avatar")
    public ResponseEntity<Void> deleteAvatar(HttpServletRequest request) throws SQLException {
        avatars.delete(account(request).userId());
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping("/preferences")
    public ResponseEntity<AccountPreferencesStore.Preferences> preferences(
            HttpServletRequest request) throws SQLException {
        return noStore(preferences.read(account(request).userId()));
    }

    @PutMapping("/preferences")
    public ResponseEntity<AccountPreferencesStore.Preferences> preferences(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String condition,
            HttpServletRequest request)
            throws SQLException {
        if (condition != null && !condition.equals("*"))
            throw problem("invalid_preferences", "Only If-None-Match: * is supported");
        if (!Set.of("thinkEnabled", "reasoningEffort", "defaultModelId", "defaultAgentId")
                .containsAll(body.keySet()))
            throw problem("invalid_preferences", "Unknown preference");
        Boolean think = null;
        if (body.containsKey("thinkEnabled")) {
            if (!(body.get("thinkEnabled") instanceof Boolean value))
                throw problem("invalid_preferences", "thinkEnabled must be a boolean");
            think = value;
        }
        String effort = null;
        if (body.containsKey("reasoningEffort")) {
            if (!(body.get("reasoningEffort") instanceof String value)
                    || !io.deepscope.advisor.model.SwitchingChatModel.REASONING_EFFORTS.contains(
                            value))
                throw problem(
                        "invalid_preferences",
                        "reasoningEffort must be one of low, medium, high, xhigh, max");
            effort = value;
        }
        String model =
                body.containsKey("defaultModelId")
                        ? text(body, "defaultModelId", "invalid_preferences")
                        : null;
        String agent =
                body.containsKey("defaultAgentId")
                        ? text(body, "defaultAgentId", "invalid_preferences")
                        : null;
        var account = account(request);
        if (model != null && !model.isEmpty()) validateModel(account, model);
        if (agent != null && !agent.isEmpty()) {
            boolean allowed =
                    access.allowed(account, AccessPoints.CHAT)
                            && agents.find(agent)
                                    .filter(
                                            row ->
                                                    AgentStore.canView(
                                                            row,
                                                            account.userId(),
                                                            access.manage(
                                                                    account,
                                                                    AccessPoints.AGENT_MANAGE)))
                                    .isPresent();
            if (!allowed) throw problem("invalid_preferences", "Default agent is unavailable");
        }
        var saved =
                preferences.write(account.userId(), think, effort, model, agent, condition != null);
        return saved.isPresent()
                ? noStore(saved.get())
                : ResponseEntity.status(412).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private void validateModel(AccountStore.Account account, String id) throws SQLException {
        var model = models.find(id).filter(row -> ModelCatalog.CHAT.equals(row.kind()));
        if (!access.allowed(account, AccessPoints.CHAT)
                || model.isEmpty()
                || !(model.get().current() || access.seesAll(account, AccessPoints.SETTINGS)))
            throw problem("invalid_preferences", "Default model is unavailable");
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(HttpServletRequest request)
            throws SQLException {
        var account = account(request);
        String userId = account.userId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "agents",
                visible(account, "agent") ? agents.listVisible(userId, false).size() : null);
        result.put(
                "skills",
                visible(account, "skill")
                        ? skills.page(userId, false, null, null, 1, 1).total()
                        : null);
        result.put(
                "knowledge",
                visible(account, "knowledge")
                        ? knowledge.page(userId, false, null, 1, 1).total()
                        : null);
        boolean toolVisible = visible(account, "tool");
        result.put(
                "tools",
                toolVisible ? tools.pageTools(userId, false, null, null, 1, 1).total() : null);
        result.put("credentials", toolVisible ? credentials(userId) : List.of());
        return noStore(result);
    }

    public record CredentialView(String toolId, String name, String origin, boolean configured) {}

    private List<CredentialView> credentials(String userId) throws SQLException {
        List<CredentialView> result = new ArrayList<>();
        int page = 1;
        while (true) {
            var batch = tools.pageTools(userId, false, null, null, page, 100);
            for (var tool : batch.items()) {
                boolean configured = ToolAuth.credentialId(tool.configJson()) != null;
                if (tool.mcpServerId() != null) {
                    var server = tools.findServer(tool.mcpServerId());
                    if (server.isPresent() && userId.equals(server.get().ownerUserId()))
                        configured |=
                                hasEntries(server.get().headersJson())
                                        || hasEntries(server.get().envJson());
                }
                result.add(
                        new CredentialView(tool.toolId(), tool.name(), tool.origin(), configured));
            }
            if (page >= batch.totalPages() || batch.items().isEmpty()) return result;
            page++;
        }
    }

    private boolean hasEntries(String value) throws SQLException {
        if (value == null || value.isBlank()) return false;
        try {
            var node = json.readTree(value);
            return node != null && node.isObject() && !node.isEmpty();
        } catch (IOException error) {
            throw new SQLException("Stored MCP configuration is invalid", error);
        }
    }

    private boolean visible(AccountStore.Account account, String domain) throws SQLException {
        return access.allowed(account, domain + ".view");
    }

    @GetMapping("/about")
    public ResponseEntity<Map<String, String>> about() {
        return noStore(
                Map.of("name", "DeepScope", "backendVersion", io.deepscope.core.Version.VERSION));
    }

    private static AccountStore.Account account(HttpServletRequest request) {
        return ApiAuthenticationFilter.account(request).orElseThrow();
    }

    private static String text(Map<String, Object> body, String key, String code) {
        if (!(body.get(key) instanceof String value)) throw problem(code, key + " must be text");
        return value;
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(value);
    }

    private static IllegalArgumentException problem(String code, String message) {
        return new AccountProblem(code, message);
    }

    private static final class AccountProblem extends IllegalArgumentException {
        private final String code;

        AccountProblem(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    @ExceptionHandler(AccountProblem.class)
    public ResponseEntity<Map<String, String>> invalid(AccountProblem error) {
        return ResponseEntity.badRequest()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("code", error.code, "message", error.getMessage()));
    }
}
