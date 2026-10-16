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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.advisor.db.AdvisorSchema;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** Only the super-admin role is builtin, and it cannot be deleted or stripped from the last holder. */
class AccessCatalogTest {

    private AccessCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:access-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        catalog = new AccessCatalog(dataSource);
        catalog.ensureBuiltin();
        try (var connection = dataSource.getConnection();
                var insert =
                        connection.prepareStatement(
                                "INSERT INTO accounts (user_id, username, password_hash, role,"
                                        + " created_at) VALUES ('admin-1', 'admin', 'x', 'admin',"
                                        + " 1)")) {
            insert.executeUpdate();
        }
        catalog.ensureBuiltin();
    }

    @Test
    void builtinSuperAdminCanChangePermissionsButCannotBeDeletedOrLoseManagementAccess()
            throws Exception {
        var role = catalog.findRole(AccessPoints.SUPER_ADMIN_ROLE_ID).orElseThrow();
        assertTrue(role.builtin());
        assertEquals(AccessPoints.ALL.size(), catalog.permissionsOfRole(role.roleId()).size());
        assertThrows(IllegalArgumentException.class, () -> catalog.deleteRole(role));
        catalog.replacePermissions(role.roleId(), List.of(AccessPoints.CHAT, AccessPoints.RBAC));
        assertEquals(
                java.util.Set.of(AccessPoints.CHAT, AccessPoints.RBAC),
                new java.util.HashSet<>(catalog.permissionsOfRole(role.roleId())));
        catalog.ensureBuiltin();
        assertEquals(2, catalog.permissionsOfRole(role.roleId()).size());
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.replacePermissions(role.roleId(), List.of(AccessPoints.CHAT)));
        assertThrows(
                IllegalArgumentException.class, () -> catalog.replaceGrants("admin-1", List.of()));
    }

    @Test
    void permissionTreeIsTheCompleteSourceAndUnknownPermissionsAreRejected() throws Exception {
        assertEquals(
                AccessPoints.ALL.size(),
                AccessPoints.permissionTree().stream()
                        .flatMap(node -> node.children().stream())
                        .map(AccessPoints.PermissionNode::id)
                        .count());
        var role = catalog.createRole("Operator", "operator", 1, "");
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.replacePermissions(role.roleId(), List.of("unknown.permission")));
    }

    @Test
    void disablingTheOnlyAssignedRoleDoesNotRestoreLegacyAccess() throws Exception {
        var role = catalog.createRole("Limited", "limited", 1, "");
        catalog.replacePermissions(role.roleId(), List.of("agent.view"));
        catalog.replaceGrants("admin-1", List.of(AccessPoints.SUPER_ADMIN_ROLE_ID, role.roleId()));
        catalog.grant("other", role.roleId());
        catalog.setRoleStatus(role, "disabled");
        var guard = new AccessGuard(catalog);
        org.junit.jupiter.api.Assertions.assertFalse(
                guard.allowed(
                        new io.agentscope.advisor.auth.AccountStore.Account(
                                "other", "other", "user", 1),
                        "agent.create"));
    }
}
