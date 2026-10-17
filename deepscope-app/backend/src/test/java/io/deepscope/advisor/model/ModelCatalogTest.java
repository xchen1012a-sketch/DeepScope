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
package io.deepscope.advisor.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.advisor.knowledge.VectorModelSource;
import io.deepscope.core.embedding.EmbeddingModel;
import io.deepscope.core.message.ContentBlock;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import reactor.core.publisher.Mono;

/** A saved vector endpoint fills the existing vector port. The secret is not the page mask. */
class ModelCatalogTest {

    private ModelCatalog catalog;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:models-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        catalog = new ModelCatalog(dataSource);
    }

    @Test
    void currentEndpointMetadataRefreshUpdatesThePersistedWindow() throws Exception {
        var endpoint =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "chat",
                                "custom",
                                "selected",
                                "https://example.invalid/v1",
                                "fake-key"),
                        null);
        ModelProbe discovery =
                new ModelProbe() {
                    @Override
                    public Result check(ModelCatalog.EndpointDraft draft) {
                        return Result.success(null);
                    }

                    @Override
                    public Optional<Integer> contextWindow(ModelCatalog.EndpointDraft draft) {
                        return "selected".equals(draft.modelName())
                                ? Optional.of(1_048_576)
                                : Optional.empty();
                    }
                };
        new AdvisorModelController(catalog, discovery, null).refreshCurrentContextWindow();
        assertEquals(Optional.of(1_048_576), catalog.contextWindow(endpoint.endpointId()));
    }

    @Test
    void storedCapabilitiesFollowTheCurrentEndpointAndSurviveCatalogRecreation() throws Exception {
        var large =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "chat",
                                "custom",
                                "large-model",
                                "https://example.invalid/v1",
                                "fake-key"),
                        null);
        catalog.saveContextWindow(large.endpointId(), 1_048_576);
        var small =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "chat",
                                "custom",
                                "small-model",
                                "https://example.invalid/v1",
                                "fake-key"),
                        null);
        catalog.saveContextWindow(small.endpointId(), 128_000);
        assertEquals(
                Optional.of(1_048_576),
                new ModelCatalog(dataSource).contextWindow(large.endpointId()));
        var source = new SavedChatModelSource(catalog, new OpenAiEndpointClient());
        assertEquals(128_000, source.current().orElseThrow().getContextWindowSize());
        catalog.makeCurrent(large.endpointId());
        assertEquals(1_048_576, source.current().orElseThrow().getContextWindowSize());
    }

    @Test
    void theNewestSuccessfulEndpointBecomesCurrentAndTheSecretStaysHidden() throws Exception {
        var first =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "chat", "openai", "old", "https://old", "sk-secret-1234"),
                        null);
        var second =
                catalog.insertCurrent(
                        new ModelCatalog.EndpointDraft(
                                "chat", "custom", "new", "https://new", "sk-other-9876"),
                        null);
        assertFalse(catalog.find(first.endpointId()).orElseThrow().current());
        assertTrue(catalog.find(second.endpointId()).orElseThrow().current());
        assertEquals("sk-••••9876", ModelCatalog.mask(second.secret()));
        assertThrows(IllegalArgumentException.class, () -> catalog.delete(second.endpointId()));
    }

    @Test
    void aSavedVectorEndpointWinsOverTheLocalConfiguration() throws Exception {
        EmbeddingModel local =
                new EmbeddingModel() {
                    @Override
                    public Mono<double[]> embed(ContentBlock block) {
                        return Mono.just(new double[] {1});
                    }

                    @Override
                    public String getModelName() {
                        return "local";
                    }

                    @Override
                    public int getDimensions() {
                        return 1;
                    }
                };
        VectorModelSource source =
                new SavedVectorModelSource(
                        catalog, new OpenAiEndpointClient(), () -> Optional.of(local));
        assertEquals("local", source.current().orElseThrow().getModelName());
        catalog.insertCurrent(
                new ModelCatalog.EndpointDraft(
                        "embedding",
                        "openai",
                        "text-embed",
                        "https://embed.example/v1",
                        "sk-vector-1234"),
                8);
        assertEquals("text-embed", source.current().orElseThrow().getModelName());
    }
}
