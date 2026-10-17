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

import io.deepscope.core.embedding.EmbeddingModel;
import io.deepscope.core.message.TextBlock;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scoped vector/keyword fusion. A ranking model cannot rewrite source evidence. */
public final class KnowledgeSearch {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearch.class);
    private final KnowledgeCatalog catalog;
    private final VectorModelSource models;
    private final ChunkIndex index;
    private final DocumentStructureStore structures;
    private final PdfModelReader pdfModels;
    private final PdfParser pdf;

    public KnowledgeSearch(KnowledgeCatalog catalog, VectorModelSource models, ChunkIndex index) {
        this(catalog, models, index, null, null);
    }

    public KnowledgeSearch(
            KnowledgeCatalog catalog,
            VectorModelSource models,
            ChunkIndex index,
            PdfModelReader pdfModels,
            PdfParser pdf) {
        this.catalog = catalog;
        this.models = models;
        this.index = index;
        this.structures = catalog.structures();
        this.pdfModels = pdfModels;
        this.pdf = pdf;
    }

    public List<String> scope(String agentId) throws SQLException {
        List<String> libraryIds = new ArrayList<>();
        for (String libraryId : catalog.agentSelection(agentId)) {
            if (catalog.readyCount(libraryId) > 0) libraryIds.add(libraryId);
        }
        return libraryIds;
    }

    public List<Found> search(List<String> libraryIds, String query) throws SQLException {
        if (libraryIds == null || libraryIds.isEmpty()) return List.of();
        if (!index.available()) throw new IllegalStateException("数据库还没有启用向量扩展");
        EmbeddingModel model =
                models.current().orElseThrow(() -> new IllegalStateException("向量模型还没有接入"));
        String text = query == null ? "" : query.strip();
        if (text.isBlank() || text.length() > 2000)
            throw new IllegalArgumentException("检索内容为空或超过 2000 字");
        double[] vector =
                model.embed(TextBlock.builder().text(text).build()).block(Duration.ofSeconds(90));
        if (vector == null || vector.length != model.getDimensions())
            throw new IllegalStateException("向量化没有完成");
        List<Found> results = new ArrayList<>();
        int limit = 0;
        for (String libraryId : libraryIds.stream().distinct().toList()) {
            var library = catalog.find(libraryId).orElse(null);
            if (library == null || library.embeddingDimensions() == null) continue;
            if (library.embeddingDimensions() != model.getDimensions())
                throw new IllegalStateException("当前向量模型维度与知识库不一致");
            limit = Math.max(limit, library.recallLimit());
            int candidates = Math.min(100, library.recallLimit() * 4);
            Map<String, Candidate> merged = new LinkedHashMap<>();
            merge(
                    merged,
                    index.search(libraryId, vector, candidates, library.scoreThreshold()),
                    true);
            merge(merged, structures.lexical(libraryId, text, candidates), false);
            List<Found> libraryResults = new ArrayList<>();
            for (Candidate candidate : merged.values()) {
                var document = catalog.findDocument(candidate.hit.documentId()).orElse(null);
                if (document == null
                        || !libraryId.equals(document.libraryId())
                        || !KnowledgeCatalog.searchable(document.status())) continue;
                var hit = candidate.hit;
                List<String> warnings =
                        new ArrayList<>(hit.source() == null ? List.of() : hit.source().warnings());
                if (KnowledgeCatalog.PARTIAL.equals(document.status()))
                    warnings.add("此文档部分页面未完成识别，请勿据此判断整篇没有相关内容");
                libraryResults.add(
                        new Found(
                                library.name(),
                                document.filename(),
                                hit.text(),
                                candidate.rank,
                                libraryId,
                                document.documentId(),
                                hit.source(),
                                structures.context(document.documentId(), hit.chunkId()),
                                candidate.vectorScore,
                                "rrf",
                                null,
                                warnings));
            }
            libraryResults.sort(Comparator.comparingDouble(Found::score).reversed());
            results.addAll(
                    libraryResults.subList(
                            0, Math.min(library.recallLimit(), libraryResults.size())));
        }
        results.sort(Comparator.comparingDouble(Found::score).reversed());
        if (results.size() > Math.min(20, limit))
            results = new ArrayList<>(results.subList(0, Math.min(20, limit)));
        return List.copyOf(rerank(text, results));
    }

    private static void merge(
            Map<String, Candidate> merged, List<ChunkIndex.Hit> hits, boolean vector) {
        int rank = 0;
        var seen = new java.util.HashSet<String>();
        for (var hit : hits) {
            String key =
                    hit.documentId() + ":" + (hit.chunkId() == null ? hit.text() : hit.chunkId());
            if (!seen.add(key)) continue;
            Candidate candidate = merged.computeIfAbsent(key, ignored -> new Candidate(hit));
            candidate.rank += 1.0 / (60 + ++rank);
            if (vector) candidate.vectorScore = hit.score();
        }
    }

    private List<Found> rerank(String query, List<Found> results) {
        if (pdfModels == null || results.isEmpty()) return results;
        try {
            List<String> order =
                    pdfModels.rerank(query, results.stream().map(Found::passage).toList());
            if (order.isEmpty()) return results;
            return order.stream()
                    .map(id -> results.get(Integer.parseInt(id)).withRanking("model-rerank"))
                    .toList();
        } catch (Exception error) {
            log.warn("Knowledge reranking unavailable ({})", error.getClass().getSimpleName());
            return results;
        }
    }

    /** Opt-in page inspection: normal searches do not incur a second vision request. */
    public String verifyPage(String libraryId, String documentId, int pageNumber, String query)
            throws Exception {
        if (pdfModels == null || pdf == null || !pdfModels.available())
            throw new IllegalStateException("未配置 PDF 视觉模型");
        var document =
                catalog.findDocument(documentId)
                        .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        if (!libraryId.equals(document.libraryId())
                || structures.find(documentId, pageNumber).isEmpty())
            throw new IllegalArgumentException("页面不存在");
        if (query == null || query.isBlank() || query.length() > 2000)
            throw new IllegalArgumentException("问题为空或超限");
        return pdfModels.verify(query, pdf.image(Path.of(document.storagePath()), pageNumber));
    }

    private static final class Candidate {
        final ChunkIndex.Hit hit;
        double rank;
        Double vectorScore;

        Candidate(ChunkIndex.Hit hit) {
            this.hit = hit;
        }
    }

    public record Found(
            String libraryName,
            String filename,
            String passage,
            double score,
            String libraryId,
            String documentId,
            PdfContent.Source source,
            List<PdfContent.Chunk> context,
            Double vectorScore,
            String rankingMethod,
            String verification,
            List<String> warnings) {
        public Found(String libraryName, String filename, String passage, double score) {
            this(
                    libraryName,
                    filename,
                    passage,
                    score,
                    null,
                    null,
                    null,
                    List.of(),
                    score,
                    "vector",
                    null,
                    List.of());
        }

        Found withRanking(String method) {
            return new Found(
                    libraryName,
                    filename,
                    passage,
                    score,
                    libraryId,
                    documentId,
                    source,
                    context,
                    vectorScore,
                    method,
                    verification,
                    warnings);
        }
    }
}
