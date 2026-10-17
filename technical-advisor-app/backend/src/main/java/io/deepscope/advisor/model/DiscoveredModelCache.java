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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Bounded, transient discovery results partitioned by connection and credential digest. */
final class DiscoveredModelCache {
    private static final int MAX_CONNECTIONS = 128;
    private static final int MAX_MODELS = 2000;
    private static final long TTL_MILLIS = Duration.ofMinutes(5).toMillis();
    private final Clock clock;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();

    DiscoveredModelCache(Clock clock) {
        this.clock = clock;
    }

    synchronized List<String> put(ModelCatalog.EndpointDraft draft, List<String> models) {
        return putResult(draft, new ModelProbe.DiscoveryResult(models, true)).items();
    }

    synchronized ModelProbe.DiscoveryResult putResult(
            ModelCatalog.EndpointDraft draft, ModelProbe.DiscoveryResult result) {
        removeExpired();
        var unique =
                result.items().stream()
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .limit(MAX_MODELS + 1L)
                        .toList();
        var bounded =
                new ModelProbe.DiscoveryResult(
                        unique.stream().limit(MAX_MODELS).toList(),
                        result.complete() && unique.size() <= MAX_MODELS,
                        result.embeddingIds().stream()
                                .filter(unique::contains)
                                .collect(java.util.stream.Collectors.toSet()));
        var key = key(draft);
        entries.remove(key);
        entries.put(
                key,
                new Entry(
                        bounded,
                        clock.millis() + TTL_MILLIS,
                        java.util.UUID.randomUUID().toString()));
        while (entries.size() > MAX_CONNECTIONS) entries.remove(entries.keySet().iterator().next());
        return bounded;
    }

    synchronized Optional<List<String>> get(ModelCatalog.EndpointDraft draft) {
        return getResult(draft).map(ModelProbe.DiscoveryResult::items);
    }

    synchronized Optional<ModelProbe.DiscoveryResult> getResult(ModelCatalog.EndpointDraft draft) {
        return getSnapshot(draft).map(Snapshot::models);
    }

    synchronized Optional<Snapshot> getSnapshot(ModelCatalog.EndpointDraft draft) {
        removeExpired();
        return Optional.ofNullable(entries.get(key(draft)))
                .map(entry -> new Snapshot(entry.models(), entry.snapshot()));
    }

    synchronized void invalidate(ModelCatalog.EndpointDraft draft) {
        entries.remove(key(draft));
    }

    private void removeExpired() {
        long now = clock.millis();
        entries.values().removeIf(entry -> entry.expiresAt() <= now);
    }

    private static Key key(ModelCatalog.EndpointDraft draft) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    (draft.secret() == null ? "" : draft.secret())
                                            .getBytes(StandardCharsets.UTF_8));
            return new Key(
                    draft.kind(),
                    draft.provider(),
                    ModelProviderPresets.connectionUrl(draft.provider(), draft.baseUrl()),
                    HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private record Key(String kind, String provider, String baseUrl, String credentialDigest) {}

    record Snapshot(ModelProbe.DiscoveryResult models, String id) {}

    private record Entry(ModelProbe.DiscoveryResult models, long expiresAt, String snapshot) {}
}
