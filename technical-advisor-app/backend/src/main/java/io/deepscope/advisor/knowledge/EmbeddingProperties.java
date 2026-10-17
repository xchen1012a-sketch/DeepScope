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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Local vector-model connection. Blank provider means none is connected yet. */
@ConfigurationProperties(prefix = "advisor.embedding")
public record EmbeddingProperties(
        @DefaultValue("") String provider,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String modelName,
        @DefaultValue("") String baseUrl,
        @DefaultValue("1024") int dimensions) {}
