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
package io.deepscope.advisor.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.deepscope.advisor.db.AdvisorSchema;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.Driver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class ToolTestServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private ToolCatalog catalog;
    private ToolTestService service;
    private HttpServer server;
    private ExecutorService serverWorkers;
    private String address;
    private final AtomicInteger writes = new AtomicInteger();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setup() throws Exception {
        var source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:tests-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(source);
        catalog = new ToolCatalog(source, json);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverWorkers = Executors.newCachedThreadPool();
        server.setExecutor(serverWorkers);
        server.createContext(
                "/",
                exchange -> {
                    writes.incrementAndGet();
                    started.countDown();
                    try {
                        if (exchange.getRequestURI().getPath().equals("/wait"))
                            release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] data = "ok".getBytes();
                    try {
                        exchange.sendResponseHeaders(200, data.length);
                        exchange.getResponseBody().write(data);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        address = "http://127.0.0.1:" + server.getAddress().getPort();
        service =
                new ToolTestService(
                        catalog,
                        json,
                        new ToolHttpExecutor(
                                json, HttpClient.newHttpClient(), Duration.ofMillis(300)),
                        new ToolCredentialStore(json, "", source),
                        null);
    }

    @AfterEach
    void cleanup() {
        release.countDown();
        service.close();
        server.stop(0);
        serverWorkers.shutdownNow();
    }

    private ToolCatalog.ToolRow tool(String permission, String path) throws Exception {
        return catalog.importTools(
                        "owner",
                        List.of(
                                new ToolImport.ImportedTool(
                                        "write", "Write", "{}", "http", "POST", address + path)),
                        permission)
                .get(0);
    }

    private ToolTestService.Request request(
            String id, boolean confirmed, Map<String, Object> input) {
        return new ToolTestService.Request(input, null, null, id, confirmed, null);
    }

    @Test
    void denyAskAndDraftCannotBypassStoredPermissions() throws Exception {
        var denied = tool("deny", "/");
        assertEquals(
                "permission_denied",
                assertThrows(
                                ToolProblem.class,
                                () ->
                                        service.test(
                                                "owner",
                                                denied,
                                                true,
                                                request("deny", true, Map.of())))
                        .code());
        var ask = tool("ask", "/");
        ToolProblem confirmation =
                assertThrows(
                        ToolProblem.class,
                        () -> service.test("owner", ask, true, request("ask", false, Map.of())));
        assertEquals("confirmation_required", confirmation.code());
        var draft =
                new ToolTestService.Request(
                        Map.of(), null, Map.of("permission", "allow"), "draft", false, null);
        assertEquals(
                "confirmation_required",
                assertThrows(ToolProblem.class, () -> service.test("owner", ask, true, draft))
                        .code());
        assertEquals(0, writes.get());
    }

    @Test
    void sameRunReturnsOriginalButChangedRequestConflicts() throws Exception {
        var tool = tool("allow", "/");
        assertEquals(
                200,
                service.test("owner", tool, true, request("same", false, Map.of())).httpStatus());
        assertEquals(
                200,
                service.test("owner", tool, true, request("same", false, Map.of())).httpStatus());
        assertEquals(
                "run_conflict",
                assertThrows(
                                ToolProblem.class,
                                () ->
                                        service.test(
                                                "owner",
                                                tool,
                                                true,
                                                request("same", false, Map.of("value", 1))))
                        .code());
        assertEquals(1, writes.get());
    }

    @Test
    void timeoutDoesNotRetryAndStopReleasesTheAccount() throws Exception {
        var tool = tool("allow", "/wait");
        var waiting =
                CompletableFuture.supplyAsync(
                        () -> service.test("owner", tool, true, request("stop", false, Map.of())));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(
                "test_busy",
                assertThrows(
                                ToolProblem.class,
                                () ->
                                        service.test(
                                                "owner",
                                                tool,
                                                true,
                                                request("other", false, Map.of())))
                        .code());
        assertEquals(
                "test_not_found",
                assertThrows(ToolProblem.class, () -> service.stop("other", "stop")).code());
        assertEquals("cancelled", service.stop("owner", "stop").status());
        assertEquals("execution_cancelled", waiting.get(2, TimeUnit.SECONDS).errorCode());
        assertEquals("cancelled", service.stop("owner", "stop").status());
        var timeout = service.test("owner", tool, true, request("timeout", false, Map.of()));
        assertEquals("execution_timeout", timeout.errorCode());
        service.test("owner", tool, true, request("timeout", false, Map.of()));
        assertEquals(2, writes.get());
    }

    @Test
    void confirmationIsBoundToAccountInputToolAndRevision() throws Exception {
        var tool = tool("ask", "/");
        var error =
                assertThrows(
                        ToolProblem.class,
                        () ->
                                service.test(
                                        "owner",
                                        tool,
                                        true,
                                        request("confirm", false, Map.of("value", 1))));
        String token = String.valueOf(error.body().get("confirmationId"));
        var changed =
                new ToolTestService.Request(
                        Map.of("value", 2), tool.revision(), null, "confirm", false, token);
        assertEquals(
                "confirmation_invalid",
                assertThrows(ToolProblem.class, () -> service.test("owner", tool, true, changed))
                        .code());
        error =
                assertThrows(
                        ToolProblem.class,
                        () ->
                                service.test(
                                        "owner",
                                        tool,
                                        true,
                                        request("confirm", false, Map.of("value", 1))));
        token = String.valueOf(error.body().get("confirmationId"));
        var approved =
                new ToolTestService.Request(
                        Map.of("value", 1), tool.revision(), null, "confirm", false, token);
        assertEquals(200, service.test("owner", tool, true, approved).httpStatus());
        assertEquals(200, service.test("owner", tool, true, approved).httpStatus());
        var reused =
                new ToolTestService.Request(
                        Map.of("value", 1), tool.revision(), null, "new-run", false, token);
        assertEquals(
                "confirmation_invalid",
                assertThrows(ToolProblem.class, () -> service.test("owner", tool, true, reused))
                        .code());
        var other = tool("ask", "/");
        assertEquals(
                "run_conflict",
                assertThrows(
                                ToolProblem.class,
                                () ->
                                        service.test(
                                                "owner",
                                                other,
                                                true,
                                                request("confirm", true, Map.of("value", 1))))
                        .code());
        assertEquals(1, writes.get());
    }

    @Test
    void testingAndRealExecutionSendExactlyTheSameRequest() throws Exception {
        var imported =
                catalog.importTools(
                                "owner",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "write",
                                                "Write",
                                                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"x-parameter-in\":\"query\"},\"value\":{\"type\":\"integer\"}}}",
                                                "http",
                                                "POST",
                                                address + "/")),
                                "allow")
                        .get(0);
        var captured = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.removeContext("/");
        server.createContext(
                "/",
                exchange -> {
                    captured.add(
                            exchange.getRequestMethod()
                                    + " "
                                    + exchange.getRequestURI()
                                    + " "
                                    + new String(exchange.getRequestBody().readAllBytes()));
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        var input = Map.<String, Object>of("query", "a b", "value", 2);
        service.test("owner", imported, true, request("mapping", false, input));
        HttpCatalogTool.create(imported, json, HttpClient.newHttpClient())
                .callAsync(io.deepscope.core.tool.ToolCallParam.builder().input(input).build())
                .block();
        assertEquals(2, captured.size());
        assertEquals(captured.get(0), captured.get(1));
    }

    @Test
    void mcpStopCancelsSdkSubscriptionAndClosesTestConnection() throws Exception {
        var sdk = org.mockito.Mockito.mock(io.deepscope.core.tool.mcp.McpClientWrapper.class);
        var subscribed = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        org.mockito.Mockito.when(
                        sdk.callTool(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(
                        reactor.core.publisher.Mono
                                .<io.modelcontextprotocol.spec.McpSchema.CallToolResult>never()
                                .doOnSubscribe(s -> subscribed.countDown())
                                .doOnCancel(cancelled::countDown));
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            closed.countDown();
                            return null;
                        })
                .when(sdk)
                .close();
        var endpoint =
                new McpEndpointParser.McpEndpoint(
                        "test", "http", null, List.of(), Map.of(), address, Map.of());
        var serverRow = catalog.insertServer(endpoint, "owner", "connected", "");
        var tool =
                catalog.approveMcpTool(
                        serverRow, new ToolCatalog.DiscoveredTool("write", "Write", "{}"), "allow");
        var source =
                new McpToolSource() {
                    public List<ToolCatalog.DiscoveredTool> list(McpEndpointParser.McpEndpoint e) {
                        return List.of();
                    }

                    public io.deepscope.core.tool.mcp.McpClientWrapper open(
                            McpEndpointParser.McpEndpoint e) {
                        return sdk;
                    }
                };
        var credentials = org.mockito.Mockito.mock(ToolCredentialStore.class);
        try (var tests =
                new ToolTestService(
                        catalog, json, new ToolHttpExecutor(json), credentials, source)) {
            var waiting =
                    CompletableFuture.supplyAsync(
                            () ->
                                    tests.test(
                                            "owner",
                                            tool,
                                            true,
                                            request("mcp-stop", false, Map.of())));
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));
            tests.stop("owner", "mcp-stop");
            assertEquals("execution_cancelled", waiting.get(2, TimeUnit.SECONDS).errorCode());
            assertTrue(cancelled.await(2, TimeUnit.SECONDS));
            assertTrue(closed.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void stoppingARejectedExecutionIsAlsoIdempotent() throws Exception {
        var broken =
                catalog.importTools(
                                "owner",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "broken",
                                                "Broken",
                                                "{}",
                                                "http",
                                                "POST",
                                                "/relative")),
                                "allow")
                        .get(0);
        assertEquals(
                "invalid_address",
                assertThrows(
                                ToolProblem.class,
                                () ->
                                        service.test(
                                                "owner",
                                                broken,
                                                true,
                                                request("rejected", false, Map.of())))
                        .code());
        assertEquals("failed", service.stop("owner", "rejected").status());
        assertEquals("failed", service.stop("owner", "rejected").status());
        assertEquals(0, writes.get());
    }

    @Test
    void terminalRunsDoNotAccumulateInMemoryAndPersistAcrossServiceInstances() throws Exception {
        var tool = tool("allow", "/");
        for (int i = 0; i < 33; i++)
            service.test("owner", tool, true, request("terminal-" + i, false, Map.of()));
        var field = ToolTestService.class.getDeclaredField("runs");
        field.setAccessible(true);
        assertTrue(
                ((Map<?, ?>) field.get(service)).size() <= 32,
                "Terminal result retention must be bounded");
        service.close();
        try (var restarted =
                new ToolTestService(
                        catalog,
                        json,
                        executorForTests(),
                        org.mockito.Mockito.mock(ToolCredentialStore.class),
                        null)) {
            assertEquals(
                    200,
                    restarted
                            .test("owner", tool, true, request("terminal-0", false, Map.of()))
                            .httpStatus());
            assertEquals(33, writes.get(), "Restart must not repeat a completed write");
            assertEquals(
                    "run_conflict",
                    assertThrows(
                                    ToolProblem.class,
                                    () ->
                                            restarted.test(
                                                    "owner",
                                                    tool,
                                                    true,
                                                    request(
                                                            "terminal-0",
                                                            false,
                                                            Map.of("different", true))))
                            .code());
        }
    }

    @Test
    void anotherServiceTreatsPersistedInflightAsUnknownAndNeverResends() throws Exception {
        var tool = tool("allow", "/wait");
        var waiting =
                CompletableFuture.supplyAsync(
                        () ->
                                service.test(
                                        "owner", tool, true, request("inflight", false, Map.of())));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        try (var restarted =
                new ToolTestService(
                        catalog,
                        json,
                        executorForTests(),
                        org.mockito.Mockito.mock(ToolCredentialStore.class),
                        null)) {
            assertEquals(
                    "execution_unknown",
                    restarted
                            .test("owner", tool, true, request("inflight", false, Map.of()))
                            .errorCode());
            assertEquals(1, writes.get());
        } finally {
            service.stop("owner", "inflight");
            waiting.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void unusedConfirmationsHavePerAccountQuotaAndExpire() throws Exception {
        var tool = tool("ask", "/");
        for (int i = 0; i < 16; i++)
            assertEquals(
                    "confirmation_required",
                    assertThrows(
                                    ToolProblem.class,
                                    () ->
                                            service.test(
                                                    "owner",
                                                    tool,
                                                    true,
                                                    request(
                                                            UUID.randomUUID().toString(),
                                                            false,
                                                            Map.of())))
                            .code());
        assertEquals(
                "confirmation_busy",
                assertThrows(
                                ToolProblem.class,
                                () ->
                                        service.test(
                                                "owner",
                                                tool,
                                                true,
                                                request("over-limit", false, Map.of())))
                        .code());
    }

    @Test
    void confirmationTokensExpireAndQuotaIsReclaimed() throws Exception {
        var tool = tool("ask", "/");
        var clock = new MutableClock();
        try (var expiring =
                new ToolTestService(
                        catalog,
                        json,
                        executorForTests(),
                        org.mockito.Mockito.mock(ToolCredentialStore.class),
                        null,
                        clock)) {
            var error =
                    assertThrows(
                            ToolProblem.class,
                            () ->
                                    expiring.test(
                                            "owner",
                                            tool,
                                            true,
                                            request("expired", false, Map.of())));
            String token = String.valueOf(error.body().get("confirmationId"));
            clock.advance(java.time.Duration.ofMinutes(6));
            var confirmed =
                    new ToolTestService.Request(
                            Map.of(), tool.revision(), null, "expired", false, token);
            assertEquals(
                    "confirmation_invalid",
                    assertThrows(
                                    ToolProblem.class,
                                    () -> expiring.test("owner", tool, true, confirmed))
                            .code());
            assertEquals(
                    "confirmation_required",
                    assertThrows(
                                    ToolProblem.class,
                                    () ->
                                            expiring.test(
                                                    "owner",
                                                    tool,
                                                    true,
                                                    request("new", false, Map.of())))
                            .code());
            assertEquals(0, writes.get());
        }
    }

    @Test
    void mcpSerializationStopsAtTheBoundInsteadOfTraversingTheWholeSdkResult() throws Exception {
        var visited = new AtomicInteger();
        var boundedJson = new ObjectMapper();
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(
                io.modelcontextprotocol.spec.McpSchema.CallToolResult.class,
                new com.fasterxml.jackson.databind.JsonSerializer<
                        io.modelcontextprotocol.spec.McpSchema.CallToolResult>() {
                    @Override
                    public void serialize(
                            io.modelcontextprotocol.spec.McpSchema.CallToolResult value,
                            com.fasterxml.jackson.core.JsonGenerator generator,
                            com.fasterxml.jackson.databind.SerializerProvider provider)
                            throws java.io.IOException {
                        generator.writeStartArray();
                        for (int i = 0; i < 512; i++) {
                            visited.incrementAndGet();
                            generator.writeString("x".repeat(1024));
                        }
                        generator.writeEndArray();
                    }
                });
        boundedJson.registerModule(module);
        var sdk = org.mockito.Mockito.mock(io.deepscope.core.tool.mcp.McpClientWrapper.class);
        org.mockito.Mockito.when(
                        sdk.callTool(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(
                        reactor.core.publisher.Mono.just(
                                new io.modelcontextprotocol.spec.McpSchema.CallToolResult(
                                        List.of(), false, null)));
        var endpoint =
                new McpEndpointParser.McpEndpoint(
                        "large", "http", null, List.of(), Map.of(), address, Map.of());
        var row = catalog.insertServer(endpoint, "owner", "connected", "");
        var tool =
                catalog.approveMcpTool(
                        row, new ToolCatalog.DiscoveredTool("large", "Large", "{}"), "allow");
        var source =
                new McpToolSource() {
                    public List<ToolCatalog.DiscoveredTool> list(McpEndpointParser.McpEndpoint e) {
                        return List.of();
                    }

                    public io.deepscope.core.tool.mcp.McpClientWrapper open(
                            McpEndpointParser.McpEndpoint e) {
                        return sdk;
                    }
                };
        try (var tests =
                new ToolTestService(
                        catalog,
                        boundedJson,
                        executorForTests(),
                        org.mockito.Mockito.mock(ToolCredentialStore.class),
                        source)) {
            var result = tests.test("owner", tool, true, request("large", false, Map.of()));
            assertTrue(result.truncated());
            assertTrue(
                    result.result().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            <= 256 * 1024);
            assertTrue(
                    visited.get() < 300,
                    "Serializer visited "
                            + visited.get()
                            + " chunks; bounded output must stop traversal");
        }
    }

    private ToolHttpExecutor executorForTests() {
        return new ToolHttpExecutor(json, HttpClient.newHttpClient(), Duration.ofMillis(300));
    }

    private static final class MutableClock extends java.time.Clock {
        private java.time.Instant instant = java.time.Instant.parse("2026-10-14T00:00:00Z");

        void advance(java.time.Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public java.time.Instant instant() {
            return instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
