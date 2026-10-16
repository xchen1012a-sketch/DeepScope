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

import java.util.ArrayList;
import java.util.List;

/** Physical page numbers and normalized, top-left origin rectangles travel with the evidence. */
public final class PdfContent {
    private PdfContent() {}

    public record Box(double x, double y, double width, double height) {
        public Box {
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(width)
                    || !Double.isFinite(height)
                    || x < 0
                    || y < 0
                    || width <= 0
                    || height <= 0
                    || x + width > 1.001
                    || y + height > 1.001) {
                throw new IllegalArgumentException("页内坐标无效");
            }
        }
    }

    public record Block(String id, String type, String text, Box box, boolean interpretation) {}

    public record Page(
            int pageNumber,
            String pageLabel,
            double width,
            double height,
            String status,
            String method,
            String nativeText,
            List<Block> blocks,
            List<String> warnings) {
        public Page {
            blocks = List.copyOf(blocks);
            warnings = List.copyOf(warnings);
        }

        public boolean needsRetry() {
            return "failed".equals(status) || "review".equals(status);
        }
    }

    public record Span(String blockId, String type, Box box) {}

    public record Source(
            int pageNumber,
            String pageLabel,
            String blockId,
            String type,
            Box box,
            String method,
            boolean interpretation,
            List<String> warnings,
            List<Span> spans) {
        public Source {
            spans = spans == null ? List.of(new Span(blockId, type, box)) : List.copyOf(spans);
        }

        public Source(
                int pageNumber,
                String pageLabel,
                String blockId,
                String type,
                Box box,
                String method,
                boolean interpretation,
                List<String> warnings) {
            this(
                    pageNumber,
                    pageLabel,
                    blockId,
                    type,
                    box,
                    method,
                    interpretation,
                    warnings,
                    List.of(new Span(blockId, type, box)));
        }
    }

    public record Chunk(String chunkId, String text, Source source) {}

    public static List<Chunk> chunks(List<Page> pages, int size, int overlap) {
        if (size < 1 || overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("切片长度与重叠长度无效");
        }
        List<Chunk> chunks = new ArrayList<>();
        for (Page page : pages) {
            if ("failed".equals(page.status())) continue;
            for (List<Block> group : textGroups(page.blocks(), size)) {
                Block block = group.get(0);
                String content =
                        group.stream()
                                .map(Block::text)
                                .collect(java.util.stream.Collectors.joining("\n\n"));
                Source source =
                        new Source(
                                page.pageNumber(),
                                page.pageLabel(),
                                block.id(),
                                block.type(),
                                union(group),
                                page.method(),
                                block.interpretation(),
                                page.warnings(),
                                group.stream()
                                        .map(item -> new Span(item.id(), item.type(), item.box()))
                                        .toList());
                List<String> texts =
                        "table".equals(block.type())
                                ? tableChunks(content, size)
                                : split(content, size, overlap);
                int part = 0;
                for (String text : texts) {
                    chunks.add(
                            new Chunk(
                                    page.pageNumber() + ":" + block.id() + ":" + part++,
                                    text,
                                    source));
                }
            }
        }
        return List.copyOf(chunks);
    }

    private static List<List<Block>> textGroups(List<Block> blocks, int size) {
        List<List<Block>> groups = new ArrayList<>();
        List<Block> pending = new ArrayList<>();
        int length = 0;
        for (Block block : blocks) {
            boolean text =
                    !block.interpretation()
                            && List.of("heading", "paragraph", "list").contains(block.type());
            if (!text
                    || "heading".equals(block.type())
                    || length + 2 + block.text().length() > size) {
                if (!pending.isEmpty()) groups.add(List.copyOf(pending));
                pending.clear();
                length = 0;
            }
            if ("header".equals(block.type()) || "footer".equals(block.type())) continue;
            if (!text) {
                groups.add(List.of(block));
                continue;
            }
            pending.add(block);
            length += block.text().length() + (pending.size() > 1 ? 2 : 0);
        }
        if (!pending.isEmpty()) groups.add(List.copyOf(pending));
        return groups;
    }

    private static Box union(List<Block> group) {
        if (group.stream().anyMatch(block -> block.box() == null)) return null;
        double x = group.stream().mapToDouble(block -> block.box().x()).min().orElseThrow();
        double y = group.stream().mapToDouble(block -> block.box().y()).min().orElseThrow();
        double right =
                group.stream()
                        .mapToDouble(block -> block.box().x() + block.box().width())
                        .max()
                        .orElseThrow();
        double bottom =
                group.stream()
                        .mapToDouble(block -> block.box().y() + block.box().height())
                        .max()
                        .orElseThrow();
        return new Box(x, y, right - x, bottom - y);
    }

    private static List<String> tableChunks(String text, int size) {
        String[] lines = text.split("\\R");
        if (lines.length < 3 || !lines[1].matches("[\\s|:\\-]+")) return split(text, size, 0);
        String header = lines[0] + "\n" + lines[1] + "\n";
        if (header.length() >= size / 2) return split(text, size, 0);
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        for (int row = 2; row < lines.length; row++) {
            for (String line : split(lines[row], size - header.length(), 0)) {
                if (current.length() + line.length() + 1 > size
                        && current.length() > header.length()) {
                    parts.add(current.toString().strip());
                    current = new StringBuilder(header);
                }
                current.append(line).append('\n');
            }
        }
        if (current.length() > header.length()) parts.add(current.toString().strip());
        return parts;
    }

    private static List<String> split(String text, int size, int overlap) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isBlank()) return parts;
        for (int start = 0; start < text.length(); ) {
            int end = Math.min(start + size, text.length());
            if (end < text.length()) {
                int boundary =
                        Math.max(text.lastIndexOf('\n', end - 1), text.lastIndexOf('。', end - 1));
                if (boundary > start + size / 2) end = boundary + 1;
            }
            String part = text.substring(start, end).strip();
            if (!part.isBlank()) parts.add(part);
            if (end == text.length()) break;
            start = Math.max(start + 1, end - overlap);
        }
        return parts;
    }
}
