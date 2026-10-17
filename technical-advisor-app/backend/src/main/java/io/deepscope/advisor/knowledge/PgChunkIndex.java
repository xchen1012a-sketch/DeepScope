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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.rag.model.Document;
import io.deepscope.core.rag.model.DocumentMetadata;
import io.deepscope.core.rag.store.PgVectorStore;
import io.deepscope.core.rag.store.dto.SearchDocumentDto;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/** One pgvector table per library, using the framework store. */
public final class PgChunkIndex implements ChunkIndex, AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Map<String, PgVectorStore> stores = new ConcurrentHashMap<>();

    public PgChunkIndex(DataSource dataSource, String jdbcUrl, String username, String password) {
        this.dataSource = dataSource;
        this.jdbcUrl = jdbcUrl;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    @Override
    public boolean available() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT 1 FROM pg_extension WHERE extname = 'vector'")) {
            return rows.next();
        } catch (SQLException unavailable) {
            return false;
        }
    }

    @Override
    public void replace(
            String libraryId, int dimensions, String documentId, List<Passage> passages) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                replace(connection, libraryId, dimensions, documentId, passages);
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        } catch (Exception error) {
            throw new IllegalStateException("向量索引写入失败", error);
        }
    }

    @Override
    public void replace(
            Connection connection,
            String libraryId,
            int dimensions,
            String documentId,
            List<Passage> passages)
            throws SQLException {
        if (dimensions < 1 || dimensions > 65536) throw new IllegalArgumentException("向量维度无效");
        try (var create = connection.createStatement()) {
            create.setQueryTimeout(15);
            create.execute(
                    "CREATE TABLE IF NOT EXISTS "
                            + table(libraryId)
                            + " (id VARCHAR(64) PRIMARY KEY, embedding vector("
                            + dimensions
                            + "),doc_id VARCHAR(256),chunk_id VARCHAR(256),content TEXT,payload"
                            + " JSONB)");
        }
        try (var delete =
                connection.prepareStatement(
                        "DELETE FROM " + table(libraryId) + " WHERE doc_id=?")) {
            delete.setQueryTimeout(15);
            delete.setString(1, documentId);
            delete.executeUpdate();
        }
        try (var insert =
                connection.prepareStatement(
                        "INSERT INTO "
                                + table(libraryId)
                                + " (id,embedding,doc_id,chunk_id,content,payload)"
                                + " VALUES(?,?::vector,?,?,?,?::jsonb)")) {
            insert.setQueryTimeout(15);
            int index = 0;
            for (Passage passage : passages) {
                if (passage.vector().length != dimensions)
                    throw new IllegalArgumentException("向量维度不匹配");
                for (double number : passage.vector())
                    if (!Double.isFinite(number)) throw new IllegalArgumentException("向量数值无效");
                insert.setString(1, java.util.UUID.randomUUID().toString());
                insert.setString(2, java.util.Arrays.toString(passage.vector()));
                insert.setString(3, documentId);
                insert.setString(
                        4,
                        passage.chunkId() == null ? Integer.toString(index++) : passage.chunkId());
                insert.setString(
                        5,
                        io.deepscope.core.util.JsonUtils.getJsonCodec()
                                .toJson(TextBlock.builder().text(passage.text()).build()));
                try {
                    insert.setString(
                            6,
                            passage.source() == null
                                    ? "{}"
                                    : JSON.writeValueAsString(Map.of("source", passage.source())));
                } catch (java.io.IOException error) {
                    throw new SQLException("来源无法写入", error);
                }
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    @Override
    public void deleteDocument(String libraryId, String documentId) {
        existing(libraryId).delete(documentId).block();
    }

    @Override
    public void deleteLibrary(String libraryId) {
        PgVectorStore store = stores.remove(table(libraryId));
        if (store != null) {
            store.close();
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + table(libraryId));
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public List<Hit> search(String libraryId, double[] query, int limit, double threshold) {
        PgVectorStore store = existing(libraryId);
        List<Document> found =
                store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(query)
                                        .limit(limit)
                                        .scoreThreshold(threshold)
                                        .build())
                        .block();
        List<Hit> hits = new ArrayList<>();
        if (found != null) {
            for (Document document : found) {
                hits.add(
                        new Hit(
                                document.getMetadata().getDocId(),
                                document.getMetadata().getContentText(),
                                document.getScore() == null ? 0 : document.getScore(),
                                document.getMetadata().getChunkId(),
                                source(document.getMetadata())));
            }
        }
        return hits;
    }

    private static PdfContent.Source source(DocumentMetadata metadata) {
        Object value = metadata.getPayload() == null ? null : metadata.getPayload().get("source");
        return value == null ? null : JSON.convertValue(value, PdfContent.Source.class);
    }

    private PgVectorStore store(String libraryId, int dimensions) {
        return stores.computeIfAbsent(
                table(libraryId),
                name -> {
                    try {
                        return PgVectorStore.builder()
                                .jdbcUrl(jdbcUrl)
                                .username(username)
                                .password(password)
                                .tableName(name)
                                .dimensions(dimensions)
                                .build();
                    } catch (io.deepscope.core.rag.exception.VectorStoreException error) {
                        throw new IllegalStateException(error);
                    }
                });
    }

    private PgVectorStore existing(String libraryId) {
        String table = table(libraryId);
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT embedding_dimensions FROM advisor_knowledge_bases WHERE"
                                        + " library_id = ?")) {
            statement.setString(1, libraryId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getObject(1) == null)
                    throw new IllegalStateException("Knowledge index dimensions unavailable");
                int dimensions = rows.getInt(1);
                try (var tables =
                        connection
                                .getMetaData()
                                .getTables(null, "public", table, new String[] {"TABLE"})) {
                    if (!tables.next())
                        throw new IllegalStateException("Knowledge index table unavailable");
                }
                return store(libraryId, dimensions);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Knowledge index unavailable", error);
        }
    }

    @Override
    public void close() {
        stores.values().forEach(PgVectorStore::close);
        stores.clear();
    }

    static String table(String libraryId) {
        String compact = libraryId.replace("-", "").toLowerCase(Locale.ROOT);
        if (!compact.matches("[a-z0-9]+")) {
            throw new IllegalArgumentException("Library id cannot name a vector table");
        }
        return "advisor_kb_" + compact;
    }
}
