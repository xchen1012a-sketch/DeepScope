/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.deepscope.advisor.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.advisor.model.ModelCatalog;
import io.deepscope.core.embedding.EmbeddingModel;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.TextBlock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import reactor.core.publisher.Mono;

class KnowledgeReindexTest {
    @Test
    void emptyReboundLibraryReturnsNoResultsWithoutAnEmbeddingRequest() throws Exception {
        String target = endpoint("new", 3);
        var source =
                source(
                        target,
                        model(
                                3,
                                text -> {
                                    throw new AssertionError(
                                            "An empty library must not invoke embeddings");
                                }));
        try (var ingest = new DocumentIngest(catalog, source, index)) {
            assertEquals(
                    0,
                    new KnowledgeReindex(catalog, models, index, ingest)
                            .change(libraryId, target)
                            .affectedDocuments());
            assertTrue(
                    new KnowledgeSearch(catalog, source, index)
                            .search(List.of(libraryId), "query")
                            .isEmpty());
        }
    }

    @TempDir Path root;
    KnowledgeCatalog catalog;
    ModelCatalog models;
    MemoryChunkIndex index;
    String libraryId;
    String oldEndpoint;

    @BeforeEach
    void setup() throws Exception {
        var data =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:reindex-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(data);
        catalog = new KnowledgeCatalog(data);
        models = new ModelCatalog(data);
        index = new MemoryChunkIndex();
        libraryId = catalog.create("owner", "rebuild", "").libraryId();
        oldEndpoint = endpoint("old", 2);
    }

    private String endpoint(String name, int dimensions) throws Exception {
        return models.insert(
                        new ModelCatalog.EndpointDraft(
                                "embedding", "openai", name, "http://localhost:9/v1", "fake"),
                        dimensions,
                        name,
                        false)
                .endpointId();
    }

    private String document(String text) throws Exception {
        Path file = root.resolve(UUID.randomUUID() + ".pdf");
        Files.writeString(file, "The published text must be used without parsing this file again.");
        var doc =
                catalog.addDocument(libraryId, "preserved.pdf", file.toString(), Files.size(file));
        var source =
                new PdfContent.Source(1, null, "b0", "paragraph", null, "native", false, List.of());
        var chunk = new PdfContent.Chunk("1:b0:0", text, source);
        catalog.structures()
                .savePage(
                        doc.documentId(),
                        new PdfContent.Page(
                                1,
                                null,
                                600,
                                800,
                                "ready",
                                "native",
                                text,
                                List.of(
                                        new PdfContent.Block("b0", "paragraph", text, null, false),
                                        new PdfContent.Block(
                                                "b1",
                                                "paragraph",
                                                "context for " + text,
                                                null,
                                                false)),
                                List.of()));
        catalog.structures()
                .publishAtomic(
                        doc.documentId(),
                        libraryId,
                        List.of(chunk),
                        index,
                        2,
                        oldEndpoint,
                        List.of(
                                new ChunkIndex.Passage(
                                        text, new double[] {1, 0}, chunk.chunkId(), source)));
        return doc.documentId();
    }

    private VectorModelSource source(String endpoint, EmbeddingModel model) {
        return new VectorModelSource() {
            public Optional<EmbeddingModel> current() {
                return Optional.empty();
            }

            public Optional<EmbeddingModel> forEndpoint(String id) {
                return endpoint.equals(id) ? Optional.of(model) : Optional.empty();
            }
        };
    }

