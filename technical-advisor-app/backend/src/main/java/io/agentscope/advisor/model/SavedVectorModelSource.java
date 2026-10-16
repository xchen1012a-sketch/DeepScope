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
package io.agentscope.advisor.model;

import io.agentscope.advisor.knowledge.VectorModelSource;
import io.agentscope.core.embedding.EmbeddingModel;
import java.sql.SQLException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Vector port: the current saved endpoint, otherwise the local embedding configuration. */
public final class SavedVectorModelSource implements VectorModelSource {

    private static final Logger log = LoggerFactory.getLogger(SavedVectorModelSource.class);

    private final ModelCatalog catalog;
    private final OpenAiEndpointClient client;
    private final VectorModelSource local;

    public SavedVectorModelSource(
            ModelCatalog catalog, OpenAiEndpointClient client, VectorModelSource local) {
        this.catalog = catalog;
        this.client = client;
        this.local = local;
    }

    @Override
    public Optional<EmbeddingModel> current() {
        try {
            Optional<ModelCatalog.Endpoint> saved = catalog.current(ModelCatalog.EMBEDDING);
            if (saved.isPresent()) {
                return Optional.of(client.embedding(saved.get()));
            }
        } catch (SQLException error) {
            log.warn("Current vector endpoint could not be read: {}", error.getMessage());
        }
        return local.current();
    }
}
