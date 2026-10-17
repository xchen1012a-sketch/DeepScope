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

import io.deepscope.core.rag.model.Document;
import io.deepscope.core.rag.reader.ReaderInput;
import io.deepscope.core.rag.reader.SplitStrategy;
import io.deepscope.core.rag.reader.TableFormat;
import io.deepscope.core.rag.reader.TextReader;
import io.deepscope.core.rag.reader.WordReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Turns an uploaded file into passages. Unsupported types and empty text are refused. */
public final class DocumentTexts {

    private DocumentTexts() {}

    public record Preview(String format, String text) {}

    /** Bounded reading only; never calls a model or changes published evidence. */
    public static Preview preview(Path file, String filename) throws Exception {
        final int maxFileBytes = 25 * 1024 * 1024;
        final int maxTextBytes = 1024 * 1024;
        if (Files.size(file) > maxFileBytes) throw new IllegalArgumentException("预览文件超限");
        String extension = extension(filename);
        String text;
        String format;
        if ("docx".equals(extension)) {
            text = String.join("\n\n", passages(file, filename, maxTextBytes, 0));
            format = "markdown";
        } else {
            format =
                    switch (extension) {
                        case "md", "markdown" -> "markdown";
                        case "html", "htm" -> "html";
                        case "txt" -> "text";
                        default -> throw new IllegalArgumentException("格式不支持预览");
                    };
            try (var input = Files.newInputStream(file)) {
                byte[] bytes = input.readNBytes(maxTextBytes + 1);
                if (bytes.length > maxTextBytes) throw new IllegalArgumentException("预览正文超限");
                text =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .decode(java.nio.ByteBuffer.wrap(bytes))
                                .toString();
            }
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > maxTextBytes)
            throw new IllegalArgumentException("预览正文超限");
        return new Preview(format, text);
    }

    public static List<String> passages(Path file, String filename, int chunkSize, int overlap)
            throws Exception {
        String extension = extension(filename);
        List<String> passages =
                switch (extension) {
                    case "md", "markdown", "txt" ->
                            chunk(
                                    Files.readString(file, StandardCharsets.UTF_8),
                                    chunkSize,
                                    overlap);
                    case "html", "htm" ->
                            chunk(
                                    stripTags(Files.readString(file, StandardCharsets.UTF_8)),
                                    chunkSize,
                                    overlap);
                    case "pdf" -> pdfPassages(file, chunkSize, overlap);
                    case "docx" ->
                            texts(
                                    new WordReader(
                                                    chunkSize,
                                                    SplitStrategy.PARAGRAPH,
                                                    overlap,
                                                    false,
                                                    true,
                                                    TableFormat.MARKDOWN)
                                            .read(ReaderInput.fromString(file.toString()))
                                            .block());
                    default ->
                            throw new IllegalArgumentException("只接受 Markdown、纯文本、HTML、PDF 和 Word");
                };
        List<String> usable = new ArrayList<>();
        for (String passage : passages) {
            if (passage != null && !passage.isBlank()) {
                usable.add(passage.strip());
            }
        }
        if (usable.isEmpty()) {
            throw new IllegalArgumentException("没有读出正文");
        }
        return usable;
    }

    public static String safeName(String filename) {
        String name = filename == null ? "" : filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        name = (slash >= 0 ? name.substring(slash + 1) : name).strip();
        return name.isBlank() ? "document" : name;
    }

    private static List<String> chunk(String text, int chunkSize, int overlap) {
        List<Document> documents =
                new TextReader(chunkSize, SplitStrategy.PARAGRAPH, overlap)
                        .read(ReaderInput.fromString(text == null ? "" : text))
                        .block();
        return texts(documents);
    }

    private static List<String> texts(List<Document> documents) {
        List<String> passages = new ArrayList<>();
        if (documents != null) {
            for (Document document : documents) {
                passages.add(document.getMetadata().getContentText());
            }
        }
        return passages;
    }

    private static String stripTags(String html) {
        return html.replaceAll("(?is)<script.*?>.*?</script>", " ")
                .replaceAll("(?is)<style.*?>.*?</style>", " ")
                .replaceAll("<[^>]+>", " ");
    }

    static String extension(String filename) {
        String name = safeName(filename).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static List<String> pdfPassages(Path file, int size, int overlap) throws Exception {
        var pages = new PdfParser().parse(file, List.of(), ignored -> {}, ignored -> {});
        if (pages.stream().anyMatch(PdfContent.Page::needsRetry)) {
            throw new IllegalArgumentException("PDF 有未完成识别的页面，请通过知识库逐页解析并配置视觉模型");
        }
        return PdfContent.chunks(pages, size, overlap).stream()
                .map(PdfContent.Chunk::text)
                .toList();
    }
}
