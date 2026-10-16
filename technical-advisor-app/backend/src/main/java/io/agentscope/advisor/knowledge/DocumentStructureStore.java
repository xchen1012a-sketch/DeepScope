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
package io.agentscope.advisor.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.advisor.db.JdbcPage;
import io.agentscope.advisor.db.PageResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** Parsed evidence is durable independently of the vectors; document locks fence deletion. */
public final class DocumentStructureStore {
    @FunctionalInterface
    interface Work {
        void run(Connection connection) throws Exception;
    }

    private final DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();

    public DocumentStructureStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    boolean locked(String documentId, Work work) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement =
                        connection.prepareStatement(
                                "SELECT document_id FROM advisor_documents WHERE document_id=? FOR"
                                        + " UPDATE")) {
                    statement.setString(1, documentId);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                work.run(connection);
                connection.commit();
                return true;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public void start(String documentId, int count, boolean reset) throws Exception {
        locked(
                documentId,
                connection -> {
                    if (reset)
                        execute(
                                connection,
                                "DELETE FROM advisor_document_pages WHERE document_id=?",
                                documentId);
                    try (var update =
                            connection.prepareStatement(
                                    "UPDATE advisor_documents SET page_count=?, processed_pages=0,"
                                            + " failed_pages=0 WHERE document_id=?")) {
                        update.setInt(1, count);
                        update.setString(2, documentId);
                        update.executeUpdate();
                    }
                });
    }

    public void savePage(String documentId, PdfContent.Page page) throws Exception {
        locked(
                documentId,
                connection -> {
                    try (var delete =
                            connection.prepareStatement(
                                    "DELETE FROM advisor_document_pages WHERE document_id=? AND"
                                            + " page_number=?")) {
                        delete.setString(1, documentId);
                        delete.setInt(2, page.pageNumber());
                        delete.executeUpdate();
                    }
                    try (var insert =
                            connection.prepareStatement(
                                    "INSERT INTO"
                                        + " advisor_document_pages(document_id,page_number,page_json)"
                                        + " VALUES(?,?,?)")) {
                        insert.setString(1, documentId);
                        insert.setInt(2, page.pageNumber());
                        insert.setString(3, json.writeValueAsString(page));
                        insert.executeUpdate();
                    }
                    List<PdfContent.Page> pages = readPages(connection, documentId);
                    try (var update =
                            connection.prepareStatement(
                                    "UPDATE advisor_documents SET processed_pages=?,failed_pages=?"
                                            + " WHERE document_id=?")) {
                        update.setInt(1, pages.size());
                        update.setInt(
                                2,
                                (int) pages.stream().filter(PdfContent.Page::needsRetry).count());
                        update.setString(3, documentId);
                        update.executeUpdate();
                    }
                });
    }

    public List<PdfContent.Page> pages(String documentId) throws Exception {
        try (var connection = dataSource.getConnection()) {
            return readPages(connection, documentId);
        }
    }

    public record Passage(String chunkId, String text) {}

    public PageResult<Passage> chunks(String documentId, int page, int size) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return JdbcPage.fetch(
                    connection,
                    new JdbcPage.Query(
                                    "SELECT chunk_id,content FROM advisor_document_chunks",
                                    "LENGTH(chunk_id),chunk_id")
                            .where("document_id=?", documentId),
                    page,
                    size,
                    statement -> {
                        var passages = new ArrayList<Passage>();
                        try (var rows = statement.executeQuery()) {
                            while (rows.next())
                                passages.add(new Passage(rows.getString(1), rows.getString(2)));
                        }
                        return passages;
                    });
        }
    }

    public PageResult<PdfContent.Page> page(String documentId, int page, int size)
            throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return JdbcPage.fetch(
                    connection,
                    new JdbcPage.Query(
                                    "SELECT page_json FROM advisor_document_pages", "page_number")
                            .where("document_id=?", documentId),
                    page,
                    size,
                    statement -> {
                        var pages = new ArrayList<PdfContent.Page>();
                        try (var rows = statement.executeQuery()) {
                            while (rows.next())
                                pages.add(decode(rows.getString(1), PdfContent.Page.class));
                        }
                        return pages;
                    });
        }
    }

    public Optional<PdfContent.Page> find(String documentId, int number) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT page_json FROM advisor_document_pages WHERE document_id=?"
                                        + " AND page_number=?")) {
            statement.setString(1, documentId);
            statement.setInt(2, number);
            try (var rows = statement.executeQuery()) {
                return rows.next()
                        ? Optional.of(decode(rows.getString(1), PdfContent.Page.class))
                        : Optional.empty();
            }
        }
    }

    void publish(
            String documentId,
            String libraryId,
            List<PdfContent.Chunk> chunks,
            String status,
            String failure,
            Runnable vectors)
            throws Exception {
        publish(documentId, libraryId, chunks, status, failure, connection -> vectors.run());
    }

    void publishAtomic(
            String documentId,
            String libraryId,
            List<PdfContent.Chunk> chunks,
            ChunkIndex index,
            int dimensions,
            List<ChunkIndex.Passage> embedded)
            throws Exception {
        publish(
                documentId,
                libraryId,
                chunks,
                KnowledgeCatalog.READY,
                "",
                connection -> {
                    if (Thread.currentThread().isInterrupted())
                        throw new InterruptedException("文档处理已取消");
                    try (var library =
                            connection.prepareStatement(
                                    "SELECT embedding_dimensions FROM advisor_knowledge_bases WHERE"
                                            + " library_id=? FOR UPDATE")) {
                        library.setString(1, libraryId);
                        try (var row = library.executeQuery()) {
                            if (!row.next()) throw new IllegalStateException("知识库已经不存在");
                            Integer previous = row.getObject(1, Integer.class);
                            if (previous != null && previous != dimensions)
                                throw new IllegalStateException("向量维度与知识库不一致");
                        }
                    }
                    try (var active =
                            connection.prepareStatement(
                                    "SELECT status FROM advisor_documents WHERE document_id=?")) {
                        active.setString(1, documentId);
                        try (var row = active.executeQuery()) {
                            if (!row.next() || !KnowledgeCatalog.PARSING.equals(row.getString(1)))
                                throw new IllegalStateException("处理任务已经结束");
                        }
                    }
                    index.replace(connection, libraryId, dimensions, documentId, embedded);
                    execute(
                            connection,
                            "DELETE FROM advisor_document_embedding_checkpoints WHERE"
                                    + " document_id=?",
                            documentId);
                    if (Thread.currentThread().isInterrupted())
                        throw new InterruptedException("文档处理已取消");
                    try (var update =
                            connection.prepareStatement(
                                    "UPDATE advisor_knowledge_bases SET embedding_dimensions=?"
                                            + " WHERE library_id=?")) {
                        update.setInt(1, dimensions);
                        update.setString(2, libraryId);
                        update.executeUpdate();
                    }
                });
    }

    private void publish(
            String documentId,
            String libraryId,
            List<PdfContent.Chunk> chunks,
            String status,
            String failure,
            Work vectors)
            throws Exception {
        locked(
                documentId,
                connection -> {
                    vectors.run(connection);
                    execute(
                            connection,
                            "DELETE FROM advisor_document_published_pages WHERE document_id=?",
                            documentId);
                    try (var copy =
                            connection.prepareStatement(
                                    "INSERT INTO"
                                        + " advisor_document_published_pages(document_id,page_number,page_json)"
                                        + " SELECT document_id,page_number,page_json FROM"
                                        + " advisor_document_pages WHERE document_id=?")) {
                        copy.setString(1, documentId);
                        copy.executeUpdate();
                    }
                    execute(
                            connection,
                            "DELETE FROM advisor_document_chunks WHERE document_id=?",
                            documentId);
                    try (var insert =
                            connection.prepareStatement(
                                    "INSERT INTO"
                                        + " advisor_document_chunks(document_id,chunk_id,library_id,content,search_text,source_json)"
                                        + " VALUES(?,?,?,?,?,?)")) {
                        for (var chunk : chunks) {
                            insert.setString(1, documentId);
                            insert.setString(2, chunk.chunkId());
                            insert.setString(3, libraryId);
                            insert.setString(4, chunk.text());
                            insert.setString(5, KnowledgeTerms.normalize(chunk.text()));
                            insert.setString(6, json.writeValueAsString(chunk.source()));
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                    try (var update =
                            connection.prepareStatement(
                                    "UPDATE advisor_documents SET"
                                            + " stage='',status=?,failure=?,updated_at=? WHERE"
                                            + " document_id=?")) {
                        update.setString(1, status);
                        update.setString(2, failure);
                        update.setLong(3, System.currentTimeMillis());
                        update.setString(4, documentId);
                        update.executeUpdate();
                    }
                });
    }

    public List<ChunkIndex.Hit> lexical(String libraryId, String query, int limit)
            throws SQLException {
        List<String> terms = KnowledgeTerms.of(query);
        if (terms.isEmpty()) return List.of();
        String score =
                String.join(
                        "+",
                        terms.stream()
                                .map(
                                        ignored ->
                                                "CASE WHEN POSITION(? IN c.search_text)>0 THEN 1"
                                                        + " ELSE 0 END")
                                .toList());
        String sql =
                "SELECT * FROM (SELECT c.*, ("
                        + score
                        + ") AS relevance FROM advisor_document_chunks c JOIN advisor_documents d"
                        + " ON c.document_id=d.document_id AND c.library_id=d.library_id WHERE"
                        + " c.library_id=? AND d.status='ready') ranked WHERE"
                        + " relevance>0 ORDER BY relevance DESC,document_id,chunk_id LIMIT ?";
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (String term : terms) statement.setString(parameter++, term);
            statement.setString(parameter++, libraryId);
            statement.setInt(parameter, Math.min(100, Math.max(1, limit)));
            var hits = new ArrayList<ChunkIndex.Hit>();
            try (var rows = statement.executeQuery()) {
                while (rows.next())
                    hits.add(
                            new ChunkIndex.Hit(
                                    rows.getString("document_id"),
                                    rows.getString("content"),
                                    rows.getDouble("relevance"),
                                    rows.getString("chunk_id"),
                                    decode(
                                            rows.getString("source_json"),
                                            PdfContent.Source.class)));
            }
            return hits;
        }
    }

    public List<PdfContent.Chunk> context(String documentId, String chunkId) throws SQLException {
        // The same physical page and its immediate neighbours are fetched as page records, not the
        // whole book.
        if (chunkId == null || !chunkId.matches("\\d+:b\\d+:\\d+")) return List.of();
        int page = Integer.parseInt(chunkId.split(":")[0]);
        List<PdfContent.Chunk> candidates = new ArrayList<>();
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT page_json FROM advisor_document_published_pages WHERE"
                                        + " document_id=? AND page_number BETWEEN ? AND ? ORDER BY"
                                        + " page_number")) {
            statement.setString(1, documentId);
            statement.setInt(2, Math.max(1, page - 1));
            statement.setInt(3, page + 1);
            List<PdfContent.Page> pages = new ArrayList<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) pages.add(decode(rows.getString(1), PdfContent.Page.class));
            }
            // Context retains whole neighbour blocks and never changes the retrieved passage.
            for (var item : pages)
                if (!"failed".equals(item.status()))
                    for (var block : item.blocks()) {
                        if (!"header".equals(block.type()) && !"footer".equals(block.type())) {
                            var source =
                                    new PdfContent.Source(
                                            item.pageNumber(),
                                            item.pageLabel(),
                                            block.id(),
                                            block.type(),
                                            block.box(),
                                            item.method(),
                                            block.interpretation(),
                                            item.warnings());
                            candidates.add(
                                    new PdfContent.Chunk(
                                            item.pageNumber() + ":" + block.id(),
                                            block.text()
                                                    .substring(
                                                            0,
                                                            Math.min(1200, block.text().length())),
                                            source));
                        }
                    }
        }
        String blockKey = chunkId.substring(0, chunkId.lastIndexOf(':'));
        for (int i = 0; i < candidates.size(); i++)
            if (blockKey.equals(candidates.get(i).chunkId())) {
                List<PdfContent.Chunk> neighbours = new ArrayList<>();
                if (i > 0) neighbours.add(candidates.get(i - 1));
                if (i + 1 < candidates.size()) neighbours.add(candidates.get(i + 1));
                return List.copyOf(neighbours);
            }
        return List.of();
    }

    private List<PdfContent.Page> readPages(Connection connection, String documentId)
            throws SQLException {
        try (var statement =
                connection.prepareStatement(
                        "SELECT page_json FROM advisor_document_pages WHERE document_id=? ORDER BY"
                                + " page_number")) {
            statement.setString(1, documentId);
            List<PdfContent.Page> pages = new ArrayList<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) pages.add(decode(rows.getString(1), PdfContent.Page.class));
            }
            return List.copyOf(pages);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception error) {
            throw new IllegalStateException("已保存的解析结构无法读取", error);
        }
    }

    private static void execute(Connection connection, String sql, String documentId)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, documentId);
            statement.executeUpdate();
        }
    }
}
