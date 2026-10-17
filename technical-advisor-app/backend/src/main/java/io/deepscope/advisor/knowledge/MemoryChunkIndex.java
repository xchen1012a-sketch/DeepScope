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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-process index used when a test supplies its own vector space. */
public final class MemoryChunkIndex implements ChunkIndex {

    private final Map<String, List<Stored>> rows = new ConcurrentHashMap<>();

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void replace(
            String libraryId, int dimensions, String documentId, List<Passage> passages) {
        List<Stored> stored = new ArrayList<>();
        for (Passage passage : passages) {
            stored.add(
                    new Stored(
                            documentId,
                            passage.text(),
                            passage.vector(),
                            passage.chunkId() == null
                                    ? Integer.toString(stored.size())
                                    : passage.chunkId(),
                            passage.source()));
        }
        rows.put(key(libraryId, documentId), stored);
    }

    @Override
    public void deleteDocument(String libraryId, String documentId) {
        rows.remove(key(libraryId, documentId));
    }

    @Override
    public void deleteLibrary(String libraryId) {
        String prefix = libraryId + ":";
        rows.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @Override
    public List<Hit> search(String libraryId, double[] query, int limit, double threshold) {
        String prefix = libraryId + ":";
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, List<Stored>> entry : rows.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            for (Stored stored : entry.getValue()) {
                double score = cosine(query, stored.vector());
                if (score >= threshold) {
                    hits.add(
                            new Hit(
                                    stored.documentId(),
                                    stored.text(),
                                    score,
                                    stored.chunkId(),
                                    stored.source()));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        if (hits.size() > limit) {
            return List.copyOf(hits.subList(0, limit));
        }
        return List.copyOf(hits);
    }

    static double cosine(double[] left, double[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        int length = Math.min(left.length, right.length);
        for (int index = 0; index < length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static String key(String libraryId, String documentId) {
        return libraryId + ":" + documentId;
    }

    private record Stored(
            String documentId,
            String text,
            double[] vector,
            String chunkId,
            PdfContent.Source source) {}
}
