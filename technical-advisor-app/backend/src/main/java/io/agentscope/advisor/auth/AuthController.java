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
package io.agentscope.advisor.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final AccountStore accounts;
    private final io.agentscope.advisor.access.AccessGuard access;
    private final io.agentscope.advisor.access.AccessCatalog roles;

    public AuthController(
            AccountStore accounts,
            io.agentscope.advisor.access.AccessGuard access,
            io.agentscope.advisor.access.AccessCatalog roles) {
        this.accounts = accounts;
        this.access = access;
        this.roles = roles;
    }

    public record LoginRequest(String username, String password) {
        @Override
        public String toString() {
            return "LoginRequest[username=" + username + "]";
        }
    }

    public record LoginResponse(String token, String userId, String username, List<String> roles) {}

    public record AccountResponse(
            String userId,
            String username,
            List<String> roles,
            List<String> permissions,
            boolean isAdmin,
            long createdAt,
            String nickname,
            String avatarUrl,
            long avatarUpdatedAt,
            List<String> roleNames) {}

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) throws SQLException {
        var login = accounts.login(request.username(), request.password());
        if (login.isEmpty())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(Map.of("error", "Invalid credentials"));
        var result = login.orElseThrow();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(
                        new LoginResponse(
                                result.token(),
                                result.account().userId(),
                                result.account().username(),
                                List.of(result.account().role())));
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<AccountResponse> me(HttpServletRequest request) throws SQLException {
        var account =
                ApiAuthenticationFilter.account(request)
                        .orElseThrow(
                                () -> new IllegalStateException("Filter did not authenticate"));
        var profile = accounts.profile(account.userId()).orElse(null);
        List<String> roleNames = new java.util.ArrayList<>();
        for (String roleId : roles.roleIds(account.userId())) {
            roles.findRole(roleId)
                    .map(io.agentscope.advisor.access.AccessCatalog.RoleRow::name)
                    .ifPresent(roleNames::add);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(
                        new AccountResponse(
                                account.userId(),
                                account.username(),
                                List.of(account.role()),
                                List.copyOf(access.granted(account)),
                                account.isAdmin(),
                                account.createdAt(),
                                profile == null ? "" : profile.nickname(),
                                profile == null || profile.avatarPath().isBlank()
                                        ? null
                                        : "/api/me/avatar?v=" + profile.avatarUpdatedAt(),
                                profile == null ? 0L : profile.avatarUpdatedAt(),
                                List.copyOf(roleNames)));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) throws SQLException {
        accounts.logout(ApiAuthenticationFilter.bearerToken(request));
        return ResponseEntity.noContent().build();
    }
}
