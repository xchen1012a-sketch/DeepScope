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

import io.deepscope.advisor.auth.AccountStore;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolves what an account may do.
 *
 * <p>Accounts with no assigned role keep the previous non-admin scope: they can use the product,
 * but not model settings or user and role administration. Once a role is assigned, only that
 * role's permissions apply.
 */
public final class AccessGuard {

    private final AccessCatalog catalog;

    public AccessGuard(AccessCatalog catalog) {
        this.catalog = catalog;
    }

    public Set<String> permissionsOf(String userId) throws SQLException {
        if (!catalog.hasAssignedRole(userId)) {
            return null;
        }
        return catalog.permissions(userId);
    }

    public boolean allowed(AccountStore.Account account, String permission) throws SQLException {
        Set<String> permissions = permissionsOf(account.userId());
        if (permissions == null) {
            String legacy = AccessPoints.legacyPermission(permission);
            return !AccessPoints.SETTINGS.equals(permission)
                    && !AccessPoints.RBAC.equals(permission)
                    && !AccessPoints.SETTINGS.equals(legacy)
                    && !AccessPoints.RBAC.equals(legacy);
        }
        return permissions.contains(permission)
                || permissions.contains(AccessPoints.legacyPermission(permission));
    }

    /** Whether this account may see every owner's rows for a management permission. */
    public boolean seesAll(AccountStore.Account account, String managePermission)
            throws SQLException {
        Set<String> permissions = permissionsOf(account.userId());
        if (permissions == null) {
            return account.isAdmin();
        }
        return permissions.contains(managePermission);
    }

    /** Same check as {@link #seesAll} for callbacks that cannot throw. */
    public boolean manage(AccountStore.Account account, String managePermission) {
        try {
            return seesAll(account, managePermission);
        } catch (SQLException error) {
            return false;
        }
    }

    public Set<String> granted(AccountStore.Account account) throws SQLException {
        Set<String> permissions = permissionsOf(account.userId());
        if (permissions != null) {
            Set<String> expanded = new HashSet<>(permissions);
            for (String permission : AccessPoints.ALL)
                if (permissions.contains(AccessPoints.legacyPermission(permission)))
                    expanded.add(permission);
            return expanded;
        }
        Set<String> legacy = new HashSet<>(AccessPoints.ALL);
        legacy.remove(AccessPoints.SETTINGS);
        legacy.remove(AccessPoints.RBAC);
        legacy.removeIf(
                permission ->
                        permission.startsWith("model.")
                                || permission.startsWith("user.")
                                || permission.startsWith("role."));
        if (account.isAdmin()) {
            return new HashSet<>(AccessPoints.ALL);
        }
        return legacy;
    }
}
