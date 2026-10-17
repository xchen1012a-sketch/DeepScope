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

/** Connects an OpenAI-compatible vector model from local configuration. */
public final class ConfiguredVectorModelSource implements VectorModelSource {

    private final EmbeddingProperties properties;

    public ConfiguredVectorModelSource(EmbeddingProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<EmbeddingModel> current() {
        if (properties == null
                || properties.provider().isBlank()
                || properties.apiKey().isBlank()
                || properties.modelName().isBlank()) {
            return Optional.empty();
        }
        if (!"openai".equalsIgnoreCase(properties.provider().strip())) {
            return Optional.empty();
        }
        return Optional.of(
                new io.deepscope.advisor.model.KnowledgeEmbeddingModel(
                        properties.modelName(),
                        properties.baseUrl().isBlank()
                                ? "https://api.openai.com/v1"
                                : properties.baseUrl(),
                        properties.apiKey(),
                        properties.dimensions()));
    }
}
