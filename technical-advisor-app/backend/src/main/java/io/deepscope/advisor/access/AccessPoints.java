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

import java.util.List;

/** The permission points used by the advisor application. */
public final class AccessPoints {

    public static final String CHAT = "chat.use";
    public static final String AGENT_VIEW = "agent.view";
    public static final String AGENT_MANAGE = "agent.manage";
    public static final String SKILL_MANAGE = "skill.manage";
    public static final String TOOL_MANAGE = "tool.manage";
    public static final String KNOWLEDGE_VIEW = "knowledge.view";
    public static final String KNOWLEDGE_MANAGE = "knowledge.manage";
    public static final String SETTINGS = "settings.manage";
    public static final String RBAC = "rbac.manage";

    public static final String SUPER_ADMIN_ROLE_ID = "role-admin";

    private static final List<PermissionNode> PERMISSION_TREE =
            List.of(
                    node("chat", "会话", "Chat", leaf(CHAT, "使用会话", "Use chat")),
                    node(
                            "agent",
                            "智能体管理",
                            "Agents",
                            leaf(AGENT_VIEW, "查看", "View"),
                            leaf("agent.create", "创建", "Create"),
                            leaf("agent.edit", "编辑", "Edit"),
                            leaf("agent.delete", "删除", "Delete"),
                            leaf(AGENT_MANAGE, "管理全部（含跨用户资源）", "Manage all owners")),
                    node(
                            "skill",
                            "技能管理",
                            "Skills",
                            leaf("skill.view", "查看", "View"),
                            leaf("skill.create", "创建/导入", "Create / import"),
                            leaf("skill.edit", "编辑", "Edit"),
                            leaf("skill.delete", "删除", "Delete"),
                            leaf(SKILL_MANAGE, "管理全部（含跨用户资源）", "Manage all owners")),
                    node(
                            "tool",
                            "工具与 MCP",
                            "Tools and MCP",
                            leaf("tool.view", "查看", "View"),
                            leaf("tool.create", "导入/接入", "Import / connect"),
                            leaf("tool.edit", "编辑/审批", "Edit / approve"),
                            leaf("tool.test", "测试", "Test"),
                            leaf("tool.delete", "删除", "Delete"),
                            leaf(TOOL_MANAGE, "管理全部（含跨用户资源）", "Manage all owners")),
                    node(
                            "knowledge",
                            "知识库管理",
                            "Knowledge",
                            leaf(KNOWLEDGE_VIEW, "查看/检索", "View / search"),
                            leaf("knowledge.create", "创建", "Create"),
                            leaf("knowledge.edit", "编辑/上传", "Edit / upload"),
                            leaf("knowledge.delete", "删除", "Delete"),
                            leaf(KNOWLEDGE_MANAGE, "管理全部（含跨用户资源）", "Manage all owners")),
                    node(
                            "model",
                            "模型设置",
                            "Models",
                            leaf("model.view", "查看", "View"),
                            leaf("model.create", "接入/获取模型", "Connect / discover"),
                            leaf("model.edit", "配置/设为默认", "Configure / set default"),
                            leaf("model.test", "测试连接", "Test connection"),
                            leaf("model.copy", "复制完整配置（含密钥）", "Copy configuration including key"),
                            leaf("model.delete", "删除", "Delete"),
                            leaf(SETTINGS, "管理全部", "Manage all")),
                    node(
                            "user",
                            "用户管理",
                            "Users",
                            leaf("user.view", "查看", "View"),
                            leaf("user.create", "新建", "Create"),
                            leaf("user.edit", "编辑/启停", "Edit / change status"),
                            leaf("user.delete", "删除", "Delete")),
                    node(
                            "role",
                            "角色管理",
                            "Roles",
                            leaf("role.view", "查看", "View"),
                            leaf("role.create", "新建", "Create"),
                            leaf("role.edit", "编辑/启停", "Edit / change status"),
                            leaf("role.grant", "授权", "Grant permissions"),
                            leaf("role.delete", "删除", "Delete"),
                            leaf(RBAC, "管理全部用户与角色", "Manage all users and roles")));

    public static final List<String> ALL =
            PERMISSION_TREE.stream()
                    .flatMap(node -> node.children().stream())
                    .map(PermissionNode::id)
                    .toList();

    private AccessPoints() {}

    public static List<PermissionNode> permissionTree() {
        return PERMISSION_TREE;
    }

    private static PermissionNode node(
            String id, String name, String nameEn, PermissionNode... children) {
        return new PermissionNode(id, name, nameEn, List.of(children));
    }

    private static PermissionNode leaf(String id, String name, String nameEn) {
        return new PermissionNode(id, name, nameEn, List.of());
    }

    /** Existing broad grants keep their previous meaning; individual actions never grant ownership. */
    public static String legacyPermission(String permission) {
        if (permission.startsWith("model.")) return SETTINGS;
        if (permission.startsWith("user.") || permission.startsWith("role.")) return RBAC;
        int separator = permission.indexOf('.');
        return separator < 0 ? permission : permission.substring(0, separator) + ".manage";
    }

    public static String operation(String path, String method, String domain) {
        if ("GET".equals(method)) {
            return path.endsWith("/permissions") ? "role.grant" : domain + ".view";
        }
        if ("DELETE".equals(method)) return domain + ".delete";
        if (path.endsWith("/copy-config") || path.endsWith("/copy")) return "model.copy";
        if (path.endsWith("/test")
                || path.endsWith("/test-input")
                || path.contains("/test-runs")
                || path.contains("/tools/tests/")) return domain + ".test";
        if (path.endsWith("/permissions")) return "role.grant";
        if (path.startsWith("/api/advisor/users/") && path.endsWith("/roles")) return "role.grant";
        if (path.endsWith("/options")) return "model.view";
        if (path.endsWith("/discover")) return "model.create";
        if ("POST".equals(method)
                && (path.matches(
                                "/api/advisor/(users|roles|models|agents|skills|knowledge|mcp-servers|agent-creation-drafts)")
                        || path.contains("/import")
                        || path.equals("/api/advisor/tools/preview")
                        || path.equals("/api/advisor/skills/suggest")
                        || path.startsWith("/api/advisor/agent-creation-drafts/")))
            return domain + ".create";
        return domain + ".edit";
    }

    /** Classify the route Spring matched, independent of matrix parameters and context paths. */
    public static String operation(jakarta.servlet.http.HttpServletRequest request, String domain) {
        Object pattern =
                request.getAttribute(
                        org.springframework.web.servlet.HandlerMapping
                                .BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path =
                pattern instanceof String value
                        ? value
                        : org.springframework.web.util.UrlPathHelper.defaultInstance
                                .getLookupPathForRequest(request);
        return operation(path, request.getMethod(), domain);
    }

    public record PermissionNode(
            String id, String name, String nameEn, List<PermissionNode> children) {}
}
