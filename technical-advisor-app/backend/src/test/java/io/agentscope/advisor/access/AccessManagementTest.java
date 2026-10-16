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
package io.agentscope.advisor.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentscope.advisor.auth.AccountStore;
import io.agentscope.advisor.auth.ApiAuthenticationFilter;
import io.agentscope.advisor.db.AdvisorSchema;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccessManagementTest {
    private AccessCatalog catalog;
    private MockMvc mvc;
    private String token;
    private String reader;
    private AccountStore accounts;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:access-management-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(ds);
        accounts = new AccountStore(ds, Clock.systemUTC(), Duration.ofHours(1));
        accounts.bootstrap("root", "fake-password");
        accounts.createUser("reader", "fake-password");
        catalog = new AccessCatalog(ds);
        catalog.ensureBuiltin();
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AdvisorAccessController(
                                        catalog, new AccessGuard(catalog), accounts, ds))
                        .addFilters(new ApiAuthenticationFilter(accounts, "/v1/admin"))
                        .build();
        token = "Bearer " + accounts.login("root", "fake-password").orElseThrow().token();
        reader = "Bearer " + accounts.login("reader", "fake-password").orElseThrow().token();
    }

    @Test
    void builtinAdminCannotBeDisabledEvenWhenAnotherSuperAdminExists() throws Exception {
        var root = accounts.login("root", "fake-password").orElseThrow().account();
        var second = accounts.login("reader", "fake-password").orElseThrow().account();
        catalog.replaceGrants(second.userId(), List.of(AccessPoints.SUPER_ADMIN_ROLE_ID));
        mvc.perform(
                        patch("/api/advisor/users/" + root.userId() + "/status")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"status\":\"disabled\"}"))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertTrue(
                accounts.login("root", "fake-password").isPresent());
        mvc.perform(
                        patch("/api/advisor/roles/role-admin/status")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"status\":\"disabled\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userAndRoleNumbersStartAtOneAndDoNotChangeWhenFiltered() throws Exception {
        mvc.perform(
                        get("/api/advisor/users")
                                .header("Authorization", token)
                                .param("page", "1")
                                .param("q", "root"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userNumber").value(1));
        mvc.perform(
                        get("/api/advisor/users")
                                .header("Authorization", token)
                                .param("page", "1")
                                .param("q", "reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userNumber").value(2));
        var role = catalog.createRole("Support", "support", 1, "");
        mvc.perform(
                        get("/api/advisor/roles")
                                .header("Authorization", token)
                                .param("page", "1")
                                .param("q", "Support"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].roleNumber").value(2))
                .andExpect(jsonPath("$.items[0].roleId").value(role.roleId()));
        mvc.perform(
                        get("/api/advisor/roles")
                                .header("Authorization", token)
                                .param("page", "1")
                                .param("code", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].roleNumber").value(1));
    }

    @Test
    void editingUserChangesLoginAndProfileWithoutReplacingRoleAssignments() throws Exception {
        var userId = accounts.login("reader", "fake-password").orElseThrow().account().userId();
        var role = catalog.createRole("Reader", "reader-role", 1, "");
        catalog.replaceGrants(userId, List.of(role.roleId()));
        mvc.perform(
                        put("/api/advisor/users/" + userId)
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{\"username\":\"renamed\",\"nickname\":\"New"
                                                + " nickname\",\"remark\":\"New remark\"}"))
                .andExpect(status().isNoContent());
        assertEquals(
                userId,
                accounts.login("renamed", "fake-password").orElseThrow().account().userId());
        org.junit.jupiter.api.Assertions.assertTrue(
                accounts.login("reader", "fake-password").isEmpty());
        assertEquals(List.of(role.roleId()), catalog.roleIds(userId));
        mvc.perform(
                        get("/api/advisor/users")
                                .header("Authorization", token)
                                .param("page", "1")
                                .param("q", "renamed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].nickname").value("New nickname"))
                .andExpect(jsonPath("$.items[0].remark").value("New remark"))
                .andExpect(jsonPath("$.items[0].userNumber").value(2));
    }

    @Test
    void duplicateOrBlankAccountsAreRejectedWithoutChangingTheProfile() throws Exception {
        var userId = accounts.login("reader", "fake-password").orElseThrow().account().userId();
        for (String username : List.of("root", " ")) {
            mvc.perform(
                            put("/api/advisor/users/" + userId)
                                    .header("Authorization", token)
                                    .contentType("application/json")
                                    .content(
                                            "{\"username\":\""
                                                    + username
                                                    + "\",\"nickname\":\"Changed\",\"remark\":\"Changed\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        get("/api/advisor/users")
                                .header("Authorization", token)
                                .param("page", "1")
                                .param("q", "reader"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].nickname").value(""))
                .andExpect(jsonPath("$.items[0].remark").value(""));
    }

    @Test
    void roleEditorSavesAllFourFieldsAndRejectsInvalidStatusAtomically() throws Exception {
        var role = catalog.createRole("Support", "support", 1, "Keep description");
        mvc.perform(
                        put("/api/advisor/roles/" + role.roleId())
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{\"name\":\"Operations\",\"code\":\"ops\",\"sort\":0,\"status\":\"disabled\"}"))
                .andExpect(status().isNoContent());
        var saved = catalog.findRole(role.roleId()).orElseThrow();
        assertEquals("Operations", saved.name());
        assertEquals("ops", saved.code());
        assertEquals(0, saved.sort());
        assertEquals("disabled", saved.status());
        assertEquals("Keep description", saved.description());
        mvc.perform(
                        put("/api/advisor/roles/" + role.roleId())
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{\"name\":\"Wrong\",\"code\":\"wrong\",\"sort\":10,\"status\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(saved, catalog.findRole(role.roleId()).orElseThrow());
        mvc.perform(
                        put("/api/advisor/roles/role-admin")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{\"name\":\"Admin\",\"code\":\"admin\",\"sort\":1,\"status\":\"disabled\"}"))
                .andExpect(status().isBadRequest());
        assertEquals("active", catalog.findRole("role-admin").orElseThrow().status());
    }

    @Test
    void adminRoleMetadataAndMenuPermissionsAreEditableButDisableAndDeleteAreRejected()
            throws Exception {
        mvc.perform(
                        put("/api/advisor/roles/role-admin")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{\"name\":\"Administrators\",\"code\":\"super_admin\",\"sort\":0,\"status\":\"active\"}"))
                .andExpect(status().isNoContent());
        assertEquals("Administrators", catalog.findRole("role-admin").orElseThrow().name());
        mvc.perform(
                        put("/api/advisor/roles/role-admin/permissions")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"permissions\":[\"agent.view\",\"rbac.manage\"]}"))
                .andExpect(status().isNoContent());
        catalog.ensureBuiltin();
        assertEquals(
                java.util.Set.of("agent.view", "rbac.manage"),
                new java.util.HashSet<>(catalog.permissionsOfRole("role-admin")));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                        "/api/advisor/roles/role-admin")
                                .header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        patch("/api/advisor/roles/role-admin/status")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"status\":\"disabled\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidRoleOrderCannotSilentlyTruncateAndPartiallyUpdateTheRole() throws Exception {
        var role = catalog.createRole("Support", "support", 1, "");
        for (String order : List.of("1.5", "-1", "2147483648", "\"invalid\"")) {
            mvc.perform(
                            put("/api/advisor/roles/" + role.roleId())
                                    .header("Authorization", token)
                                    .contentType("application/json")
                                    .content(
                                            "{\"name\":\"Wrong\",\"code\":\"wrong\",\"sort\":"
                                                    + order
                                                    + ",\"status\":\"active\"}"))
                    .andExpect(status().isBadRequest());
            assertEquals(role, catalog.findRole(role.roleId()).orElseThrow());
        }
    }

    @Test
    void userEditorCannotGrantSuperAdminOrChangeAnAdminPassword() throws Exception {
        var editor = catalog.createRole("User editor", "user-editor", 1, "");
        catalog.replacePermissions(
                editor.roleId(), List.of("user.view", "user.edit", "user.create"));
        var readerId = accounts.login("reader", "fake-password").orElseThrow().account().userId();
        var rootId = accounts.login("root", "fake-password").orElseThrow().account().userId();
        catalog.replaceGrants(readerId, List.of(editor.roleId()));
        mvc.perform(
                        put("/api/advisor/users/" + readerId)
                                .header("Authorization", reader)
                                .contentType("application/json")
                                .content("{\"nickname\":\"Editor\",\"roleIds\":[\"role-admin\"]}"))
                .andExpect(status().isForbidden());
        assertEquals(List.of(editor.roleId()), catalog.roleIds(readerId));
        mvc.perform(
                        put("/api/advisor/users/" + rootId)
                                .header("Authorization", reader)
                                .contentType("application/json")
                                .content(
                                        "{\"password\":\"replacement-password\",\"roleIds\":[\"role-admin\"]}"))
                .andExpect(status().isForbidden());
        org.junit.jupiter.api.Assertions.assertTrue(
                accounts.login("root", "fake-password").isPresent());
    }

    @Test
    void separateRoleAndPasswordActionsPersistTheirOwnChanges() throws Exception {
        var readerId = accounts.login("reader", "fake-password").orElseThrow().account().userId();
        var role = catalog.createRole("Support", "support", 1, "");
        mvc.perform(
                        put("/api/advisor/users/" + readerId + "/roles")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"roleIds\":[\"" + role.roleId() + "\"]}"))
                .andExpect(status().isNoContent());
        assertEquals(List.of(role.roleId()), catalog.roleIds(readerId));
        mvc.perform(
                        patch("/api/advisor/users/" + readerId + "/password")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"password\":\"fake-new-password\"}"))
                .andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertTrue(
                accounts.login("reader", "fake-new-password").isPresent());
        org.junit.jupiter.api.Assertions.assertTrue(
                accounts.login("reader", "fake-password").isEmpty());
    }

    @Test
    void userEditorCannotTakeOverACustomRoleWithMoreSensitivePermissions() throws Exception {
        accounts.createUser("key-manager", "fake-password");
        var targetId =
                accounts.login("key-manager", "fake-password").orElseThrow().account().userId();
        var sensitive = catalog.createRole("Key manager", "key-manager", 1, "");
        catalog.replacePermissions(sensitive.roleId(), List.of("model.copy"));
        catalog.replaceGrants(targetId, List.of(sensitive.roleId()));
        var editor = catalog.createRole("Editor", "editor", 1, "");
        catalog.replacePermissions(editor.roleId(), List.of("user.edit"));
        catalog.replaceGrants(
                accounts.login("reader", "fake-password").orElseThrow().account().userId(),
                List.of(editor.roleId()));
        mvc.perform(
                        patch("/api/advisor/users/" + targetId + "/password")
                                .header("Authorization", reader)
                                .contentType("application/json")
                                .content("{\"password\":\"takeover-password\"}"))
                .andExpect(status().isForbidden());
        org.junit.jupiter.api.Assertions.assertTrue(
                accounts.login("key-manager", "fake-password").isPresent());
    }

    @Test
    void permissionsAreLoadedFreshAndUnknownIdsCannotReplaceAnExistingGrant() throws Exception {
        var role = catalog.createRole("Support", "support", 1, "");
        catalog.replacePermissions(role.roleId(), List.of(AccessPoints.CHAT));
        mvc.perform(
                        get("/api/advisor/roles/" + role.roleId() + "/permissions")
                                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0]").value("chat.use"));
        mvc.perform(
                        put("/api/advisor/roles/" + role.roleId() + "/permissions")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"permissions\":[\"unknown.permission\"]}"))
                .andExpect(status().isBadRequest());
        assertEquals(List.of("chat.use"), catalog.permissionsOfRole(role.roleId()));
        mvc.perform(
                        put("/api/advisor/roles/role-admin/permissions")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"permissions\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void permissionTreeIncludesRealButtonOperationsAndRequiresAdministration() throws Exception {
        mvc.perform(get("/api/advisor/permissions/tree").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$[?(@.id == 'agent')].children[*].id",
                                org.hamcrest.Matchers.hasItem("agent.edit")))
                .andExpect(
                        jsonPath(
                                "$[?(@.id == 'model')].children[*].id",
                                org.hamcrest.Matchers.hasItem("model.test")));
        mvc.perform(get("/api/advisor/permissions/tree").header("Authorization", reader))
                .andExpect(status().isForbidden());
    }
}
