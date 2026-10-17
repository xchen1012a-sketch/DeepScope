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
package io.deepscope.advisor.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.advisor.auth.AccountStore;
import io.deepscope.advisor.db.AdvisorSchema;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class AccountPreferencesStoreTest {
    SimpleDriverDataSource source;
    String userId;

    @BeforeEach
    void setup() throws Exception {
        source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:preferences-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(source);
        var accounts = new AccountStore(source, Clock.systemUTC(), Duration.ofHours(12));
        accounts.createUser("owner", "test-only-password");
        userId = accounts.login("owner", "test-only-password").orElseThrow().account().userId();
    }

    @Test
    void independentStoresCannotBothInitializePreferences() throws Exception {
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first =
                    executor.submit(
                            () -> {
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                return new AccountPreferencesStore(source)
                                        .write(userId, true, null, null, null, true);
                            });
            var second =
                    executor.submit(
                            () -> {
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                return new AccountPreferencesStore(source)
                                        .write(userId, false, null, null, null, true);
                            });
            start.countDown();
            var resultA = first.get(5, TimeUnit.SECONDS);
            var resultB = second.get(5, TimeUnit.SECONDS);
            assertTrue(
                    resultA.isPresent() ^ resultB.isPresent(),
                    "exactly one initialization must succeed");
            var saved = new AccountPreferencesStore(source).read(userId);
            assertTrue(saved.initialized());
            if (resultA.isPresent()) assertTrue(saved.thinkEnabled());
            else assertFalse(saved.thinkEnabled());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void reasoningEffortDefaultsToHighAndSurvivesPartialUpdates() throws Exception {
        var store = new AccountPreferencesStore(source);
        assertEquals("high", store.read(userId).reasoningEffort());
        store.write(userId, null, "xhigh", null, null, false);
        store.write(userId, null, null, "chosen-model", null, false);
        assertEquals("xhigh", store.read(userId).reasoningEffort());
    }

    @Test
    void simultaneousPartialUpdatesPreserveBothEditsAcrossStoreReopen() throws Exception {
        new AccountPreferencesStore(source).write(userId, true, null, "", "", false);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first =
                    executor.submit(
                            () -> {
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                return new AccountPreferencesStore(source)
                                        .write(userId, null, null, "chosen-model", null, false);
                            });
            var second =
                    executor.submit(
                            () -> {
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                return new AccountPreferencesStore(source)
                                        .write(userId, false, null, null, "chosen-agent", false);
                            });
            start.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isPresent());
            assertTrue(second.get(5, TimeUnit.SECONDS).isPresent());
            var saved = new AccountPreferencesStore(source).read(userId);
            assertFalse(saved.thinkEnabled());
            assertEquals("chosen-model", saved.defaultModelId());
            assertEquals("chosen-agent", saved.defaultAgentId());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
