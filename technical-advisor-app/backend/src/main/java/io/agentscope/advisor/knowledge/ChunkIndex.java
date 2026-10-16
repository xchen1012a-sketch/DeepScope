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

import java.util.List;

/** One vector space per library. Search never ranks across libraries. */
public interface ChunkIndex {

    boolean available();

    void replace(String libraryId, int dimensions, String documentId, List<Passage> passages);

    default void replace(
            java.sql.Connection connection,
            String libraryId,
            int dimensions,
            String documentId,
            List<Passage> passages)
            throws java.sql.SQLException {
        replace(libraryId, dimensions, documentId, passages);
    }

    void deleteDocument(String libraryId, String documentId);

    void deleteLibrary(String libraryId);

    List<Hit> search(String libraryId, double[] query, int limit, double threshold);

    record Passage(String text, double[] vector, String chunkId, PdfContent.Source source) {
        public Passage(String text, double[] vector) {
            this(text, vector, null, null);
        }
    }

    record Hit(
            String documentId,
            String text,
            double score,
            String chunkId,
            PdfContent.Source source) {
        public Hit(String documentId, String text, double score) {
            this(documentId, text, score, null, null);
        }
    }
}
