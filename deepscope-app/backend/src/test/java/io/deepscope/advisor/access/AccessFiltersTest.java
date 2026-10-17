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
package io.deepscope.advisor.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.db.AdvisorSchema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccessFiltersTest {
    private MockMvc mvc;
    private final long start = Instant.parse("2026-10-15T00:00:00Z").toEpochMilli();
    private final long end = Instant.parse("2026-10-16T00:00:00Z").toEpochMilli();

    @BeforeEach
    void setup() throws Exception {
        var source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:access-filters-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(source);
        var accounts = new AccountStore(source, Clock.systemUTC(), Duration.ofHours(1));
        accounts.bootstrap("manager", "fake-password");
        var catalog = new AccessCatalog(source);
        catalog.ensureBuiltin();
        managerId = accounts.login("manager", "fake-password").orElseThrow().account().userId();
        try (var connection = source.getConnection()) {
            try (var insert =
                    connection.prepareStatement(
                            "INSERT INTO accounts (user_id, username, nickname, password_hash,"
                                + " role, created_at) VALUES (?, ?, ?, 'fake-hash', 'user', ?)")) {
                String[] names = {"before", "first", "last", "after", "other"};
                long[] times = {start - 1, start, end - 1, end, start};
                for (int index = 0; index < names.length; index++) {
                    insert.setString(1, names[index]);
                    insert.setString(2, "user-" + names[index]);
                    insert.setString(3, index == 4 ? "Other" : "Support%_");
                    insert.setLong(4, times[index]);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            String[] roleNames = {"Ops before", "Ops first", "Ops last", "Ops after"};
            long[] times = {start - 1, start, end - 1, end};
            for (int index = 0; index < roleNames.length; index++) {
                var role = catalog.createRole(roleNames[index], "ops-" + index, 1, "");
                try (var update =
                        connection.prepareStatement(
                                "UPDATE advisor_roles SET created_at=? WHERE role_id=?")) {
                    update.setLong(1, times[index]);
                    update.setString(2, role.roleId());
                    update.executeUpdate();
                }
            }
        }
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AdvisorAccessController(
                                        catalog, new AccessGuard(catalog), accounts, source))
                        .build();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder page(
            String resource) {
        return page(resource, 1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder page(
            String resource, int page) {
        return get("/api/advisor/" + resource)
                .param("page", String.valueOf(page))
                .param("size", "1")
                .requestAttr(
                        AccountStore.Account.class.getName(),
                        new AccountStore.Account(managerId, "manager", "admin", 1));
    }

    private String managerId;

    @Test
    void usersCombineNicknameAndCreationRangeBeforePagingWithLiteralWildcards() throws Exception {
        mvc.perform(
                        page("users")
                                .param("q", "user-")
                                .param("nickname", "%_")
                                .param("createdFrom", String.valueOf(start))
                                .param("createdBefore", String.valueOf(end)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].username").value("user-first"));
        mvc.perform(
                        page("users", 2)
                                .param("q", "user-")
                                .param("nickname", "%_")
                                .param("createdFrom", String.valueOf(start))
                                .param("createdBefore", String.valueOf(end)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].username").value("user-last"));
    }

    @Test
    void rolesCreationRangeIncludesStartAndExcludesTheFollowingDay() throws Exception {
        mvc.perform(
                        page("roles")
                                .param("q", "Ops")
                                .param("createdFrom", String.valueOf(start))
                                .param("createdBefore", String.valueOf(end)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Ops first"));
        mvc.perform(
                        page("roles", 2)
                                .param("q", "Ops")
                                .param("createdFrom", String.valueOf(start))
                                .param("createdBefore", String.valueOf(end)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Ops last"));
    }

    @Test
    void invalidOrReversedTimeRangesAreRejectedForBothLists() throws Exception {
        for (String resource : new String[] {"users", "roles"}) {
            mvc.perform(page(resource).param("createdFrom", "bad"))
                    .andExpect(status().isBadRequest());
            mvc.perform(page(resource).param("createdBefore", "-1"))
                    .andExpect(status().isBadRequest());
            mvc.perform(
                            page(resource)
                                    .param("createdFrom", String.valueOf(end))
                                    .param("createdBefore", String.valueOf(start)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void eitherCreationBoundaryCanBeUsedAlone() throws Exception {
        for (String resource : new String[] {"users", "roles"}) {
            var from =
                    page(resource)
                            .param("q", resource.equals("users") ? "user-" : "Ops")
                            .param("createdFrom", String.valueOf(start));
            var before =
                    page(resource)
                            .param("q", resource.equals("users") ? "user-" : "Ops")
                            .param("createdBefore", String.valueOf(end));
            if (resource.equals("users")) {
                from.param("nickname", "%_");
                before.param("nickname", "%_");
            }
            mvc.perform(from).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
            mvc.perform(before).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
        }
    }
}
