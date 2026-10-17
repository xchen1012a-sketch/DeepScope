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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Knowledge libraries, their documents, and who may search them. */
public final class KnowledgeCatalog {

    public static final String PARSING = "parsing";
    public static final String READY = "ready";
    public static final String PARTIAL = "partial";
    public static final String FAILED = "failed";
    public static final int DEFAULT_CHUNK = 512;
    public static final int DEFAULT_OVERLAP = 50;
    public static final int DEFAULT_LIMIT = 5;
    public static final double DEFAULT_THRESHOLD = 0.2;

    private final DataSource dataSource;

    public KnowledgeCatalog(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DocumentStructureStore structures() {
        return new DocumentStructureStore(dataSource);
    }

    public boolean requestReprocess(String documentId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE advisor_documents SET status='parsing', failure='',"
                                    + " updated_at=? WHERE document_id=? AND status<>'parsing'")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, documentId);
            return statement.executeUpdate() == 1;
        }
    }

    public static boolean searchable(String status) {
        return READY.equals(status);
    }

    EmbeddingCheckpoint embeddingCheckpoint() {
        return new EmbeddingCheckpoint(dataSource);
    }

    public void embeddingProgress(String id, int done, int total) throws SQLException {
        try (var connection = dataSource.getConnection();
                var update =
                        connection.prepareStatement(
                                "UPDATE advisor_documents SET"
                                    + " embedded_chunks=?,total_chunks=?,stage='embedding',updated_at=?"
                                    + " WHERE document_id=? AND status='parsing'")) {
            update.setInt(1, done);
            update.setInt(2, total);
            update.setLong(3, System.currentTimeMillis());
            update.setString(4, id);
            update.executeUpdate();
        }
    }

    public String stage(String documentId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT stage FROM advisor_documents WHERE document_id=?")) {
            statement.setString(1, documentId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : "";
            }
        }
    }

    public String parseSignature(String documentId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT parse_signature FROM advisor_documents WHERE"
                                        + " document_id=?")) {
            statement.setString(1, documentId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : "";
            }
        }
    }

    public void rememberParseSignature(String documentId, String signature) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE advisor_documents SET parse_signature=? WHERE"
                                        + " document_id=?")) {
            statement.setString(1, signature);
            statement.setString(2, documentId);
            statement.executeUpdate();
        }
    }

    public void progress(String documentId, String stage) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE advisor_documents SET stage=?,updated_at=? WHERE"
                                        + " document_id=? AND status='parsing'")) {
            statement.setString(1, stage);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, documentId);
            statement.executeUpdate();
        }
    }

    public void failProcessing(String documentId, String reason) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE advisor_documents SET status=CASE WHEN EXISTS (SELECT 1"
                                    + " FROM advisor_document_chunks c WHERE"
                                    + " c.document_id=advisor_documents.document_id) THEN 'ready'"
                                    + " ELSE 'failed' END, stage='',failure=?,updated_at=? WHERE"
                                    + " document_id=? AND status='parsing'")) {
            statement.setString(1, reason);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, documentId);
            statement.executeUpdate();
        }
    }

    public LibraryRow create(String ownerUserId, String name, String description)
            throws SQLException {
        String title = name == null ? "" : name.strip();
        if (title.isBlank()) {
            throw new IllegalArgumentException("Knowledge library needs a name");
        }
        if (findByName(ownerUserId, title).isPresent()) {
            throw new IllegalArgumentException("A library with this name already exists");
        }
        String libraryId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO advisor_knowledge_bases (library_id, name,"
                                        + " description, owner_user_id, chunk_size, overlap,"
                                        + " recall_limit, score_threshold, embedding_dimensions,"
                                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                                        + " NULL, ?, ?)")) {
            statement.setString(1, libraryId);
            statement.setString(2, title);
            statement.setString(3, description == null ? "" : description.strip());
            statement.setString(4, ownerUserId);
            statement.setInt(5, DEFAULT_CHUNK);
            statement.setInt(6, DEFAULT_OVERLAP);
            statement.setInt(7, DEFAULT_LIMIT);
            statement.setDouble(8, DEFAULT_THRESHOLD);
            statement.setLong(9, now);
            statement.setLong(10, now);
            statement.executeUpdate();
        }
        return find(libraryId).orElseThrow();
    }

    public List<LibraryRow> list(String userId, boolean admin) throws SQLException {
        return list(userId, admin, null);
    }

    public List<LibraryRow> list(String userId, boolean admin, String query) throws SQLException {
        String sql =
                admin
                        ? "SELECT * FROM advisor_knowledge_bases ORDER BY created_at"
                        : "SELECT * FROM advisor_knowledge_bases WHERE owner_user_id = ? ORDER BY"
                                + " created_at";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        io.deepscope.advisor.db.ListSearch.prepare(
                                connection,
                                sql,
                                query,
                                List.of("name", "description"),
                                (admin ? new Object[0] : new Object[] {userId}))) {
            try (ResultSet rows = statement.executeQuery()) {
                List<LibraryRow> libraries = new ArrayList<>();
                while (rows.next()) {
                    libraries.add(library(rows));
                }
                return libraries;
            }
        }
    }

    public io.deepscope.advisor.db.PageResult<LibraryRow> page(
            String userId, boolean admin, String query, int page, int size) throws SQLException {
        var sql =
                new io.deepscope.advisor.db.JdbcPage.Query(
                                "SELECT * FROM advisor_knowledge_bases", "created_at, library_id")
                        .where(
                                admin ? "1 = 1" : "owner_user_id = ?",
                                admin ? new Object[0] : new Object[] {userId})
                        .search(query, List.of("name", "description"));
        try (var connection = dataSource.getConnection()) {
            return io.deepscope.advisor.db.JdbcPage.fetch(
                    connection,
                    sql,
                    page,
                    size,
                    statement -> {
                        var libraries = new ArrayList<LibraryRow>();
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) libraries.add(library(rows));
                        }
                        return libraries;
                    });
        }
    }

    public io.deepscope.advisor.db.PageResult<DocumentRow> pageDocuments(
            String libraryId, String query, String status, int page, int size) throws SQLException {
        var sql =
                new io.deepscope.advisor.db.JdbcPage.Query(
                                "SELECT * FROM advisor_documents", "created_at, document_id")
                        .equal("library_id", libraryId)
                        .equal("status", status)
                        .search(query, List.of("filename"));
        try (var connection = dataSource.getConnection()) {
            return io.deepscope.advisor.db.JdbcPage.fetch(
                    connection,
                    sql,
                    page,
                    size,
                    statement -> {
                        var documents = new ArrayList<DocumentRow>();
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) documents.add(document(rows));
                        }
                        return documents;
                    });
        }
    }

    public Optional<LibraryRow> find(String libraryId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT * FROM advisor_knowledge_bases WHERE library_id = ?")) {
            statement.setString(1, libraryId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(library(rows)) : Optional.empty();
            }
        }
    }

    public void updateSettings(
            String libraryId, int chunkSize, int overlap, int recallLimit, double threshold)
            throws SQLException {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize || recallLimit <= 0) {
            throw new IllegalArgumentException("Retrieval settings are not usable");
        }
        if (threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("Similarity threshold must be between 0 and 1");
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_knowledge_bases SET chunk_size = ?, overlap = ?,"
                                        + " recall_limit = ?, score_threshold = ?, updated_at = ?"
                                        + " WHERE library_id = ?")) {
            statement.setInt(1, chunkSize);
            statement.setInt(2, overlap);
            statement.setInt(3, recallLimit);
            statement.setDouble(4, threshold);
            statement.setLong(5, System.currentTimeMillis());
            statement.setString(6, libraryId);
            statement.executeUpdate();
        }
    }

    public void rememberDimensions(String libraryId, int dimensions) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_knowledge_bases SET embedding_dimensions = ?,"
                                        + " updated_at = ? WHERE library_id = ? AND"
                                        + " embedding_dimensions IS NULL")) {
            statement.setInt(1, dimensions);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, libraryId);
            statement.executeUpdate();
        }
    }

    public void deleteLibrary(String libraryId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            deleteWhere(
                    connection, "DELETE FROM advisor_documents WHERE library_id = ?", libraryId);
            deleteWhere(
                    connection,
                    "DELETE FROM advisor_agent_libraries WHERE library_id = ?",
                    libraryId);
            deleteWhere(
                    connection,
                    "DELETE FROM advisor_skill_libraries WHERE library_id = ?",
                    libraryId);
            deleteWhere(
                    connection,
                    "DELETE FROM advisor_knowledge_bases WHERE library_id = ?",
                    libraryId);
        }
    }

    public DocumentRow addDocument(
            String libraryId, String filename, String storagePath, long sizeBytes)
            throws SQLException {
        String documentId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO advisor_documents (document_id, library_id, filename,"
                                        + " storage_path, size_bytes, status, failure, created_at,"
                                        + " updated_at) VALUES (?, ?, ?, ?, ?, ?, '', ?, ?)")) {
            statement.setString(1, documentId);
            statement.setString(2, libraryId);
            statement.setString(3, filename);
            statement.setString(4, storagePath);
            statement.setLong(5, sizeBytes);
            statement.setString(6, PARSING);
            statement.setLong(7, now);
            statement.setLong(8, now);
            statement.executeUpdate();
        }
        return findDocument(documentId).orElseThrow();
    }

    public void mark(String documentId, String status, String failure) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_documents SET status = ?, failure = ?, updated_at"
                                        + " = ? WHERE document_id = ?")) {
            statement.setString(1, status);
            statement.setString(2, failure == null ? "" : failure);
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, documentId);
            statement.executeUpdate();
        }
    }

    public int failInterrupted() throws SQLException {
        try (var connection = dataSource.getConnection();
                var delete =
                        connection.prepareStatement(
                                "DELETE FROM advisor_document_chunks WHERE document_id IN (SELECT"
                                        + " document_id FROM advisor_documents WHERE"
                                        + " status='partial')")) {
            delete.executeUpdate();
        }
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "UPDATE advisor_documents SET"
                                        + " status='failed',stage='',failure='旧版部分完成文档需重新处理' WHERE"
                                        + " status='partial'")) {
            statement.executeUpdate();
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE advisor_documents SET stage='',status = ?, failure = ?,"
                                        + " updated_at = ? WHERE status = ?")) {
            statement.setString(1, FAILED);
            statement.setString(2, "处理中断");
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, PARSING);
            return statement.executeUpdate();
        }
    }

    public Optional<DocumentRow> findDocument(String documentId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT * FROM advisor_documents WHERE document_id = ?")) {
            statement.setString(1, documentId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(document(rows)) : Optional.empty();
            }
        }
    }

    public List<DocumentRow> documents(String libraryId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT * FROM advisor_documents WHERE library_id = ? ORDER BY"
                                        + " created_at")) {
            statement.setString(1, libraryId);
            try (ResultSet rows = statement.executeQuery()) {
                List<DocumentRow> documents = new ArrayList<>();
                while (rows.next()) {
                    documents.add(document(rows));
                }
                return documents;
            }
        }
    }

    public void deleteDocument(String documentId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            deleteWhere(
                    connection, "DELETE FROM advisor_documents WHERE document_id = ?", documentId);
        }
    }

    public int readyCount(String libraryId) throws SQLException {
        return countByStatus(libraryId, READY);
    }

    public int parsingCount(String libraryId) throws SQLException {
        return countByStatus(libraryId, PARSING);
    }

    public Map<String, Integer> statusCounts(String libraryId) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT SUM(CASE WHEN status='ready' THEN 1 ELSE 0"
                                        + " END), SUM(CASE WHEN status='parsing' THEN 1 ELSE 0 END)"
                                        + " FROM advisor_documents WHERE library_id=?")) {
            statement.setString(1, libraryId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return Map.of("readyCount", rows.getInt(1), "parsingCount", rows.getInt(2));
            }
        }
    }

    private int countByStatus(String libraryId, String status) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM advisor_documents WHERE library_id = ? AND"
                                        + " status = ?")) {
            statement.setString(1, libraryId);
            statement.setString(2, status);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    public void replaceAgentSelection(String agentId, List<String> libraryIds) throws SQLException {
        replace("advisor_agent_libraries", "agent_id", agentId, libraryIds);
    }

    public List<String> agentSelection(String agentId) throws SQLException {
        return selection(
                "SELECT library_id FROM advisor_agent_libraries WHERE agent_id = ?", agentId);
    }

    public static boolean canView(LibraryRow library, String userId, boolean admin) {
        return admin || library.ownerUserId().equals(userId);
    }

    public static boolean canEdit(LibraryRow library, String userId, boolean admin) {
        return canView(library, userId, admin);
    }

    private Optional<LibraryRow> findByName(String ownerUserId, String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT * FROM advisor_knowledge_bases WHERE owner_user_id = ? AND"
                                        + " name = ?")) {
            statement.setString(1, ownerUserId);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(library(rows)) : Optional.empty();
            }
        }
    }

    private void replace(String table, String ownerColumn, String ownerId, List<String> libraryIds)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete =
                        connection.prepareStatement(
                                "DELETE FROM " + table + " WHERE " + ownerColumn + " = ?")) {
                    delete.setString(1, ownerId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO "
                                        + table
                                        + " ("
                                        + ownerColumn
                                        + ", library_id) VALUES (?, ?)")) {
                    for (String libraryId : libraryIds) {
                        insert.setString(1, ownerId);
                        insert.setString(2, libraryId);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private List<String> selection(String sql, String ownerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rows.next()) {
                    ids.add(rows.getString(1));
                }
                return ids;
            }
        }
    }

    private static void deleteWhere(Connection connection, String sql, String id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private static LibraryRow library(ResultSet rows) throws SQLException {
        int dimensions = rows.getInt("embedding_dimensions");
        boolean dimensionsMissing = rows.wasNull();
        return new LibraryRow(
                rows.getString("library_id"),
                rows.getString("name"),
                rows.getString("description"),
                rows.getString("owner_user_id"),
                rows.getInt("chunk_size"),
                rows.getInt("overlap"),
                rows.getInt("recall_limit"),
                rows.getDouble("score_threshold"),
                dimensionsMissing ? null : dimensions,
                rows.getLong("created_at"),
                rows.getLong("updated_at"));
    }

    private static DocumentRow document(ResultSet rows) throws SQLException {
        return new DocumentRow(
                rows.getString("document_id"),
                rows.getString("library_id"),
                rows.getString("filename"),
                rows.getString("storage_path"),
                rows.getLong("size_bytes"),
                rows.getString("status"),
                rows.getString("failure"),
                rows.getLong("created_at"),
                rows.getLong("updated_at"),
                rows.getInt("page_count"),
                rows.getInt("processed_pages"),
                rows.getInt("failed_pages"),
                rows.getString("stage"),
                rows.getInt("embedded_chunks"),
                rows.getInt("total_chunks"));
    }

    public record LibraryRow(
            String libraryId,
            String name,
            String description,
            String ownerUserId,
            int chunkSize,
            int overlap,
            int recallLimit,
            double scoreThreshold,
            Integer embeddingDimensions,
            long createdAt,
            long updatedAt) {}

    public record DocumentRow(
            String documentId,
            String libraryId,
            String filename,
            String storagePath,
            long sizeBytes,
            String status,
            String failure,
            long createdAt,
            long updatedAt,
            int pageCount,
            int processedPages,
            int failedPages,
            String stage,
            int embeddedChunks,
            int totalChunks) {
        public DocumentRow(
                String documentId,
                String libraryId,
                String filename,
                String storagePath,
                long sizeBytes,
                String status,
                String failure,
                long createdAt,
                long updatedAt,
                int pageCount,
                int processedPages,
                int failedPages,
                String stage) {
            this(
                    documentId,
                    libraryId,
                    filename,
                    storagePath,
                    sizeBytes,
                    status,
                    failure,
                    createdAt,
                    updatedAt,
                    pageCount,
                    processedPages,
                    failedPages,
                    stage,
                    0,
                    0);
        }

        public DocumentRow(
                String documentId,
                String libraryId,
                String filename,
                String storagePath,
                long sizeBytes,
                String status,
                String failure,
                long createdAt,
                long updatedAt) {
            this(
                    documentId,
                    libraryId,
                    filename,
                    storagePath,
                    sizeBytes,
                    status,
                    failure,
                    createdAt,
                    updatedAt,
                    0,
                    0,
                    0,
                    "");
        }
    }
}
