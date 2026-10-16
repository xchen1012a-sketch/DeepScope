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
package io.agentscope.advisor.tool;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import java.util.List;

/**
 * Turns catalog permission levels into framework rules.
 *
 * <p>Unspecified tools stay available: the mode allows them. An explicit deny or ask on the
 * catalog wins over that default. Deny rules are what the runtime copies onto a spawned
 * subagent; ask rules apply to the agent that owns this context.
 */
public final class ToolPermissions {

    private ToolPermissions() {}

    public static PermissionContextState context(List<ToolCatalog.ToolRow> tools) {
        PermissionContextState.Builder builder =
                PermissionContextState.builder().mode(PermissionMode.BYPASS);
        for (ToolCatalog.ToolRow tool : tools) {
            PermissionRule rule =
                    new PermissionRule(tool.name(), null, behavior(tool.permission()), "catalog");
            switch (tool.permission()) {
                case ToolCatalog.PERMISSION_DENY -> builder.addDenyRule(tool.name(), rule);
                case ToolCatalog.PERMISSION_ASK -> builder.addAskRule(tool.name(), rule);
                case ToolCatalog.PERMISSION_ALLOW -> builder.addAllowRule(tool.name(), rule);
                default -> {
                    /* unknown levels are rejected when the row is written */
                }
            }
        }
        return builder.build();
    }

    private static PermissionBehavior behavior(String permission) {
        return switch (permission) {
            case ToolCatalog.PERMISSION_DENY -> PermissionBehavior.DENY;
            case ToolCatalog.PERMISSION_ALLOW -> PermissionBehavior.ALLOW;
            default -> PermissionBehavior.ASK;
        };
    }
}
