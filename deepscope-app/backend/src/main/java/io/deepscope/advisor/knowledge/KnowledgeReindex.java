/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.deepscope.advisor.knowledge;

import io.deepscope.advisor.model.ModelCatalog;
import java.util.ArrayList;
import java.util.List;

/** Durable in-place rebuild intent and transactional reset, followed by bounded dispatch. */
final class KnowledgeReindex {
    static final class Busy extends IllegalStateException {
        Busy() {
            super("知识库正在处理或重建，请完成后再更换模型");
        }
    }

    record Result(
            int affectedDocuments,
            String embeddingEndpointId,
            int embeddingDimensions,
            boolean rebuilding) {}

    private final KnowledgeCatalog catalog;
    private final ModelCatalog models;
    private final ChunkIndex index;
    private final DocumentIngest ingest;

    KnowledgeReindex(
            KnowledgeCatalog catalog,
            ModelCatalog models,
            ChunkIndex index,
            DocumentIngest ingest) {
        this.catalog = catalog;
        this.models = models;
        this.index = index;
        this.ingest = ingest;
    }

    Result change(String libraryId, String endpointId) throws Exception {
        if (models == null || endpointId == null || endpointId.isBlank())
            throw new IllegalArgumentException("请选择有效向量模型");
        List<String> documents =
                catalog.libraryTransaction(
                        libraryId,
                        connection -> {
                            int dimensions;
                            try (var selected =
                                    connection.prepareStatement(
                                            "SELECT kind,dimensions FROM advisor_model_endpoints"
                                                    + " WHERE endpoint_id=? FOR UPDATE")) {
                                selected.setString(1, endpointId);
                                try (var rows = selected.executeQuery()) {
                                    if (!rows.next()
                                            || !ModelCatalog.EMBEDDING.equals(rows.getString(1))
                                            || rows.getInt(2) < 1
                                            || rows.getInt(2) > 65536)
                                        throw new IllegalArgumentException("所选模型不存在、不是向量模型或维度无效");
                                    dimensions = rows.getInt(2);
                                }
                            }
                            String bound;
                            String state;
                            try (var current =
                                    connection.prepareStatement(
                                            "SELECT embedding_endpoint_id,index_state FROM"
                                                + " advisor_knowledge_bases WHERE library_id=?")) {
                                current.setString(1, libraryId);
                                try (var rows = current.executeQuery()) {
                                    rows.next();
                                    bound = rows.getString(1);
                                    state = rows.getString(2);
                                }
                            }
                            List<String> ids = new ArrayList<>();
                            try (var query =
                                    connection.prepareStatement(
                                            "SELECT document_id,status FROM advisor_documents WHERE"
                                                    + " library_id=? ORDER BY document_id")) {
                                query.setString(1, libraryId);
                                try (var rows = query.executeQuery()) {
                                    while (rows.next()) {
                                        String id = rows.getString(1);
                                        if (KnowledgeCatalog.PARSING.equals(rows.getString(2))
                                                || ingest.isActive(id)) throw new Busy();
                                        ids.add(id);
                                    }
                                }
                            }
                            if (endpointId.equals(bound) && !"rebuilding".equals(state))
                                return List.of();
                            try (var clear =
                                    connection.prepareStatement(
                                            "DELETE FROM advisor_document_embedding_checkpoints"
                                                + " WHERE document_id IN (SELECT document_id FROM"
                                                + " advisor_documents WHERE library_id=?)")) {
                                clear.setString(1, libraryId);
                                clear.executeUpdate();
                            }
                            try (var update =
                                    connection.prepareStatement(
                                            "UPDATE advisor_knowledge_bases SET"
                                                + " embedding_endpoint_id=?,embedding_dimensions=?,index_state=?,updated_at=?"
                                                + " WHERE library_id=?")) {
                                update.setString(1, endpointId);
                                update.setInt(2, dimensions);
                                update.setString(3, ids.isEmpty() ? "ready" : "rebuilding");
                                update.setLong(4, System.currentTimeMillis());
                                update.setString(5, libraryId);
                                update.executeUpdate();
                            }
                            try (var update =
                                    connection.prepareStatement(
                                            "UPDATE advisor_documents SET"
                                                + " status='parsing',stage='queued',failure='',embedded_chunks=0,total_chunks=0,updated_at=?"
                                                + " WHERE library_id=?")) {
                                update.setLong(1, System.currentTimeMillis());
                                update.setString(2, libraryId);
                                update.executeUpdate();
                            }
                            index.reset(connection, libraryId);
                            return ids;
                        });
        if (!documents.isEmpty()) ingest.submitRebuild(documents);
        var library = catalog.find(libraryId).orElseThrow();
        return new Result(
                documents.size(), endpointId, library.embeddingDimensions(), library.rebuilding());
    }
}
