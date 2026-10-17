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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.deepscope.advisor.access.AccessCatalog;
import io.deepscope.advisor.access.AccessPoints;
import io.deepscope.advisor.agent.AgentStore;
import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.auth.ApiAuthenticationFilter;
import io.deepscope.advisor.knowledge.KnowledgeCatalog;
import io.deepscope.advisor.model.ModelCatalog;
import io.deepscope.advisor.skill.SkillCatalog;
import io.deepscope.advisor.tool.ToolCatalog;
import io.deepscope.advisor.tool.ToolImport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class AccountSelfServiceTest {
    @TempDir static Path root;
    private static final String PASSWORD = "test-only-password";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("advisor.data-dir", root::toString);
        registry.add("deepscope.openai.api-key", () -> "test-only-key");
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:account-self-service;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired WebApplicationContext context;
    @Autowired ApiAuthenticationFilter authentication;
    @Autowired AccountStore accounts;
    @Autowired AccessCatalog access;
    @Autowired AgentStore agents;
    @Autowired SkillCatalog skills;
    @Autowired KnowledgeCatalog knowledge;
    @Autowired ToolCatalog tools;
    @Autowired ModelCatalog models;
    @Autowired DataSource dataSource;
    MockMvc mvc;
    AccountStore.Login login;
    String bearer;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(authentication).build();
        String username = "self-" + UUID.randomUUID();
        accounts.createUser(username, PASSWORD);
        login = accounts.login(username, PASSWORD).orElseThrow();
        bearer = "Bearer " + login.token();
    }

    @Test
    void profileReadsTheManagedAccountAndAssignedRoleNames() throws Exception {
        String userId = login.account().userId();
        accounts.updateProfile(userId, "renamed-" + UUID.randomUUID(), "Alice", "");
        String username = accounts.profile(userId).orElseThrow().username();
        var role = access.createRole("分析员", "analyst-" + UUID.randomUUID(), 1, "");
        access.replaceGrants(userId, List.of(role.roleId()));
        mvc.perform(get("/api/auth/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.roleNames[0]").value("分析员"));
        access.updateRole(role, "高级分析员", role.code(), 1, "");
        mvc.perform(get("/api/auth/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleNames[0]").value("高级分析员"));
    }

    @Test
    void safeSessionDeletionRejectsRunningAndForeignSessions() throws Exception {
        var sessions = context.getBean(io.deepscope.advisor.session.SessionStore.class);
        var owned = sessions.create(login.account().userId(), null);
        var foreign = sessions.create("another-owner", null);
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE advisor_sessions SET status='running' WHERE"
                                        + " session_id=?")) {
            update.setString(1, owned.sessionId());
            update.executeUpdate();
        }
        mvc.perform(delete("/api/me/sessions/" + owned.sessionId()).header("Authorization", bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("sessions_running"));
        mvc.perform(
                        delete("/api/me/sessions/" + foreign.sessionId())
                                .header("Authorization", bearer))
                .andExpect(status().isNotFound());
        assertTrue(sessions.find(owned.sessionId()).isPresent());
        assertTrue(sessions.find(foreign.sessionId()).isPresent());
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE advisor_sessions SET status='idle' WHERE session_id=?")) {
            update.setString(1, owned.sessionId());
            update.executeUpdate();
        }
        mvc.perform(delete("/api/me/sessions/" + owned.sessionId()).header("Authorization", bearer))
                .andExpect(status().isNoContent());
        assertTrue(sessions.find(owned.sessionId()).isEmpty());
    }

    @Test
    void profileUpdatesOnlyOwnNicknameAndKeepsAdministratorRemark() throws Exception {
        accounts.updateProfile(login.account().userId(), "Before", "Administrator note");
        mvc.perform(json(put("/api/me/profile"), "{\"nickname\":\"  张三  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(login.account().userId()))
                .andExpect(jsonPath("$.nickname").value("张三"))
                .andExpect(jsonPath("$.permissions").isArray())
                .andExpect(jsonPath("$.avatarPath").doesNotExist());
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT remark FROM accounts WHERE user_id=?")) {
            statement.setString(1, login.account().userId());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("Administrator note", rows.getString(1));
            }
        }
        mvc.perform(json(put("/api/me/profile"), "{\"nickname\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value(""));
    }

    @Test
    void profileRejectsMissingNonTextAndOverlongValuesWithoutChangingProfile() throws Exception {
        for (String body :
                List.of(
                        "{}",
                        "{\"nickname\":null}",
                        "{\"nickname\":42}",
                        "{\"nickname\":\"" + "字".repeat(41) + "\"}")) {
            mvc.perform(json(put("/api/me/profile"), body)).andExpect(status().isBadRequest());
        }
        assertEquals("", accounts.profile(login.account().userId()).orElseThrow().nickname());
    }

    @Test
    void changingPasswordRevokesOtherSessionsAndKeepsCurrentSession() throws Exception {
        var other = accounts.login(login.account().username(), PASSWORD).orElseThrow();
        mvc.perform(
                        json(
                                put("/api/me/password"),
                                "{\"currentPassword\":\""
                                        + PASSWORD
                                        + "\",\"newPassword\":\"new-test-password\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isUnauthorized());
        assertTrue(accounts.login(login.account().username(), PASSWORD).isEmpty());
        assertTrue(accounts.login(login.account().username(), "new-test-password").isPresent());
    }

    @Test
    void invalidPasswordChangesDoNotChangePasswordOrRevokeSessions() throws Exception {
        var other = accounts.login(login.account().username(), PASSWORD).orElseThrow();
        mvc.perform(
                        json(
                                put("/api/me/password"),
                                "{\"currentPassword\":\"wrong\",\"newPassword\":\"new-test-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("current_password_mismatch"));
        for (String password : List.of("short", "x".repeat(257))) {
            mvc.perform(
                            json(
                                    put("/api/me/password"),
                                    "{\"currentPassword\":\""
                                            + PASSWORD
                                            + "\",\"newPassword\":\""
                                            + password
                                            + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("weak_password"));
        }
        assertTrue(accounts.verifyPassword(login.account().userId(), PASSWORD));
        assertTrue(accounts.authenticate(other.token()).isPresent());
    }

    @Test
    void explicitlyRevokingOtherSessionsCannotAffectAnotherAccount() throws Exception {
        var otherSession = accounts.login(login.account().username(), PASSWORD).orElseThrow();
        String strangerName = "stranger-" + UUID.randomUUID();
        accounts.createUser(strangerName, PASSWORD);
        var stranger = accounts.login(strangerName, PASSWORD).orElseThrow();
        mvc.perform(post("/api/me/sessions/revoke-others").header("Authorization", bearer))
                .andExpect(status().isNoContent());
        assertTrue(accounts.authenticate(login.token()).isPresent());
        assertTrue(accounts.authenticate(otherSession.token()).isEmpty());
        assertTrue(accounts.authenticate(stranger.token()).isPresent());
    }

    @Test
    void avatarsAreAuthenticatedReplaceOldFilesAndDeleteBackToMissing() throws Exception {
        byte[] png = image("png", 8, 8);
        mvc.perform(
                        multipart("/api/me/avatar")
                                .file(
                                        new MockMultipartFile(
                                                "file", "../../untrusted.png", "image/png", png))
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").isNotEmpty());
        var first = accounts.profile(login.account().userId()).orElseThrow();
        Path oldFile = root.resolve(first.avatarPath());
        assertTrue(Files.exists(oldFile));
        mvc.perform(get("/api/me/avatar").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "private, max-age=86400"))
                .andExpect(
                        header().string(
                                        "Vary",
                                        org.hamcrest.Matchers.containsString("Authorization")))
                .andExpect(content().bytes(png));
        mvc.perform(
                        multipart("/api/me/avatar")
                                .file(
                                        new MockMultipartFile(
                                                "file", "x.jpg", "image/jpeg", image("jpg", 8, 8)))
                                .header("Authorization", bearer))
                .andExpect(status().isOk());
        var second = accounts.profile(login.account().userId()).orElseThrow();
        assertFalse(Files.exists(oldFile));
        assertNotEquals(first.avatarPath(), second.avatarPath());
        assertTrue(second.avatarUpdatedAt() > first.avatarUpdatedAt());
        mvc.perform(delete("/api/me/avatar").header("Authorization", bearer))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me/avatar").header("Authorization", bearer))
                .andExpect(status().isNotFound());
        assertFalse(Files.exists(root.resolve(second.avatarPath())));
    }

    @Test
    void rejectsInvalidAvatarContentsSizesAndDimensionsWithoutReplacingOldAvatar()
            throws Exception {
        for (MockMultipartFile file :
                List.of(
                        new MockMultipartFile(
                                "file", "x.svg", "image/svg+xml", "<svg/>".getBytes()),
                        new MockMultipartFile(
                                "file", "x.png", "image/png", "not an image".getBytes()),
                        new MockMultipartFile(
                                "file", "x.png", "image/png", new byte[10 * 1024 * 1024 + 1]),
                        new MockMultipartFile("file", "x.png", "image/png", image("png", 8193, 1)),
                        new MockMultipartFile(
                                "file", "x.webp", "image/webp", image("png", 1, 1)))) {
            mvc.perform(multipart("/api/me/avatar").file(file).header("Authorization", bearer))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_avatar"));
        }
        assertEquals("", accounts.profile(login.account().userId()).orElseThrow().avatarPath());
    }

    @Test
    void anAvatarCannotReadOrDeleteAnUnmanagedHistoricalPath() throws Exception {
        Path outside = root.resolve("unmanaged.png");
        byte[] png = image("png", 1, 1);
        Files.write(outside, png);
        accounts.setAvatar(login.account().userId(), outside.toString());
        mvc.perform(get("/api/me/avatar").header("Authorization", bearer))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/me/avatar").header("Authorization", bearer))
                .andExpect(status().isNoContent());
        assertArrayEquals(png, Files.readAllBytes(outside));
    }

    @Test
    void preferencesDefaultAndPartialUpdatesArePersistedOnlyForTheCaller() throws Exception {
        mvc.perform(get("/api/me/preferences").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thinkEnabled").value(false))
                .andExpect(jsonPath("$.reasoningEffort").value("high"))
                .andExpect(jsonPath("$.defaultModelId").value(""))
                .andExpect(jsonPath("$.defaultAgentId").value(""));
        var agent = agents.create("Mine", "", login.account().userId());
        mvc.perform(
                        json(
                                put("/api/me/preferences"),
                                "{\"thinkEnabled\":true,\"defaultAgentId\":\""
                                        + agent.agentId()
                                        + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thinkEnabled").value(true));
        mvc.perform(json(put("/api/me/preferences"), "{\"defaultAgentId\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thinkEnabled").value(true));
        mvc.perform(json(put("/api/me/preferences"), "{\"reasoningEffort\":\"max\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasoningEffort").value("max"));
        mvc.perform(get("/api/me/preferences").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thinkEnabled").value(true))
                .andExpect(jsonPath("$.reasoningEffort").value("max"))
                .andExpect(jsonPath("$.defaultAgentId").value(""));
    }

    @Test
    void invalidPreferenceReferencesAndTypesAreRejectedAtomically() throws Exception {
        var foreign = agents.create("Other", "", "another-owner");
        var model =
                models.insert(
                        new ModelCatalog.EndpointDraft(
                                "chat", "openai", "fake", "http://127.0.0.1:1", "fake-secret"),
                        null,
                        "fake",
                        false);
        for (String body :
                List.of(
                        "{\"thinkEnabled\":true,\"defaultAgentId\":\"" + foreign.agentId() + "\"}",
                        "{\"defaultModelId\":\"" + model.endpointId() + "\"}",
                        "{\"defaultModelId\":\"missing\"}",
                        "{\"thinkEnabled\":\"true\"}",
                        "{\"reasoningEffort\":\"ultra\"}",
                        "{\"reasoningEffort\":true}",
                        "{\"defaultAgentId\":null}")) {
            mvc.perform(json(put("/api/me/preferences"), body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/me/preferences").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thinkEnabled").value(false));
    }

    @Test
    void overviewCountsOnlyOwnResourcesAndNeverReturnsCredentialContents() throws Exception {
        String userId = login.account().userId();
        agents.create("Own", "", userId);
        agents.create("Other", "", "other-owner");
        skills.createDraft(userId, "own-skill", "", "# Skill");
        skills.createDraft("other-owner", "other-skill", "", "# Skill");
        knowledge.create(userId, "Own", "");
        knowledge.create("other-owner", "Other", "");
        tools.importTools(
                userId,
                List.of(
                        new ToolImport.ImportedTool(
                                "own-tool", "", "{}", "declared", null, null, null)),
                "allow");
        tools.importTools(
                "other-owner",
                List.of(
                        new ToolImport.ImportedTool(
                                "other-tool", "", "{}", "declared", null, null, null)),
                "allow");
        var own = tools.listTools(userId, false).get(0);
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE advisor_tools SET config_json=? WHERE tool_id=?")) {
            update.setString(
                    1, "{\"credentialId\":\"private-reference\",\"secret\":\"must-never-leak\"}");
            update.setString(2, own.toolId());
            update.executeUpdate();
        }
        String response =
                mvc.perform(get("/api/me/overview").header("Authorization", bearer))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.agents").value(1))
                        .andExpect(jsonPath("$.skills").value(1))
                        .andExpect(jsonPath("$.knowledge").value(1))
                        .andExpect(jsonPath("$.tools").value(1))
                        .andExpect(jsonPath("$.credentials.length()").value(1))
                        .andExpect(jsonPath("$.credentials[0].configured").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertFalse(response.contains("private-reference"));
        assertFalse(response.contains("must-never-leak"));
        assertFalse(response.contains("other-tool"));
    }

    @Test
    void settingsRemainAvailableWithoutModulePermissionsButOverviewHidesModules() throws Exception {
        var role = access.createRole("chat-" + UUID.randomUUID(), "Chat only", 1, "");
        access.replacePermissions(role.roleId(), List.of(AccessPoints.CHAT));
        access.replaceGrants(login.account().userId(), List.of(role.roleId()));
        mvc.perform(get("/api/me/overview").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.skills").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tools").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.knowledge").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.credentials").isEmpty());
        mvc.perform(json(put("/api/me/profile"), "{\"nickname\":\"Allowed\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void allSelfServicePathsRequireAuthenticationAndActiveModelStaysPublic() throws Exception {
        for (var request :
                List.of(
                        put("/api/me/profile"),
                        put("/api/me/password"),
                        post("/api/me/avatar"),
                        get("/api/me/avatar"),
                        delete("/api/me/avatar"),
                        get("/api/me/preferences"),
                        put("/api/me/preferences"),
                        get("/api/me/overview"),
                        get("/api/me/about"),
                        post("/api/me/sessions/revoke-others"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/me/preferences").header("Authorization", "Bearer expired-invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/advisor/models/active")).andExpect(status().isOk());
    }

    @Test
    void validWebpIsServedAndMalformedOrOversizedWebpIsRejected() throws Exception {
        // A real 1x1 frame encoded by libwebp; no native encoder is needed to run the test.
        byte[] webp =
                java.util.Base64.getDecoder()
                        .decode("UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAgA0JaQAA3AA/vv9UAA=");
        mvc.perform(
                        multipart("/api/me/avatar")
                                .file(
                                        new MockMultipartFile(
                                                "file", "pixel.webp", "image/webp", webp))
                                .header("Authorization", bearer))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me/avatar").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"))
                .andExpect(content().bytes(webp));
        byte[] oversized = webp.clone();
        oversized[26] = 1;
        oversized[27] = 32; // VP8 frame width = 8193.
        byte[] truncated = java.util.Arrays.copyOf(webp, webp.length - 1);
        for (byte[] invalid : List.of(oversized, truncated)) {
            mvc.perform(
                            multipart("/api/me/avatar")
                                    .file(
                                            new MockMultipartFile(
                                                    "file", "pixel.webp", "image/webp", invalid))
                                    .header("Authorization", bearer))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/me/avatar").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(content().bytes(webp));
    }

    @Test
    void animatedWebpWithinTheSameBoundsIsAccepted() throws Exception {
        byte[] webp =
                java.util.Base64.getDecoder()
                        .decode(
                                "UklGRpQAAABXRUJQVlA4WAoAAAACAAAAAAAAAAAAQU5JTQYAAAD/////AABBTk1GMAAAAAAAAAAAAAAAAAAAAGQAAABWUDggGAAAADABAJ0BKgEAAQACADQlpAADcAD++/1QAEFOTUYwAAAAAAAAAAAAAAAAAAAAZAAAAFZQOCAYAAAAMAEAnQEqAQABAAIANCWkAANwAP77/VAA");
        mvc.perform(
                        multipart("/api/me/avatar")
                                .file(
                                        new MockMultipartFile(
                                                "file", "animated.webp", "image/webp", webp))
                                .header("Authorization", bearer))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me/avatar").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(content().bytes(webp));
    }

    @Test
    void exactlyAllowedDimensionsWorkAndContentTypeCannotDisguiseAnotherFormat() throws Exception {
        byte[] png = image("png", 8192, 1);
        mvc.perform(
                        multipart("/api/me/avatar")
                                .file(
                                        new MockMultipartFile(
                                                "file", "boundary.png", "image/png", png))
                                .header("Authorization", bearer))
                .andExpect(status().isOk());
        mvc.perform(
                        multipart("/api/me/avatar")
                                .file(new MockMultipartFile("file", "fake.jpg", "image/jpeg", png))
                                .header("Authorization", bearer))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/avatar").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(content().bytes(png));
    }

    @Test
    void anAvatarCanUseTheExpandedTenMiBLimit() throws Exception {
        byte[] png = java.util.Arrays.copyOf(image("png", 1, 1), 10 * 1024 * 1024);
        mvc.perform(
                        multipart("/api/me/avatar")
                                .file(new MockMultipartFile("file", "large.png", "image/png", png))
                                .header("Authorization", bearer))
                .andExpect(status().isOk());
        assertFalse(
                accounts.profile(login.account().userId()).orElseThrow().avatarPath().isBlank());
    }

    @Test
    void symbolicLinksCannotEscapeTheAvatarDirectory() throws Exception {
        Path external = Files.createDirectory(root.resolve("external-" + UUID.randomUUID()));
        Path owner = root.resolve("avatars").resolve(login.account().userId());
        Files.createDirectories(owner.getParent());
        Files.createSymbolicLink(owner, external);
        mvc.perform(
                        multipart("/api/me/avatar")
                                .file(
                                        new MockMultipartFile(
                                                "file", "x.png", "image/png", image("png", 1, 1)))
                                .header("Authorization", bearer))
                .andExpect(status().isBadRequest());
        try (var files = Files.list(external)) {
            assertEquals(0, files.count());
        }
        assertEquals("", accounts.profile(login.account().userId()).orElseThrow().avatarPath());
    }

    @Test
    void localThinkMigrationOnlyInitializesAnUnsetServerPreference() throws Exception {
        mvc.perform(get("/api/me/preferences").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(false));
        mvc.perform(
                        json(put("/api/me/preferences"), "{\"thinkEnabled\":true}")
                                .header("If-None-Match", "*"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(true));
        mvc.perform(
                        json(put("/api/me/preferences"), "{\"thinkEnabled\":false}")
                                .header("If-None-Match", "*"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(get("/api/me/preferences").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thinkEnabled").value(true));
        mvc.perform(json(put("/api/me/preferences"), "{\"thinkEnabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(
                        json(put("/api/me/preferences"), "{\"thinkEnabled\":true}")
                                .header("If-None-Match", "*"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(get("/api/me/preferences").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thinkEnabled").value(false));
    }

    @Test
    void preferencesAndAvatarsRemainIsolatedAcrossAccounts() throws Exception {
        mvc.perform(json(put("/api/me/preferences"), "{\"thinkEnabled\":true}"))
                .andExpect(status().isOk());
        String name = "isolated-" + UUID.randomUUID();
        accounts.createUser(name, PASSWORD);
        var other = accounts.login(name, PASSWORD).orElseThrow();
        mvc.perform(get("/api/me/preferences").header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thinkEnabled").value(false));
        mvc.perform(get("/api/me/avatar").header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void selectingTheCurrentChatModelWorksButEmbeddingModelsAreNotChatDefaults() throws Exception {
        var chat =
                models.insert(
                        new ModelCatalog.EndpointDraft(
                                "chat", "openai", "fake", "http://127.0.0.1:1", "fake-secret"),
                        null,
                        "fake",
                        true);
        var embedding =
                models.insert(
                        new ModelCatalog.EndpointDraft(
                                "embedding",
                                "openai",
                                "fake-embedding",
                                "http://127.0.0.1:1",
                                "fake-secret"),
                        3,
                        "fake",
                        false);
        mvc.perform(
                        json(
                                put("/api/me/preferences"),
                                "{\"defaultModelId\":\"" + chat.endpointId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultModelId").value(chat.endpointId()));
        mvc.perform(
                        json(
                                put("/api/me/preferences"),
                                "{\"defaultModelId\":\"" + embedding.endpointId() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unavailablePublishingAgentsAndDeniedChatCannotBecomeDefaults() throws Exception {
        var agent = agents.create("Publishing", "", login.account().userId());
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE advisor_agents SET lifecycle='staging' WHERE agent_id=?")) {
            update.setString(1, agent.agentId());
            update.executeUpdate();
        }
        mvc.perform(
                        json(
                                put("/api/me/preferences"),
                                "{\"defaultAgentId\":\"" + agent.agentId() + "\"}"))
                .andExpect(status().isBadRequest());
        var ready = agents.create("Ready", "", login.account().userId());
        var role = access.createRole("no-chat-" + UUID.randomUUID(), "No chat", 1, "");
        access.replacePermissions(role.roleId(), List.of(AccessPoints.AGENT_VIEW));
        access.replaceGrants(login.account().userId(), List.of(role.roleId()));
        mvc.perform(
                        json(
                                put("/api/me/preferences"),
                                "{\"defaultAgentId\":\"" + ready.agentId() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aboutReportsBuildResolvedVersionWithoutConfigurationSecrets() throws Exception {
        mvc.perform(get("/api/me/about").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("DeepScope"))
                .andExpect(jsonPath("$.backendVersion").value(io.deepscope.core.Version.VERSION))
                .andExpect(jsonPath("$.secret").doesNotExist());
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        assertTrue(
                ImageIO.write(
                        new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB),
                        format,
                        output));
        return output.toByteArray();
    }
}
