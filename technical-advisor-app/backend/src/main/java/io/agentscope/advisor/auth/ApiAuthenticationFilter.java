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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Authenticates the API surface that needs a logged-in visitor: framework administration, the
 * AG-UI chat surface, account endpoints and the advisor business API.
 *
 * <p>The resolved account is exposed through {@link #account(HttpServletRequest)} so business
 * controllers and services can attribute every resource to its owner (per-user isolation).
 */
public final class ApiAuthenticationFilter extends OncePerRequestFilter {
    static final String ACCOUNT_ATTRIBUTE = AccountStore.Account.class.getName();
    private final AccountStore accounts;
    private final String adminBasePath;

    public ApiAuthenticationFilter(AccountStore accounts, String adminBasePath) {
        this.accounts = accounts;
        this.adminBasePath = adminBasePath.replaceAll("/+$", "");
        if (!this.adminBasePath.startsWith("/") || this.adminBasePath.isEmpty())
            throw new IllegalArgumentException("Admin base path must be an absolute non-root path");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getLookupPathForRequest(request);
        if ("/api/advisor/models/active".equals(path)) {
            return true;
        }
        for (String prefix :
                List.of(
                        adminBasePath,
                        "/agui",
                        "/api/admin",
                        "/api/advisor",
                        "/api/auth/me",
                        "/api/auth/logout",
                        "/api/me")) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return false;
        }
        return true;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            var account = accounts.authenticate(bearerToken(request));
            if (account.isEmpty()) {
                String path = UrlPathHelper.defaultInstance.getLookupPathForRequest(request);
                if ("/api/advisor/commands".equals(path)) {
                    chain.doFilter(request, response);
                    return;
                }
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Unauthorized\"}");
                return;
            }
            request.setAttribute(ACCOUNT_ATTRIBUTE, account.orElseThrow());
            chain.doFilter(request, response);
        } catch (SQLException error) {
            throw new ServletException("Authentication storage unavailable", error);
        }
    }

    /** The account the current request is authenticated as, injected by this filter. */
    public static Optional<AccountStore.Account> account(HttpServletRequest request) {
        return Optional.ofNullable(request.getAttribute(ACCOUNT_ATTRIBUTE))
                .map(AccountStore.Account.class::cast);
    }

    public static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim()
                : null;
    }
}
