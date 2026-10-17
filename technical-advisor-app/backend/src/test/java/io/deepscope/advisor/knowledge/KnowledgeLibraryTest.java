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

import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.core.embedding.EmbeddingModel;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.TextBlock;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
