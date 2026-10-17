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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.deepscope.advisor.access.AccessCatalog;
import io.deepscope.advisor.access.AccessGuard;
import io.deepscope.advisor.agent.AgentStore;
import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.core.tool.mcp.McpClientWrapper;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdvisorToolControllerTest {
    @Test
    void generatedTestInputIsScopedToVisibleAndExecutableTools() throws Exception {
        var tool =
                catalog.importTools(
                                "owner",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "test", "Test", "{}", "http", "GET", address)),
                                "allow")
                        .get(0);
        var inputs = org.mockito.Mockito.mock(ToolTestInputService.class);
        org.mockito.Mockito.when(inputs.suggest(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("latitude", 39.9042));
        var controller =
                new AdvisorToolController(
                        catalog,
                        mcp,
                        agentStore,
                        null,
                        json,
                        new AccessGuard(new AccessCatalog(source)),
                        new ToolCredentialStore(json, CREDENTIAL_KEY, source));
        controller.setTestInputService(inputs);
        var testMvc = MockMvcBuilders.standaloneSetup(controller).build();
        testMvc.perform(
                        post("/api/advisor/tools/" + tool.toolId() + "/test-input")
                                .requestAttr(AccountStore.Account.class.getName(), account))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.input.latitude").value(39.9042));
        testMvc.perform(
                        post("/api/advisor/tools/" + tool.toolId() + "/test-input")
                                .requestAttr(
                                        AccountStore.Account.class.getName(),
                                        new AccountStore.Account("other", "other", "user", 0)))
                .andExpect(status().isNotFound());
        catalog.updatePermission(tool.toolId(), "deny");
        testMvc.perform(
                        post("/api/advisor/tools/" + tool.toolId() + "/test-input")
                                .requestAttr(AccountStore.Account.class.getName(), account))
                .andExpect(status().isForbidden());
        assertEquals(
                "tool.test",
                io.deepscope.advisor.access.AccessPoints.operation(
                        "/api/advisor/tools/x/test-input", "POST", "tool"));
        org.mockito.Mockito.verify(inputs, org.mockito.Mockito.times(1))
                .suggest(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void temperatureTestAcceptsTheFrontendRequestWithoutAnOptionalConfirmationFlag()
            throws Exception {
        server.createContext(
                "/temperature",
                exchange -> {
                    assertEquals("GET", exchange.getRequestMethod());
                    assertTrue(exchange.getRequestURI().getRawQuery().contains("latitude=39.9042"));
                    assertTrue(
                            exchange.getRequestURI().getRawQuery().contains("longitude=116.4074"));
                    assertTrue(
                            exchange.getRequestURI()
                                    .getRawQuery()
                                    .contains("current=temperature_2m"));
                    byte[] response =
                            "{\"temperature\":20}"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        var tools =
                catalog.importTools(
                        "owner",
                        List.of(
                                new ToolImport.ImportedTool(
                                        "get_current_temperature",
                                        "Current temperature",
                                        "{\"type\":\"object\",\"properties\":{\"latitude\":{\"type\":\"number\",\"x-parameter-in\":\"query\"},\"longitude\":{\"type\":\"number\",\"x-parameter-in\":\"query\"},\"current\":{\"type\":\"string\",\"enum\":[\"temperature_2m\"],\"x-parameter-in\":\"query\"}},\"required\":[\"latitude\",\"longitude\",\"current\"]}",
                                        "http",
                                        "GET",
                                        address.replace("/weather", "/temperature"))),
                        "allow");
        var tool = tools.get(0);
        mvc.perform(
                        post("/api/advisor/tools/" + tool.toolId() + "/test")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "runId",
                                                        "weather-test",
                                                        "savedRevision",
                                                        tool.revision(),
                                                        "input",
                                                        Map.of(
                                                                "latitude",
                                                                39.9042,
                                                                "longitude",
                                                                116.4074,
                                                                "current",
                                                                "temperature_2m")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpStatus").value(200));
        assertEquals(
                0,
                writes.get(),
                "the target receives GET, while the test control endpoint uses POST");
    }

    private static final String CREDENTIAL_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String TOKEN = "fake-token";

    @TempDir Path root;
    private ToolCatalog catalog;
    private AgentStore agentStore;
    private MockMvc mvc;
    private HttpServer server;
    private String address;
    private String guardedAddress;
    private final ObjectMapper json = new ObjectMapper();
    private final AccountStore.Account account =
            new AccountStore.Account("owner", "tester", "user", 0);
    private final AtomicInteger heads = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();
    private DataSource source;
    private McpToolSource mcp;

    @BeforeEach
    void setup() throws Exception {
        source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:tool-api-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(source);
        catalog = new ToolCatalog(source, json);
        agentStore = new AgentStore(source, root);
        mcp =
                new McpToolSource() {
                    public List<ToolCatalog.DiscoveredTool> list(
                            McpEndpointParser.McpEndpoint endpoint) {
                        if ("fail".equals(endpoint.name())
                                || "http://127.0.0.1:1/failing".equals(endpoint.url())) {
                            throw new RuntimeException("Cannot connect to MCP");
                        }
                        return List.of(new ToolCatalog.DiscoveredTool("calc", "Calculator", "{}"));
                    }

                    public McpClientWrapper open(McpEndpointParser.McpEndpoint endpoint) {
                        throw new UnsupportedOperationException();
                    }
                };
        var controller =
                new AdvisorToolController(
                        catalog,
                        mcp,
                        agentStore,
                        null,
                        json,
                        new AccessGuard(new AccessCatalog(source)),
                        new ToolCredentialStore(json, CREDENTIAL_KEY, source));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/weather",
                exchange -> {
                    if ("HEAD".equals(exchange.getRequestMethod())) {
                        heads.incrementAndGet();
                        exchange.sendResponseHeaders(200, -1);
                    } else {
                        if (!"GET".equals(exchange.getRequestMethod())) writes.incrementAndGet();
                        byte[] body =
                                "{\"temperature\":20}"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                    exchange.close();
                });
        server.createContext(
                "/guarded",
                exchange -> {
                    String auth = exchange.getRequestHeaders().getFirst("Authorization");
                    exchange.sendResponseHeaders(("Bearer " + TOKEN).equals(auth) ? 200 : 401, -1);
                    exchange.close();
                });
        server.start();
        address = "http://127.0.0.1:" + server.getAddress().getPort() + "/weather";
        guardedAddress = "http://127.0.0.1:" + server.getAddress().getPort() + "/guarded";
    }

    @AfterEach
    void close() {
        if (server != null) server.stop(0);
    }

    private Map<String, Object> importBody() {
        return Map.of(
                "kind",
                "schema",
                "address",
                address,
                "content",
                "{\"name\":\"weather\",\"inputSchema\":{\"type\":\"object\"}}",
                "permission",
                "ask");
    }

    @Test
    void repeatedImportRequiresAnExplicitDuplicateDecision() throws Exception {
        mvc.perform(
                        post("/api/advisor/tools/import")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(importBody())))
                .andExpect(status().isCreated());
        mvc.perform(
                        post("/api/advisor/tools/import")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(importBody())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate_confirmation_required"));
        assertEquals(1, catalog.listTools("owner", false).size());
    }

    @Test
    void identicalIdempotencyKeyReplaysOriginalToolAndRejectsDifferentContent() throws Exception {
        String body = json.writeValueAsString(importBody());
        String first =
                mvc.perform(
                                post("/api/advisor/tools/import")
                                        .header("Idempotency-Key", "save-one")
                                        .requestAttr(AccountStore.Account.class.getName(), account)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String replay =
                mvc.perform(
                                post("/api/advisor/tools/import")
                                        .header("Idempotency-Key", "save-one")
                                        .requestAttr(AccountStore.Account.class.getName(), account)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertEquals(first, replay);
        var changed = new java.util.LinkedHashMap<>(importBody());
        changed.put("permission", "deny");
        mvc.perform(
                        post("/api/advisor/tools/import")
                                .header("Idempotency-Key", "save-one")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(changed)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_conflict"));
        assertEquals(1, heads.get());
    }

    @Test
    void invalidSelectionIsRejectedBeforeConnectionCheck() throws Exception {
        var body = new java.util.LinkedHashMap<>(importBody());
        body.put("chosenOperations", List.of("unknown-operation"));
        mvc.perform(
                        post("/api/advisor/tools/import")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_selection"));
        assertEquals(0, heads.get());
        assertTrue(catalog.listTools("owner", false).isEmpty());
    }

    @Test
    void previewShowsStableOperationAndExistingCandidate() throws Exception {
        mvc.perform(
                        post("/api/advisor/tools/import")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(importBody())))
                .andExpect(status().isCreated());
        String id = catalog.listTools("owner", false).get(0).toolId();
        mvc.perform(
                        post("/api/advisor/tools/import/preview")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(importBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operationId").isString())
                .andExpect(jsonPath("$[0].duplicates[0].toolId").value(id));
    }

    @Test
    void missingExecutionAddressDoesNotSaveAnUncallableTool() throws Exception {
        mvc.perform(
                        post("/api/advisor/tools/import")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "kind",
                                                        "schema",
                                                        "content",
                                                        "{\"name\":\"weather\",\"inputSchema\":{\"type\":\"object\"}}",
                                                        "permission",
                                                        "ask"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("address_required"));
        assertTrue(catalog.listTools("owner", false).isEmpty());
    }

    @Test
    void connectionFailureDoesNotSaveOrClaimTheAddressIsMissing() throws Exception {
        mvc.perform(
                        post("/api/advisor/tools/import")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "kind",
                                                        "schema",
                                                        "address",
                                                        "http://127.0.0.1:1/weather",
                                                        "content",
                                                        "{\"name\":\"weather\",\"inputSchema\":{\"type\":\"object\"}}",
                                                        "permission",
                                                        "ask"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("connection_failed"));
        assertTrue(catalog.listTools("owner", false).isEmpty());
    }

    @Test
    void saveChecksConnectionWithoutExecutingTheDeclaredPost() throws Exception {
        mvc.perform(
                        post("/api/advisor/tools/import")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "kind",
                                                        "schema",
                                                        "address",
                                                        address,
                                                        "content",
                                                        "{\"name\":\"weather\",\"inputSchema\":{\"type\":\"object\"}}",
                                                        "permission",
                                                        "ask"))))
                .andExpect(status().isCreated());
        assertEquals(1, heads.get());
        assertEquals(0, writes.get());
    }

    @Test
    void editChangesDefinitionWithoutReplacingIdentityOrAgentBindings() throws Exception {
        var tool =
                catalog.importTools(
                                "owner",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "weather",
                                                "old",
                                                "{\"type\":\"object\"}",
                                                "http",
                                                "GET",
                                                address)),
                                "ask")
                        .get(0);
        catalog.replaceSelection("agent-one", List.of(tool.toolId()), List.of());
        mvc.perform(
                        patch("/api/advisor/tools/" + tool.toolId())
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "name",
                                                        "weather_updated",
                                                        "description",
                                                        "new",
                                                        "httpUrl",
                                                        address,
                                                        "httpMethod",
                                                        "GET",
                                                        "inputSchema",
                                                        "{\"type\":\"object\"}",
                                                        "permission",
                                                        "ask"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("weather_updated"));
        assertEquals(List.of(tool.toolId()), catalog.selection("agent-one").toolIds());
        assertEquals("new", catalog.findTool(tool.toolId()).orElseThrow().description());
    }

    @Test
    void parseFailuresReachTheClientWithTheirCode() throws Exception {
        mvc.perform(
                        post("/api/advisor/tools/import")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "kind",
                                                        "openapi",
                                                        "content",
                                                        "{\"paths\":{}}",
                                                        "permission",
                                                        "ask"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("no_operations"));
        assertTrue(catalog.listTools("owner", false).isEmpty());
    }

    @Test
    void credentialsReachTheCheckButNeverTheToolView() throws Exception {
        var response =
                mvc.perform(
                                post("/api/advisor/tools/import")
                                        .requestAttr(AccountStore.Account.class.getName(), account)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                importBody(
                                                        guardedAddress,
                                                        Map.of("type", "bearer", "value", TOKEN))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$[0].hasCredential").value(true))
                        .andReturn();

        assertFalse(
                response.getResponse().getContentAsString().contains(TOKEN),
                "the response must not carry the secret back");
    }

    @Test
    void rejectedCredentialsKeepTheToolOutOfTheCatalog() throws Exception {
        mvc.perform(
                        post("/api/advisor/tools/import")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        importBody(
                                                guardedAddress,
                                                Map.of("type", "bearer", "value", "wrong"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("auth_failed"));
        assertTrue(catalog.listTools("owner", false).isEmpty());
    }

    @Test
    void anAnswerWithoutConfiguredCredentialsStillSaves() throws Exception {
        mvc.perform(
                        post("/api/advisor/tools/import")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(importBody(guardedAddress, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].hasCredential").value(false));
    }

    @Test
    void editingWithoutAnAuthBlockKeepsTheStoredCredential() throws Exception {
        var tool =
                mvc.perform(
                                post("/api/advisor/tools/import")
                                        .requestAttr(AccountStore.Account.class.getName(), account)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                importBody(
                                                        guardedAddress,
                                                        Map.of("type", "bearer", "value", TOKEN))))
                        .andReturn()
                        .getResponse();
        var imported = json.readTree(tool.getContentAsString()).get(0).path("toolId").asText();

        mvc.perform(
                        patch("/api/advisor/tools/" + imported)
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "name", "guarded_renamed",
                                                        "httpUrl", guardedAddress,
                                                        "httpMethod", "GET",
                                                        "inputSchema", "{\"type\":\"object\"}",
                                                        "permission", "ask"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCredential").value(true));
    }

    private String importBody(String endpoint, Map<String, String> auth) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("kind", "schema");
        body.put("address", endpoint);
        body.put("content", "{\"name\":\"guarded\",\"inputSchema\":{\"type\":\"object\"}}");
        body.put("permission", "ask");
        if (auth != null) body.put("auth", auth);
        return json.writeValueAsString(body);
    }

    @Test
    void editingOnlyTheNameKeepsAWorkingDefinitionWithoutProbingAgain() throws Exception {
        var tool =
                catalog.importTools(
                                "owner",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "weather",
                                                "Weather",
                                                "{}",
                                                "declared",
                                                null,
                                                null)),
                                "ask")
                        .get(0);
        mvc.perform(
                        patch("/api/advisor/tools/" + tool.toolId())
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "name",
                                                        "weather_named",
                                                        "permission",
                                                        "ask"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("weather_named"));
        assertEquals(0, heads.get());
    }

    @Test
    void previewDoesNotSaveOrProbe() throws Exception {
        mvc.perform(
                        post("/api/advisor/tools/preview")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "kind",
                                                        "schema",
                                                        "content",
                                                        "{\"name\":\"weather\",\"description\":\"Weather\",\"inputSchema\":{\"type\":\"object\"}}",
                                                        "address",
                                                        address))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("weather"));
        assertTrue(catalog.listTools("owner", false).isEmpty());
        assertEquals(0, heads.get());
    }

    @Test
    void previewWorksOnImportPreviewRouteAndDiagnosesErrors() throws Exception {
        mvc.perform(
                        post("/api/advisor/tools/import/preview")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "kind",
                                                        "openapi",
                                                        "content",
                                                        "{\"paths\":{}}"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("no_operations"));
    }

    @Test
    void staleEditCannotOverwrite() throws Exception {
        var tool =
                catalog.importTools(
                                "owner",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "weather", "Weather", "{}", "http", "GET",
                                                address)),
                                "ask")
                        .get(0);
        mvc.perform(
                        patch("/api/advisor/tools/" + tool.toolId())
                                .header("If-Match", "0")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"permission\":\"allow\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("revision_conflict"));
        assertEquals("ask", catalog.findTool(tool.toolId()).orElseThrow().permission());
    }

    @Test
    void deniedTestNeverExecutes() throws Exception {
        var tool =
                catalog.importTools(
                                "owner",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "weather", "Weather", "{}", "http", "POST",
                                                address)),
                                "deny")
                        .get(0);
        mvc.perform(
                        post("/api/advisor/tools/" + tool.toolId() + "/test")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"runId\":\"one\",\"confirmed\":true,\"input\":{}}"))
                .andExpect(status().isForbidden());
        assertEquals(0, writes.get());
    }

    @Test
    void testRunDoesNotRepeatWrites() throws Exception {
        var tool =
                catalog.importTools(
                                "owner",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "weather", "Weather", "{}", "http", "POST",
                                                address)),
                                "ask")
                        .get(0);
        for (int n = 0; n < 2; n++)
            mvc.perform(
                            post("/api/advisor/tools/" + tool.toolId() + "/test")
                                    .requestAttr(AccountStore.Account.class.getName(), account)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"runId\":\"same-run\",\"confirmed\":true,\"input\":{}}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.httpStatus").value(200));
        assertEquals(1, writes.get());
    }

    @Test
    void editMcpServerSucceedsAndProtectsConcurrency() throws Exception {
        var server =
                catalog.insertServer(
                        new McpEndpointParser.McpEndpoint(
                                "mcp-service",
                                "stdio",
                                "node",
                                List.of("server.js"),
                                Map.of(),
                                null,
                                Map.of()),
                        "owner",
                        "connected",
                        "");

        // 1. Stale revision fails with 409
        mvc.perform(
                        patch("/api/advisor/mcp-servers/" + server.serverId())
                                .header("If-Match", "999")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"new-name\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("revision_conflict"));

        // 2. Unreachable endpoint fails with 502 and preserves old config
        mvc.perform(
                        patch("/api/advisor/mcp-servers/" + server.serverId())
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"url\":\"http://127.0.0.1:1/failing\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("connection_failed"));
        var unchanged = catalog.findServer(server.serverId()).orElseThrow();
        assertEquals("mcp-service", unchanged.name());
        assertEquals(1, unchanged.revision());

        // 3. Successful update
        mvc.perform(
                        patch("/api/advisor/mcp-servers/" + server.serverId())
                                .header("If-Match", "1")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"text\":\"npx -y"
                                                + " @modelcontextprotocol/server-everything\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("npx"))
                .andExpect(jsonPath("$.revision").value(2));
        var updated = catalog.findServer(server.serverId()).orElseThrow();
        assertEquals("npx", updated.name());
        assertEquals(2, updated.revision());
    }

    @Test
    void importSchemaBoundToExistingMcpTool() throws Exception {
        var server =
                catalog.insertServer(
                        new McpEndpointParser.McpEndpoint(
                                "mcp-service",
                                "stdio",
                                "node",
                                List.of("server.js"),
                                Map.of(),
                                null,
                                Map.of()),
                        "owner",
                        "connected",
                        "");
        var mcpTool =
                catalog.approveMcpTool(
                        server, new ToolCatalog.DiscoveredTool("calc", "Calculator", "{}"), "ask");

        mvc.perform(
                        post("/api/advisor/tools/import")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "kind",
                                                        "schema",
                                                        "content",
                                                        "{\"name\":\"my_calc\",\"inputSchema\":{\"type\":\"object\"}}",
                                                        "boundToolId",
                                                        mcpTool.toolId(),
                                                        "permission",
                                                        "allow"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].name").value("my_calc"))
                .andExpect(jsonPath("$[0].origin").value("mcp"))
                .andExpect(jsonPath("$[0].mcpServerId").value(server.serverId()));

        assertEquals(0, heads.get());
        var saved = catalog.listTools("owner", false);
        assertEquals(2, saved.size());
        assertTrue(catalog.findTool(mcpTool.toolId()).isPresent());
        assertEquals("calc", catalog.findTool(mcpTool.toolId()).orElseThrow().name());
        assertTrue(
                saved.stream()
                        .anyMatch(
                                row ->
                                        row.name().equals("my_calc")
                                                && !row.toolId().equals(mcpTool.toolId())));
    }

    @Test
    void importSchemaWithInvalidBoundToolFails() throws Exception {
        mvc.perform(
                        post("/api/advisor/tools/import")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "kind",
                                                        "schema",
                                                        "content",
                                                        "{\"name\":\"bad\",\"inputSchema\":{\"type\":\"object\"}}",
                                                        "boundToolId",
                                                        "non-existent-tool-id",
                                                        "permission",
                                                        "allow"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("bound_tool_not_found"));
    }

    @Test
    void patchHttpToolPreservesSourceAndHandlesAuthActions() throws Exception {
        var importReq = new java.util.LinkedHashMap<>(importBody());
        importReq.put("auth", Map.of("type", "bearer", "value", TOKEN));
        String createdJson =
                mvc.perform(
                                post("/api/advisor/tools/import")
                                        .requestAttr(AccountStore.Account.class.getName(), account)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(importReq)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var toolList = json.readTree(createdJson);
        String toolId = toolList.get(0).get("toolId").asText();
        var toolRow = catalog.findTool(toolId).orElseThrow();
        String originalCredId = ToolAuth.credentialId(toolRow.configJson());
        assertNotNull(originalCredId);
        assertTrue(toolRow.configJson().contains("\"source\""));

        // 1. PATCH with invalid auth action -> 422 invalid_auth
        mvc.perform(
                        patch("/api/advisor/tools/" + toolId)
                                .header("If-Match", "1")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "auth",
                                                        Map.of("action", "unknown_action")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_auth"));

        // 2. PATCH with auth: keep -> retains credential and source metadata
        mvc.perform(
                        patch("/api/advisor/tools/" + toolId)
                                .header("If-Match", "1")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "description",
                                                        "new-description",
                                                        "auth",
                                                        Map.of("action", "keep")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
        var afterKeep = catalog.findTool(toolId).orElseThrow();
        assertEquals("new-description", afterKeep.description());
        assertEquals(originalCredId, ToolAuth.credentialId(afterKeep.configJson()));
        assertTrue(afterKeep.configJson().contains("\"source\""));

        // 3. PATCH with auth: replace -> generates new credential and retains source metadata
        mvc.perform(
                        patch("/api/advisor/tools/" + toolId)
                                .header("If-Match", "2")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "auth",
                                                        Map.of(
                                                                "action",
                                                                "replace",
                                                                "type",
                                                                "bearer",
                                                                "value",
                                                                "new-secret-val")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3));
        var afterReplace = catalog.findTool(toolId).orElseThrow();
        String newCredId = ToolAuth.credentialId(afterReplace.configJson());
        assertNotNull(newCredId);
        assertNotEquals(originalCredId, newCredId);
        assertTrue(afterReplace.configJson().contains("\"source\""));

        // 4. PATCH with auth: clear -> clears credential reference and retains source metadata
        mvc.perform(
                        patch("/api/advisor/tools/" + toolId)
                                .header("If-Match", "3")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("auth", Map.of("action", "clear")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(4));
        var afterClear = catalog.findTool(toolId).orElseThrow();
        assertNull(ToolAuth.credentialId(afterClear.configJson()));
        assertTrue(afterClear.configJson().contains("\"source\""));
    }

    @Test
    void refreshAndApproveMcpServerWithAcceptedChanges() throws Exception {
        var serverRow =
                catalog.insertServer(
                        new McpEndpointParser.McpEndpoint(
                                "math",
                                "http",
                                null,
                                List.of(),
                                Map.of(),
                                "http://localhost:8080/mcp",
                                Map.of()),
                        account.userId(),
                        "connected",
                        "");

        // 1. Refresh -> returns diff with added tool "calc"
        String refreshResponse =
                mvc.perform(
                                post("/api/advisor/mcp-servers/"
                                                + serverRow.serverId()
                                                + "/refresh")
                                        .requestAttr(AccountStore.Account.class.getName(), account))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.diff.added[0].name").value("calc"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        com.fasterxml.jackson.databind.JsonNode refreshJson = json.readTree(refreshResponse);
        String fingerprint = refreshJson.path("diff").path("fingerprint").asText();
        assertNotNull(fingerprint);

        // 2. Approve with mismatching fingerprint -> 409 mcp_changed
        mvc.perform(
                        post("/api/advisor/mcp-servers/" + serverRow.serverId() + "/approve")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "expectedFingerprint",
                                                        "wrong-fingerprint",
                                                        "acceptedChanges",
                                                        Map.of(
                                                                "added",
                                                                List.of(
                                                                        Map.of(
                                                                                "name",
                                                                                "calc",
                                                                                "permission",
                                                                                "allow")))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("mcp_changed"));

        // 3. Approve with correct fingerprint -> 200 and tool added
        mvc.perform(
                        post("/api/advisor/mcp-servers/" + serverRow.serverId() + "/approve")
                                .requestAttr(AccountStore.Account.class.getName(), account)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "expectedFingerprint",
                                                        fingerprint,
                                                        "acceptedChanges",
                                                        Map.of(
                                                                "added",
                                                                List.of(
                                                                        Map.of(
                                                                                "name",
                                                                                "calc",
                                                                                "permission",
                                                                                "allow")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools[0]").value("calc"));

        assertEquals(1, catalog.toolsOfServer(serverRow.serverId()).size());
        assertEquals("allow", catalog.toolsOfServer(serverRow.serverId()).get(0).permission());
    }

    @Test
    void testToolImpactAndRevisionGuardedDelete() throws Exception {
        var imported =
                catalog.importTools(
                        "owner",
                        List.of(
                                new ToolImport.ImportedTool(
                                        "search",
                                        "Search query",
                                        "{}",
                                        "http",
                                        "GET",
                                        "https://example.com/search")),
                        "allow");
        String toolId = imported.get(0).toolId();
        long rev = imported.get(0).revision();

        var agent = agentStore.create("Test Agent", "For testing impact", "owner");
        catalog.replaceSelection(agent.agentId(), List.of(toolId), List.of());

        // 1. GET impact
        mvc.perform(
                        get("/api/advisor/tools/" + toolId + "/impact")
                                .requestAttr(AccountStore.Account.class.getName(), account))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolId").value(toolId))
                .andExpect(jsonPath("$.name").value("search"))
                .andExpect(jsonPath("$.impactedCount").value(1))
                .andExpect(jsonPath("$.impactedAgents[0].name").value("Test Agent"));

        // 2. DELETE with mismatched If-Match -> 409
        mvc.perform(
                        delete("/api/advisor/tools/" + toolId)
                                .header("If-Match", "999")
                                .requestAttr(AccountStore.Account.class.getName(), account))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("revision_conflict"));
        assertTrue(catalog.findTool(toolId).isPresent());

        // 3. DELETE with matched If-Match -> 204
        mvc.perform(
                        delete("/api/advisor/tools/" + toolId)
                                .header("If-Match", String.valueOf(rev))
                                .requestAttr(AccountStore.Account.class.getName(), account))
                .andExpect(status().isNoContent());
        assertTrue(catalog.findTool(toolId).isEmpty());
        assertTrue(catalog.impactedAgentIdsForTool(toolId).isEmpty());
    }

    @Test
    void testMcpServerImpactAndRevisionGuardedDelete() throws Exception {
        var server =
                catalog.insertServer(
                        new McpEndpointParser.McpEndpoint(
                                "mcp-files",
                                "stdio",
                                "echo",
                                List.of(),
                                java.util.Map.of(),
                                null,
                                java.util.Map.of()),
                        "owner",
                        "connected",
                        "");
        var tool =
                catalog.approveMcpTool(
                        server, new ToolCatalog.DiscoveredTool("read_file", "Read", "{}"), "allow");

        var agent = agentStore.create("Server Agent", "For server impact", "owner");
        catalog.replaceSelection(agent.agentId(), List.of(), List.of(server.serverId()));

        // 1. GET impact
        mvc.perform(
                        get("/api/advisor/mcp-servers/" + server.serverId() + "/impact")
                                .requestAttr(AccountStore.Account.class.getName(), account))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverId").value(server.serverId()))
                .andExpect(jsonPath("$.name").value("mcp-files"))
                .andExpect(jsonPath("$.impactedCount").value(1))
                .andExpect(jsonPath("$.impactedAgents[0].name").value("Server Agent"))
                .andExpect(jsonPath("$.tools[0].name").value("read_file"));

        // 2. DELETE with mismatched If-Match -> 409
        mvc.perform(
                        delete("/api/advisor/mcp-servers/" + server.serverId())
                                .header("If-Match", "999")
                                .requestAttr(AccountStore.Account.class.getName(), account))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("revision_conflict"));
        assertTrue(catalog.findServer(server.serverId()).isPresent());

        // 3. DELETE with matched If-Match -> 204
        mvc.perform(
                        delete("/api/advisor/mcp-servers/" + server.serverId())
                                .header("If-Match", String.valueOf(server.revision()))
                                .requestAttr(AccountStore.Account.class.getName(), account))
                .andExpect(status().isNoContent());
        assertTrue(catalog.findServer(server.serverId()).isEmpty());
        assertTrue(catalog.toolsOfServer(server.serverId()).isEmpty());
        assertTrue(catalog.impactedAgentIdsForServer(server.serverId()).isEmpty());
    }

    @Test
    void testRetryApplyEndpoint() throws Exception {
        var provisioner =
                org.mockito.Mockito.mock(io.deepscope.advisor.agent.AgentProvisioner.class);
        var coordinator =
                new ToolRuntimeCoordinator(catalog, agentStore, provisioner, source, json);
        var controllerWithCoord =
                new AdvisorToolController(
                        catalog,
                        mcp,
                        agentStore,
                        provisioner,
                        json,
                        new AccessGuard(new AccessCatalog(source)),
                        new ToolCredentialStore(json, CREDENTIAL_KEY, source),
                        new McpUpdateService(catalog, mcp, json),
                        coordinator);
        var mvcCoord = MockMvcBuilders.standaloneSetup(controllerWithCoord).build();

        // 1. Non-existent requestId -> 404
        mvcCoord.perform(
                        post("/api/advisor/tool-changes/non-existent/retry-apply")
                                .requestAttr(AccountStore.Account.class.getName(), account))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("request_not_found"));

        // 2. Insert change request into DB
        agentStore.create("Test Agent", "desc", "owner");
        coordinator.applyChanges("owner", "req-123", List.of(), List.of());

        // 3. retryApply succeeds
        mvcCoord.perform(
                        post("/api/advisor/tool-changes/req-123/retry-apply")
                                .requestAttr(AccountStore.Account.class.getName(), account))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-123"))
                .andExpect(jsonPath("$.status").value("applied"));
    }
}
