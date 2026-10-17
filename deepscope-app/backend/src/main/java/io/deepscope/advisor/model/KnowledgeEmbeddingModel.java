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
package io.deepscope.advisor.model;

import io.deepscope.core.embedding.EmbeddingModel;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.TextBlock;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/** OpenAI-compatible text embeddings using the bounded cancellable knowledge HTTP boundary. */
public final class KnowledgeEmbeddingModel implements EmbeddingModel {
    private final String name;
    private final String baseUrl;
    private final String secret;
    private final int dimensions;

    public KnowledgeEmbeddingModel(String name, String baseUrl, String secret, int dimensions) {
        this.name = name;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.secret = secret;
        this.dimensions = dimensions;
    }

    @Override
    public String getModelName() {
        return name;
    }

    /** Stable configuration identity without persisting credentials or endpoint URLs in checkpoints. */
    public String fingerprint() {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (baseUrl
                                                            + "\n"
                                                            + name
                                                            + "\n"
                                                            + dimensions
                                                            + "\n"
                                                            + secret)
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }

    @Override
    public Mono<double[]> embed(ContentBlock block) {
        if (!(block instanceof TextBlock text)
                || text.getText() == null
                || text.getText().isBlank())
            return Mono.error(new IllegalArgumentException("向量输入为空"));
        return KnowledgeModelHttp.post(
                        baseUrl + "/embeddings",
                        Map.of("Authorization", "Bearer " + secret),
                        Map.of(
                                "model",
                                name,
                                "input",
                                List.of(text.getText()),
                                "dimensions",
                                dimensions,
                                "encoding_format",
                                "float"))
                .map(
                        result -> {
                            var values = result.path("data").path(0).path("embedding");
                            if (!values.isArray() || values.size() != dimensions)
                                throw new IllegalStateException("向量响应维度不匹配");
                            double[] vector = new double[dimensions];
                            for (int i = 0; i < dimensions; i++) {
                                if (!values.get(i).isNumber()
                                        || !Double.isFinite(values.get(i).asDouble()))
                                    throw new IllegalStateException("向量响应数值无效");
                                vector[i] = values.get(i).asDouble();
                            }
                            return vector;
                        });
    }
}
