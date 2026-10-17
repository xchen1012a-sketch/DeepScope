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
package io.deepscope.advisor.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.advisor.db.AdvisorSchema;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class CommandCatalogTest {

    private CommandCatalog catalog;
    private DataSource dataSource;

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;

    @BeforeEach
    void setUp() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:command-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        catalog = new CommandCatalog(dataSource);
    }

    @Test
    void readyToolCommandsFollowOwnershipAndLivePermission() throws Exception {
        var tools =
                new io.deepscope.advisor.tool.ToolCatalog(
                        dataSource, new com.fasterxml.jackson.databind.ObjectMapper());
        var tool =
                tools.importTools(
                                "user-a",
                                List.of(
                                        new io.deepscope.advisor.tool.ToolImport.ImportedTool(
                                                "weather",
                                                "Weather",
                                                "{}",
                                                "http",
                                                "GET",
                                                "https://example.com/weather")),
                                "ask")
                        .get(0);
        String code = "tool-" + tool.toolId();
        assertTrue(
                catalog.listAvailable("user-a").stream()
                        .anyMatch(c -> code.equals(c.code()) && "tool".equals(c.targetType())));
        assertFalse(catalog.listAvailable("user-b").stream().anyMatch(c -> code.equals(c.code())));
        assertFalse(catalog.listAvailable(null).stream().anyMatch(c -> code.equals(c.code())));
        tools.updatePermission(tool.toolId(), "deny");
        assertFalse(catalog.listAvailable("user-a").stream().anyMatch(c -> code.equals(c.code())));
        tools.updatePermission(tool.toolId(), "allow");
        assertTrue(catalog.listAvailable("user-a").stream().anyMatch(c -> code.equals(c.code())));
        tools.deleteTool(tool.toolId());
        assertFalse(catalog.listAvailable("user-a").stream().anyMatch(c -> code.equals(c.code())));
    }

    @Test
    void mcpCommandsRequireApprovedToolsAndAnOwnedEnabledServer() throws Exception {
        var tools =
                new io.deepscope.advisor.tool.ToolCatalog(
                        dataSource, new com.fasterxml.jackson.databind.ObjectMapper());
        var server =
                tools.insertServer(
                        new io.deepscope.advisor.tool.McpEndpointParser.McpEndpoint(
                                "weather",
                                "stdio",
                                "echo",
                                List.of(),
                                java.util.Map.of(),
                                null,
                                java.util.Map.of()),
                        "user-a",
                        "connected",
                        "");
        var tool =
                tools.approveMcpTool(
                        server,
                        new io.deepscope.advisor.tool.ToolCatalog.DiscoveredTool(
                                "weather", "Weather", "{}"),
                        "ask");
        String code = "mcp-" + tool.toolId();
        assertTrue(catalog.findAvailableByCode(code, "user-a").isPresent());
        tools.markServer(server.serverId(), "error", "temporary network failure");
        assertTrue(catalog.findAvailableByCode(code, "user-a").isPresent());
        tools.markServer(server.serverId(), "disabled", "");
        assertTrue(catalog.findAvailableByCode(code, "user-a").isEmpty());
        tools.markServer(server.serverId(), "connected", "");
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE advisor_tools SET approved = 0");
            assertTrue(catalog.findAvailableByCode(code, "user-a").isEmpty());
            statement.executeUpdate(
                    "UPDATE advisor_tools SET approved = 1, owner_user_id = 'user-b'");
            assertTrue(catalog.findAvailableByCode(code, "user-b").isEmpty());
        }
    }

    @Test
    void skillCommandsRequirePublishedContentAndNeverGrantAdminForeignAccess() throws Exception {
        var skills = new io.deepscope.advisor.skill.SkillCatalog(dataSource, root);
        var skill =
                skills.createDraft(
                        "user-a",
                        "review",
                        "Review",
                        "---\nname: review\ndescription: Review\n---\nReview the input");
        String code = "skill-" + skill.skillId();
        assertTrue(catalog.findAvailableByCode(code, "user-a").isEmpty());
        skills.publish(skill);
        assertTrue(catalog.findAvailableByCode(code, "user-a").isPresent());
        assertTrue(catalog.findAvailableByCode(code, "user-b").isEmpty());
        assertTrue(skills.list("user-b", true).isEmpty());
        assertEquals(0, skills.page("user-b", true, null, null, 1, 20).total());
        assertFalse(io.deepscope.advisor.skill.SkillCatalog.canView(skill, "user-b", true));
        assertFalse(io.deepscope.advisor.skill.SkillCatalog.canEdit(skill, "user-b", true));
        var published = skills.find(skill.skillId()).orElseThrow();
        java.nio.file.Files.writeString(
                java.nio.file.Path.of(published.liveDir()).resolve("SKILL.md"), "");
        assertTrue(catalog.findAvailableByCode(code, "user-a").isEmpty());
        skills.unpublish(published);
        assertTrue(catalog.findAvailableByCode(code, "user-a").isEmpty());
    }

    @Test
    void unavailableTargetsInvalidateCustomAliasesAndMalformedToolsStayHidden() throws Exception {
        var tools =
                new io.deepscope.advisor.tool.ToolCatalog(
                        dataSource, new com.fasterxml.jackson.databind.ObjectMapper());
        var tool =
                tools.importTools(
                                "user-a",
                                List.of(
                                        new io.deepscope.advisor.tool.ToolImport.ImportedTool(
                                                "weather",
                                                "Weather",
                                                "{}",
                                                "http",
                                                "GET",
                                                "https://example.com/weather")),
                                "allow")
                        .get(0);
        catalog.createCustom("user-a", "weather", "Weather", "", "tool", tool.toolId(), "{}", 20);
        assertTrue(catalog.findAvailableByCode("weather", "user-a").isPresent());
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE advisor_tools SET lifecycle_status = 'apply_failed'");
            assertTrue(catalog.findAvailableByCode("weather", "user-a").isEmpty());
            statement.executeUpdate(
                    "UPDATE advisor_tools SET lifecycle_status = 'ready', input_schema = '[]'");
            assertTrue(catalog.findAvailableByCode("weather", "user-a").isEmpty());
            statement.executeUpdate("UPDATE advisor_tools SET input_schema = '{}', http_url = ''");
            assertTrue(catalog.findAvailableByCode("weather", "user-a").isEmpty());
        }
    }

    @Test
    void ensureBuiltinSeedsCommandsIdempotently() throws SQLException {
        catalog.ensureBuiltin();
        List<CommandCatalog.CommandRow> initial = catalog.listAvailable(null);
        assertTrue(initial.stream().anyMatch(c -> "compact".equals(c.code())));
        assertTrue(initial.stream().anyMatch(c -> "clear".equals(c.code())));

        // Second call should not fail or duplicate
        catalog.ensureBuiltin();
        List<CommandCatalog.CommandRow> second = catalog.listAvailable(null);
        assertEquals(initial.size(), second.size());
    }

    @Test
    void listAvailableIsolatesUserCustomCommands() throws SQLException {
        catalog.ensureBuiltin();
        catalog.createCustom(
                "user-a",
                "custom-a",
                "自定义指令A",
                "用户A的专属指令",
                CommandCatalog.TARGET_PROMPT,
                null,
                "{}",
                50);
        catalog.createCustom(
                "user-b",
                "custom-b",
                "自定义指令B",
                "用户B的专属指令",
                CommandCatalog.TARGET_PROMPT,
                null,
                "{}",
                60);

        List<CommandCatalog.CommandRow> visitorList = catalog.listAvailable(null);
        assertFalse(visitorList.stream().anyMatch(c -> "custom-a".equals(c.code())));
        assertFalse(visitorList.stream().anyMatch(c -> "custom-b".equals(c.code())));

        List<CommandCatalog.CommandRow> userAList = catalog.listAvailable("user-a");
        assertTrue(userAList.stream().anyMatch(c -> "compact".equals(c.code())));
        assertTrue(userAList.stream().anyMatch(c -> "custom-a".equals(c.code())));
        assertFalse(userAList.stream().anyMatch(c -> "custom-b".equals(c.code())));

        List<CommandCatalog.CommandRow> userBList = catalog.listAvailable("user-b");
        assertTrue(userBList.stream().anyMatch(c -> "compact".equals(c.code())));
        assertFalse(userBList.stream().anyMatch(c -> "custom-a".equals(c.code())));
        assertTrue(userBList.stream().anyMatch(c -> "custom-b".equals(c.code())));
    }

    @Test
    void deleteCustomOnlyDeletesWhenOwnerMatches() throws SQLException {
        CommandCatalog.CommandRow created =
                catalog.createCustom(
                        "user-a",
                        "my-cmd",
                        "我的指令",
                        "",
                        CommandCatalog.TARGET_ACTION,
                        null,
                        "{}",
                        100);

        // Another user cannot delete
        assertFalse(catalog.deleteCustom(created.commandId(), "user-b"));
        assertTrue(catalog.find(created.commandId()).isPresent());

        // Owner can delete
        assertTrue(catalog.deleteCustom(created.commandId(), "user-a"));
        assertFalse(catalog.find(created.commandId()).isPresent());
    }
}
