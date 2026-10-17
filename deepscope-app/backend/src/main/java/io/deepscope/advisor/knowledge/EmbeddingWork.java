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
package io.deepscope.advisor.knowledge;

import io.deepscope.core.embedding.EmbeddingModel;
import io.deepscope.core.message.TextBlock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.MDC;

/** One global bounded ingestion pool, with only a small window submitted by each document. */
final class EmbeddingWork implements AutoCloseable {
    @FunctionalInterface
    interface Completed {
        void save(int index, double[] vector) throws Exception;
    }

    private record Result(int index, double[] vector) {}

    private final ThreadPoolExecutor executor;
    private final int workers;

    EmbeddingWork(int workers) {
        if (workers < 1 || workers > 8) throw new IllegalArgumentException("向量并发配置无效");
        this.workers = workers;
        AtomicInteger sequence = new AtomicInteger();
        executor =
                new ThreadPoolExecutor(
                        workers,
                        workers,
                        60,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(64),
                        task -> {
                            Thread thread =
                                    new Thread(
                                            task,
                                            "knowledge-embedding-" + sequence.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    List<ChunkIndex.Passage> embed(
            List<PdfContent.Chunk> chunks,
            EmbeddingModel model,
            Map<Integer, double[]> cached,
            Completed completed)
            throws Exception {
        var completion = new ExecutorCompletionService<Result>(executor);
        List<Future<Result>> futures = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        double[][] vectors = new double[chunks.size()][];
        for (int i = 0; i < chunks.size(); i++) {
            vectors[i] = cached.get(i);
            if (vectors[i] == null) missing.add(i);
            else validate(model, vectors[i]);
        }
        Map<String, String> context = MDC.getCopyOfContextMap();
        int submitted = 0;
        try {
            for (int received = 0; received < missing.size(); received++) {
                while (submitted < missing.size() && submitted - received < workers) {
                    int index = missing.get(submitted++);
                    futures.add(
                            completion.submit(
                                    () -> {
                                        if (context != null) MDC.setContextMap(context);
                                        try {
                                            double[] vector =
                                                    model.embed(
                                                                    TextBlock.builder()
                                                                            .text(
                                                                                    chunks.get(
                                                                                                    index)
                                                                                            .text())
                                                                            .build())
                                                            .block(Duration.ofSeconds(65));
                                            validate(model, vector);
                                            return new Result(index, vector);
                                        } finally {
                                            MDC.clear();
                                        }
                                    }));
                }
                Result result = completion.take().get();
                vectors[result.index()] = result.vector();
                completed.save(result.index(), result.vector());
            }
            List<ChunkIndex.Passage> passages = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                var chunk = chunks.get(i);
                passages.add(
                        new ChunkIndex.Passage(
                                chunk.text(), vectors[i], chunk.chunkId(), chunk.source()));
            }
            return passages;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw new IllegalStateException("向量任务失败");
        } finally {
            futures.forEach(future -> future.cancel(true));
        }
    }

    static void validate(EmbeddingModel model, double[] vector) {
        if (vector == null
                || vector.length != model.getDimensions()
                || java.util.Arrays.stream(vector).anyMatch(value -> !Double.isFinite(value)))
            throw new IllegalStateException("向量响应维度或数值无效");
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
