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
import java.util.Optional;

/**
 * The only place a vector model is obtained.
 *
 * <p>Ingestion and search ask this source. The current adapter reads the local embedding
 * configuration. The settings stage can replace the bean without changing callers. The chat model
 * is never used here.
 */
public interface VectorModelSource {

    /** Empty when no vector model is connected. */
    Optional<EmbeddingModel> current();

    /** Resolves an embedding model for a specific saved endpoint ID. Defaults to current(). */
    default Optional<EmbeddingModel> forEndpoint(String endpointId) {
        return current();
    }

    /** Resolves the current default embedding endpoint ID, if saved in catalog. */
    default Optional<String> currentEndpointId() {
        return Optional.empty();
    }

    /** Empty disables reuse for adapters whose configuration identity cannot be established. */
    default String checkpointIdentity(EmbeddingModel model) {
        return model instanceof io.deepscope.advisor.model.KnowledgeEmbeddingModel configured
                ? configured.fingerprint()
                : "";
    }
}
