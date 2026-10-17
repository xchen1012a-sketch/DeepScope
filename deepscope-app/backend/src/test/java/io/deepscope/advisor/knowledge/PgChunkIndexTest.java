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
package io.deepscope.advisor.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.advisor.db.AdvisorSchema;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs only against an explicitly supplied isolated PostgreSQL database with pgvector. */
@EnabledIfEnvironmentVariable(named = "ADVISOR_TEST_PGVECTOR_URL", matches = ".+")
class PgChunkIndexTest {
    @Test
    void firstFailedPublicationDoesNotLeaveATableLockedToTheOldDimension() throws Exception {
        String url = System.getenv("ADVISOR_TEST_PGVECTOR_URL");
        assertNotNull(url);
        String user = System.getenv("ADVISOR_TEST_PGVECTOR_USER");
        String password = System.getenv("ADVISOR_TEST_PGVECTOR_PASSWORD");
        var data = new DriverManagerDataSource(url, user, password);
        AdvisorSchema.initialize(data);
        var catalog = new KnowledgeCatalog(data);
        var library = catalog.create("first-" + UUID.randomUUID(), "first-failure", "");
        try (var index = new PgChunkIndex(data, url, user, password)) {
            try {
                assertThrows(
                        Exception.class,
                        () ->
                                index.replace(
                                        library.libraryId(),
                                        3,
                                        "doc",
                                        List.of(
                                                new ChunkIndex.Passage(
                                                        "bad",
                                                        new double[] {1, 0, 0},
                                                        "x".repeat(300),
                                                        null))));
                try (var connection = data.getConnection();
                        var tables =
                                connection
                                        .getMetaData()
                                        .getTables(
                                                null,
                                                "public",
                                                PgChunkIndex.table(library.libraryId()),
                                                new String[] {"TABLE"})) {
                    assertTrue(
                            !tables.next(),
                            "first table creation must roll back with the failed batch");
                }
                index.replace(
                        library.libraryId(),
                        4,
                        "doc",
                        List.of(
                                new ChunkIndex.Passage(
                                        "four dimensions", new double[] {1, 0, 0, 0})));
                catalog.rememberDimensions(library.libraryId(), 4);
                assertEquals(
                        1,
                        index.search(library.libraryId(), new double[] {1, 0, 0, 0}, 5, .5).size());
            } finally {
                index.deleteLibrary(library.libraryId());
                catalog.deleteLibrary(library.libraryId());
            }
        }
    }

    @Test
    void failedFinalBatchRollsBackVectorsAndTextTogetherAndKeepsPreviousCompleteVersion()
            throws Exception {
        String url = System.getenv("ADVISOR_TEST_PGVECTOR_URL");
        assertNotNull(url, "Set isolated pgvector test URL");
        String user = System.getenv("ADVISOR_TEST_PGVECTOR_USER");
        String password = System.getenv("ADVISOR_TEST_PGVECTOR_PASSWORD");
        var data = new DriverManagerDataSource(url, user, password);
        AdvisorSchema.initialize(data);
        var catalog = new KnowledgeCatalog(data);
        var library = catalog.create("atomic-" + UUID.randomUUID(), "atomic", "");
        var doc = catalog.addDocument(library.libraryId(), "fixture.txt", "unused", 1);
        var structures = catalog.structures();
        try (var index = new PgChunkIndex(data, url, user, password)) {
            try {
                var old = new PdfContent.Chunk("old", "previous complete evidence", null);
                structures.publishAtomic(
                        doc.documentId(),
                        library.libraryId(),
                        List.of(old),
                        index,
                        3,
                        List.of(
                                new ChunkIndex.Passage(
                                        old.text(), new double[] {1, 0, 0}, "old", null)));
                assertTrue(catalog.requestReprocess(doc.documentId()));
                var broken =
                        List.of(
                                new ChunkIndex.Passage(
                                        "new first", new double[] {0, 1, 0}, "first", null),
                                new ChunkIndex.Passage(
                                        "new failing row",
                                        new double[] {0, 1, 0},
                                        "x".repeat(300),
                                        null));
                assertThrows(
                        Exception.class,
                        () ->
                                structures.publishAtomic(
                                        doc.documentId(),
                                        library.libraryId(),
                                        List.of(new PdfContent.Chunk("new", "new text", null)),
                                        index,
                                        3,
                                        broken));
                catalog.failProcessing(doc.documentId(), "fixture final batch failure");
                assertEquals(
                        "ready", catalog.findDocument(doc.documentId()).orElseThrow().status());
                assertEquals(
                        "previous complete evidence",
                        index.search(library.libraryId(), new double[] {1, 0, 0}, 10, 0)
                                .get(0)
                                .text());
                assertEquals(
                        1, index.search(library.libraryId(), new double[] {1, 0, 0}, 10, 0).size());
                assertTrue(structures.lexical(library.libraryId(), "new text", 10).isEmpty());
                assertEquals(1, structures.lexical(library.libraryId(), "previous", 10).size());
            } finally {
                index.deleteLibrary(library.libraryId());
                catalog.deleteLibrary(library.libraryId());
            }
        }
    }

    @Test
    void aNewInstanceSearchesAndDeletesExistingPersistentPassages() throws Exception {
        String url = System.getenv("ADVISOR_TEST_PGVECTOR_URL");
        assertNotNull(url, "Set ADVISOR_TEST_PGVECTOR_URL to an isolated pgvector test database");
        String user = System.getenv("ADVISOR_TEST_PGVECTOR_USER");
        String password = System.getenv("ADVISOR_TEST_PGVECTOR_PASSWORD");
        var source = new DriverManagerDataSource(url, user, password);
        AdvisorSchema.initialize(source);
        var catalog = new KnowledgeCatalog(source);
        var library = catalog.create("test-" + UUID.randomUUID(), "restart-test", "");
        var first = new PgChunkIndex(source, url, user, password);
        var second = new PgChunkIndex(source, url, user, password);
        var citation =
                new PdfContent.Source(
                        7,
                        "iii",
                        "b0",
                        "table",
                        new PdfContent.Box(.1, .2, .5, .3),
                        "vision",
                        false,
                        List.of("review"));
        try {
            first.replace(
                    library.libraryId(),
                    3,
                    "test-document",
                    List.of(
                            new ChunkIndex.Passage(
                                    "persistent passage",
                                    new double[] {1, 0, 0},
                                    "7:b0:0",
                                    citation)));
            catalog.rememberDimensions(library.libraryId(), 3);
            assertEquals(
                    1, first.search(library.libraryId(), new double[] {1, 0, 0}, 5, 0.5).size());
            first.close();
            var hits = second.search(library.libraryId(), new double[] {1, 0, 0}, 5, 0.5);
            assertEquals(1, hits.size(), "a process-local cache miss must open the existing table");
            assertEquals("persistent passage", hits.get(0).text());
            assertEquals("7:b0:0", hits.get(0).chunkId());
            assertEquals(citation, hits.get(0).source());
            second.deleteDocument(library.libraryId(), "test-document");
            assertTrue(
                    second.search(library.libraryId(), new double[] {1, 0, 0}, 5, 0.5).isEmpty());
        } finally {
            second.deleteLibrary(library.libraryId());
            catalog.deleteLibrary(library.libraryId());
            first.close();
            second.close();
        }
    }
}
