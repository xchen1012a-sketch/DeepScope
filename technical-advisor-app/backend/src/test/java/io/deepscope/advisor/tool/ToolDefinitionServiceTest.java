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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.core.tool.mcp.McpClientWrapper;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class ToolDefinitionServiceTest {
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private final ObjectMapper json = new ObjectMapper();
    private SimpleDriverDataSource source;
    private ToolCatalog catalog;
    private ToolDefinitionService service;
    private HttpServer server;
    private String base;
    private final AtomicInteger heads = new AtomicInteger();
    private final AtomicInteger authenticatedHeads = new AtomicInteger();
    private final McpToolSource mcp =
            new McpToolSource() {
                public List<ToolCatalog.DiscoveredTool> list(
                        McpEndpointParser.McpEndpoint endpoint) {
                    return List.of();
                }

                public McpClientWrapper open(McpEndpointParser.McpEndpoint endpoint) {
                    throw new UnsupportedOperationException();
                }
            };

    @BeforeEach
    void setup() throws Exception {
        source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:import-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(source);
        catalog = new ToolCatalog(source, json);
        service = instance();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    heads.incrementAndGet();
                    if ("Bearer fake-secret"
                            .equals(exchange.getRequestHeaders().getFirst("Authorization")))
                        authenticatedHeads.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void close() {
        server.stop(0);
    }

    private ToolDefinitionService instance() {
        return new ToolDefinitionService(
                catalog, json, new ToolCredentialStore(json, KEY, source), mcp);
    }

    private Map<String, Object> body(String description) {
        return new LinkedHashMap<>(
                Map.of(
                        "kind",
                        "schema",
                        "address",
                        base + "/one",
                        "content",
                        "{\"name\":\"one\",\"description\":\""
                                + description
                                + "\",\"inputSchema\":{\"type\":\"object\"}}",
                        "permission",
                        "ask"));
    }

    private Map<String, Object> action(
            Map<String, Object> body, String type, ToolCatalog.ToolRow target, String name) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", type);
        if (target != null) {
            detail.put("toolId", target.toolId());
            detail.put("expectedRevision", target.revision());
        }
        if (name != null) detail.put("name", name);
        body.put("duplicateActions", Map.of("POST:/one", detail));
        return body;
    }

    @Test
    void createRequiresNewNameAndSkipDoesNotProbeOrSaveCredentials() throws Exception {
        var old = service.importTools("owner", false, body("old"), null).get(0);
        var duplicate = action(body("new"), "create", null, null);
        assertEquals(
                "tool_name_conflict",
                assertThrows(
                                ToolProblem.class,
                                () -> service.importTools("owner", false, duplicate, null))
                        .code());
        var created =
                service.importTools(
                                "owner",
                                false,
                                action(body("new"), "create", null, "local_alias"),
                                null)
                        .get(0);
        assertNotEquals(old.toolId(), created.toolId());
        assertEquals("local_alias", created.name());
        int before = heads.get();
        var skip = action(body("new"), "skip", null, null);
        skip.put("auth", Map.of("type", "bearer", "value", "fake-secret"));
        assertTrue(service.importTools("owner", false, skip, "skip").isEmpty());
        assertEquals(before, heads.get());
        assertEquals(0, count("advisor_tool_credentials"));
        assertEquals(2, catalog.listTools("owner", false).size());
    }

    @Test
    void updateRetainsIdentityPermissionBindingsAndCredentialWithKeepAction() throws Exception {
        var initial = body("old");
        initial.put("permission", "deny");
        initial.put("auth", Map.of("type", "bearer", "value", "fake-secret"));
        var old = service.importTools("owner", false, initial, null).get(0);
        catalog.replaceSelection("agent", List.of(old.toolId()), List.of());
        var update = action(body("changed"), "update", old, null);
        update.put("auth", Map.of("action", "keep"));
        var edited = service.importTools("owner", false, update, "update").get(0);
        assertEquals(old.toolId(), edited.toolId());
        assertEquals("changed", edited.description());
        assertEquals("deny", edited.permission());
        assertEquals(2, edited.revision());
        assertEquals(
                ToolAuth.credentialId(old.configJson()),
                ToolAuth.credentialId(edited.configJson()));
        assertEquals(2, authenticatedHeads.get());
        assertEquals(List.of(old.toolId()), catalog.selection("agent").toolIds());
        assertEquals(
                "POST:/one",
                json.readTree(edited.configJson()).path("source").path("operationId").asText());
        assertEquals(1, count("advisor_tool_credentials"));
        assertEquals(
                "revision_conflict",
                assertThrows(
                                ToolProblem.class,
                                () -> service.importTools("owner", false, update, null))
                        .code());
    }

    @Test
    void defaultPermissionRemainsAskAndInvalidPermissionIsDiagnosedBeforeProbe() throws Exception {
        var body = body("new");
        body.remove("permission");
        assertEquals(
                "ask",
                assertDoesNotThrow(() -> service.importTools("owner", false, body, null))
                        .get(0)
                        .permission());
        var invalid = body("new");
        invalid.put("permission", "invalid");
        invalid.put(
                "duplicateActions",
                Map.of("POST:/one", Map.of("action", "create", "name", "other")));
        int before = heads.get();
        assertEquals(
                "invalid_permission",
                assertThrows(
                                ToolProblem.class,
                                () -> service.importTools("owner", false, invalid, null))
                        .code());
        assertEquals(before, heads.get());
    }

    @Test
    void privateCredentialsRejectOtherAdminsAndOwnerReplacementKeepsOmittedAuth() throws Exception {
        var initial = body("old");
        initial.put("auth", Map.of("type", "bearer", "value", "fake-secret"));
        var old = service.importTools("owner", false, initial, null).get(0);
        var update = action(body("replacement"), "update", old, null);
        update.put(
                "auth", Map.of("action", "replace", "type", "bearer", "value", "fake-new-secret"));
        assertEquals(
                "tool_not_found",
                assertThrows(
                                ToolProblem.class,
                                () -> service.importTools("admin", true, update, "admin-update"))
                        .code());
        var edited = service.importTools("owner", false, update, "owner-update").get(0);
        assertEquals("owner", edited.ownerUserId());
        var store = new ToolCredentialStore(json, KEY, source);
        assertEquals(
                "fake-new-secret",
                assertDoesNotThrow(
                                () ->
                                        store.read(
                                                "owner",
                                                ToolAuth.credentialId(edited.configJson())))
                        .get("value"));
        var keep = action(body("keep"), "update", edited, null);
        var kept = service.importTools("owner", false, keep, null).get(0);
        assertEquals(
                ToolAuth.credentialId(edited.configJson()),
                ToolAuth.credentialId(kept.configJson()));
        assertEquals(2, count("advisor_tool_credentials"));
    }

    @Test
    void differentSourceSameNameRequiresAliasAndCannotUpdateUnrelatedTool() throws Exception {
        var old = service.importTools("owner", false, body("old"), null).get(0);
        var different = body("different");
        different.put("address", base + "/other");
        assertEquals(
                "duplicate_confirmation_required",
                assertThrows(
                                ToolProblem.class,
                                () -> service.importTools("owner", false, different, null))
                        .code());
        different.put(
                "duplicateActions",
                Map.of(
                        "POST:/other",
                        Map.of(
                                "action",
                                "update",
                                "toolId",
                                old.toolId(),
                                "expectedRevision",
                                old.revision())));
        assertEquals(
                "invalid_binding",
                assertThrows(
                                ToolProblem.class,
                                () -> service.importTools("owner", false, different, null))
                        .code());
        different.put(
                "duplicateActions",
                Map.of("POST:/other", Map.of("action", "create", "name", "new_alias")));
        assertEquals(
                "new_alias", service.importTools("owner", false, different, null).get(0).name());
        assertEquals(old, catalog.findTool(old.toolId()).orElseThrow());
    }

    @Test
    void replayAcrossInstancesUsesResultSnapshotAfterToolIsEdited() throws Exception {
        var body = body("old");
        var original = service.importTools("owner", false, body, "same");
        catalog.updatePermission(original.get(0).toolId(), "deny");
        assertEquals(original, instance().importTools("owner", false, body, "same"));
        var different = body("different");
        assertEquals(
                "idempotency_conflict",
                assertThrows(
                                ToolProblem.class,
                                () -> instance().importTools("owner", false, different, "same"))
                        .code());
        assertEquals(1, catalog.listTools("owner", false).size());
    }

    @Test
    void concurrentInstancesWithSameKeySaveOnlyOneBatch() throws Exception {
        var other = instance();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return service.importTools(
                                        "owner", false, body("same"), "concurrent");
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return other.importTools(
                                        "owner", false, body("same"), "concurrent");
                            });
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, catalog.listTools("owner", false).size());
            assertEquals(1, importRecords());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeyWhenAnotherInstanceCommitsBeforeValidationReplaysSnapshot()
            throws Exception {
        var first = instance();
        java.util.concurrent.atomic.AtomicBoolean intercepted =
                new java.util.concurrent.atomic.AtomicBoolean();
        DataSource proxySource =
                new SimpleDriverDataSource(new Driver(), source.getUrl(), "sa", "") {
                    @Override
                    public Connection getConnection() throws SQLException {
                        Connection conn = super.getConnection();
                        if (intercepted.compareAndSet(false, true)) {
                            return (Connection)
                                    java.lang.reflect.Proxy.newProxyInstance(
                                            Connection.class.getClassLoader(),
                                            new Class<?>[] {Connection.class},
                                            (proxy, method, args) -> {
                                                Object res = method.invoke(conn, args);
                                                if ("close".equals(method.getName())) {
                                                    first.importTools(
                                                            "owner",
                                                            false,
                                                            body("interleaved"),
                                                            "key-1");
                                                }
                                                return res;
                                            });
                        }
                        return conn;
                    }
                };
        var second =
                new ToolDefinitionService(
                        new ToolCatalog(proxySource, json),
                        json,
                        new ToolCredentialStore(json, KEY, proxySource),
                        mcp);
        var replayed = second.importTools("owner", false, body("interleaved"), "key-1");
        assertEquals(1, replayed.size());
        assertEquals("one", replayed.get(0).name());
        assertEquals(1, catalog.listTools("owner", false).size());
    }

    @Test
    void authActionRejectsUnknownAction() {
        var body = body("auth-test");
        body.put("auth", Map.of("action", "delete_secret"));
        var error =
                assertThrows(
                        ToolProblem.class, () -> service.importTools("owner", false, body, null));
        assertEquals("invalid_auth", error.code());
    }

    @Test
    void authActionClearRemovesCredentialsEvenWhenTypeOrValueIsProvided() throws Exception {
        var initial = body("auth-clear-test");
        initial.put("auth", Map.of("type", "bearer", "value", "fake-secret"));
        var old = service.importTools("owner", false, initial, null).get(0);
        assertTrue(ToolAuth.credentialId(old.configJson()) != null);

        var update = action(body("updated-desc"), "update", old, null);
        update.put(
                "auth",
                Map.of(
                        "action",
                        "clear",
                        "type",
                        "bearer",
                        "value",
                        "stale-secret-should-be-ignored"));
        var updated = service.importTools("owner", false, update, null).get(0);
        assertEquals(null, ToolAuth.credentialId(updated.configJson()));
        assertEquals(1, count("advisor_tool_credentials"));
    }

    @Test
    void authActionReplaceRequiresValidType() {
        var body = body("auth-replace-test");
        body.put("auth", Map.of("action", "replace"));
        var error =
                assertThrows(
                        ToolProblem.class, () -> service.importTools("owner", false, body, null));
        assertEquals("invalid_auth", error.code());

        var noneBody = body("auth-replace-none");
        noneBody.put("auth", Map.of("action", "replace", "type", "none"));
        var noneError =
                assertThrows(
                        ToolProblem.class,
                        () -> service.importTools("owner", false, noneBody, null));
        assertEquals("invalid_auth", noneError.code());
    }

    @Test
    void concurrentWithoutKeySameNameRejectsSecondAsNameConflict() throws Exception {
        var first = instance();
        var second = instance();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var body1 = action(body("no-key-same-1"), "create", null, null);
            var body2 = action(body("no-key-same-2"), "create", null, null);
            var future1 =
                    executor.submit(
                            () -> {
                                start.await();
                                return first.importTools("owner", false, body1, null);
                            });
            var future2 =
                    executor.submit(
                            () -> {
                                start.await();
                                return second.importTools("owner", false, body2, null);
                            });
            start.countDown();
            boolean firstFailed = false;
            boolean secondFailed = false;
            try {
                future1.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof ToolProblem tp
                        && "tool_name_conflict".equals(tp.code())) {
                    firstFailed = true;
                } else throw e;
            }
            try {
                future2.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof ToolProblem tp
                        && "tool_name_conflict".equals(tp.code())) {
                    secondFailed = true;
                } else throw e;
            }
            assertTrue(
                    firstFailed ^ secondFailed,
                    "Exactly one must succeed and the other fail with tool_name_conflict");
            assertEquals(1, catalog.listTools("owner", false).size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeyDifferentContentRejectsSecondWithIdempotencyConflict() throws Exception {
        var first = instance();
        var second = instance();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var future1 =
                    executor.submit(
                            () -> {
                                start.await();
                                return first.importTools(
                                        "owner", false, body("body-A"), "conflict-key");
                            });
            var future2 =
                    executor.submit(
                            () -> {
                                start.await();
                                return second.importTools(
                                        "owner", false, body("body-B"), "conflict-key");
                            });
            start.countDown();
            boolean firstFailed = false;
            boolean secondFailed = false;
            try {
                future1.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof ToolProblem tp
                        && "idempotency_conflict".equals(tp.code())) {
                    firstFailed = true;
                } else throw e;
            }
            try {
                future2.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof ToolProblem tp
                        && "idempotency_conflict".equals(tp.code())) {
                    secondFailed = true;
                } else throw e;
            }
            assertTrue(
                    firstFailed ^ secondFailed,
                    "Exactly one must succeed and the other fail with idempotency_conflict");
            assertEquals(1, catalog.listTools("owner", false).size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sqliteConcurrencySerializesCompetingImports() throws Exception {
        var tempDb = Files.createTempFile("advisor-sqlite-", ".db");
        try {
            var sqliteSource =
                    new SimpleDriverDataSource(
                            new org.sqlite.JDBC(),
                            "jdbc:sqlite:" + tempDb.toAbsolutePath(),
                            "",
                            "");
            try (var conn = sqliteSource.getConnection();
                    var s = conn.createStatement()) {
                s.execute(
                        "CREATE TABLE IF NOT EXISTS advisor_tools (tool_id TEXT PRIMARY KEY, name"
                            + " TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', input_schema"
                            + " TEXT NOT NULL DEFAULT '{}', origin TEXT NOT NULL, permission TEXT"
                            + " NOT NULL, owner_user_id TEXT NULL, mcp_server_id TEXT NULL,"
                            + " http_method TEXT NULL, http_url TEXT NULL, approved INTEGER NOT"
                            + " NULL DEFAULT 1, created_at BIGINT NOT NULL, updated_at BIGINT NOT"
                            + " NULL, revision BIGINT NOT NULL DEFAULT 1, config_json TEXT NOT NULL"
                            + " DEFAULT '{}', provider_name TEXT NULL, lifecycle_status TEXT NOT"
                            + " NULL DEFAULT 'ready')");
                s.execute(
                        "CREATE TABLE IF NOT EXISTS advisor_tool_credentials (credential_id TEXT"
                            + " PRIMARY KEY, owner_user_id TEXT NOT NULL, ciphertext TEXT NOT NULL,"
                            + " nonce TEXT NOT NULL, created_at BIGINT NOT NULL)");
                s.execute(
                        "CREATE TABLE IF NOT EXISTS advisor_tool_change_requests (owner_user_id"
                                + " TEXT NOT NULL, request_key TEXT NOT NULL, digest TEXT NOT NULL,"
                                + " result_json TEXT NOT NULL, created_at BIGINT NOT NULL, PRIMARY"
                                + " KEY(owner_user_id, request_key))");
            }
            var sqliteCatalog = new ToolCatalog(sqliteSource, json);
            var sqliteService1 =
                    new ToolDefinitionService(
                            sqliteCatalog,
                            json,
                            new ToolCredentialStore(json, KEY, sqliteSource),
                            mcp);
            var sqliteService2 =
                    new ToolDefinitionService(
                            sqliteCatalog,
                            json,
                            new ToolCredentialStore(json, KEY, sqliteSource),
                            mcp);
            var executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                var f1 =
                        executor.submit(
                                () -> {
                                    start.await();
                                    return sqliteService1.importTools(
                                            "owner", false, body("sqlite-test"), "sqlite-key");
                                });
                var f2 =
                        executor.submit(
                                () -> {
                                    start.await();
                                    return sqliteService2.importTools(
                                            "owner", false, body("sqlite-test"), "sqlite-key");
                                });
                start.countDown();
                assertEquals(f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS));
                assertEquals(1, sqliteCatalog.listTools("owner", false).size());
            } finally {
                executor.shutdownNow();
            }
        } finally {
            Files.deleteIfExists(tempDb);
        }
    }

    @Test
    void chosenOperationsOnlySavesChosenCandidateAndHashDetectsStalePreview() throws Exception {
        var batch = batchBody();
        var preview = service.preview("owner", false, batch);
        batch.put("chosenOperations", List.of("GET:/first"));
        batch.put("definitionHash", preview.get(0).get("definitionHash"));
        var result = service.importTools("owner", false, batch, null);
        assertEquals(1, result.size());
        assertEquals("first", result.get(0).name());
        batch.put("definitionHash", "stale");
        int before = heads.get();
        assertEquals(
                "definition_changed",
                assertThrows(
                                ToolProblem.class,
                                () -> service.importTools("owner", false, batch, null))
                        .code());
        assertEquals(before, heads.get());
    }

    @Test
    void sqlFailureMidBatchRollsBackUpdatedDefinitionCredentialAndReplayResult() throws Exception {
        var seed = batchBody();
        seed.put("chosenOperations", List.of("GET:/first"));
        var old = service.importTools("owner", false, seed, null).get(0);
        catalog.replaceSelection("agent", List.of(old.toolId()), List.of());
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            s.execute(
                    "ALTER TABLE advisor_tools ADD CONSTRAINT reject_second CHECK (name <>"
                            + " 'second')");
        }
        var batch = batchBody();
        batch.put("auth", Map.of("type", "bearer", "value", "fake-secret"));
        batch.put(
                "duplicateActions",
                Map.of(
                        "GET:/first",
                        Map.of(
                                "action",
                                "update",
                                "toolId",
                                old.toolId(),
                                "expectedRevision",
                                old.revision())));
        assertThrows(
                SQLException.class, () -> service.importTools("owner", false, batch, "failing"));
        assertEquals(old, catalog.findTool(old.toolId()).orElseThrow());
        assertEquals(1, catalog.listTools("owner", false).size());
        assertEquals(List.of(old.toolId()), catalog.selection("agent").toolIds());
        assertEquals(0, count("advisor_tool_credentials"));
        assertEquals(0, importRecords());
    }

    private Map<String, Object> batchBody() {
        return new LinkedHashMap<>(
                Map.of(
                        "kind",
                        "openapi",
                        "permission",
                        "ask",
                        "content",
                        "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\""
                                + base
                                + "\"}],\"paths\":{\"/first\":{\"get\":{\"operationId\":\"first\"}},\"/second\":{\"get\":{\"operationId\":\"second\"}}}}"));
    }

    private int count(String table) throws SQLException {
        try (var c = source.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getInt(1);
        }
    }

    private int importRecords() throws SQLException {
        try (var c = source.getConnection();
                var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT COUNT(*) FROM advisor_tool_change_requests WHERE"
                                        + " request_key LIKE 'import:request:%'")) {
            r.next();
            return r.getInt(1);
        }
    }
}
