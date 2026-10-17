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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.core.embedding.EmbeddingModel;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.TextBlock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import reactor.core.publisher.Mono;

/** A library is searchable only after a real embedding, and only inside its own space. */
class KnowledgeLibraryTest {

    @TempDir Path root;

    private KnowledgeCatalog catalog;
    private MemoryChunkIndex index;
    private DocumentIngest ingest;
    private KnowledgeSearch search;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:knowledge-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(dataSource);
        catalog = new KnowledgeCatalog(dataSource);
        index = new MemoryChunkIndex();
        search = new KnowledgeSearch(catalog, () -> Optional.of(new MarkerModel()), index);
        ingest = new DocumentIngest(catalog, () -> Optional.of(new MarkerModel()), index);
    }

    @Test
    void aMissingVectorModelFailsTheDocumentAndStoresNothing() throws Exception {
        var library = catalog.create("carol", "合同", "");
        Path file = root.resolve("note.md");
        Files.writeString(file, "甲库独有的条款");
        var document =
                catalog.addDocument(
                        library.libraryId(), "note.md", file.toString(), Files.size(file));
        new DocumentIngest(catalog, Optional::empty, index).process(document.documentId());
        assertEquals(
                KnowledgeCatalog.FAILED,
                catalog.findDocument(document.documentId()).orElseThrow().status());
        assertTrue(
                catalog.findDocument(document.documentId())
                        .orElseThrow()
                        .failure()
                        .contains("向量模型"));
        assertTrue(index.search(library.libraryId(), new double[] {1, 0, 0, 0}, 5, 0).isEmpty());
    }

    @Test
    void searchStaysInsideTheLibraryThatHoldsThePassage() throws Exception {
        var left = catalog.create("carol", "甲库", "");
        var right = catalog.create("carol", "乙库", "");
        store(left.libraryId(), "甲库独有的条款");
        store(right.libraryId(), "乙库独有的天气");

        var hits = search.search(java.util.List.of(left.libraryId()), "甲库");
        assertEquals(1, hits.size());
        assertEquals("甲库", hits.get(0).libraryName());
        assertTrue(hits.get(0).passage().contains("条款"));
        assertTrue(search.search(java.util.List.of(right.libraryId()), "甲库").isEmpty());
    }

    @Test
    void uncheckingAnAgentDropsThatLibraryFromTheNextSearch() throws Exception {
        var own = catalog.create("carol", "自选", "");
        var other = catalog.create("carol", "另一库", "");
        store(own.libraryId(), "甲库独有的自选");
        store(other.libraryId(), "乙库独有的另一份材料");
        catalog.replaceAgentSelection(
                "agent-1", java.util.List.of(own.libraryId(), other.libraryId()));

        assertEquals(2, search.scope("agent-1").size());
        catalog.replaceAgentSelection("agent-1", java.util.List.of(own.libraryId()));
        assertEquals(java.util.List.of(own.libraryId()), search.scope("agent-1"));
    }

    @Test
    void crossLibrarySearchAndIngestUsePerLibraryBoundVectorModels() throws Exception {
        class DynamicModelSource implements VectorModelSource {
            String currentEndpointId = "ep-model-a";
            final Map<String, EmbeddingModel> models = new HashMap<>();

            @Override
            public Optional<EmbeddingModel> current() {
                return Optional.ofNullable(models.get(currentEndpointId));
            }

            @Override
            public Optional<String> currentEndpointId() {
                return Optional.ofNullable(currentEndpointId);
            }

            @Override
            public Optional<EmbeddingModel> forEndpoint(String endpointId) {
                return Optional.ofNullable(models.get(endpointId));
            }
        }

        class FixedModel implements EmbeddingModel {
            private final String name;
            private final int dim;
            private final int activeAxis;

            FixedModel(String name, int dim, int activeAxis) {
                this.name = name;
                this.dim = dim;
                this.activeAxis = activeAxis;
            }

            @Override
            public Mono<double[]> embed(ContentBlock block) {
                double[] vec = new double[dim];
                vec[activeAxis] = 1.0;
                return Mono.just(vec);
            }

            @Override
            public String getModelName() {
                return name;
            }

            @Override
            public int getDimensions() {
                return dim;
            }
        }

        var dynSource = new DynamicModelSource();
        dynSource.models.put("ep-model-a", new FixedModel("model-a", 3, 0));
        dynSource.models.put("ep-model-b", new FixedModel("model-b", 5, 4));

        var testIngest = new DocumentIngest(catalog, dynSource, index);
        var testSearch = new KnowledgeSearch(catalog, dynSource, index);

        // 1. Ingest Library A with current = ep-model-a (3 dims)
        var libA = catalog.create("carol", "库A", "");
        Path fileA = root.resolve("docA.md");
        Files.writeString(fileA, "库A的文档内容");
        var docA =
                catalog.addDocument(
                        libA.libraryId(), "docA.md", fileA.toString(), Files.size(fileA));
        testIngest.process(docA.documentId());

        var fetchedA = catalog.find(libA.libraryId()).orElseThrow();
        assertEquals("ep-model-a", fetchedA.embeddingEndpointId());
        assertEquals(3, fetchedA.embeddingDimensions().intValue());

        // 2. Switch current to ep-model-b (5 dims)
        dynSource.currentEndpointId = "ep-model-b";

        // Ingest Library B with current = ep-model-b (5 dims)
        var libB = catalog.create("carol", "库B", "");
        Path fileB = root.resolve("docB.md");
        Files.writeString(fileB, "库B的文档内容");
        var docB =
                catalog.addDocument(
                        libB.libraryId(), "docB.md", fileB.toString(), Files.size(fileB));
        testIngest.process(docB.documentId());

        var fetchedB = catalog.find(libB.libraryId()).orElseThrow();
        assertEquals("ep-model-b", fetchedB.embeddingEndpointId());
        assertEquals(5, fetchedB.embeddingDimensions().intValue());

        // 3. Ingest another document in Library A: it must continue to use Library A's bound model
        // (ep-model-a, 3 dims), NOT current (ep-model-b, 5 dims)
        Path fileA2 = root.resolve("docA2.md");
        Files.writeString(fileA2, "库A的补充文档");
        var docA2 =
                catalog.addDocument(
                        libA.libraryId(), "docA2.md", fileA2.toString(), Files.size(fileA2));
        testIngest.process(docA2.documentId());
        assertEquals(
                KnowledgeCatalog.READY,
                catalog.findDocument(docA2.documentId()).orElseThrow().status());

        // 4. Cross-library search across both libA and libB: each queries using its own model
        var crossHits = testSearch.search(List.of(libA.libraryId(), libB.libraryId()), "任何查询词");
        assertTrue(crossHits.stream().anyMatch(h -> h.libraryName().equals("库A")));
        assertTrue(crossHits.stream().anyMatch(h -> h.libraryName().equals("库B")));
    }

    @Test
    void anUnsupportedFileIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DocumentTexts.passages(root.resolve("picture.png"), "picture.png", 200, 20));
    }

    @Test
    void readsRealBinaryWordParagraphsAndTables() throws Exception {
        Path file = root.resolve("binary.docx");
        try (var word = new org.apache.poi.xwpf.usermodel.XWPFDocument();
                var output = Files.newOutputStream(file)) {
            word.createParagraph().createRun().setText("WORD-PARAGRAPH-7429");
            word.createTable(1, 2).getRow(0).getCell(0).setText("TABLE-9182");
            word.write(output);
        }
        String result = String.join("\n", DocumentTexts.passages(file, "binary.docx", 512, 50));
        assertTrue(result.contains("WORD-PARAGRAPH-7429"));
        assertTrue(result.contains("TABLE-9182"));
    }

    private void store(String libraryId, String text) throws Exception {
        Path file = root.resolve(libraryId + ".md");
        Files.writeString(file, text);
        var document = catalog.addDocument(libraryId, "note.md", file.toString(), Files.size(file));
        ingest.process(document.documentId());
        assertEquals(
                KnowledgeCatalog.READY,
                catalog.findDocument(document.documentId()).orElseThrow().status());
    }

    /** Puts the passage on a fixed axis so two libraries cannot match each other's query. */
    private static final class MarkerModel implements EmbeddingModel {
        @Override
        public Mono<double[]> embed(ContentBlock block) {
            String text = block instanceof TextBlock written ? written.getText() : "";
            double[] vector = new double[4];
            if (text.contains("甲库")) {
                vector[0] = 1;
            } else if (text.contains("乙库")) {
                vector[1] = 1;
            } else {
                vector[2] = 1;
            }
            return Mono.just(vector);
        }

        @Override
        public String getModelName() {
            return "marker";
        }

        @Override
        public int getDimensions() {
            return 4;
        }
    }

    @Test
    void knowledgeRetrievalToolDescriptionContainsContractConstraints() {
        var tool = new KnowledgeRetrievalTool("agent-1", mock(KnowledgeSearch.class), null);
        String desc = tool.getDescription();
        assertTrue(desc.contains("【业务分类：知识库只读检索】"));
        assertTrue(desc.contains("【适用场景】"));
        assertTrue(desc.contains("【禁用场景】"));
        assertTrue(desc.contains("【审核规则】"));
        assertTrue(desc.contains("【执行规范】"));
    }
}
