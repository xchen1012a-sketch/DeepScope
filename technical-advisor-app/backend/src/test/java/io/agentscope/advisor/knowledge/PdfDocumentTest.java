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
package io.agentscope.advisor.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfDocumentTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 90, 180, 270})
    void timedOutPageCanRecoverFromTwoSafeRegionsWithOriginalCoordinates(int rotation)
            throws Exception {
        Path file = root.resolve("regions.pdf");
        try (PDDocument document = new PDDocument()) {
            var sheet = new PDPage();
            sheet.setRotation(rotation);
            document.addPage(sheet);
            var image = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, 200, 300);
            graphics.setColor(java.awt.Color.BLACK);
            graphics.fillRect(20, 30, 160, 20);
            graphics.fillRect(20, 240, 160, 20);
            graphics.dispose();
            try (var content = new PDPageContentStream(document, sheet)) {
                content.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, 612, 792);
            }
            document.save(file.toFile());
            image.flush();
        }
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var vision =
                new PdfParser.Vision() {
                    public boolean available() {
                        return true;
                    }

                    public PdfContent.Page parse(byte[] bytes, PdfContent.Page page)
                            throws Exception {
                        int call = calls.incrementAndGet();
                        if (call == 1)
                            throw new java.util.concurrent.TimeoutException("fixture timeout");
                        return new PdfContent.Page(
                                page.pageNumber(),
                                page.pageLabel(),
                                page.width(),
                                page.height(),
                                "ready",
                                "vision",
                                "",
                                java.util.List.of(
                                        new PdfContent.Block(
                                                "b0",
                                                "paragraph",
                                                "Region " + call,
                                                new PdfContent.Box(
                                                        .1,
                                                        call == 3 ? .8 : .2,
                                                        .3,
                                                        call == 3 ? .2 : .1),
                                                false)),
                                java.util.List.of());
                    }
                };
        var pages =
                new PdfParser(vision, 10, 1000)
                        .parse(file, java.util.List.of(), count -> {}, page -> {});
        assertEquals("ready", pages.get(0).status());
        assertEquals(3, calls.get());
        assertEquals(2, pages.get(0).blocks().size());
        var first = pages.get(0).blocks().get(0).box();
        var second = pages.get(0).blocks().get(1).box();
        org.junit.jupiter.api.Assertions.assertTrue(
                switch (rotation) {
                    case 90 -> second.x() < first.x();
                    case 180 -> second.y() < first.y();
                    case 270 -> second.x() > first.x();
                    default -> second.y() >= .5;
                });
    }

    @Test
    void timeoutDoesNotBlindlySplitAcrossDensePageContent() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var vision =
                new PdfParser.Vision() {
                    public boolean available() {
                        return true;
                    }

                    public PdfContent.Page parse(byte[] image, PdfContent.Page page)
                            throws Exception {
                        calls.incrementAndGet();
                        throw new java.util.concurrent.TimeoutException("fixture timeout");
                    }
                };
        var pages =
                new PdfParser(vision, 10, 1000)
                        .parse(pdf(true), java.util.List.of(), count -> {}, page -> {});
        assertEquals("failed", pages.get(1).status());
        assertEquals(1, calls.get());
    }

    @org.junit.jupiter.api.Test
    void adjacentShortTextMergesWithoutCrossingHeadingsTablesOrPages() {
        var blocks =
                java.util.List.of(
                        new PdfContent.Block("b0", "heading", "采购规则", null, false),
                        new PdfContent.Block("b1", "paragraph", "金额 123.45 元。", null, false),
                        new PdfContent.Block("b2", "paragraph", "需要批准。", null, false),
                        new PdfContent.Block(
                                "b3", "table", "|A|B|\n|---|---|\n|X|12|", null, false),
                        new PdfContent.Block("b4", "heading", "下一节", null, false),
                        new PdfContent.Block("b5", "paragraph", "不可跨节拼接。", null, false));
        var page =
                new PdfContent.Page(
                        1, null, 600, 800, "ready", "native", "", blocks, java.util.List.of());
        var chunks = PdfContent.chunks(java.util.List.of(page), 200, 20);
        assertEquals(3, chunks.size());
        assertEquals("采购规则\n\n金额 123.45 元。\n\n需要批准。", chunks.get(0).text());
        assertEquals("table", chunks.get(1).source().type());
        assertEquals(
                java.util.List.of("b0", "b1", "b2"),
                chunks.get(0).source().spans().stream().map(PdfContent.Span::blockId).toList());
    }

    @TempDir Path root;

    @Test
    void ordinaryPdfNeverMergesDifferentPagesIntoOnePassage() throws Exception {
        Path file = pdf(false);
        assertEquals(2, DocumentTexts.passages(file, "pages.pdf", 10000, 0).size());
    }

    @Test
    void mixedPdfCannotSilentlyDropAnImageOnlyPage() throws Exception {
        Path file = pdf(true);
        assertThrows(
                IllegalArgumentException.class,
                () -> DocumentTexts.passages(file, "mixed.pdf", 10000, 0));
    }

    @Test
    void missingVisionIsRecordedOnTheSpecificMixedPage() throws Exception {
        var pages =
                new PdfParser()
                        .parse(
                                pdf(true),
                                java.util.List.of(),
                                count -> assertEquals(2, count),
                                ignored -> {});
        assertEquals("ready", pages.get(0).status());
        assertEquals("failed", pages.get(1).status());
        assertEquals(2, pages.get(1).pageNumber());
        org.junit.jupiter.api.Assertions.assertTrue(
                pages.get(1).warnings().get(0).contains("视觉模型"));
    }

    @Test
    void retriesReuseSuccessfulPagesAndSupplyRealPageImage() throws Exception {
        Path file = pdf(true);
        var saved = new PdfParser().parse(file, java.util.List.of(), ignored -> {}, ignored -> {});
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        PdfParser.Vision vision =
                new PdfParser.Vision() {
                    public boolean available() {
                        return true;
                    }

                    public PdfContent.Page parse(byte[] image, PdfContent.Page page)
                            throws Exception {
                        calls.incrementAndGet();
                        var rendered =
                                javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image));
                        org.junit.jupiter.api.Assertions.assertTrue(
                                Math.max(rendered.getWidth(), rendered.getHeight()) <= 1000);
                        return new PdfContent.Page(
                                page.pageNumber(),
                                page.pageLabel(),
                                page.width(),
                                page.height(),
                                "ready",
                                "vision",
                                page.nativeText(),
                                java.util.List.of(
                                        new PdfContent.Block(
                                                "b0",
                                                "paragraph",
                                                "Scanned evidence",
                                                null,
                                                false)),
                                java.util.List.of("模型转录"));
                    }
                };
        var pages =
                new PdfParser(vision, 10, 1000).parse(file, saved, ignored -> {}, ignored -> {});
        assertEquals(1, calls.get());
        var chunks = PdfContent.chunks(pages, 200, 20);
        assertEquals(2, chunks.size());
        assertEquals("vision", chunks.get(1).source().method());
        assertEquals(2, chunks.get(1).source().pageNumber());
    }

    @Test
    void refusesOversizedPageCountBeforeRendering() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PdfParser(null, 1, 1000)
                                .parse(
                                        pdf(false),
                                        java.util.List.of(),
                                        ignored -> {},
                                        ignored -> {}));
    }

    @Test
    void blankPagesAreExplicitAndDoNotGenerateEvidence() throws Exception {
        Path file = root.resolve("blank.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(file.toFile());
        }
        var pages = new PdfParser().parse(file, java.util.List.of(), ignored -> {}, ignored -> {});
        assertEquals("blank", pages.get(0).status());
        assertEquals(0, PdfContent.chunks(pages, 200, 20).size());
    }

    @Test
    void inlineImagesWithNativeTitlesAreNotSilentlyIgnored() throws Exception {
        Path file = pdf(false);
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
            var extra = new org.apache.pdfbox.pdmodel.common.PDStream(document);
            try (var content = extra.createOutputStream()) {
                content.write(
                        "q 400 0 0 500 20 20 cm BI /W 1 /H 1 /CS /RGB /BPC 8 ID "
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                content.write(new byte[] {0, 0, 0});
                content.write(" EI Q\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            var streams = new java.util.ArrayList<org.apache.pdfbox.pdmodel.common.PDStream>();
            document.getPage(0).getContentStreams().forEachRemaining(streams::add);
            streams.add(extra);
            document.getPage(0).setContents(streams);
            document.save(root.resolve("inline.pdf").toFile());
        }
        var pages =
                new PdfParser()
                        .parse(
                                root.resolve("inline.pdf"),
                                java.util.List.of(),
                                ignored -> {},
                                ignored -> {});
        assertEquals("review", pages.get(0).status());
    }

    @Test
    void coordinatesFollowPhysicalPageRotation() throws Exception {
        Path file = pdf(false);
        var baseline =
                new PdfParser()
                        .parse(file, java.util.List.of(), ignored -> {}, ignored -> {})
                        .get(0)
                        .blocks()
                        .get(0)
                        .box();
        for (int rotation : new int[] {90, 180, 270}) {
            Path rotated = root.resolve("rotated-" + rotation + ".pdf");
            try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
                document.getPage(0).setRotation(rotation);
                document.save(rotated.toFile());
            }
            var actual =
                    new PdfParser()
                            .parse(rotated, java.util.List.of(), ignored -> {}, ignored -> {})
                            .get(0)
                            .blocks()
                            .get(0)
                            .box();
            double expectedX =
                    rotation == 90
                            ? 1 - baseline.y() - baseline.height()
                            : rotation == 180 ? 1 - baseline.x() - baseline.width() : baseline.y();
            double expectedY =
                    rotation == 90
                            ? baseline.x()
                            : rotation == 180
                                    ? 1 - baseline.y() - baseline.height()
                                    : 1 - baseline.x() - baseline.width();
            assertEquals(expectedX, actual.x(), .001);
            assertEquals(expectedY, actual.y(), .001);
        }
    }

    @Test
    void tableChunksRepeatHeadersAndNeverInventAPage() {
        var page =
                new PdfContent.Page(
                        7,
                        "iii",
                        600,
                        800,
                        "ready",
                        "vision",
                        "",
                        java.util.List.of(
                                new PdfContent.Block(
                                        "b0",
                                        "table",
                                        "|Item|CNY|\n|---|---|\n" + "|sample|100.00|\n".repeat(10),
                                        null,
                                        false)),
                        java.util.List.of());
        var chunks = PdfContent.chunks(java.util.List.of(page), 80, 0);
        org.junit.jupiter.api.Assertions.assertTrue(chunks.size() > 1);
        for (var chunk : chunks) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    chunk.text().startsWith("|Item|CNY|\n|---|---|"));
            org.junit.jupiter.api.Assertions.assertTrue(chunk.text().length() <= 80);
            assertEquals(7, chunk.source().pageNumber());
            assertEquals("iii", chunk.source().pageLabel());
        }
    }

    Path pdf(boolean scanned) throws Exception {
        Path file = root.resolve(scanned ? "mixed.pdf" : "native.pdf");
        try (PDDocument pdf = new PDDocument()) {
            for (int page = 0; page < 2; page++) {
                PDPage sheet = new PDPage();
                pdf.addPage(sheet);
                try (PDPageContentStream content = new PDPageContentStream(pdf, sheet)) {
                    if (scanned && page == 1) {
                        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
                        content.drawImage(
                                LosslessFactory.createFromImage(pdf, image), 20, 20, 400, 600);
                    } else {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(30, 700);
                        content.showText("Evidence on page " + (page + 1));
                        content.endText();
                    }
                }
            }
            pdf.save(file.toFile());
        }
        return file;
    }
}