    private EmbeddingModel model(
            int dimensions, java.util.function.Function<String, Mono<double[]>> embed) {
        return new EmbeddingModel() {
            public String getModelName() {
                return "new";
            }

            public int getDimensions() {
                return dimensions;
            }

            public Mono<double[]> embed(ContentBlock input) {
                return embed.apply(((TextBlock) input).getText());
            }
        };
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void sameOrDifferentDimensionRebuildsAllTextAndKeepsWholeLibraryPaused(int dimensions)
            throws Exception {
        String first = document("one 123.45");
        String second = document("two 678.90");
        String target = endpoint("new", dimensions);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var seen = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var model =
                model(
                        dimensions,
                        text ->
                                Mono.fromCallable(
                                        () -> {
                                            seen.add(text);
                                            if (text.startsWith("two")) {
                                                entered.countDown();
                                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                            }
                                            double[] vector = new double[dimensions];
                                            vector[1] = 1;
                                            return vector;
                                        }));
        var source = source(target, model);
        try (var ingest = new DocumentIngest(catalog, source, index)) {
            var result =
                    new KnowledgeReindex(catalog, models, index, ingest).change(libraryId, target);
            assertEquals(2, result.affectedDocuments());
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                await(() -> "ready".equals(catalog.findDocument(first).orElseThrow().status()));
                assertTrue(catalog.find(libraryId).orElseThrow().rebuilding());
                catalog.replaceAgentSelection("rebuild-agent", List.of(libraryId));
                assertTrue(
                        new KnowledgeSearch(catalog, source, index)
                                .scope("rebuild-agent")
                                .isEmpty());
                assertThrows(
                        KnowledgeReindex.Busy.class,
                        () ->
                                new KnowledgeSearch(catalog, source, index)
                                        .search(List.of(libraryId), "query"));
            } finally {
                release.countDown();
            }
            await(() -> !catalog.find(libraryId).orElseThrow().rebuilding());
            assertEquals(target, catalog.find(libraryId).orElseThrow().embeddingEndpointId());
            assertEquals(dimensions, catalog.find(libraryId).orElseThrow().embeddingDimensions());
            assertEquals("ready", catalog.findDocument(second).orElseThrow().status());
            assertTrue(seen.containsAll(List.of("one 123.45", "two 678.90")));
            assertEquals(
                    2,
                    new KnowledgeSearch(catalog, source, index)
                            .search(List.of(libraryId), "query")
                            .size());
        }
    }

