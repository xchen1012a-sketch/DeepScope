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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.deepscope.advisor.db.AdvisorSchema;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class AuthControllerTest {
    private AccountStore accounts;
    private DataSource dataSource;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:auth-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        accounts = new AccountStore(dataSource, Clock.systemUTC(), Duration.ofHours(12));
        accounts.bootstrap("test-admin", "test-password-only");
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AuthController(
                                        accounts,
                                        new io.deepscope.advisor.access.AccessGuard(
                                                new io.deepscope.advisor.access.AccessCatalog(
                                                        dataSource)),
                                        new io.deepscope.advisor.access.AccessCatalog(dataSource)),
                                new ProbeController())
                        .addFilters(new ApiAuthenticationFilter(accounts, "/v1/admin"))
                        .build();
    }

    @Test
    void acceptsCorrectPasswordUsingTheExistingFrontendContract() throws Exception {
        mvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"test-admin\",\"password\":\"test-password-only\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test-admin"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.roles[0]").value("admin"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void rejectsWrongCredentialsAndMissingIdentityTokens() throws Exception {
        mvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"test-admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/advisor/probe")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheServerOwnedIdentityAndRevokesLogout() throws Exception {
        String bearer =
                "Bearer "
                        + accounts.login("test-admin", "test-password-only").orElseThrow().token();
        mvc.perform(get("/api/auth/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test-admin"))
                .andExpect(jsonPath("$.isAdmin").value(true))
                .andExpect(jsonPath("$.nickname").value(""))
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());
        mvc.perform(post("/api/auth/logout").header("Authorization", bearer))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void selfServiceRoutesRequireATokenAndReportTheProfileFields() throws Exception {
        mvc.perform(get("/api/me/profile")).andExpect(status().isUnauthorized());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/me/profile"))
                .andExpect(status().isUnauthorized());

        String bearer =
                "Bearer "
                        + accounts.login("test-admin", "test-password-only").orElseThrow().token();
        var account = accounts.authenticate(bearer.substring(7)).orElseThrow();
        accounts.setNickname(account.userId(), "张三");
        accounts.setAvatar(account.userId(), "avatars/" + account.userId() + "/a.png");
        mvc.perform(get("/api/auth/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("张三"))
                .andExpect(
                        jsonPath("$.avatarUrl")
                                .value(org.hamcrest.Matchers.startsWith("/api/me/avatar?v=")));
    }

    @Test
    void protectsFrameworkAdminAndBusinessEndpointsAndLeavesPublicChatUnchanged() throws Exception {
        mvc.perform(get("/v1/admin/probe")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/probe")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/advisor/probe")).andExpect(status().isUnauthorized());
        mvc.perform(get("/public-chat-probe")).andExpect(status().isOk());
        String bearer =
                "Bearer "
                        + accounts.login("test-admin", "test-password-only").orElseThrow().token();
        mvc.perform(get("/v1/admin/probe").header("Authorization", bearer))
                .andExpect(status().isOk());
        mvc.perform(get("/api/advisor/probe").header("Authorization", bearer))
                .andExpect(status().isOk());
    }

    @Test
    void protectsAdminPathsWithMatrixParametersAndConfiguredPrefixes() throws Exception {
        mvc.perform(get("/v1/admin;metadata/probe")).andExpect(status().isUnauthorized());
        var configured =
                MockMvcBuilders.standaloneSetup(new ProbeController())
                        .addFilters(new ApiAuthenticationFilter(accounts, "/custom/admin/"))
                        .build();
        configured.perform(get("/custom/admin/probe")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectsAdminEndpointsUnderAConfiguredServletPrefix() throws Exception {
        mvc.perform(
                        get("/console/v1/admin/probe")
                                .with(
                                        request -> {
                                            request.setServletPath("/console");
                                            request.setPathInfo("/v1/admin/probe");
                                            return request;
                                        }))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationConfigReportsStatusAndMinLength() throws Exception {
        mvc.perform(get("/api/auth/registration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.minPasswordLength").value(8));
    }

    @Test
    void registrationRejectsWhenDisabled() throws Exception {
        AuthProperties disabledProps =
                new AuthProperties("", "", Duration.ofHours(12), false, "role-chat", 8);
        var accessCatalog = new io.deepscope.advisor.access.AccessCatalog(dataSource);
        var disabledController =
                new AuthController(
                        accounts,
                        new io.deepscope.advisor.access.AccessGuard(accessCatalog),
                        accessCatalog,
                        disabledProps);
        var disabledMvc =
                MockMvcBuilders.standaloneSetup(disabledController)
                        .addFilters(new ApiAuthenticationFilter(accounts, "/v1/admin"))
                        .build();

        disabledMvc
                .perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"newuser@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void registerSuccessfullyCreatesUserWithChatOnlyPermissionAndAutoLogsIn() throws Exception {
        AuthProperties props =
                new AuthProperties("", "", Duration.ofHours(12), true, "role-chat", 8);
        var accessCatalog = new io.deepscope.advisor.access.AccessCatalog(dataSource);
        accessCatalog.ensureBuiltin();
        var controller =
                new AuthController(
                        accounts,
                        new io.deepscope.advisor.access.AccessGuard(accessCatalog),
                        accessCatalog,
                        props);
        var registrationMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .addFilters(new ApiAuthenticationFilter(accounts, "/v1/admin"))
                        .build();

        var result =
                registrationMvc
                        .perform(
                                post("/api/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"email\":\"User@Example.COM\",\"password\":\"secretPass123\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.username").value("user@example.com"))
                        .andExpect(jsonPath("$.token").isNotEmpty())
                        .andExpect(jsonPath("$.userId").isNotEmpty())
                        .andExpect(jsonPath("$.roles[0]").value("user"))
                        .andReturn();

        String token =
                com.jayway.jsonpath.JsonPath.read(
                        result.getResponse().getContentAsString(), "$.token");

        registrationMvc
                .perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("user@example.com"))
                .andExpect(jsonPath("$.isAdmin").value(false))
                .andExpect(jsonPath("$.permissions").isArray())
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0]").value("chat.use"));

        // Idempotent registration with the same email (regardless of case) and matching password
        // returns 200 OK
        registrationMvc
                .perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"USER@EXAMPLE.COM\",\"password\":\"secretPass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("user@example.com"))
                .andExpect(jsonPath("$.token").isNotEmpty());

        // Same email with different password returns 409 Conflict
        registrationMvc
                .perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"user@example.com\",\"password\":\"anotherSecret\"}"))
                .andExpect(status().isConflict());

        registrationMvc
                .perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"USER@EXAMPLE.COM\",\"password\":\"anotherSecret\"}"))
                .andExpect(status().isConflict());

        // Password is not checked for cross-account deduplication: another account can use the
        // exact same password
        registrationMvc
                .perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"another@example.com\",\"password\":\"secretPass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("another@example.com"));

        // Login works with case variations of the registered email
        registrationMvc
                .perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"User@Example.COM\",\"password\":\"secretPass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("user@example.com"));

        registrationMvc
                .perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"another@example.com\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.error").isNotEmpty());

        registrationMvc
                .perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"not-an-email\",\"password\":\"secretPass123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @RestController
    static class ProbeController {
        @GetMapping({
            "/v1/admin/probe",
            "/api/admin/probe",
            "/api/advisor/probe",
            "/public-chat-probe"
        })
        public String probe() {
            return "ok";
        }
    }
}
