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

import io.agentscope.advisor.auth.AccountStore;
import io.agentscope.advisor.db.AdvisorSchema;
import java.util.UUID;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

class AccessPaginationTest {
    @Test
    void filtersBeforePagingAndKeepsUserSecretsOutOfResponse() throws Exception {
        var ds =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:access-page-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(ds);
        var catalog = new AccessCatalog(ds);
        var a = catalog.createRole("A%_", "alpha", 1, "");
        catalog.createRole("B", "beta", 2, "");
        assertEquals(1, catalog.rolesPage("%_", "active", "alpha", 1, 1).total());
        assertEquals(1, catalog.rolesPage("%_", "active", "lph", 1, 1).total());
        assertEquals(0, catalog.rolesPage("B", "active", "lph", 1, 1).total());
        assertEquals(0, catalog.rolesPage("%_", "disabled", "lph", 1, 1).total());
        assertEquals(0, catalog.rolesPage(null, null, "%_", 1, 1).total());
        assertEquals("B", catalog.rolesPage(null, null, null, 2, 1).items().get(0).name());
        try (var c = ds.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO accounts (user_id,username,password_hash,role,created_at,status)"
                        + " VALUES"
                        + " ('u1','A%_','secret','admin',1,'active'),('u2','B','secret','user',2,'disabled')");
        }
        catalog.ensureBuiltin();
        catalog.replaceGrants("u2", java.util.List.of(a.roleId()));
        var controller =
                new AdvisorAccessController(
                        catalog,
                        new AccessGuard(catalog),
                        new AccountStore(
                                ds, java.time.Clock.systemUTC(), java.time.Duration.ofHours(1)),
                        ds);
        var request = new MockHttpServletRequest();
        request.setAttribute(
                AccountStore.Account.class.getName(),
                new AccountStore.Account("u1", "A%_", "admin", 1));
        request.setParameter("page", "1");
        request.setParameter("size", "1");
        request.setParameter("status", "disabled");
        request.setParameter("roleId", a.roleId());
        var response = controller.usersPage(request);
        var page = (io.agentscope.advisor.db.PageResult<?>) response.getBody();
        assertEquals(1, page.total());
        assertEquals(false, page.items().toString().contains("secret"));
        request.setParameter("q", "%_");
        assertEquals(
                0,
                ((io.agentscope.advisor.db.PageResult<?>) controller.usersPage(request).getBody())
                        .total());
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                controller)
                        .build();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/advisor/users")
                                .requestAttr(
                                        AccountStore.Account.class.getName(),
                                        new AccountStore.Account("u1", "A%_", "admin", 1))
                                .param("page", "2")
                                .param("size", "1"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.items[0].username")
                                .value("B"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.total")
                                .value(2));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/advisor/users")
                                .requestAttr(
                                        AccountStore.Account.class.getName(),
                                        new AccountStore.Account("u1", "A%_", "admin", 1))
                                .param("page", "0"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/advisor/roles")
                                .requestAttr(
                                        AccountStore.Account.class.getName(),
                                        new AccountStore.Account("u1", "A%_", "admin", 1))
                                .param("page", "1")
                                .param("size", "101"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/api/advisor/users")
                                .requestAttr(
                                        AccountStore.Account.class.getName(),
                                        new AccountStore.Account("u2", "B", "user", 2))
                                .param("page", "1"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isForbidden());
    }
}
