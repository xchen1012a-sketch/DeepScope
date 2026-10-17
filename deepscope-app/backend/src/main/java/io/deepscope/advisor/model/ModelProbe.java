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
package io.deepscope.advisor.model;

/** A minimum call that must succeed before an endpoint becomes current. */
public interface ModelProbe {

    Result check(ModelCatalog.EndpointDraft draft);

    default boolean supportsImages(ModelCatalog.EndpointDraft draft) {
        return false;
    }

    default java.util.List<String> discover(ModelCatalog.EndpointDraft draft) {
        throw new DiscoveryException("unsupported");
    }

    default DiscoveryResult discoverResult(ModelCatalog.EndpointDraft draft) {
        return new DiscoveryResult(discover(draft), true);
    }

    record DiscoveryResult(
            java.util.List<String> items, boolean complete, java.util.Set<String> embeddingIds) {
        public DiscoveryResult(java.util.List<String> items, boolean complete) {
            this(items, complete, java.util.Set.of());
        }

        public DiscoveryResult {
            items = java.util.List.copyOf(items);
            embeddingIds = java.util.Set.copyOf(embeddingIds);
        }
    }

    /** Stable failure category; never carries an upstream response or credentials. */
    final class DiscoveryException extends IllegalArgumentException {
        private final String code;

        public DiscoveryException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Context capacity reported by the configured endpoint; absent when not advertised. */
    default java.util.Optional<Integer> contextWindow(ModelCatalog.EndpointDraft draft) {
        return java.util.Optional.empty();
    }

    record Result(boolean ok, String failure, Integer dimensions) {
        public static Result success(Integer dimensions) {
            return new Result(true, "", dimensions);
        }

        public static Result failure(String failure) {
            return new Result(false, failure == null || failure.isBlank() ? "连通失败" : failure, null);
        }
    }
}
