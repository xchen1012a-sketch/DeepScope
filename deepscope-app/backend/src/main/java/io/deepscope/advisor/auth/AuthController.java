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
package io.deepscope.advisor.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final io.deepscope.advisor.access.AccessGuard access;
    private final io.deepscope.advisor.access.AccessCatalog roles;
    private final AuthProperties authProperties;

    public AuthController(
            AccountStore accounts,
            io.deepscope.advisor.access.AccessGuard access,
            io.deepscope.advisor.access.AccessCatalog roles) {
        this(
                accounts,
                access,
                roles,
                new AuthProperties("", "", java.time.Duration.ofHours(12), true, "role-chat", 8));
    }

    @Autowired
    public AuthController(
            AccountStore accounts,
            io.deepscope.advisor.access.AccessGuard access,
            io.deepscope.advisor.access.AccessCatalog roles,
            AuthProperties authProperties) {
        this.accounts = accounts;
        this.access = access;
        this.roles = roles;
        this.authProperties = authProperties;
    }

    public record RegistrationConfigResponse(boolean enabled, int minPasswordLength) {}

    public record RegisterRequest(String email, String password) {
        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + "]";
        }
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

    @GetMapping("/api/auth/registration")
    public ResponseEntity<RegistrationConfigResponse> registrationConfig() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(
                        new RegistrationConfigResponse(
                                authProperties.registrationEnabled(),
                                authProperties.minPasswordLength()));
    }

    private static final java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static Map<String, String> errorMessage(String message) {
        return Map.of("message", message, "error", message);
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) throws SQLException {
        if (!authProperties.registrationEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(errorMessage("Registration is disabled"));
        }
        if (request.email() == null || request.email().isBlank()) {
            return ResponseEntity.badRequest()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(errorMessage("Email is required"));
        }
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(email).matches() || email.length() > 100) {
            return ResponseEntity.badRequest()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(errorMessage("Invalid email address format"));
        }
        if (request.password() == null
                || request.password().length() < authProperties.minPasswordLength()) {
            return ResponseEntity.badRequest()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(
                            errorMessage(
                                    "Password must be at least "
                                            + authProperties.minPasswordLength()
                                            + " characters"));
        }
        if (request.password().length() > 256) {
            return ResponseEntity.badRequest()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(errorMessage("Password is too long"));
        }

        try {
            var login = accounts.register(email, request.password(), authProperties.defaultRole());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(
                            new LoginResponse(
                                    login.token(),
                                    login.account().userId(),
                                    login.account().username(),
                                    List.of(login.account().role())));
        } catch (AccountStore.DuplicateAccountException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(errorMessage("Email already registered"));
        }
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) throws SQLException {
        var login = accounts.login(request.username(), request.password());
        if (login.isEmpty())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(errorMessage("Invalid credentials"));
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
                    .map(io.deepscope.advisor.access.AccessCatalog.RoleRow::name)
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
