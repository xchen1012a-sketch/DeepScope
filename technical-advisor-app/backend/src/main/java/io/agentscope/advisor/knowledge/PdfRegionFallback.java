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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** One nonrecursive split, only along a fully blank band, preserving original coordinates. */
final class PdfRegionFallback {
    private PdfRegionFallback() {}

    static PdfContent.Page parse(
            byte[] png,
            PdfContent.Page original,
            PdfParser.Vision vision,
            boolean columns,
            int rotation)
            throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) return null;
        int turn = Math.floorMod(rotation, 360);
        if (turn != 0) {
            BufferedImage canonical = unrotate(image, turn);
            image.flush();
            image = canonical;
        }
        try {
            boolean vertical = columns;
            int seam = seam(image, vertical);
            if (seam < 0 && vertical) {
                vertical = false;
                seam = seam(image, false);
            }
            if (seam < 0) return null;
            List<PdfContent.Block> blocks = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            for (int part = 0; part < 2; part++) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("分区解析已取消");
                int x = vertical && part == 1 ? seam : 0;
                int y = !vertical && part == 1 ? seam : 0;
                int width =
                        vertical ? (part == 0 ? seam : image.getWidth() - seam) : image.getWidth();
                int height =
                        !vertical
                                ? (part == 0 ? seam : image.getHeight() - seam)
                                : image.getHeight();
                byte[] crop;
                try (var bytes = new ByteArrayOutputStream()) {
                    ImageIO.write(image.getSubimage(x, y, width, height), "png", bytes);
                    crop = bytes.toByteArray();
                }
                var region =
                        new PdfContent.Page(
                                original.pageNumber(),
                                original.pageLabel(),
                                (turn == 90 || turn == 270 ? original.height() : original.width())
                                        * width
                                        / image.getWidth(),
                                (turn == 90 || turn == 270 ? original.width() : original.height())
                                        * height
                                        / image.getHeight(),
                                "ready",
                                "region",
                                "",
                                List.of(),
                                List.of());
                var parsed = vision.parse(crop, region);
                if (parsed == null
                        || parsed.needsRetry()
                        || !List.of("ready", "blank").contains(parsed.status()))
                    throw new IllegalArgumentException("分区解析内容不完整");
                warnings.addAll(parsed.warnings());
                for (var block : parsed.blocks()) {
                    var box = block.box();
                    var mapped =
                            box == null
                                    ? null
                                    : new PdfContent.Box(
                                            (x + box.x() * width) / image.getWidth(),
                                                    (y + box.y() * height) / image.getHeight(),
                                            box.width() * width / image.getWidth(),
                                                    box.height() * height / image.getHeight());
                    blocks.add(
                            new PdfContent.Block(
                                    "b" + blocks.size(),
                                    block.type(),
                                    block.text(),
                                    rotateBox(mapped, turn),
                                    block.interpretation()));
                }
            }
            if (blocks.isEmpty()) throw new IllegalArgumentException("分区空白判断与原页非空矛盾");
            warnings.add("[region_fallback] 本页由两个安全分区识别并映射回原页");
            return new PdfContent.Page(
                    original.pageNumber(),
                    original.pageLabel(),
                    original.width(),
                    original.height(),
                    "ready",
                    "vision",
                    original.nativeText(),
                    blocks,
                    warnings.stream().distinct().toList());
        } finally {
            image.flush();
        }
    }

    private static BufferedImage unrotate(BufferedImage image, int turn) {
        int width = image.getWidth(), height = image.getHeight();
        var result =
                new BufferedImage(
                        turn == 180 ? width : height,
                        turn == 180 ? height : width,
                        BufferedImage.TYPE_INT_RGB);
        var graphics = result.createGraphics();
        try {
            switch (turn) {
                case 90 -> {
                    graphics.translate(0, width);
                    graphics.rotate(-Math.PI / 2);
                }
                case 180 -> {
                    graphics.translate(width, height);
                    graphics.rotate(Math.PI);
                }
                case 270 -> {
                    graphics.translate(height, 0);
                    graphics.rotate(Math.PI / 2);
                }
                default -> throw new IllegalArgumentException("PDF 页面旋转无效");
            }
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static PdfContent.Box rotateBox(PdfContent.Box box, int turn) {
        if (box == null) return null;
        return switch (turn) {
            case 90 ->
                    new PdfContent.Box(
                            Math.max(0, 1 - box.y() - box.height()),
                            box.x(),
                            box.height(),
                            box.width());
            case 180 ->
                    new PdfContent.Box(
                            Math.max(0, 1 - box.x() - box.width()),
                            Math.max(0, 1 - box.y() - box.height()),
                            box.width(),
                            box.height());
            case 270 ->
                    new PdfContent.Box(
                            box.y(),
                            Math.max(0, 1 - box.x() - box.width()),
                            box.height(),
                            box.width());
            default -> box;
        };
    }

    private static int seam(BufferedImage image, boolean vertical) {
        int length = vertical ? image.getWidth() : image.getHeight();
        int breadth = vertical ? image.getHeight() : image.getWidth();
        for (int offset = 0; offset < length * .15; offset++) {
            for (int position : new int[] {length / 2 - offset, length / 2 + offset}) {
                boolean blank = true;
                for (int band = -1; band <= 1 && blank; band++) {
                    for (int cross = 0; cross < breadth; cross++) {
                        int rgb =
                                vertical
                                        ? image.getRGB(position + band, cross)
                                        : image.getRGB(cross, position + band);
                        if ((rgb >> 16 & 255) < 235
                                || (rgb >> 8 & 255) < 235
                                || (rgb & 255) < 235) {
                            blank = false;
                            break;
                        }
                    }
                }
                if (blank) return position;
            }
        }
        return -1;
    }
}
