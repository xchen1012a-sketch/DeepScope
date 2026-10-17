/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deepscope.extensions.redis.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.deepscope.core.session.SessionKey;
import io.deepscope.core.session.log.AtomicSessionStore;
import io.deepscope.core.session.log.DurableSession;
import io.deepscope.core.session.log.NativeSessionLogStore;
import io.deepscope.core.session.log.SessionExecutionData;
import io.deepscope.core.session.log.SessionLogException;
import io.deepscope.harness.agent.session.StoreSessionStorage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import redis.clients.jedis.UnifiedJedis;

/** Real Redis Lua CAS and journal fencing, with an isolated namespace per invocation. */
@EnabledIfEnvironmentVariable(named = "DEEPSCOPE_TEST_REDIS_URL", matches = ".+")
class RedisSessionStorageIntegrationTest {
    @Test
    void independentClientsRaceCasAndRecoverTheFencedCommittedPrefix() throws Exception {
        String prefix = "regression:" + UUID.randomUUID() + ":";
        String url = System.getenv("DEEPSCOPE_TEST_REDIS_URL");
        try (var firstClient = new UnifiedJedis(url);
                var secondClient = new UnifiedJedis(url)) {
            var firstStore = new RedisStore(firstClient, prefix);
            var secondStore = new RedisStore(secondClient, prefix);
            try {
                var start = new CountDownLatch(1);
                var firstCas =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    await(start);
                                    return firstStore.putIfVersion(
                                            List.of("cas"), "key", Map.of("value", "first"), 0);
                                });
                var secondCas =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    await(start);
                                    return secondStore.putIfVersion(
                                            List.of("cas"), "key", Map.of("value", "second"), 0);
                                });
                start.countDown();
                assertNotEquals(
                        firstCas.get(10, TimeUnit.SECONDS), secondCas.get(10, TimeUnit.SECONDS));
                assertEquals(1, firstStore.get(List.of("cas"), "key").version());
                var key = new SessionKey("t", "t", "session");
                var firstStorage = new StoreSessionStorage(firstStore, List.of("tenant"));
                var first = new NativeSessionLogStore(new AtomicSessionStore(firstStorage));
                var second =
                        new NativeSessionLogStore(
                                new AtomicSessionStore(
                                        new StoreSessionStorage(secondStore, List.of("tenant"))));
                try (DurableSession writer = first.openWriter(key, "run-a", "turn", null)) {
                    assertThrows(
                            SessionLogException.class,
                            () -> first.openWriter(key, "run-b", "turn", null));
                    writer.append(new SessionExecutionData.TurnStart(1));
                    var committed = second.readKernel(key, null);
                    assertEquals(1, committed.size());
                    assertEquals("turn/start", committed.events().get(0).type());
                }
                assertEquals(
                        0,
                        new NativeSessionLogStore(
                                        new AtomicSessionStore(
                                                new StoreSessionStorage(
                                                        secondStore, List.of("other"))))
                                .readKernel(key, null)
                                .size());
            } finally {
                for (String key : firstClient.keys(prefix + "*")) firstClient.del(key);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("latch timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
