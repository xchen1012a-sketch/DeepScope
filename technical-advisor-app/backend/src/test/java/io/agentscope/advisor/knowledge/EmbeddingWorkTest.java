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
package io.agentscope.advisor.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class EmbeddingWorkTest {
    @Test
    void documentsShareTheGlobalLimitAndReturnOriginalPassageOrder() throws Exception {
        var entered = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        EmbeddingModel model =
                new Fixture() {
                    public Mono<double[]> embed(ContentBlock input) {
                        return Mono.fromCallable(
                                () -> {
                                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                                    entered.countDown();
                                    try {
                                        release.await(3, TimeUnit.SECONDS);
                                        return new double[] {
                                            Integer.parseInt(((TextBlock) input).getText()), 0
                                        };
                                    } finally {
                                        active.decrementAndGet();
                                    }
                                });
                    }
                };
        var callers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try (var work = new EmbeddingWork(3)) {
            var first =
                    callers.submit(() -> work.embed(chunks(), model, Map.of(), (i, vector) -> {}));
            var second =
                    callers.submit(() -> work.embed(chunks(), model, Map.of(), (i, vector) -> {}));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(3, maximum.get());
            } finally {
                release.countDown();
            }
            for (var result :
                    List.of(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS)))
                for (int i = 0; i < result.size(); i++) assertEquals(i, result.get(i).vector()[0]);
            assertTrue(maximum.get() <= 3);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void firstFailureCancelsOtherRealSubscriptions() throws Exception {
        var subscribed = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        EmbeddingModel model =
                new Fixture() {
                    public Mono<double[]> embed(ContentBlock input) {
                        if ("0".equals(((TextBlock) input).getText()))
                            return Mono.fromCallable(
                                    () -> {
                                        assertTrue(subscribed.await(2, TimeUnit.SECONDS));
                                        throw new IllegalStateException("fixture failure");
                                    });
                        return Mono.<double[]>never()
                                .doOnSubscribe(value -> subscribed.countDown())
                                .doOnCancel(cancelled::countDown);
                    }
                };
        try (var work = new EmbeddingWork(2)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> work.embed(chunks(), model, Map.of(), (i, vector) -> {}));
            assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        }
    }

    private static List<PdfContent.Chunk> chunks() {
        return List.of(
                new PdfContent.Chunk("0", "0", null),
                new PdfContent.Chunk("1", "1", null),
                new PdfContent.Chunk("2", "2", null));
    }

    private abstract static class Fixture implements EmbeddingModel {
        public String getModelName() {
            return "fixture";
        }

        public int getDimensions() {
            return 2;
        }
    }
}
