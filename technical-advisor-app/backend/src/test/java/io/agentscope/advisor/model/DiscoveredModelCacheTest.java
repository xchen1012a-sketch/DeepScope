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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DiscoveredModelCacheTest {
    @Test
    void expiresAtFiveMinutesWithoutReadExtendingLifetime() {
        var clock = new MutableClock();
        var cache = new DiscoveredModelCache(clock);
        var draft = draft("chat", "openai", "https://example.invalid/v1", "fake-one");
        cache.put(draft, List.of("remote"));
        clock.now = Instant.ofEpochMilli(299999);
        assertEquals(List.of("remote"), cache.get(draft).orElseThrow());
        clock.now = Instant.ofEpochMilli(300000);
        assertTrue(cache.get(draft).isEmpty());
    }

    @Test
    void limitsStoredIdsAndEvictsOldestConnection() {
        var cache = new DiscoveredModelCache(Clock.systemUTC());
        var first = draft("chat", "openai", "https://example.invalid/v1", "fake-first");
        cache.put(first, IntStream.range(0, 2100).mapToObj(index -> "model-" + index).toList());
        assertEquals(2000, cache.get(first).orElseThrow().size());
        for (int index = 0; index < 128; index++) {
            cache.put(
                    draft("chat", "openai", "https://example.invalid/v1", "fake-" + index),
                    List.of("remote"));
        }
        assertTrue(cache.get(first).isEmpty());
        assertTrue(
                cache.get(draft("chat", "openai", "https://example.invalid/v1", "fake-127"))
                        .isPresent());
    }

    @Test
    void isolatesKindProtocolUrlAndKeyButNormalizesTrailingSlash() {
        var cache = new DiscoveredModelCache(Clock.systemUTC());
        cache.put(
                draft("chat", "openai", "https://example.invalid/v1/", "fake-one"),
                List.of("remote", "remote", ""));
        assertEquals(
                List.of("remote"),
                cache.get(draft("chat", "openai", "https://example.invalid/v1", "fake-one"))
                        .orElseThrow());
        assertTrue(
                cache.get(draft("embedding", "openai", "https://example.invalid/v1", "fake-one"))
                        .isEmpty());
        assertTrue(
                cache.get(draft("chat", "custom", "https://example.invalid/v1", "fake-one"))
                        .isEmpty());
        assertTrue(
                cache.get(draft("chat", "openai", "https://other.invalid/v1", "fake-one"))
                        .isEmpty());
        assertTrue(
                cache.get(draft("chat", "openai", "https://example.invalid/v1", "fake-two"))
                        .isEmpty());
    }

    private static ModelCatalog.EndpointDraft draft(
            String kind, String provider, String url, String secret) {
        return new ModelCatalog.EndpointDraft(kind, provider, null, url, secret);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
