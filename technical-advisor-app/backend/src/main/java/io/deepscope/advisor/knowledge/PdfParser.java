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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Page-local extraction; missing OCR is an explicit page failure, including in mixed PDFs. */
public final class PdfParser {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PdfParser.class);

    public interface Vision {
        boolean available();

        PdfContent.Page parse(byte[] image, PdfContent.Page nativePage) throws Exception;

        default String cacheKey() {
            return getClass().getName();
        }

        default String modelId() {
            return "";
        }
    }

    private final Vision vision;
    private final int maxPages;
    private final int imageEdge;

    public PdfParser(Vision vision, int maxPages, int imageEdge) {
        if (maxPages < 1 || maxPages > 10000 || imageEdge < 256 || imageEdge > 4000) {
            throw new IllegalArgumentException("PDF 解析限制无效");
        }
        this.vision = vision;
        this.maxPages = maxPages;
        this.imageEdge = imageEdge;
    }

    public PdfParser() {
        this(null, 300, 2000);
    }

    public String cacheKey() {
        // Successful page records retain the same contract; adding a failure fallback must not
        // force existing successful pages through the model again.
        return "pdf-layout-v2:" + imageEdge + ":" + (vision == null ? "native" : vision.cacheKey());
    }

    public List<PdfContent.Page> parse(
            Path file,
            List<PdfContent.Page> cached,
            IntConsumer total,
            Consumer<PdfContent.Page> completed)
            throws Exception {
        try (PDDocument document = open(file)) {
            total.accept(document.getNumberOfPages());
            String[] labels =
                    document.getDocumentCatalog().getPageLabels() == null
                            ? null
                            : document.getDocumentCatalog()
                                    .getPageLabels()
                                    .getLabelsByPageIndices();
            List<PdfContent.Page> pages = new ArrayList<>();
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("PDF 处理已取消");
                int number = i + 1;
                PdfContent.Page saved =
                        cached.stream()
                                .filter(page -> page.pageNumber() == number && !page.needsRetry())
                                .findFirst()
                                .orElse(null);
                PdfContent.Page page = saved;
                if (page == null) {
                    try {
                        page = read(document, i, labels);
                    } catch (IOException | IllegalArgumentException error) {
                        log.warn(
                                "PDF native page failed page={} code={} type={}",
                                number,
                                io.deepscope.advisor.model.KnowledgeModelFailure.code(error),
                                error.getClass().getSimpleName());
                        var bounds = document.getPage(i).getCropBox();
                        page =
                                new PdfContent.Page(
                                        number,
                                        labels != null && i < labels.length ? labels[i] : null,
                                        bounds.getWidth(),
                                        bounds.getHeight(),
                                        "failed",
                                        "native",
                                        "",
                                        List.of(),
                                        List.of("[pdf_read_failed] 此页文件解析失败，请检查文件是否损坏"));
                    }
                }
                completed.accept(page);
                pages.add(page);
            }
            return List.copyOf(pages);
        }
    }

    public byte[] image(Path file, int pageNumber) throws IOException {
        try (PDDocument document = open(file)) {
            if (pageNumber < 1 || pageNumber > document.getNumberOfPages()) {
                throw new IllegalArgumentException("页码不存在");
            }
            return render(document, pageNumber - 1);
        }
    }

    private PDDocument open(Path file) throws IOException {
        PDDocument pdf = Loader.loadPDF(file.toFile());
        if (pdf.getNumberOfPages() < 1
                || pdf.getNumberOfPages() > maxPages
                || !pdf.getCurrentAccessPermission().canExtractContent()) {
            pdf.close();
            throw new IllegalArgumentException("PDF 页数超出限制或未授权提取正文");
        }
        return pdf;
    }

    private PdfContent.Page read(PDDocument pdf, int index, String[] labels) throws IOException {
        var sheet = pdf.getPage(index);
        double width = sheet.getCropBox().getWidth();
        double height = sheet.getCropBox().getHeight();
        if (Math.floorMod(sheet.getRotation(), 180) == 90) {
            double swap = width;
            width = height;
            height = swap;
        }
        Positions positions =
                new Positions(
                        sheet.getCropBox().getWidth(),
                        sheet.getCropBox().getHeight(),
                        Math.floorMod(sheet.getRotation(), 360));
        positions.setStartPage(index + 1);
        positions.setEndPage(index + 1);
        String text = positions.getText(pdf).strip();
        var graphics = new PdfPageGraphics(sheet);
        boolean images = graphics.image;
        boolean broken =
                text.codePoints().filter(c -> c == 0xfffd || c == 0).count()
                        > Math.max(1, text.length() / 50);
        boolean blank = text.isBlank() && !images && graphics.paths == 0;
        String status = blank ? "blank" : "ready";
        var page =
                new PdfContent.Page(
                        index + 1,
                        labels != null && index < labels.length ? labels[index] : null,
                        width,
                        height,
                        status,
                        "native",
                        text,
                        positions.paragraphs(),
                        List.of());
        boolean requiresVision =
                !blank
                        && (text.isBlank()
                                || broken
                                || images
                                || graphics.paths > 2
                                || positions.columns
                                || positions.truncated
                                || positions.rotatedText);
        if (!requiresVision) return page;
        if (vision != null && vision.available()) {
            long started = System.nanoTime();
            byte[] rendered = render(pdf, index);
            try {
                return vision.parse(rendered, page);
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted()
                        || "cancelled"
                                .equals(
                                        io.deepscope.advisor.model.KnowledgeModelFailure.code(
                                                error))) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException("PDF 解析已取消");
                }
                String request =
                        error instanceof io.deepscope.advisor.model.KnowledgeModelFailure failure
                                ? failure.requestId()
                                : "";
                log.warn(
                        "PDF vision page failed page={} model={} code={} request={} elapsedMs={}"
                                + " type={}",
                        index + 1,
                        vision.modelId(),
                        io.deepscope.advisor.model.KnowledgeModelFailure.code(error),
                        request,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - started),
                        error.getClass().getSimpleName());
                String code = io.deepscope.advisor.model.KnowledgeModelFailure.code(error);
                if (java.util.Set.of(
                                "model_timeout",
                                "model_output_invalid",
                                "model_output_truncated",
                                "model_output_empty_or_large")
                        .contains(code)) {
                    try {
                        log.info("PDF regional fallback started page={}", index + 1);
                        var recovered =
                                PdfRegionFallback.parse(
                                        rendered,
                                        page,
                                        vision,
                                        positions.columns,
                                        sheet.getRotation());
                        if (recovered != null) {
                            log.info("PDF regional fallback completed page={}", index + 1);
                            return recovered;
                        }
                        log.info(
                                "PDF regional fallback unavailable page={}"
                                        + " reason=no_safe_separator",
                                index + 1);
                    } catch (Exception regionError) {
                        if (Thread.currentThread().isInterrupted()
                                || "cancelled"
                                        .equals(
                                                io.deepscope.advisor.model.KnowledgeModelFailure
                                                        .code(regionError))) {
                            Thread.currentThread().interrupt();
                            throw new java.util.concurrent.CancellationException("PDF 分区解析已取消");
                        }
                        log.warn(
                                "PDF regional fallback failed page={} code={}",
                                index + 1,
                                io.deepscope.advisor.model.KnowledgeModelFailure.code(regionError));
                        return failed(
                                page,
                                io.deepscope.advisor.model.KnowledgeModelFailure.safeMessage(
                                        regionError));
                    }
                }
                return failed(
                        page, io.deepscope.advisor.model.KnowledgeModelFailure.safeMessage(error));
            }
        }
        if (text.isBlank() || broken) return failed(page, "此页需要视觉识别，请配置 PDF 视觉模型后重试");
        return new PdfContent.Page(
                page.pageNumber(),
                page.pageLabel(),
                width,
                height,
                "review",
                "native",
                text,
                page.blocks(),
                List.of("复杂布局或图文页仅提取原生文字，需视觉解析复核"));
    }

    private static PdfContent.Page failed(PdfContent.Page page, String warning) {
        return new PdfContent.Page(
                page.pageNumber(),
                page.pageLabel(),
                page.width(),
                page.height(),
                "failed",
                "native",
                page.nativeText(),
                List.of(),
                List.of(warning));
    }

    private byte[] render(PDDocument pdf, int page) throws IOException {
        var box = pdf.getPage(page).getCropBox();
        float longest = Math.max(box.getWidth(), box.getHeight());
        if (!Float.isFinite(longest) || longest <= 0) throw new IOException("PDF 页面尺寸无效");
        BufferedImage image =
                new PDFRenderer(pdf).renderImage(page, imageEdge / longest, ImageType.RGB);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        } finally {
            image.flush();
        }
    }

    private static final class Positions extends PDFTextStripper {
        final List<PdfContent.Block> blocks = new ArrayList<>();
        final double width;
        final double height;
        boolean columns;
        boolean truncated;
        boolean rotatedText;
        final int rotation;

        Positions(double width, double height, int rotation) throws IOException {
            this.width = width;
            this.height = height;
            this.rotation = rotation;
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            super.writeString(text, positions);
            if (text.isBlank() || positions.isEmpty()) return;
            if (blocks.size() >= 500) {
                truncated = true;
                return;
            }
            double left = width, top = height, right = 0, bottom = 0;
            TextPosition previous = null;
            for (TextPosition p : positions) {
                if (p.getDir() != 0) rotatedText = true;
                left = Math.min(left, p.getXDirAdj());
                top = Math.min(top, p.getYDirAdj() - p.getHeightDir());
                right = Math.max(right, p.getXDirAdj() + p.getWidthDirAdj());
                bottom = Math.max(bottom, p.getYDirAdj());
                if (previous != null
                        && p.getXDirAdj() - previous.getXDirAdj() - previous.getWidthDirAdj()
                                > width * .15) columns = true;
                previous = p;
            }
            double x = Math.max(0, Math.min(.999, left / width));
            double y = Math.max(0, Math.min(.999, top / height));
            double w = Math.max(.001, Math.min(1 - x, (right - left) / width));
            double h = Math.max(.001, Math.min(1 - y, (bottom - top) / height));
            PdfContent.Box box =
                    switch (rotation) {
                        case 90 -> new PdfContent.Box(Math.max(0, 1 - y - h), x, h, w);
                        case 180 ->
                                new PdfContent.Box(
                                        Math.max(0, 1 - x - w), Math.max(0, 1 - y - h), w, h);
                        case 270 -> new PdfContent.Box(y, Math.max(0, 1 - x - w), h, w);
                        default -> new PdfContent.Box(x, y, w, h);
                    };
            blocks.add(
                    new PdfContent.Block(
                            "b" + blocks.size(),
                            positions.get(0).getFontSizeInPt() >= 16 ? "heading" : "paragraph",
                            text.strip(),
                            box,
                            false));
        }

        List<PdfContent.Block> paragraphs() {
            if (rotatedText)
                return blocks.stream()
                        .map(
                                block ->
                                        new PdfContent.Block(
                                                block.id(),
                                                block.type(),
                                                block.text(),
                                                null,
                                                false))
                        .toList();
            List<PdfContent.Block> grouped = new ArrayList<>();
            for (var line : blocks) {
                if (!grouped.isEmpty()) {
                    var last = grouped.get(grouped.size() - 1);
                    var a = last.box();
                    var b = line.box();
                    double gap = b.y() - a.y() - a.height();
                    if (Math.abs(b.y() - a.y()) < .01 && b.x() - a.x() - a.width() > .12)
                        columns = true;
                    if (!columns
                            && "paragraph".equals(last.type())
                            && last.type().equals(line.type())
                            && Math.abs(a.x() - b.x()) < .03
                            && gap > 0
                            && gap < b.height() * 1.8) {
                        double x = Math.min(a.x(), b.x());
                        double y = Math.min(a.y(), b.y());
                        var box =
                                new PdfContent.Box(
                                        x,
                                        y,
                                        Math.max(a.x() + a.width(), b.x() + b.width()) - x,
                                        Math.max(a.y() + a.height(), b.y() + b.height()) - y);
                        grouped.set(
                                grouped.size() - 1,
                                new PdfContent.Block(
                                        last.id(),
                                        last.type(),
                                        last.text() + "\n" + line.text(),
                                        box,
                                        false));
                        continue;
                    }
                }
                grouped.add(
                        new PdfContent.Block(
                                "b" + grouped.size(), line.type(), line.text(), line.box(), false));
            }
            return List.copyOf(grouped);
        }
    }
}
