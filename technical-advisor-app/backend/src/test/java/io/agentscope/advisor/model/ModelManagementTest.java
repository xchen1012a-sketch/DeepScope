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
package io.agentscope.advisor.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentscope.advisor.access.AccessCatalog;
import io.agentscope.advisor.access.AccessGuard;
import io.agentscope.advisor.auth.AccountStore;
import io.agentscope.advisor.auth.ApiAuthenticationFilter;
import io.agentscope.advisor.db.AdvisorSchema;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ModelManagementTest {

    @Test
    void knowledgePdfRoleIsExclusiveAndNeverSelectsEmbedding() throws Exception {
        var first =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "chat",
                                "openai",
                                "vision-one",
                                "https://example.invalid/v1",
                                "fake"),
                        null);
        var second =
                catalog.insert(
                        new ModelCatalog.EndpointDraft(
                                "chat",
                                "openai",
                                "vision-two",
                                "https://example.invalid/v1",
                                "fake"),
                        null,
                        "Vision 2",
                        false);
        catalog.setKnowledgePdf(first.endpointId(), true);
        assertEquals(first.endpointId(), catalog.knowledgePdf().orElseThrow().endpointId());
        catalog.setKnowledgePdf(second.endpointId(), true);
        assertEquals(second.endpointId(), catalog.knowledgePdf().orElseThrow().endpointId());
        catalog.setKnowledgePdf(first.endpointId(), false);
        assertEquals(second.endpointId(), catalog.knowledgePdf().orElseThrow().endpointId());
        var embedding =
                catalog.insert(
                        new ModelCatalog.EndpointDraft(
                                "embedding",
                                "openai",
                                "embed",
                                "https://example.invalid/v1",
                                "fake"),
                        3,
                        "E",
                        false);
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.setKnowledgePdf(embedding.endpointId(), true));
        catalog.delete(second.endpointId());
        assertTrue(catalog.knowledgePdf().isEmpty());
    }

    private javax.sql.DataSource source;
    private ModelCatalog catalog;
    private MockMvc mvc;
    private String token;
    private boolean fail;
    private boolean imageSupport;

    @Test
    void savingPdfRoleRequiresImageProbeAndDoesNotChangeDefaultChat() throws Exception {
        String request =
                "{\"kind\":\"chat\",\"provider\":\"openai\",\"modelName\":\"vision-fixture\",\"baseUrl\":\"https://example.invalid/v1\",\"secret\":\"fake\",\"knowledgePdf\":true}";
        mvc.perform(
                        post("/api/advisor/models")
                                .header("Authorization", token)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isBadRequest());
        assertTrue(catalog.knowledgePdf().isEmpty());
        imageSupport = true;
        mvc.perform(
                        post("/api/advisor/models")
                                .header("Authorization", token)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.knowledgePdf").value(true))
                .andExpect(jsonPath("$.current").value(false));
        assertEquals("vision-fixture", catalog.knowledgePdf().orElseThrow().modelName());
        assertTrue(catalog.current("chat").isEmpty());
    }

    private int discoveries;
    private String userToken;
    private AccessCatalog accessCatalog;
    private AccountStore accounts;
    private String discoveryFailure;
    private ModelProbe.DiscoveryResult discoveryResult;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:manage-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(ds);
        source = ds;
        catalog = new ModelCatalog(ds);
        accounts = new AccountStore(ds, Clock.systemUTC(), Duration.ofHours(12));
        accounts.bootstrap("root", "root-password");
        accounts.createUser("reader", "reader-password");
        accessCatalog = new AccessCatalog(ds);
        accessCatalog.ensureBuiltin();
        ModelProbe probe =
                new ModelProbe() {
                    @Override
                    public boolean supportsImages(ModelCatalog.EndpointDraft draft) {
                        return imageSupport;
                    }

                    @Override
                    public Result check(ModelCatalog.EndpointDraft draft) {
                        return fail
                                ? Result.failure("密钥无效")
                                : Result.success(
                                        ModelCatalog.EMBEDDING.equals(draft.kind()) ? 3 : null);
                    }

                    @Override
                    public java.util.List<String> discover(ModelCatalog.EndpointDraft draft) {
                        discoveries++;
                        return java.util.List.of("remote-first", "remote-second");
                    }

                    @Override
                    public DiscoveryResult discoverResult(ModelCatalog.EndpointDraft draft) {
                        if (discoveryFailure != null)
                            throw new DiscoveryException(discoveryFailure);
                        if (discoveryResult != null) {
                            discoveries++;
                            return discoveryResult;
                        }
                        return ModelProbe.super.discoverResult(draft);
                    }
                };
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AdvisorModelController(
                                        catalog, probe, new AccessGuard(new AccessCatalog(ds))))
                        .addFilters(new ApiAuthenticationFilter(accounts, "/v1/admin"))
                        .build();
        token = "Bearer " + accounts.login("root", "root-password").orElseThrow().token();
        userToken = "Bearer " + accounts.login("reader", "reader-password").orElseThrow().token();
    }

    @Test
    void providerSearchUsesNamesAliasesKindAndLimits() throws Exception {
        mvc.perform(
                        get("/api/advisor/models/providers")
                                .header("Authorization", token)
                                .param("kind", "chat")
                                .param("q", "深度")
                                .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("deepseek"))
                .andExpect(jsonPath("$.items[0].provider").value("openai"))
                .andExpect(jsonPath("$.items[0].baseUrl").value("https://api.deepseek.com/v1"));
        mvc.perform(
                        get("/api/advisor/models/providers")
                                .header("Authorization", token)
                                .param("kind", "embedding")
                                .param("q", "anthropic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(
                        get("/api/advisor/models/providers")
                                .header("Authorization", token)
                                .param("limit", "51"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void copyConfigReturnsSecretOnlyThroughTheProtectedCopyEndpoint() throws Exception {
        var endpoint =
                catalog.insert(
                        new ModelCatalog.EndpointDraft(
                                ModelCatalog.CHAT,
                                "openai",
                                "gpt-test",
                                "https://example.invalid/v1",
                                "fake-copy-secret"),
                        null,
                        "测试模型",
                        false);

        mvc.perform(
                        post("/api/advisor/models/" + endpoint.endpointId() + "/copy-config")
                                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("openai"))
                .andExpect(jsonPath("$.modelName").value("gpt-test"))
                .andExpect(jsonPath("$.secret").value("fake-copy-secret"))
                .andExpect(jsonPath("$.secretMask").doesNotExist());

        mvc.perform(get("/api/advisor/models").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chat[0].secret").doesNotExist());
        mvc.perform(
                        post("/api/advisor/models/" + endpoint.endpointId() + "/copy-config")
                                .header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void copyingAModelCreatesAPersistedMaskedDuplicateWithoutChangingTheDefault() throws Exception {
        var endpoint =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "embedding",
                                "openai",
                                "text-embedding-3-small",
                                "https://api.openai.com/v1",
                                "fake-secret"),
                        1536);
        fail = true;
        var response =
                mvc.perform(
                                post("/api/advisor/models/" + endpoint.endpointId() + "/copy")
                                        .header("Authorization", token)
                                        .contentType("application/json")
                                        .content("{\"displayName\":\"Vector copy\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.displayName").value("Vector copy"))
                        .andExpect(jsonPath("$.dimensions").value(1536))
                        .andExpect(jsonPath("$.current").value(false))
                        .andExpect(jsonPath("$.secret").doesNotExist())
                        .andReturn();
        String id =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(response.getResponse().getContentAsString())
                        .path("endpointId")
                        .asText();
        org.junit.jupiter.api.Assertions.assertNotEquals(endpoint.endpointId(), id);
        var copy = catalog.find(id).orElseThrow();
        assertEquals(endpoint.secret(), copy.secret());
        assertEquals(endpoint.baseUrl(), copy.baseUrl());
        assertEquals(endpoint.modelName(), copy.modelName());
        assertEquals(
                endpoint.endpointId(), catalog.current("embedding").orElseThrow().endpointId());
        assertEquals(2, catalog.list("embedding").size());
        mvc.perform(
                        post("/api/advisor/models/" + endpoint.endpointId() + "/copy")
                                .header("Authorization", userToken)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/advisor/models/missing/copy")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testOnlyPermissionCannotExportSavedSecretsToAChangedConnection() throws Exception {
        var role = accessCatalog.createRole("Tester", "tester", 1, "");
        accessCatalog.replacePermissions(role.roleId(), java.util.List.of("model.test"));
        accessCatalog.replaceGrants(
                accounts.login("reader", "reader-password").orElseThrow().account().userId(),
                java.util.List.of(role.roleId()));
        var endpoint =
                catalog.insert(
                        new ModelCatalog.EndpointDraft(
                                "chat",
                                "openai",
                                "test",
                                "https://original.invalid/v1",
                                "fake-stored-secret"),
                        null,
                        "Test",
                        false);
        mvc.perform(
                        post("/api/advisor/models/test")
                                .header("Authorization", userToken)
                                .contentType("application/json")
                                .content(
                                        "{\"endpointId\":\""
                                                + endpoint.endpointId()
                                                + "\",\"baseUrl\":\"https://attacker.invalid/v1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/advisor/models/" + endpoint.endpointId() + "/copy-config")
                                .header("Authorization", userToken))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/advisor/models/" + endpoint.endpointId() + "/test")
                                .header("Authorization", userToken))
                .andExpect(status().isOk());
    }

    @Test
    void matrixParametersCannotTurnConfigurationCopyIntoAnEditAction() throws Exception {
        var role = accessCatalog.createRole("Editor", "model-editor", 1, "");
        accessCatalog.replacePermissions(role.roleId(), java.util.List.of("model.edit"));
        accessCatalog.replaceGrants(
                accounts.login("reader", "reader-password").orElseThrow().account().userId(),
                java.util.List.of(role.roleId()));
        var endpoint =
                catalog.insert(
                        new ModelCatalog.EndpointDraft(
                                "chat",
                                "openai",
                                "test",
                                "https://original.invalid/v1",
                                "fake-stored-secret"),
                        null,
                        "Test",
                        false);
        mvc.perform(
                        post("/api/advisor/models/"
                                        + endpoint.endpointId()
                                        + "/copy-config;view=edit")
                                .header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void embeddingDiscoveryAndCachedSearchKeepAllProviderModelsIncludingOpaqueDeploymentIds()
            throws Exception {
        discoveryResult =
                new ModelProbe.DiscoveryResult(
                        java.util.List.of("gpt-chat", "embedding-3", "unclassified-id"), true);
        String body =
                "{\"kind\":\"embedding\",\"provider\":\"openai\",\"baseUrl\":\"https://open.bigmodel.cn/api/paas/v4\",\"secret\":\"fake-key\"}";
        mvc.perform(
                        post("/api/advisor/models/discover")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2]").value("unclassified-id"));
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[2]").value("unclassified-id"));
    }

    @Test
    void supplierFilterDistinguishesServicesUsingTheSameOpenAiProtocol() throws Exception {
        catalog.insert(
                new ModelCatalog.EndpointDraft(
                        "chat",
                        "openai",
                        "deepseek-test",
                        "https://api.deepseek.com/v1",
                        "fake-key"),
                null,
                "DeepSeek",
                false);
        catalog.insert(
                new ModelCatalog.EndpointDraft(
                        "chat", "openai", "openai-test", "https://api.openai.com/v1", "fake-key"),
                null,
                "OpenAI",
                false);
        mvc.perform(
                        get("/api/advisor/models")
                                .header("Authorization", token)
                                .param("page", "1")
                                .param("kind", "chat")
                                .param("presetId", "deepseek"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].modelName").value("deepseek-test"));
    }

    @Test
    void detailedDiscoveryAndSearchExposeSourceAndPartialLimits() throws Exception {
        discoveryResult =
                new ModelProbe.DiscoveryResult(
                        java.util.stream.IntStream.range(0, 2101)
                                .mapToObj(i -> "remote-" + i)
                                .toList(),
                        true);
        String body =
                "{\"kind\":\"chat\",\"provider\":\"openai\",\"secret\":\"fake\",\"detailed\":true}";
        mvc.perform(
                        post("/api/advisor/models/discover")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2000))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.status").value("discovered"));
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("discovered"))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.total").value(2000));
        assertEquals(1, discoveries);
    }

    @Test
    void failedRefreshCannotLeaveOldDiscoveryMasqueradingAsFreshResults() throws Exception {
        String body =
                "{\"kind\":\"chat\",\"provider\":\"openai\",\"secret\":\"fake\",\"detailed\":true}";
        mvc.perform(
                        post("/api/advisor/models/discover")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk());
        discoveryFailure = "auth";
        mvc.perform(
                        post("/api/advisor/models/discover")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("model_discovery_auth"));
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("preset"))
                .andExpect(jsonPath("$.complete").value(false));
    }

    @Test
    void emptyAndUnsupportedDiscoveryHaveDifferentOutcomes() throws Exception {
        String body =
                "{\"kind\":\"chat\",\"provider\":\"openai\",\"secret\":\"fake\",\"detailed\":true}";
        discoveryResult = new ModelProbe.DiscoveryResult(java.util.List.of(), true);
        mvc.perform(
                        post("/api/advisor/models/discover")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("empty"))
                .andExpect(jsonPath("$.complete").value(true));
        discoveryFailure = "unsupported";
        mvc.perform(
                        post("/api/advisor/models/discover")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("model_discovery_unsupported"));
    }

    @Test
    void modelOptionsPageThroughDiscoveredResultsWithoutRepeatingUpstreamCalls() throws Exception {
        discoveryResult =
                new ModelProbe.DiscoveryResult(
                        java.util.stream.IntStream.range(0, 65)
                                .mapToObj(i -> "remote-" + i)
                                .toList(),
                        true);
        String body = "{\"kind\":\"chat\",\"provider\":\"openai\",\"secret\":\"fake\"}";
        mvc.perform(
                        post("/api/advisor/models/discover")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body.substring(0, body.length() - 1) + ",\"page\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(30))
                .andExpect(jsonPath("$.items[0]").value("remote-30"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body.substring(0, body.length() - 1) + ",\"page\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0]").value("remote-60"))
                .andExpect(jsonPath("$.hasMore").value(false));
        assertEquals(1, discoveries);
    }

    @Test
    void nextModelPageRejectsAReplacedDiscoverySnapshot() throws Exception {
        discoveryResult =
                new ModelProbe.DiscoveryResult(
                        java.util.stream.IntStream.range(0, 65)
                                .mapToObj(i -> "remote-" + i)
                                .toList(),
                        true);
        String body = "{\"kind\":\"chat\",\"provider\":\"openai\",\"secret\":\"fake\"}";
        mvc.perform(
                        post("/api/advisor/models/discover")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk());
        var response =
                mvc.perform(
                                post("/api/advisor/models/options")
                                        .header("Authorization", token)
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.snapshot").isNotEmpty())
                        .andReturn();
        String snapshot =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(response.getResponse().getContentAsString())
                        .path("snapshot")
                        .asText();
        mvc.perform(
                        post("/api/advisor/models/discover")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        body.substring(0, body.length() - 1)
                                                + ",\"page\":2,\"snapshot\":\""
                                                + snapshot
                                                + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("model_options_changed"));
    }

    @Test
    void glmSuppliersSeparateStandardAndCodingEndpointsAndOfferVerifiedModelIds() throws Exception {
        mvc.perform(
                        get("/api/advisor/models/providers")
                                .header("Authorization", token)
                                .param("kind", "chat")
                                .param("q", "智谱"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[0].id").value("zhipu"))
                .andExpect(
                        jsonPath("$.items[0].baseUrl")
                                .value("https://open.bigmodel.cn/api/paas/v4"))
                .andExpect(
                        jsonPath("$.items[1].baseUrl")
                                .value("https://open.bigmodel.cn/api/coding/paas/v4"));
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{\"kind\":\"chat\",\"provider\":\"openai\",\"presetId\":\"zhipu\",\"baseUrl\":\"https://open.bigmodel.cn/api/paas/v4\",\"q\":\"glm-5.3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]").value("glm-5.3"));
        assertEquals(0, discoveries);
    }

    @Test
    void optionsUseDiscoveryCacheWithoutUpstreamOnSearchAndIsolateSecrets() throws Exception {
        String connection =
                "\"kind\":\"chat\",\"provider\":\"openai\",\"baseUrl\":\"https://example.invalid/v1\",\"secret\":\"fake-key-one\"";
        mvc.perform(
                        post("/api/advisor/models/discover")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{" + connection + "}"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{" + connection + ",\"q\":\"SECOND\",\"limit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0]").value("remote-second"))
                .andExpect(jsonPath("$.secret").doesNotExist());
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{"
                                                + connection.replace("fake-key-one", "fake-key-two")
                                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        assertEquals(1, discoveries);
    }

    @Test
    void modelSearchRequiresSettingsPermissionEvenWhenEndpointIsSaved() throws Exception {
        mvc.perform(get("/api/advisor/models/providers").header("Authorization", userToken))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", userToken)
                                .contentType("application/json")
                                .content("{\"endpointId\":\"saved\"}"))
                .andExpect(status().isForbidden());
        assertEquals(0, discoveries);
    }

    @Test
    void savedOptionsRetainSecretUntilConnectionChangesAndViewsIdentifyActualSupplier()
            throws Exception {
        var saved =
                catalog.insert(
                        new ModelCatalog.EndpointDraft(
                                "chat",
                                "openai",
                                "chosen",
                                "https://api.deepseek.com/v1/",
                                "fake-saved-key"),
                        null,
                        "Saved",
                        false);
        String endpointBody = "{\"endpointId\":\"" + saved.endpointId() + "\"}";
        mvc.perform(
                        post("/api/advisor/models/discover")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(endpointBody))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(endpointBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0]").value("remote-first"));
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        endpointBody.replace(
                                                "}",
                                                ",\"secret\":\"changed-key\",\"q\":\"remote\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(
                        get("/api/advisor/models")
                                .header("Authorization", token)
                                .param("page", "1")
                                .param("kind", "chat"))
                .andExpect(jsonPath("$.items[0].providerName").value("DeepSeek"))
                .andExpect(jsonPath("$.items[0].presetId").value("deepseek"))
                .andExpect(
                        jsonPath("$.items[0].providerKeysUrl")
                                .value("https://platform.deepseek.com/api_keys"))
                .andExpect(jsonPath("$.items[0].secret").doesNotExist());
        assertEquals(1, discoveries);
    }

    @Test
    void staticOptionsDoNotClaimModelsForAnUnmatchedConnection() throws Exception {
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{\"kind\":\"chat\",\"provider\":\"openai\",\"q\":\"MINI\",\"limit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0]").value("gpt-4.1-mini"));
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{\"kind\":\"chat\",\"provider\":\"openai\",\"presetId\":\"deepseek\",\"baseUrl\":\"https://unmatched.invalid/v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"kind\":\"unknown\",\"provider\":\"openai\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/advisor/models/options")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"kind\":\"chat\",\"provider\":\"openai\",\"limit\":0}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, discoveries);
    }

    @Test
    void unknownCustomConnectionHasNoMisleadingProviderKeysLink() throws Exception {
        catalog.insert(
                new ModelCatalog.EndpointDraft(
                        "chat", "openai", "chosen", "https://unmatched.invalid/v1", "fake-key"),
                null,
                "Custom",
                false);
        mvc.perform(
                        get("/api/advisor/models")
                                .header("Authorization", token)
                                .param("page", "1")
                                .param("kind", "chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].presetId").value("custom"))
                .andExpect(jsonPath("$.items[0].providerKeysUrl").doesNotExist());
    }

    @Test
    void pagedModelsKeepDefaultOutsideFilterAndNeverExposeSecret() throws Exception {
        catalog.insertCurrent(
                new ModelCatalog.EndpointDraft("chat", "openai", "alpha", null, "private-alpha"),
                null);
        var current =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "chat", "custom", "beta", null, "private-beta"),
                        null);
        mvc.perform(
                        get("/api/advisor/models")
                                .header("Authorization", token)
                                .param("page", "1")
                                .param("size", "1")
                                .param("kind", "chat")
                                .param("provider", "openai")
                                .param("q", "alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].modelName").value("alpha"))
                .andExpect(jsonPath("$.defaultModel.endpointId").value(current.endpointId()))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString("private-"))));
        mvc.perform(get("/api/advisor/models").header("Authorization", token).param("page", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/advisor/models")
                                .header("Authorization", token)
                                .param("page", "1")
                                .param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addingModelDoesNotReplaceDefaultAndReturnsDisplayName() throws Exception {
        var original =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft("chat", "openai", "old", null, "fake-key"),
                        null);
        mvc.perform(
                        post("/api/advisor/models")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{\"kind\":\"chat\",\"provider\":\"openai\",\"modelName\":\"new\",\"secret\":\"new-key\",\"displayName\":\"My"
                                            + " model\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current").value(false))
                .andExpect(jsonPath("$.displayName").value("My model"))
                .andExpect(jsonPath("$.lastTestStatus").value("passed"));
        assertEquals(original.endpointId(), catalog.current("chat").orElseThrow().endpointId());
    }

    @Test
    void draftTestDoesNotPersist() throws Exception {
        mvc.perform(
                        post("/api/advisor/models/test")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{\"kind\":\"chat\",\"provider\":\"openai\",\"modelName\":\"new\",\"secret\":\"new-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        assertTrue(catalog.list("chat").isEmpty());
    }

    @Test
    void failedEditKeepsOriginalConfiguration() throws Exception {
        var original =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft("chat", "openai", "old", null, "fake-key"),
                        null);
        fail = true;
        mvc.perform(
                        put("/api/advisor/models/" + original.endpointId())
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"modelName\":\"new\"}"))
                .andExpect(status().isBadGateway());
        assertEquals("old", catalog.find(original.endpointId()).orElseThrow().modelName());
    }

    @Test
    void successfulEditRetainsSavedSecretAndId() throws Exception {
        var original =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft("chat", "openai", "old", null, "fake-key"),
                        null);
        mvc.perform(
                        put("/api/advisor/models/" + original.endpointId())
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"modelName\":\"new\",\"displayName\":\"Friendly\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpointId").value(original.endpointId()))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.displayName").value("Friendly"));
        assertEquals("fake-key", catalog.find(original.endpointId()).orElseThrow().secret());
    }

    @Test
    void savedFailureIsRecordedWithoutChangingDefault() throws Exception {
        var original =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft("chat", "openai", "old", null, "fake-key"),
                        null);
        fail = true;
        mvc.perform(
                        post("/api/advisor/models/" + original.endpointId() + "/test")
                                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false));
        mvc.perform(get("/api/advisor/models").header("Authorization", token))
                .andExpect(jsonPath("$.chat[0].lastTestStatus").value("failed"));
        assertTrue(catalog.find(original.endpointId()).orElseThrow().current());
    }

    @Test
    void cannotDeleteModelReferencedBySession() throws Exception {
        var original =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft("chat", "openai", "old", null, "fake-key"),
                        null);
        catalog.insertCurrent(
                new ModelCatalog.EndpointDraft("chat", "openai", "new", null, "fake-key"), null);
        try (var connection = source.getConnection();
                var insert =
                        connection.prepareStatement(
                                "INSERT INTO"
                                    + " advisor_sessions(session_id,owner_user_id,title,status,created_at,updated_at,model_endpoint_id)"
                                    + " VALUES('session','owner','title','active',1,1,?)")) {
            insert.setString(1, original.endpointId());
            insert.executeUpdate();
        }
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                        "/api/advisor/models/" + original.endpointId())
                                .header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertTrue(catalog.find(original.endpointId()).isPresent());
        try (var connection = source.getConnection();
                var delete =
                        connection.prepareStatement(
                                "DELETE FROM advisor_model_endpoints WHERE endpoint_id=?")) {
            delete.setString(1, original.endpointId());
            assertEquals(
                    "23503",
                    assertThrows(java.sql.SQLException.class, delete::executeUpdate).getSQLState());
        }
    }

    @Test
    void indexedKnowledgeBlocksVectorReplacementEvenWithSameDimensions() throws Exception {
        var original =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "embedding", "openai", "old", null, "fake-key"),
                        3);
        try (var connection = source.getConnection();
                var insert = connection.createStatement()) {
            insert.executeUpdate(
                    "INSERT INTO"
                        + " advisor_knowledge_bases(library_id,name,owner_user_id,chunk_size,overlap,recall_limit,score_threshold,embedding_dimensions,created_at,updated_at)"
                        + " VALUES('lib','name','owner',100,10,5,0.5,3,1,1)");
        }
        mvc.perform(
                        post("/api/advisor/models")
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{\"kind\":\"embedding\",\"provider\":\"openai\",\"modelName\":\"new\",\"secret\":\"key\",\"makeCurrent\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertEquals(
                original.endpointId(), catalog.current("embedding").orElseThrow().endpointId());
        assertEquals(1, catalog.list("embedding").size());
        mvc.perform(
                        put("/api/advisor/models/" + original.endpointId())
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"baseUrl\":\"\",\"displayName\":\"Renamed vector\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed vector"));
    }

    @Test
    void discoveryRequiresSettingsPermission() throws Exception {
        mvc.perform(
                        post("/api/advisor/models/discover")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void editCanResetCustomAddressAndCannotChangeProvider() throws Exception {
        var endpoint =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "chat", "openai", "old", "https://example.invalid/v1", "fake-key"),
                        null);
        mvc.perform(
                        put("/api/advisor/models/" + endpoint.endpointId())
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"baseUrl\":\"\",\"secret\":\"fake-reentered-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value(""));
        mvc.perform(
                        put("/api/advisor/models/" + endpoint.endpointId())
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"provider\":\"anthropic\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void editedAddressPersistsAsCustomWithAReenteredKey() throws Exception {
        var endpoint =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "chat",
                                "openai",
                                "test-model",
                                "https://api.openai.com/v1",
                                "fake-original"),
                        null);
        mvc.perform(
                        put("/api/advisor/models/" + endpoint.endpointId())
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{\"provider\":\"custom\",\"baseUrl\":\"https://gateway.invalid/v1\",\"secret\":\"fake-reentered\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("custom"))
                .andExpect(jsonPath("$.presetId").value("custom"));
        assertEquals("custom", catalog.find(endpoint.endpointId()).orElseThrow().provider());
        assertEquals("fake-reentered", catalog.find(endpoint.endpointId()).orElseThrow().secret());
    }
}
