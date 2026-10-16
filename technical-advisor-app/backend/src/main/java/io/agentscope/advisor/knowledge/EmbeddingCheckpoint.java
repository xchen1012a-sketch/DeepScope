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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** Internal unsearchable vectors, committed independently until the whole document can publish. */
final class EmbeddingCheckpoint {
    private final DataSource data;
    private final DocumentStructureStore structures;
    private final ObjectMapper json = new ObjectMapper();

    EmbeddingCheckpoint(DataSource data) {
        this.data = data;
        this.structures = new DocumentStructureStore(data);
    }

    static String hash(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String contentHash(PdfContent.Chunk chunk) throws Exception {
        return hash(chunk.text() + "\n" + json.writeValueAsString(chunk.source()));
    }

    Map<Integer, double[]> load(
            String id, String fingerprint, List<PdfContent.Chunk> chunks, int dimensions)
            throws Exception {
        if (fingerprint.isEmpty()) return Map.of();
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) positions.put(chunks.get(i).chunkId(), i);
        Map<Integer, double[]> cached = new HashMap<>();
        try (var connection = data.getConnection();
                var query =
                        connection.prepareStatement(
                                "SELECT chunk_id,content_hash,vector_json FROM"
                                    + " advisor_document_embedding_checkpoints WHERE document_id=?"
                                    + " AND fingerprint=?")) {
            query.setString(1, id);
            query.setString(2, fingerprint);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    Integer position = positions.get(rows.getString(1));
                    if (position == null
                            || !contentHash(chunks.get(position)).equals(rows.getString(2)))
                        continue;
                    try {
                        var values = json.readTree(rows.getString(3));
                        if (!values.isArray() || values.size() != dimensions) continue;
                        double[] vector = new double[dimensions];
                        boolean valid = true;
                        for (int i = 0; i < dimensions; i++) {
                            if (!values.get(i).isNumber()
                                    || !Double.isFinite(values.get(i).asDouble())) {
                                valid = false;
                                break;
                            }
                            vector[i] = values.get(i).asDouble();
                        }
                        if (valid) cached.put(position, vector);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                        org.slf4j.LoggerFactory.getLogger(EmbeddingCheckpoint.class)
                                .warn(
                                        "Invalid embedding checkpoint document={} chunk={}",
                                        id,
                                        rows.getString(1));
                    }
                }
            }
        }
        return cached;
    }

    void prepare(String id, String fingerprint) throws Exception {
        structures.locked(
                id,
                connection -> {
                    try (var delete =
                            connection.prepareStatement(
                                    "DELETE FROM advisor_document_embedding_checkpoints WHERE"
                                            + " document_id=? AND fingerprint<>?")) {
                        delete.setString(1, id);
                        delete.setString(2, fingerprint);
                        delete.executeUpdate();
                    }
                });
    }

    void save(String id, String fingerprint, PdfContent.Chunk chunk, double[] vector)
            throws Exception {
        if (fingerprint.isEmpty()) return;
        structures.locked(
                id,
                connection -> {
                    try (var delete =
                            connection.prepareStatement(
                                    "DELETE FROM advisor_document_embedding_checkpoints WHERE"
                                            + " document_id=? AND chunk_id=?")) {
                        delete.setString(1, id);
                        delete.setString(2, chunk.chunkId());
                        delete.executeUpdate();
                    }
                    try (var insert =
                            connection.prepareStatement(
                                    "INSERT INTO"
                                        + " advisor_document_embedding_checkpoints(document_id,chunk_id,fingerprint,content_hash,vector_json)"
                                        + " VALUES(?,?,?,?,?)")) {
                        insert.setString(1, id);
                        insert.setString(2, chunk.chunkId());
                        insert.setString(3, fingerprint);
                        insert.setString(4, contentHash(chunk));
                        insert.setString(5, json.writeValueAsString(vector));
                        insert.executeUpdate();
                    }
                });
    }
}
