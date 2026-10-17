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
package io.deepscope.advisor.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.tool.ToolCatalog;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Validates idempotent schema evolution and backward compatibility for tool/mcp lifecycle tables.
 */
class AdvisorSchemaMigrationTest {

    @Test
    void schemaEvolutionIsIdempotentAndPreservesLegacyRecords() throws Exception {
        DataSource dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:migration-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");

        // 1. Create baseline legacy schema without new lifecycle columns and tables
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "CREATE TABLE accounts (user_id TEXT PRIMARY KEY, username TEXT UNIQUE NOT"
                            + " NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL DEFAULT"
                            + " 'admin', created_at BIGINT NOT NULL)");
            stmt.execute(
                    "CREATE TABLE advisor_mcp_servers (server_id TEXT PRIMARY KEY, name TEXT NOT"
                        + " NULL, transport TEXT NOT NULL, command TEXT NULL, args_json TEXT NOT"
                        + " NULL DEFAULT '[]', env_json TEXT NOT NULL DEFAULT '{}', url TEXT NULL,"
                        + " headers_json TEXT NOT NULL DEFAULT '{}', status TEXT NOT NULL,"
                        + " status_message TEXT NOT NULL DEFAULT '', owner_user_id TEXT NOT NULL,"
                        + " created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)");
            stmt.execute(
                    "CREATE TABLE advisor_tools (tool_id TEXT PRIMARY KEY, name TEXT NOT NULL,"
                            + " description TEXT NOT NULL DEFAULT '', input_schema TEXT NOT NULL"
                            + " DEFAULT '{}', origin TEXT NOT NULL, permission TEXT NOT NULL,"
                            + " owner_user_id TEXT NULL, mcp_server_id TEXT NULL, http_method TEXT"
                            + " NULL, http_url TEXT NULL, approved INTEGER NOT NULL DEFAULT 1,"
                            + " created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)");
            stmt.execute(
                    "CREATE TABLE advisor_agent_tools (agent_id TEXT NOT NULL, tool_id TEXT NOT"
                            + " NULL, PRIMARY KEY (agent_id, tool_id))");
            stmt.execute(
                    "CREATE TABLE advisor_agent_servers (agent_id TEXT NOT NULL, server_id TEXT NOT"
                            + " NULL, PRIMARY KEY (agent_id, server_id))");

            // Insert legacy records
            long now = System.currentTimeMillis();
            // Legacy HTTP tool
            stmt.execute(
                    String.format(
                            "INSERT INTO advisor_tools (tool_id, name, description, input_schema,"
                                    + " origin, permission, owner_user_id, http_method, http_url,"
                                    + " approved, created_at, updated_at) VALUES ('legacy-http-1',"
                                    + " 'legacy_weather', 'Legacy Weather API',"
                                    + " '{\"type\":\"object\"}', 'http', 'allow', 'user-1', 'GET',"
                                    + " 'https://api.example.com/weather', 1, %d, %d)",
                            now, now));
            // Legacy declared tool
            stmt.execute(
                    String.format(
                            "INSERT INTO advisor_tools (tool_id, name, description, input_schema,"
                                    + " origin, permission, owner_user_id, approved, created_at,"
                                    + " updated_at) VALUES ('legacy-declared-1', 'declared_stub',"
                                    + " 'Stub', '{}', 'declared', 'ask', 'user-1', 1, %d, %d)",
                            now, now));
            // Legacy MCP server and tool
            stmt.execute(
                    String.format(
                            "INSERT INTO advisor_mcp_servers (server_id, name, transport, command,"
                                    + " status, owner_user_id, created_at, updated_at) VALUES"
                                    + " ('legacy-srv-1', 'file_srv', 'stdio', 'echo', 'connected',"
                                    + " 'user-1', %d, %d)",
                            now, now));
            stmt.execute(
                    String.format(
                            "INSERT INTO advisor_tools (tool_id, name, description, input_schema,"
                                + " origin, permission, owner_user_id, mcp_server_id, approved,"
                                + " created_at, updated_at) VALUES ('legacy-mcp-1', 'read_file',"
                                + " 'Read', '{}', 'mcp', 'ask', 'user-1', 'legacy-srv-1', 1, %d,"
                                + " %d)",
                            now, now));

            // Legacy agent tool & server bindings
            stmt.execute(
                    "INSERT INTO advisor_agent_tools (agent_id, tool_id) VALUES ('agent-100',"
                            + " 'legacy-http-1')");
            stmt.execute(
                    "INSERT INTO advisor_agent_servers (agent_id, server_id) VALUES ('agent-100',"
                            + " 'legacy-srv-1')");
        }

        // 2. Run AdvisorSchema.initialize migration
        assertDoesNotThrow(() -> AdvisorSchema.initialize(dataSource));

        // 3. Verify idempotence: running initialize a second time must succeed without errors
        assertDoesNotThrow(() -> AdvisorSchema.initialize(dataSource));

        // 4. Verify catalog compatibility with migrated data
        ToolCatalog catalog = new ToolCatalog(dataSource, new ObjectMapper());

        List<ToolCatalog.ToolRow> userTools = catalog.listTools("user-1", true);
        assertEquals(3, userTools.size());

        ToolCatalog.ToolRow httpTool =
                userTools.stream()
                        .filter(t -> t.toolId().equals("legacy-http-1"))
                        .findFirst()
                        .orElse(null);
        assertNotNull(httpTool);
        assertEquals("legacy_weather", httpTool.name());
        assertEquals("http", httpTool.origin());
        assertEquals("allow", httpTool.permission());
        assertEquals("GET", httpTool.httpMethod());
        assertEquals("https://api.example.com/weather", httpTool.httpUrl());
        assertEquals(1L, httpTool.revision());

        ToolCatalog.ToolRow declaredTool =
                userTools.stream()
                        .filter(t -> t.toolId().equals("legacy-declared-1"))
                        .findFirst()
                        .orElse(null);
        assertNotNull(declaredTool);
        assertEquals("declared", declaredTool.origin());
        assertEquals(1L, declaredTool.revision());

        ToolCatalog.ToolRow mcpTool =
                userTools.stream()
                        .filter(t -> t.toolId().equals("legacy-mcp-1"))
                        .findFirst()
                        .orElse(null);
        assertNotNull(mcpTool);
        assertEquals("mcp", mcpTool.origin());
        assertEquals("legacy-srv-1", mcpTool.mcpServerId());

        // Verify effective tools for agent-100
        List<ToolCatalog.ToolRow> effective = catalog.effectiveTools("agent-100");
        assertEquals(2, effective.size());
        assertTrue(effective.stream().anyMatch(t -> t.toolId().equals("legacy-http-1")));
        assertTrue(effective.stream().anyMatch(t -> t.toolId().equals("legacy-mcp-1")));
    }
}
