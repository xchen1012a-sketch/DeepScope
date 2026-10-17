/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.deepscope.advisor.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.advisor.model.ModelCatalog;
import io.deepscope.core.embedding.EmbeddingModel;
import io.deepscope.core.message.ContentBlock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Mono;

class KnowledgeReindexPgTest {
    @Test
    void concurrentTargetDeletionRechecksReferencesAfterBindingCommits() throws Exception {
        var fixture = fixture();
        String application = "rebind-delete-" + UUID.randomUUID();
        String url =
                fixture.data.getUrl()
                        + (fixture.data.getUrl().contains("?") ? "&" : "?")
                        + "ApplicationName="
                        + application;
        var deletion =
                new ModelCatalog(
                        new DriverManagerDataSource(
                                url, fixture.data.getUsername(), fixture.data.getPassword()));
        var thread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var index = fixture.index;
                var connection = fixture.data.getConnection()) {
            fixture.publish(index);
            connection.setAutoCommit(false);
            KnowledgeLibraryLock.acquire(connection, fixture.libraryId);
            try (var lock =
                    connection.prepareStatement(
                            "SELECT endpoint_id FROM advisor_model_endpoints WHERE endpoint_id=?"
                                    + " FOR UPDATE")) {
                lock.setString(1, fixture.target);
                try (var rows = lock.executeQuery()) {
                    assertTrue(rows.next());
                }
            }
            var deleting =
                    thread.submit(
                            () -> {
                                deletion.delete(fixture.target);
                                return null;
                            });
            try {
                boolean waiting = false;
                long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (!waiting && System.nanoTime() < end) {
                    try (var check = fixture.data.getConnection();
                            var query =
                                    check.prepareStatement(
                                            "SELECT COUNT(*) FROM pg_stat_activity WHERE"
                                                    + " application_name=? AND"
                                                    + " wait_event_type='Lock'")) {
                        query.setString(1, application);
                        try (var rows = query.executeQuery()) {
                            rows.next();
                            waiting = rows.getInt(1) > 0;
                        }
                    }
                    if (!waiting) Thread.sleep(10);
                }
                assertTrue(waiting, "deletion must reach the locked target before binding commits");
                try (var update =
                        connection.prepareStatement(
                                "UPDATE advisor_knowledge_bases SET"
                                        + " embedding_endpoint_id=?,embedding_dimensions=3 WHERE"
                                        + " library_id=?")) {
                    update.setString(1, fixture.target);
                    update.setString(2, fixture.libraryId);
                    update.executeUpdate();
                }
                connection.commit();
                var error =
                        assertThrows(
                                java.util.concurrent.ExecutionException.class,
                                () -> deleting.get(3, TimeUnit.SECONDS));
                assertTrue(error.getCause() instanceof IllegalArgumentException);
                assertTrue(fixture.models.find(fixture.target).isPresent());
            } finally {
                connection.rollback();
                index.deleteLibrary(fixture.libraryId);
                fixture.catalog.deleteLibrary(fixture.libraryId);
            }
        } finally {
            thread.shutdownNow();
        }
    }

    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void resetsPersistentTableAndCachedStoreForSameOrDifferentDimensions(int dimensions)
            throws Exception {
        var fixture = fixture();
        try (var index = fixture.index;
                var ingest =
                        new DocumentIngest(fixture.catalog, fixture.source(dimensions), index)) {
            fixture.publish(index);
            assertEquals(1, index.search(fixture.libraryId, new double[] {1, 0}, 5, .5).size());
            try {
                new KnowledgeReindex(fixture.catalog, fixture.models, index, ingest)
                        .change(fixture.libraryId, fixture.target);
                assertTrue(fixture.entered.await(3, TimeUnit.SECONDS));
                assertNull(fixture.tableType());
                fixture.release.countDown();
                long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (fixture.catalog.find(fixture.libraryId).orElseThrow().rebuilding()
                        && System.nanoTime() < end) Thread.sleep(15);
                assertFalse(fixture.catalog.find(fixture.libraryId).orElseThrow().rebuilding());
                assertEquals("vector(" + dimensions + ")", fixture.tableType());
                double[] query = new double[dimensions];
                query[1] = 1;
                assertEquals(1, index.search(fixture.libraryId, query, 5, .9).size());
                try (var connection = fixture.data.getConnection();
                        var count =
                                connection.prepareStatement(
                                        "SELECT COUNT(*) FROM"
                                                + " advisor_document_embedding_checkpoints WHERE"
                                                + " document_id=?")) {
                    count.setString(1, fixture.documentId);
                    try (var rows = count.executeQuery()) {
                        rows.next();
                        assertEquals(0, rows.getInt(1));
                    }
                }
            } finally {
                fixture.release.countDown();
                index.deleteLibrary(fixture.libraryId);
                fixture.catalog.deleteLibrary(fixture.libraryId);
            }
        }
    }

    @Test
    void resetFailureRollsBackBindingDocumentStateAndDroppedTable() throws Exception {
        var fixture = fixture();
        try (var index = fixture.index;
                var ingest = new DocumentIngest(fixture.catalog, fixture.source(3), index)) {
            fixture.publish(index);
            assertEquals(1, index.search(fixture.libraryId, new double[] {1, 0}, 5, .5).size());
            ChunkIndex interrupted =
                    new ChunkIndex() {
                        public boolean available() {
                            return true;
                        }

                        public void replace(
                                String library,
                                int dimension,
                                String document,
                                List<Passage> passages) {
                            throw new UnsupportedOperationException();
                        }

                        public void deleteDocument(String library, String document) {
                            throw new UnsupportedOperationException();
                        }

                        public void deleteLibrary(String library) {
                            throw new UnsupportedOperationException();
                        }

                        public List<Hit> search(
                                String library, double[] query, int limit, double threshold) {
                            throw new UnsupportedOperationException();
                        }

                        public void reset(Connection connection, String library)
                                throws SQLException {
                            index.reset(connection, library);
                            throw new SQLException("fixture reset failure");
                        }
                    };
            try {
                assertThrows(
                        SQLException.class,
                        () ->
                                new KnowledgeReindex(
                                                fixture.catalog,
                                                fixture.models,
                                                interrupted,
                                                ingest)
                                        .change(fixture.libraryId, fixture.target));
                assertEquals(
                        fixture.oldEndpoint,
                        fixture.catalog
                                .find(fixture.libraryId)
                                .orElseThrow()
                                .embeddingEndpointId());
                assertEquals(
                        "ready",
                        fixture.catalog.findDocument(fixture.documentId).orElseThrow().status());
                assertEquals("vector(2)", fixture.tableType());
                assertEquals(1, index.search(fixture.libraryId, new double[] {1, 0}, 5, .5).size());
            } finally {
                index.deleteLibrary(fixture.libraryId);
                fixture.catalog.deleteLibrary(fixture.libraryId);
            }
        }
    }

    private Fixture fixture() throws Exception {
        String url = System.getenv("ADVISOR_TEST_PGVECTOR_URL");
        assertNotNull(url, "Use an isolated pgvector test database");
        var data =
                new DriverManagerDataSource(
                        url,
                        System.getenv("ADVISOR_TEST_PGVECTOR_USER"),
                        System.getenv("ADVISOR_TEST_PGVECTOR_PASSWORD"));
        AdvisorSchema.initialize(data);
        var catalog = new KnowledgeCatalog(data);
        var models = new ModelCatalog(data);
        String library = catalog.create("reindex-" + UUID.randomUUID(), "reindex", "").libraryId();
        String old =
                models.insert(
                                new ModelCatalog.EndpointDraft(
                                        "embedding",
                                        "openai",
                                        "old",
                                        "http://localhost:9/v1",
                                        "fake"),
                                2,
                                "Old",
                                false)
                        .endpointId();
        String target =
                models.insert(
                                new ModelCatalog.EndpointDraft(
                                        "embedding",
                                        "openai",
                                        "new",
                                        "http://localhost:9/v1",
                                        "fake"),
                                3,
                                "New",
                                false)
                        .endpointId();
        Path file = root.resolve(UUID.randomUUID() + ".txt");
        Files.writeString(file, "preserved text");
        String document =
                catalog.addDocument(library, "preserved.txt", file.toString(), Files.size(file))
                        .documentId();
        return new Fixture(
                data,
                catalog,
                models,
                new PgChunkIndex(data, url, data.getUsername(), data.getPassword()),
                library,
                document,
                old,
                target);
    }

    private static final class Fixture {
        final DriverManagerDataSource data;
        final KnowledgeCatalog catalog;
        final ModelCatalog models;
        final PgChunkIndex index;
        final String libraryId, documentId, oldEndpoint, target;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

        Fixture(
                DriverManagerDataSource data,
                KnowledgeCatalog catalog,
                ModelCatalog models,
                PgChunkIndex index,
                String libraryId,
                String documentId,
                String oldEndpoint,
                String target) {
            this.data = data;
            this.catalog = catalog;
            this.models = models;
            this.index = index;
            this.libraryId = libraryId;
            this.documentId = documentId;
            this.oldEndpoint = oldEndpoint;
            this.target = target;
        }

        void publish(PgChunkIndex index) throws Exception {
            catalog.structures()
                    .publishAtomic(
                            documentId,
                            libraryId,
                            List.of(new PdfContent.Chunk("0", "preserved text", null)),
                            index,
                            2,
                            oldEndpoint,
                            List.of(
                                    new ChunkIndex.Passage(
                                            "preserved text", new double[] {1, 0}, "0", null)));
        }

        VectorModelSource source(int dimensions) throws Exception {
            if (dimensions != 3)
                try (var connection = data.getConnection();
                        var update =
                                connection.prepareStatement(
                                        "UPDATE advisor_model_endpoints SET dimensions=? WHERE"
                                                + " endpoint_id=?")) {
                    update.setInt(1, dimensions);
                    update.setString(2, target);
                    update.executeUpdate();
                }
            return new VectorModelSource() {
                public Optional<EmbeddingModel> current() {
                    return Optional.empty();
                }

                public Optional<EmbeddingModel> forEndpoint(String id) {
                    return Optional.of(
                            new EmbeddingModel() {
                                public String getModelName() {
                                    return "new";
                                }

                                public int getDimensions() {
                                    return dimensions;
                                }

                                public Mono<double[]> embed(ContentBlock input) {
                                    return Mono.fromCallable(
                                            () -> {
                                                entered.countDown();
                                                assertTrue(release.await(6, TimeUnit.SECONDS));
                                                double[] vector = new double[dimensions];
                                                vector[1] = 1;
                                                return vector;
                                            });
                                }
                            });
                }
            };
        }

        String tableType() throws Exception {
            try (var connection = data.getConnection();
                    var query =
                            connection.prepareStatement(
                                    "SELECT format_type(a.atttypid,a.atttypmod) FROM pg_attribute a"
                                        + " JOIN pg_class c ON c.oid=a.attrelid WHERE c.relname=?"
                                        + " AND a.attname='embedding'")) {
                query.setString(1, PgChunkIndex.table(libraryId));
                try (var rows = query.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        }
    }
}
