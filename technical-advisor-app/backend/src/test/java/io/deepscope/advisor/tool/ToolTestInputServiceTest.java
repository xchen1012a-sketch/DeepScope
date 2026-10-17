/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deepscope.advisor.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.Model;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import reactor.core.publisher.Flux;

class ToolTestInputServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Model model = mock(Model.class);

    private ToolCatalog.ToolRow tool() throws Exception {
        var source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:ai-input-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(source);
        return new ToolCatalog(source, json)
                .importTools(
                        "owner",
                        List.of(
                                new ToolImport.ImportedTool(
                                        "get_current_temperature",
                                        "Read temperature",
                                        "{\"type\":\"object\",\"properties\":{\"latitude\":{\"type\":\"number\"}},\"required\":[\"latitude\"]}",
                                        "http",
                                        "GET",
                                        "https://example.invalid/weather")),
                        "allow")
                .get(0);
    }

    private void answer(String text) {
        when(model.stream(anyList(), anyList(), any()))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text(text).build()))
                                        .build()));
    }

    @Test
    void generatesTypedArgumentsUsingTheConfiguredModelWithoutExecutingTheTool() throws Exception {
        answer("{\"latitude\":39.9042}");
        assertEquals(
                Map.of("latitude", 39.9042), new ToolTestInputService(model, json).suggest(tool()));
    }

    @Test
    void rejectsInvalidTypesAndMalformedOutputsRatherThanFallingBackToInventedArguments()
            throws Exception {
        var tool = tool();
        for (String output :
                List.of(
                        "{\"latitude\":\"unknown\"}",
                        "[]",
                        "not JSON",
                        "{}",
                        "{\"latitude\":1} {\"extra\":true}")) {
            answer(output);
            var error =
                    assertThrows(
                            ToolProblem.class,
                            () -> new ToolTestInputService(model, json).suggest(tool));
            assertEquals(502, error.status());
            assertEquals("ai_input_failed", error.code());
        }
    }

    @Test
    void modelFailuresPropagateAsAnExplicitSafeError() throws Exception {
        when(model.stream(anyList(), anyList(), any()))
                .thenReturn(Flux.error(new IllegalStateException("private-provider-details")));
        var tool = tool();
        assertEquals(
                "ai_input_failed",
                assertThrows(
                                ToolProblem.class,
                                () -> new ToolTestInputService(model, json).suggest(tool))
                        .code());
    }

    @Test
    void legacyMcpSchemaUsesRealDiscoveryForAiAndRejectsForeignOrDisabledServers()
            throws Exception {
        var source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:legacy-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(source);
        var catalog = new ToolCatalog(source, json);
        var server =
                catalog.insertServer(
                        new McpEndpointParser.McpEndpoint(
                                "docs", "stdio", "echo", List.of(), Map.of(), null, Map.of()),
                        "owner",
                        "connected",
                        "");
        var tool =
                catalog.approveMcpTool(
                        server,
                        new ToolCatalog.DiscoveredTool(
                                "query-docs",
                                "Query",
                                "JsonSchema[type=object, properties={query={type=string}}]"),
                        "allow");
        var mcp = mock(McpToolSource.class);
        when(mcp.list(any()))
                .thenReturn(
                        List.of(
                                new ToolCatalog.DiscoveredTool(
                                        "query-docs",
                                        "Query",
                                        "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}")));
        answer("{\"query\":\"React cleanup examples\"}");
        assertEquals(
                Map.of("query", "React cleanup examples"),
                new ToolTestInputService(model, json, catalog, mcp).suggest(tool));
        verify(mcp, never()).open(any());
        assertEquals(
                tool.inputSchema(), catalog.findTool(tool.toolId()).orElseThrow().inputSchema());
        catalog.markServer(server.serverId(), "disabled", "");
        assertEquals(
                "invalid_schema",
                assertThrows(
                                ToolProblem.class,
                                () -> ToolInputSchemas.resolve(tool, catalog, mcp, json))
                        .code());
        catalog.markServer(server.serverId(), "connected", "");
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE advisor_mcp_servers SET owner_user_id = 'another-user'");
        }
        assertEquals(
                "invalid_schema",
                assertThrows(
                                ToolProblem.class,
                                () -> ToolInputSchemas.resolve(tool, catalog, mcp, json))
                        .code());
    }
}