    @Test
    void largerThanQueueRebuildDoesNotRejectPendingDocuments() throws Exception {
        for (int i = 0; i < 5; i++) document("evidence " + i);
        String target = endpoint("new", 3);
        var gate = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var source =
                source(
                        target,
                        model(
                                3,
                                text ->
                                        Mono.fromCallable(
                                                () -> {
                                                    entered.countDown();
                                                    assertTrue(gate.await(5, TimeUnit.SECONDS));
                                                    return new double[] {1, 0, 0};
                                                })));
        try (var ingest =
                new DocumentIngest(
                        catalog, source, index, new PdfParser(), 1, 1, Duration.ofSeconds(15))) {
            assertEquals(
                    5,
                    new KnowledgeReindex(catalog, models, index, ingest)
                            .change(libraryId, target)
                            .affectedDocuments());
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(
                        catalog.documents(libraryId).stream()
                                .noneMatch(doc -> "failed".equals(doc.status())));
            } finally {
                gate.countDown();
            }
            await(() -> !catalog.find(libraryId).orElseThrow().rebuilding());
            assertEquals(5, catalog.readyCount(libraryId));
        }
    }

    @Test
    void failedRebuildCannotExposeOldTextAsReadyAndCanResumeAfterInterruption() throws Exception {
        String id = document("old published content");
        String target = endpoint("new", 3);
        var fail = new AtomicBoolean(true);
        var source =
                source(
                        target,
                        model(
                                3,
                                text ->
                                        fail.get()
                                                ? Mono.error(
                                                        new IllegalStateException(
                                                                "fixture unavailable"))
                                                : Mono.just(new double[] {1, 0, 0})));
        try (var ingest = new DocumentIngest(catalog, source, index)) {
            new KnowledgeReindex(catalog, models, index, ingest).change(libraryId, target);
            await(
                    () ->
                            "failed".equals(catalog.findDocument(id).orElseThrow().status())
                                    && !ingest.isActive(id));
            assertTrue(catalog.find(libraryId).orElseThrow().rebuilding());
            assertTrue(catalog.requestReprocess(id));
            catalog.failInterrupted();
            assertTrue(catalog.find(libraryId).orElseThrow().rebuilding());
            fail.set(false);
            assertTrue(catalog.requestReprocess(id));
            ingest.submit(id);
            await(() -> !catalog.find(libraryId).orElseThrow().rebuilding());
            assertEquals("ready", catalog.findDocument(id).orElseThrow().status());
        }
    }

    @Test
    void busyOrInvalidTargetDoesNotChangeExistingBinding() throws Exception {
        document("existing");
        String target = endpoint("new", 3);
        var source = source(target, model(3, text -> Mono.just(new double[] {1, 0, 0})));
        try (var ingest = new DocumentIngest(catalog, source, index)) {
            var reindex = new KnowledgeReindex(catalog, models, index, ingest);
            assertThrows(
                    IllegalArgumentException.class, () -> reindex.change(libraryId, "missing"));
            catalog.addDocument(libraryId, "incoming.txt", root.resolve("incoming").toString(), 1);
            assertThrows(KnowledgeReindex.Busy.class, () -> reindex.change(libraryId, target));
            assertEquals(oldEndpoint, catalog.find(libraryId).orElseThrow().embeddingEndpointId());
            assertFalse(catalog.find(libraryId).orElseThrow().rebuilding());
        }
    }

    private interface Checked {
        boolean done() throws Exception;
    }

    @Test
    void rebuildingPublishedTextPreservesItsOriginalPageSnapshot() throws Exception {
        String id = document("trusted original text");
        String target = endpoint("new", 3);
        assertTrue(catalog.requestReprocess(id));
        catalog.structures().start(id, 1, true);
        catalog.structures()
                .savePage(
                        id,
                        new PdfContent.Page(
                                1,
                                null,
                                600,
                                800,
                                "failed",
                                "native",
                                "unpublished draft",
                                List.of(),
                                List.of("fixture failure")));
        catalog.failProcessing(id, "fixture parse failure");
        catalog.rememberParseSignature(id, "newer-unpublished-parse-config");
        var source = source(target, model(3, text -> Mono.just(new double[] {1, 0, 0})));
        try (var ingest = new DocumentIngest(catalog, source, index)) {
            new KnowledgeReindex(catalog, models, index, ingest).change(libraryId, target);
            await(() -> !catalog.find(libraryId).orElseThrow().rebuilding());
            assertEquals(
                    "trusted original text",
                    catalog.structures().find(id, 1).orElseThrow().nativeText());
            assertEquals(
                    "context for trusted original text",
                    catalog.structures().context(id, "1:b0:0").get(0).text());
            assertEquals("", catalog.parseSignature(id));
        }
    }

    @Test
    void rebuildDuringRemoteRerankCannotReturnOldResults() throws Exception {
        document("trusted original text");
        String target = endpoint("new", 3);
        var oldModel = model(2, text -> Mono.just(new double[] {1, 0}));
        var newModel = model(3, text -> Mono.just(new double[] {1, 0, 0}));
        VectorModelSource source =
                new VectorModelSource() {
                    public Optional<EmbeddingModel> current() {
                        return Optional.of(oldModel);
                    }

                    public Optional<EmbeddingModel> forEndpoint(String id) {
                        return Optional.of(id.equals(target) ? newModel : oldModel);
                    }
                };
        var ranking = org.mockito.Mockito.mock(PdfModelReader.class);
        try (var ingest = new DocumentIngest(catalog, source, index)) {
            org.mockito.Mockito.when(
                            ranking.rerank(
                                    org.mockito.ArgumentMatchers.anyString(),
                                    org.mockito.ArgumentMatchers.anyList()))
                    .thenAnswer(
                            call -> {
                                new KnowledgeReindex(catalog, models, index, ingest)
                                        .change(libraryId, target);
                                return List.of();
                            });
            var search = new KnowledgeSearch(catalog, source, index, ranking, new PdfParser());
            assertThrows(
                    KnowledgeReindex.Busy.class, () -> search.search(List.of(libraryId), "query"));
        }
    }

    private static void await(Checked condition) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        while (System.nanoTime() < until) {
            if (condition.done()) return;
            Thread.sleep(15);
        }
        throw new AssertionError("Rebuild did not reach expected state");
    }
}
