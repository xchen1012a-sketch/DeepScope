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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.advisor.db.AdvisorSchema;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class HttpCatalogToolTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private final ObjectMapper json = new ObjectMapper();
    private DataSource source;
    private ToolCredentialStore store;
    private HttpServer server;
    private String serverUrl;
    private final AtomicReference<String> authorizationHeader = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:http-catalog-tool-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(source);
        store = new ToolCredentialStore(json, KEY, source);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/test",
                exchange -> {
                    requestCount.incrementAndGet();
                    authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] response = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        serverUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/test";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void callAsyncInjectsDecryptedCredentialFromStore() throws Exception {
        var pending =
                store.prepare("owner-1", Map.of("type", "bearer", "value", "secret-token-123"));
        store.insert(pending);

        String configJson = ToolAuth.reference(Map.of("type", "bearer"), pending.id());
        var toolRow =
                new ToolCatalog.ToolRow(
                        "tool-1",
                        "test_tool",
                        "test tool description",
                        "{\"type\":\"object\"}",
                        ToolCatalog.ORIGIN_HTTP,
                        "allow",
                        "owner-1",
                        null,
                        "GET",
                        serverUrl,
                        true,
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                        1L,
                        configJson);

        ToolHttpExecutor executor =
                new ToolHttpExecutor(json, HttpClient.newHttpClient(), Duration.ofSeconds(5));

        HttpCatalogTool tool =
                HttpCatalogTool.create(
                        toolRow,
                        json,
                        executor,
                        () -> {
                            String credId = ToolAuth.credentialId(toolRow.configJson());
                            try {
                                return store.read(toolRow.ownerUserId(), credId);
                            } catch (Exception e) {
                                return null;
                            }
                        },
                        () -> {});

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(Map.of()).build()).block();

        assertEquals(1, requestCount.get());
        assertEquals("Bearer secret-token-123", authorizationHeader.get());
        assertNotEquals(ToolResultState.ERROR, result.getState());
        assertTrue(textOf(result).contains("200"));
    }

    @Test
    void callAsyncPreflightGuardPreventsExecutionWhenDenied() {
        var toolRow =
                new ToolCatalog.ToolRow(
                        "tool-2",
                        "denied_tool",
                        "denied description",
                        "{\"type\":\"object\"}",
                        ToolCatalog.ORIGIN_HTTP,
                        "deny",
                        "owner-1",
                        null,
                        "GET",
                        serverUrl,
                        true,
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                        1L,
                        "{}");

        ToolHttpExecutor executor =
                new ToolHttpExecutor(json, HttpClient.newHttpClient(), Duration.ofSeconds(5));

        HttpCatalogTool tool =
                HttpCatalogTool.create(
                        toolRow,
                        json,
                        executor,
                        () -> null,
                        () -> {
                            throw new ToolProblem(403, "tool_denied", "Execution denied");
                        });

        ToolResultBlock result =
                tool.callAsync(ToolCallParam.builder().input(Map.of()).build()).block();

        assertEquals(0, requestCount.get());
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(textOf(result).contains("Execution denied"));
    }

    private static String textOf(ToolResultBlock result) {
        if (result == null || result.getOutput() == null) return "";
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .collect(java.util.stream.Collectors.joining());
    }
}
