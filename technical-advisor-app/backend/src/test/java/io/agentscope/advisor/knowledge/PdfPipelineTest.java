/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.advisor.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.advisor.db.AdvisorSchema;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import reactor.core.publisher.Mono;

class PdfPipelineTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void failedEmbeddingResumesOnlyMatchingModelCheckpoints(boolean changeModel) throws Exception {
        var library = catalog.create("owner", "Embedding recovery", "");
        Path file = root.resolve("recover.txt");
        Files.writeString(file, "evidence ".repeat(200));
        int total =
                DocumentTexts.passages(file, "recover.txt", library.chunkSize(), library.overlap())
                        .size();
        var doc =
                catalog.addDocument(
                        library.libraryId(), "recover.txt", file.toString(), Files.size(file));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var revision = new java.util.concurrent.atomic.AtomicInteger(1);
        EmbeddingModel flaky =
                new EmbeddingModel() {
                    public String getModelName() {
                        return "checkpoint-fixture";
                    }

                    public int getDimensions() {
                        return 2;
                    }

                    public Mono<double[]> embed(ContentBlock input) {
                        return calls.incrementAndGet() == 2
                                ? Mono.error(new IllegalStateException("fixture failure"))
                                : Mono.just(new double[] {1, 0});
                    }
                };
        VectorModelSource source =
                new VectorModelSource() {
                    public Optional<EmbeddingModel> current() {
                        return Optional.of(flaky);
                    }

                    public String checkpointIdentity(EmbeddingModel selected) {
                        return "fixture-" + revision.get();
                    }
                };
        try (var ingest =
                new DocumentIngest(
                        catalog,
                        source,
                        index,
                        new PdfParser(),
                        1,
                        1,
                        java.time.Duration.ofMinutes(1),
                        1)) {
            ingest.process(doc.documentId());
            assertEquals("failed", catalog.findDocument(doc.documentId()).orElseThrow().status());
            assertTrue(index.search(library.libraryId(), new double[] {1, 0}, 10, 0).isEmpty());
            if (changeModel) revision.incrementAndGet();
            assertEquals(1, catalog.findDocument(doc.documentId()).orElseThrow().embeddedChunks());
            assertEquals(total, catalog.findDocument(doc.documentId()).orElseThrow().totalChunks());
            assertTrue(catalog.requestReprocess(doc.documentId()));
            ingest.process(doc.documentId());
            assertEquals("ready", catalog.findDocument(doc.documentId()).orElseThrow().status());
            assertEquals(changeModel ? total + 2 : total + 1, calls.get());
            assertEquals(
                    total, catalog.findDocument(doc.documentId()).orElseThrow().embeddedChunks());
        }
    }

    @Test
    void oneDocumentEmbedsMultiplePassagesConcurrently() throws Exception {
        var library = catalog.create("owner", "Parallel chunks", "");
        Path file = root.resolve("many.txt");
        Files.writeString(file, "evidence ".repeat(200));
        var doc =
                catalog.addDocument(
                        library.libraryId(), "many.txt", file.toString(), Files.size(file));
        var entered = new java.util.concurrent.CountDownLatch(3);
        var release = new java.util.concurrent.CountDownLatch(1);
        EmbeddingModel parallel =
                new EmbeddingModel() {
                    public String getModelName() {
                        return "parallel";
                    }

                    public int getDimensions() {
                        return 2;
                    }

                    public Mono<double[]> embed(ContentBlock input) {
                        return Mono.fromCallable(
                                () -> {
                                    entered.countDown();
                                    release.await(4, java.util.concurrent.TimeUnit.SECONDS);
                                    return new double[] {1, 0};
                                });
                    }
                };
        try (var ingest = new DocumentIngest(catalog, () -> Optional.of(parallel), index)) {
            ingest.submit(doc.documentId());
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertEquals("ready", awaitTerminal(doc.documentId()));
        }
    }

    @Test
    void cancelledVisionParsingPropagatesInsteadOfRecordingAnOrdinaryPageFailure()
            throws Exception {
        var fixture = new PdfDocumentTest();
        fixture.root = root;
        var vision =
                new PdfParser.Vision() {
                    public boolean available() {
                        return true;
                    }

                    public PdfContent.Page parse(byte[] image, PdfContent.Page page) {
                        throw new java.util.concurrent.CancellationException(
                                "cancelled by deadline");
                    }
                };
        var completed = new java.util.ArrayList<PdfContent.Page>();
        try {
            Path file = fixture.pdf(true);
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () ->
                            new PdfParser(vision, 10, 1000)
                                    .parse(file, List.of(), count -> {}, completed::add));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, completed.size());
            assertEquals("ready", completed.get(0).status());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void successfulPagesAreReusedOnlyWithTheSameParserConfiguration() throws Exception {
        var library = catalog.create("owner", "Checkpoint", "");
        var fixture = new PdfDocumentTest();
        fixture.root = root;
        Path original = fixture.pdf(true);
        Path file = root.resolve("two-scans.pdf");
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(original.toFile())) {
            pdf.removePage(0);
            pdf.importPage(pdf.getPage(0));
            pdf.save(file.toFile());
        }
        var doc =
                catalog.addDocument(
                        library.libraryId(), "scans.pdf", file.toString(), Files.size(file));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var revision = new java.util.concurrent.atomic.AtomicInteger(1);
        var failing = new AtomicBoolean(true);
        var changeDuringPage = new AtomicBoolean(false);
        var vision =
                new PdfParser.Vision() {
                    public boolean available() {
                        return true;
                    }

                    public String cacheKey() {
                        return "fixture-" + revision.get();
                    }

                    public PdfContent.Page parse(byte[] image, PdfContent.Page page) {
                        calls.incrementAndGet();
                        if (page.pageNumber() == 2 && changeDuringPage.get())
                            revision.incrementAndGet();
                        if (page.pageNumber() == 2 && failing.get())
                            throw new IllegalStateException("private diagnostic");
                        return new PdfContent.Page(
                                page.pageNumber(),
                                null,
                                page.width(),
                                page.height(),
                                "ready",
                                "vision",
                                "",
                                List.of(
                                        new PdfContent.Block(
                                                "b0",
                                                "paragraph",
                                                "Evidence " + page.pageNumber(),
                                                null,
                                                false)),
                                List.of());
                    }
                };
        try (var ingest =
                new DocumentIngest(
                        catalog,
                        () -> Optional.of(model),
                        index,
                        new PdfParser(vision, 10, 1000))) {
            ingest.process(doc.documentId());
            assertEquals("failed", catalog.findDocument(doc.documentId()).orElseThrow().status());
            assertEquals(2, calls.get());
            assertTrue(index.search(library.libraryId(), new double[] {1, 0}, 10, 0).isEmpty());
            assertFalse(
                    structures
                            .pages(doc.documentId())
                            .get(1)
                            .warnings()
                            .toString()
                            .contains("private diagnostic"));
            failing.set(false);
            assertTrue(catalog.requestReprocess(doc.documentId()));
            ingest.process(doc.documentId());
            assertEquals(3, calls.get(), "only the failed page calls the model again");
            assertEquals("ready", catalog.findDocument(doc.documentId()).orElseThrow().status());
            revision.incrementAndGet();
            assertTrue(catalog.requestReprocess(doc.documentId()));
            ingest.process(doc.documentId());
            assertEquals(5, calls.get(), "a changed configuration must parse both pages again");
            revision.incrementAndGet();
            changeDuringPage.set(true);
            assertTrue(catalog.requestReprocess(doc.documentId()));
            ingest.process(doc.documentId());
            assertEquals(
                    "",
                    catalog.parseSignature(doc.documentId()),
                    "configuration changes must invalidate all checkpoints");
            assertEquals(
                    "ready",
                    catalog.findDocument(doc.documentId()).orElseThrow().status(),
                    "the previous complete publication remains usable");
            assertTrue(
                    catalog.findDocument(doc.documentId())
                            .orElseThrow()
                            .failure()
                            .contains("配置发生变化"));
        }
    }

    @Test
    void twoDocumentsExecuteConcurrentlyInsteadOfBlockingTheQueue() throws Exception {
        var library = catalog.create("owner", "Concurrent", "");
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        EmbeddingModel slow =
                new EmbeddingModel() {
                    public String getModelName() {
                        return "slow";
                    }

                    public int getDimensions() {
                        return 2;
                    }

                    public Mono<double[]> embed(ContentBlock input) {
                        return Mono.fromCallable(
                                () -> {
                                    entered.countDown();
                                    if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                                        throw new IllegalStateException("test barrier timed out");
                                    return new double[] {1, 0};
                                });
                    }
                };
        try (var ingest = new DocumentIngest(catalog, () -> Optional.of(slow), index)) {
            for (int i = 0; i < 2; i++) {
                Path file = root.resolve("parallel-" + i + ".txt");
                Files.writeString(file, "concurrent evidence");
                var doc =
                        catalog.addDocument(
                                library.libraryId(),
                                "parallel.txt",
                                file.toString(),
                                Files.size(file));
                ingest.submit(doc.documentId());
            }
            try {
                assertTrue(
                        entered.await(3, java.util.concurrent.TimeUnit.SECONDS),
                        "both workers must enter independently");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void taskDeadlineCancelsEmbeddingAndAllowsTheNextDocumentToFinish() throws Exception {
        var library = catalog.create("owner", "Timeout", "");
        Path slowFile = root.resolve("slow.txt");
        Files.writeString(slowFile, "slow");
        var slowDoc = catalog.addDocument(library.libraryId(), "slow.txt", slowFile.toString(), 4);
        var cancelled = new java.util.concurrent.CountDownLatch(1);
        EmbeddingModel bounded =
                new EmbeddingModel() {
                    public String getModelName() {
                        return "deadline";
                    }

                    public int getDimensions() {
                        return 2;
                    }

                    public Mono<double[]> embed(ContentBlock input) {
                        return ((io.agentscope.core.message.TextBlock) input)
                                        .getText()
                                        .equals("slow")
                                ? Mono.<double[]>never().doOnCancel(cancelled::countDown)
                                : Mono.just(new double[] {1, 0});
                    }
                };
        try (var ingest =
                new DocumentIngest(
                        catalog,
                        () -> Optional.of(bounded),
                        index,
                        new PdfParser(),
                        1,
                        1,
                        java.time.Duration.ofMillis(500))) {
            ingest.submit(slowDoc.documentId());
            assertTrue(cancelled.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("failed", awaitTerminal(slowDoc.documentId()));
            Path fastFile = root.resolve("fast.txt");
            Files.writeString(fastFile, "fast");
            var fast = catalog.addDocument(library.libraryId(), "fast.txt", fastFile.toString(), 4);
            ingest.submit(fast.documentId());
            assertEquals("ready", awaitTerminal(fast.documentId()));
        }
    }

    private String awaitTerminal(String id) throws Exception {
        long until = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < until) {
            String status = catalog.findDocument(id).orElseThrow().status();
            if (!"parsing".equals(status)) return status;
            Thread.sleep(15);
        }
        throw new AssertionError("Document did not reach a terminal status");
    }

    @TempDir Path root;
    KnowledgeCatalog catalog;
    DocumentStructureStore structures;
    MemoryChunkIndex index;
    final EmbeddingModel model =
            new EmbeddingModel() {
                public Mono<double[]> embed(ContentBlock block) {
                    return Mono.just(new double[] {1, 0});
                }

                public String getModelName() {
                    return "fixture";
                }

                public int getDimensions() {
                    return 2;
                }
            };

    @BeforeEach
    void setup() throws Exception {
        var data =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:pdf-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(data);
        AdvisorSchema.initialize(data);
        catalog = new KnowledgeCatalog(data);
        structures = catalog.structures();
        index = new MemoryChunkIndex();
    }

    @Test
    void oneFailedPdfPageFailsTheWholeDocumentWithoutPublishingEvidence() throws Exception {
        var library = catalog.create("owner", "Mixed", "");
        var fixture = new PdfDocumentTest();
        fixture.root = root;
        Path file = fixture.pdf(true);
        var doc =
                catalog.addDocument(
                        library.libraryId(), "mixed.pdf", file.toString(), Files.size(file));
        try (var ingest = new DocumentIngest(catalog, () -> Optional.of(model), index)) {
            ingest.process(doc.documentId());
        }
        var saved = catalog.findDocument(doc.documentId()).orElseThrow();
        assertEquals("failed", saved.status());
        assertEquals(2, saved.pageCount());
        assertEquals(2, saved.processedPages());
        assertEquals(1, saved.failedPages());
        assertEquals(0, catalog.readyCount(library.libraryId()));
        assertEquals(1, structures.page(doc.documentId(), 2, 1).items().size());
        assertEquals(2, structures.page(doc.documentId(), 2, 1).items().get(0).pageNumber());
        assertTrue(index.search(library.libraryId(), new double[] {1, 0}, 10, 0).isEmpty());
        assertTrue(structures.lexical(library.libraryId(), "page", 10).isEmpty());
    }

    @Test
    void failedPageRetryRebuildsIndexWithoutDuplicatingSuccessfulPages() throws Exception {
        var library = catalog.create("owner", "Retry", "");
        var fixture = new PdfDocumentTest();
        fixture.root = root;
        Path file = fixture.pdf(true);
        var doc =
                catalog.addDocument(
                        library.libraryId(), "mixed.pdf", file.toString(), Files.size(file));
        try (var ingest = new DocumentIngest(catalog, () -> Optional.of(model), index)) {
            ingest.process(doc.documentId());
        }
        PdfParser.Vision vision =
                new PdfParser.Vision() {
                    public boolean available() {
                        return true;
                    }

                    public PdfContent.Page parse(byte[] image, PdfContent.Page page) {
                        return new PdfContent.Page(
                                2,
                                null,
                                page.width(),
                                page.height(),
                                "ready",
                                "vision",
                                "",
                                List.of(
                                        new PdfContent.Block(
                                                "b0", "paragraph", "识别金额 12.50 元", null, false)),
                                List.of("模型转录"));
                    }
                };
        assertTrue(catalog.requestReprocess(doc.documentId()));
        assertFalse(catalog.requestReprocess(doc.documentId()));
        try (var ingest =
                new DocumentIngest(
                        catalog,
                        () -> Optional.of(model),
                        index,
                        new PdfParser(vision, 10, 1000))) {
            ingest.process(doc.documentId(), true);
        }
        assertEquals("ready", catalog.findDocument(doc.documentId()).orElseThrow().status());
        assertEquals(2, structures.pages(doc.documentId()).size());
        assertEquals(2, index.search(library.libraryId(), new double[] {1, 0}, 10, 0).size());
        assertEquals(1, structures.lexical(library.libraryId(), "12.50", 10).size());
    }

    @Test
    void deletingDocumentCascadesEvidenceAndFencesLateIndexPublish() throws Exception {
        var library = catalog.create("owner", "Delete", "");
        var doc =
                catalog.addDocument(library.libraryId(), "x.pdf", root.resolve("x").toString(), 1);
        var page =
                new PdfContent.Page(
                        1,
                        null,
                        600,
                        800,
                        "ready",
                        "native",
                        "Evidence",
                        List.of(new PdfContent.Block("b0", "paragraph", "Evidence", null, false)),
                        List.of());
        structures.start(doc.documentId(), 1, true);
        structures.savePage(doc.documentId(), page);
        var chunks = PdfContent.chunks(List.of(page), 200, 0);
        structures.publish(doc.documentId(), library.libraryId(), chunks, "ready", "", () -> {});
        catalog.deleteDocument(doc.documentId());
        assertTrue(structures.pages(doc.documentId()).isEmpty());
        assertTrue(structures.lexical(library.libraryId(), "Evidence", 10).isEmpty());
        var called = new AtomicBoolean();
        structures.publish(
                doc.documentId(), library.libraryId(), chunks, "ready", "", () -> called.set(true));
        assertFalse(called.get());
    }

    @Test
    void keywordMatchCanRecoverChineseAndIdentifiersBelowVectorThreshold() throws Exception {
        var library = catalog.create("owner", "Keywords", "");
        var doc = catalog.addDocument(library.libraryId(), "manual.txt", "unused", 1);
        var chunk = new PdfContent.Chunk("0", "设备合同编号 AB-2026-001，保修期限为三年。", null);
        structures.publish(
                doc.documentId(),
                library.libraryId(),
                List.of(chunk),
                "ready",
                "",
                () ->
                        index.replace(
                                library.libraryId(),
                                2,
                                doc.documentId(),
                                List.of(
                                        new ChunkIndex.Passage(
                                                chunk.text(), new double[] {0, 1}, "0", null))));
        catalog.rememberDimensions(library.libraryId(), 2);
        var search = new KnowledgeSearch(catalog, () -> Optional.of(model), index);
        for (String query : List.of("AB-2026-001", "保修期限是多久")) {
            var hits = search.search(List.of(library.libraryId()), query);
            assertEquals(1, hits.size());
            assertEquals(null, hits.get(0).vectorScore());
            assertEquals("rrf", hits.get(0).rankingMethod());
        }
        assertTrue(structures.lexical(library.libraryId(), "' OR 9=9 --", 10).isEmpty());
    }

    @Test
    void refusesForeignDocumentEvenWhenIndexReturnsItUnderSelectedLibrary() throws Exception {
        var left = catalog.create("owner", "Left", "");
        var right = catalog.create("other", "Right", "");
        var foreign = catalog.addDocument(right.libraryId(), "secret.txt", "unused", 1);
        catalog.mark(foreign.documentId(), "ready", "");
        catalog.rememberDimensions(left.libraryId(), 2);
        index.replace(
                left.libraryId(),
                2,
                foreign.documentId(),
                List.of(new ChunkIndex.Passage("secret", new double[] {1, 0})));
        assertTrue(
                new KnowledgeSearch(catalog, () -> Optional.of(model), index)
                        .search(List.of(left.libraryId()), "secret")
                        .isEmpty());
    }

    @Test
    void neighbourContextKeepsSeparatePhysicalPageSources() throws Exception {
        var library = catalog.create("owner", "Context", "");
        var doc = catalog.addDocument(library.libraryId(), "x.pdf", "unused", 1);
        for (int page = 1; page <= 3; page++)
            structures.savePage(
                    doc.documentId(),
                    new PdfContent.Page(
                            page,
                            null,
                            600,
                            800,
                            "ready",
                            "native",
                            "p" + page,
                            List.of(
                                    new PdfContent.Block(
                                            "b0", "paragraph", "p" + page, null, false)),
                            List.of()));
        structures.publish(
                doc.documentId(),
                library.libraryId(),
                PdfContent.chunks(structures.pages(doc.documentId()), 200, 0),
                "ready",
                "",
                () -> {});
        var context = structures.context(doc.documentId(), "2:b0:0");
        assertEquals(
                List.of(1, 3), context.stream().map(chunk -> chunk.source().pageNumber()).toList());
    }

    @Test
    void enlargedCandidatesRespectRecallLimitAndEmitSourceLinkWithoutContext() throws Exception {
        var library = catalog.create("owner", "Limit", "");
        catalog.updateSettings(library.libraryId(), 200, 20, 1, .2);
        catalog.rememberDimensions(library.libraryId(), 2);
        for (int i = 0; i < 4; i++) {
            var doc =
                    catalog.addDocument(library.libraryId(), "evidence-" + i + ".pdf", "unused", 1);
            var source =
                    new PdfContent.Source(
                            1, null, "b0", "paragraph", null, "native", false, List.of());
            var chunk = new PdfContent.Chunk("1:b0:0", "Relevant evidence", source);
            structures.publish(
                    doc.documentId(),
                    library.libraryId(),
                    List.of(chunk),
                    "ready",
                    "",
                    () ->
                            index.replace(
                                    library.libraryId(),
                                    2,
                                    doc.documentId(),
                                    List.of(
                                            new ChunkIndex.Passage(
                                                    chunk.text(),
                                                    new double[] {1, 0},
                                                    chunk.chunkId(),
                                                    source))));
        }
        var search = new KnowledgeSearch(catalog, () -> Optional.of(model), index);
        assertEquals(1, search.search(List.of(library.libraryId()), "evidence").size());
        catalog.replaceAgentSelection("agent", List.of(library.libraryId()));
        var result =
                new KnowledgeRetrievalTool("agent", search, null)
                        .callAsync(
                                io.agentscope.core.tool.ToolCallParam.builder()
                                        .input(java.util.Map.of("query", "evidence"))
                                        .build())
                        .block();
        assertTrue(
                ((io.agentscope.core.message.TextBlock) result.getOutput().get(0))
                        .getText()
                        .contains("?document="));
    }
}
