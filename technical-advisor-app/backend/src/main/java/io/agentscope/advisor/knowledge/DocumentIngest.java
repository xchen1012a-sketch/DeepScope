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

import io.agentscope.core.embedding.EmbeddingModel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Bounded independent document jobs; only complete documents publish in a short transaction. */
public final class DocumentIngest implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DocumentIngest.class);
    private final KnowledgeCatalog catalog;
    private final VectorModelSource models;
    private final ChunkIndex index;
    private final PdfParser pdf;
    private final DocumentStructureStore structures;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Map<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Duration taskTimeout;
    private final EmbeddingWork embeddingWork;

    public DocumentIngest(KnowledgeCatalog catalog, VectorModelSource models, ChunkIndex index) {
        this(catalog, models, index, new PdfParser());
    }

    public DocumentIngest(
            KnowledgeCatalog catalog, VectorModelSource models, ChunkIndex index, PdfParser pdf) {
        this(catalog, models, index, pdf, 2, 32, Duration.ofMinutes(20));
    }

    public DocumentIngest(
            KnowledgeCatalog catalog,
            VectorModelSource models,
            ChunkIndex index,
            PdfParser pdf,
            int workers,
            int queueCapacity,
            Duration taskTimeout) {
        this(catalog, models, index, pdf, workers, queueCapacity, taskTimeout, 3);
    }

    public DocumentIngest(
            KnowledgeCatalog catalog,
            VectorModelSource models,
            ChunkIndex index,
            PdfParser pdf,
            int workers,
            int queueCapacity,
            Duration taskTimeout,
            int embeddingWorkers) {
        if (workers < 1
                || workers > 8
                || queueCapacity < 1
                || queueCapacity > 256
                || taskTimeout.isNegative()
                || taskTimeout.isZero()) throw new IllegalArgumentException("文档并发配置无效");
        this.catalog = catalog;
        this.models = models;
        this.index = index;
        this.pdf = pdf;
        this.structures = catalog.structures();
        this.taskTimeout = taskTimeout;
        this.embeddingWork = new EmbeddingWork(embeddingWorkers);
        AtomicInteger sequence = new AtomicInteger();
        executor =
                new ThreadPoolExecutor(
                        workers,
                        workers,
                        60,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(queueCapacity),
                        runnable -> {
                            Thread thread =
                                    new Thread(
                                            runnable,
                                            "knowledge-ingest-" + sequence.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        deadlines =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> {
                            Thread thread = new Thread(runnable, "knowledge-ingest-deadlines");
                            thread.setDaemon(true);
                            return thread;
                        });
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setKeepAliveTime(60, TimeUnit.SECONDS);
        deadlines.allowCoreThreadTimeOut(true);
    }

    public void submit(String documentId) {
        submit(documentId, false);
    }

    public boolean isActive(String documentId) {
        return tasks.containsKey(documentId) || active.contains(documentId);
    }

    /** Public processing publishes a complete document; internal checkpoints may be reused. */
    public void submit(String documentId, boolean retryPages) {
        AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();
        FutureTask<Void> task =
                new FutureTask<>(
                        () -> {
                            try {
                                process(documentId);
                            } finally {
                                tasks.remove(documentId);
                            }
                            return null;
                        }) {
                    @Override
                    protected void done() {
                        ScheduledFuture<?> scheduled = timeout.get();
                        if (scheduled != null) scheduled.cancel(false);
                    }
                };
        if (tasks.putIfAbsent(documentId, task) != null) return;
        try {
            catalog.progress(documentId, "queued");
            timeout.set(
                    deadlines.schedule(
                            () -> {
                                if (!task.isDone()) {
                                    task.cancel(true);
                                    executor.remove(task);
                                    if (!active.contains(documentId))
                                        tasks.remove(documentId, task);
                                    failure(documentId, "文档处理超时，请检查模型连接后重新处理");
                                }
                            },
                            taskTimeout.toMillis(),
                            TimeUnit.MILLISECONDS));
            executor.execute(task);
            if (task.isDone()) timeout.get().cancel(false);
        } catch (Exception rejected) {
            task.cancel(true);
            tasks.remove(documentId, task);
            failure(documentId, "处理队列已满或服务正在关闭，请稍后重新处理");
        }
    }

    public void process(String documentId) {
        process(documentId, false);
    }

    void process(String documentId, boolean retryPages) {
        if (!active.add(documentId)) return;
        String attemptId = java.util.UUID.randomUUID().toString();
        long started = System.nanoTime();
        try (var documentContext = MDC.putCloseable("documentId", documentId);
                var attemptContext = MDC.putCloseable("attemptId", attemptId)) {
            log.info("Document processing started");
            checkCancelled();
            catalog.progress(documentId, "parsing");
            var document = catalog.findDocument(documentId).orElse(null);
            if (document == null) return;
            var library =
                    catalog.find(document.libraryId())
                            .orElseThrow(() -> new IllegalStateException("知识库已经不存在"));
            if (!index.available()) throw new IllegalArgumentException("数据库还没有启用向量扩展");
            EmbeddingModel model =
                    models.current().orElseThrow(() -> new IllegalArgumentException("向量模型还没有接入"));
            String modelIdentity = models.checkpointIdentity(model);
            String selectedVersion = modelVersion(model);
            if (library.embeddingDimensions() != null
                    && library.embeddingDimensions() != model.getDimensions())
                throw new IllegalArgumentException("向量维度和这个库已经写入的不一致");
            Path sourceFile = Path.of(document.storagePath());
            boolean pdfFile = "pdf".equals(DocumentTexts.extension(document.filename()));
            String expectedSignature =
                    pdfFile ? parseSignature(sourceFile) : fileSignature(sourceFile);
            long parsingStarted = System.nanoTime();
            List<PdfContent.Chunk> passages = read(document, library);
            log.info(
                    "Parsing completed pages={} chunks={} elapsedMs={}",
                    structures.pages(documentId).size(),
                    passages.size(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - parsingStarted));
            checkCancelled();
            var incomplete =
                    structures.pages(documentId).stream()
                            .filter(PdfContent.Page::needsRetry)
                            .toList();
            if (!incomplete.isEmpty())
                throw new IllegalArgumentException(
                        "全文解析未完成：第 "
                                + incomplete.stream()
                                        .limit(20)
                                        .map(page -> Integer.toString(page.pageNumber()))
                                        .collect(java.util.stream.Collectors.joining("、"))
                                + " 页识别失败或内容不完整，请查看页面原因后重新处理");
            if (passages.isEmpty()) throw new IllegalArgumentException("没有读出正文；请查看页面识别状态");
            var checkpoint = catalog.embeddingCheckpoint();
            String fingerprint =
                    modelIdentity.isBlank()
                            ? ""
                            : EmbeddingCheckpoint.hash(
                                    "embedding-v2:"
                                            + modelIdentity
                                            + ":"
                                            + expectedSignature
                                            + ":"
                                            + library.chunkSize()
                                            + ":"
                                            + library.overlap());
            checkpoint.prepare(documentId, fingerprint);
            var cached = checkpoint.load(documentId, fingerprint, passages, model.getDimensions());
            AtomicInteger completedChunks = new AtomicInteger(cached.size());
            catalog.embeddingProgress(documentId, cached.size(), passages.size());
            long embeddingStarted = System.nanoTime();
            log.info("Embedding started chunks={} reused={}", passages.size(), cached.size());
            List<ChunkIndex.Passage> embedded =
                    embeddingWork.embed(
                            passages,
                            model,
                            cached,
                            (position, vector) -> {
                                checkCancelled();
                                checkpoint.save(
                                        documentId, fingerprint, passages.get(position), vector);
                                catalog.embeddingProgress(
                                        documentId,
                                        completedChunks.incrementAndGet(),
                                        passages.size());
                            });
            log.info(
                    "Embedding completed chunks={} elapsedMs={}",
                    passages.size(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - embeddingStarted));
            checkCancelled();
            if (!selectedVersion.equals(
                    modelVersion(
                            models.current()
                                    .orElseThrow(() -> new IllegalArgumentException("向量模型已经不可用")))))
                throw new IllegalArgumentException("向量模型配置发生变化，请重新处理");
            if (pdfFile) verifySignature(documentId, sourceFile, expectedSignature);
            else if (!expectedSignature.equals(fileSignature(sourceFile)))
                throw new IllegalArgumentException("全文解析未完成：文件发生变化，请重新处理");
            catalog.progress(documentId, "publishing");
            structures.publishAtomic(
                    documentId,
                    library.libraryId(),
                    passages,
                    index,
                    model.getDimensions(),
                    embedded);
            log.info(
                    "Document processing completed elapsedMs={}",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (Exception error) {
            log.warn(
                    "Document {} attempt={} processing failed code={} elapsedMs={} type={}",
                    documentId,
                    attemptId,
                    io.agentscope.advisor.model.KnowledgeModelFailure.code(error),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    error.getClass().getSimpleName());
            failure(documentId, reason(error));
        } finally {
            active.remove(documentId);
        }
    }

    private List<PdfContent.Chunk> read(
            KnowledgeCatalog.DocumentRow document, KnowledgeCatalog.LibraryRow library)
            throws Exception {
        Path file = Path.of(document.storagePath());
        if ("pdf".equals(DocumentTexts.extension(document.filename()))) {
            String id = document.documentId();
            String signature = parseSignature(file);
            boolean reuse = signature.equals(catalog.parseSignature(id));
            var cached = reuse ? structures.pages(id) : List.<PdfContent.Page>of();
            var pages =
                    pdf.parse(
                            file,
                            cached,
                            count -> {
                                try {
                                    structures.start(id, count, !reuse);
                                    catalog.rememberParseSignature(id, signature);
                                } catch (Exception error) {
                                    throw new IllegalStateException(error);
                                }
                            },
                            page -> {
                                try {
                                    verifySignature(id, file, signature);
                                    structures.savePage(id, page);
                                    catalog.progress(id, "parsing");
                                } catch (IllegalArgumentException changed) {
                                    throw changed;
                                } catch (Exception error) {
                                    throw new IllegalStateException(error);
                                }
                            });
            verifySignature(id, file, signature);
            return PdfContent.chunks(pages, library.chunkSize(), library.overlap());
        }
        List<PdfContent.Chunk> chunks = new ArrayList<>();
        for (String text :
                DocumentTexts.passages(
                        file, document.filename(), library.chunkSize(), library.overlap()))
            chunks.add(new PdfContent.Chunk(Integer.toString(chunks.size()), text, null));
        return chunks;
    }

    private void verifySignature(String id, Path file, String expected) throws Exception {
        if (!expected.equals(parseSignature(file))) {
            catalog.rememberParseSignature(id, "");
            throw new IllegalArgumentException("全文解析未完成：解析期间文件或模型配置发生变化，请重新处理");
        }
    }

    private String parseSignature(Path file) throws Exception {
        return fileSignature(file) + ":" + pdf.cacheKey();
    }

    private String fileSignature(Path file) throws Exception {
        return file.toAbsolutePath()
                + ":"
                + java.nio.file.Files.size(file)
                + ":"
                + java.nio.file.Files.getLastModifiedTime(file).toMillis();
    }

    private String modelVersion(EmbeddingModel model) {
        String identity = models.checkpointIdentity(model);
        return identity.isBlank()
                ? model.getClass().getName()
                        + ":"
                        + model.getModelName()
                        + ":"
                        + model.getDimensions()
                : identity;
    }

    private void failure(String id, String message) {
        try {
            catalog.failProcessing(id, message);
        } catch (Exception error) {
            log.warn(
                    "Document {} failure could not be recorded ({})",
                    id,
                    error.getClass().getSimpleName());
        }
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("文档处理已取消或超时");
    }

    @Override
    public void close() {
        for (var entry : tasks.entrySet()) {
            entry.getValue().cancel(true);
            failure(entry.getKey(), "服务关闭导致处理中断，请重新处理");
        }
        deadlines.shutdownNow();
        executor.shutdownNow();
        embeddingWork.close();
    }

    static String reason(Throwable error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (error instanceof java.nio.charset.CharacterCodingException)
            return "文件读取失败：请检查文本编码或文件格式";
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof io.agentscope.advisor.model.KnowledgeModelFailure)
                return io.agentscope.advisor.model.KnowledgeModelFailure.safeMessage(error);
        }
        if (error instanceof java.util.concurrent.TimeoutException
                || error instanceof InterruptedException
                || message.toLowerCase(java.util.Locale.ROOT).contains("timeout"))
            return "模型请求或文档处理超时，请检查网络后重新处理";
        if (error instanceof IllegalArgumentException
                && (message.startsWith("全文解析未完成")
                        || message.startsWith("没有读出正文")
                        || message.startsWith("只接受")
                        || message.startsWith("向量")
                        || message.startsWith("数据库"))) return message;
        if (error instanceof java.io.IOException) return "文档读取失败，请检查文件是否损坏或已加密";
        return "文档处理失败：模型连接、向量化或索引提交未完成，请检查模型配置后重新处理";
    }
}
