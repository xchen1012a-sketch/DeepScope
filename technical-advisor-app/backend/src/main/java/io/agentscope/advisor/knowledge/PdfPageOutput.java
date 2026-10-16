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

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validates evidence independently from optional location and presentation metadata. */
final class PdfPageOutput {
    private static final Set<String> TYPES =
            Set.of("heading", "paragraph", "list", "table", "image", "header", "footer");
    private static final Set<String> NON_BLOCKING =
            Set.of("coordinates_estimated", "location_unavailable", "reading_order_note");
    private static final Set<String> CONTENT_ISSUES =
            Set.of("content_missing", "unreadable", "truncated", "table_incomplete");

    private PdfPageOutput() {}

    static PdfContent.Page decode(JsonNode root, PdfContent.Page original) {
        if (root == null
                || !root.isObject()
                || !root.path("blocks").isArray()
                || root.path("blocks").size() > 500
                || !root.path("warnings").isArray()
                || root.has("complete") && !root.path("complete").isBoolean()
                || root.has("blank") && !root.path("blank").isBoolean()) throw invalid();
        List<String> warnings = new ArrayList<>();
        warnings.add("模型转录与定位未经人工核验");
        boolean incomplete = root.has("complete") && !root.path("complete").asBoolean();
        for (JsonNode warning : root.path("warnings")) {
            if (!warning.isTextual() || warning.asText().length() > 1000) throw invalid();
            String value = warning.asText();
            warnings.add(value);
            if (!value.isBlank() && !locationOnly(value)) incomplete = true;
        }
        if (root.has("issues")) {
            if (!root.path("issues").isArray() || root.path("issues").size() > 100) throw invalid();
            for (var issue : root.path("issues")) {
                if (!issue.path("code").isTextual()
                        || !issue.path("severity").isTextual()
                        || !issue.path("message").isTextual()
                        || issue.path("message").asText().length() > 1000) throw invalid();
                String code = issue.path("code").asText();
                String severity = issue.path("severity").asText();
                if (!Set.of("info", "warning", "error").contains(severity)) throw invalid();
                if (CONTENT_ISSUES.contains(code)
                        || "error".equals(severity)
                        || !NON_BLOCKING.contains(code)) incomplete = true;
                warnings.add(
                        "["
                                + (NON_BLOCKING.contains(code) || CONTENT_ISSUES.contains(code)
                                        ? code
                                        : "unknown_issue")
                                + "] "
                                + issue.path("message").asText());
            }
        }
        List<PdfContent.Block> blocks = new ArrayList<>();
        for (var block : root.path("blocks")) {
            String type = block.path("type").asText();
            if (!TYPES.contains(type) || !block.path("interpretation").isBoolean()) throw invalid();
            boolean interpretation = block.path("interpretation").asBoolean();
            if ("image".equals(type) != interpretation)
                throw new IllegalArgumentException("图表解释必须独立标记");
            String text =
                    "table".equals(type) && block.has("table")
                            ? table(block.path("table"))
                            : block.path("text").asText("").strip();
            if (text.isBlank()) throw invalid();
            PdfContent.Box location = null;
            try {
                location = box(block.get("box"));
            } catch (IllegalArgumentException invalidLocation) {
                warnings.add("[location_unavailable] 此块位置无效，保留原文与物理页，不提供区域定位");
            }
            blocks.add(
                    new PdfContent.Block(
                            "b" + blocks.size(), type, text, location, interpretation));
        }
        boolean blank = root.path("blank").asBoolean(false);
        if (blocks.isEmpty() && !blank || blank && !blocks.isEmpty()) throw invalid();
        if (blank && !original.nativeText().isBlank())
            throw new IllegalArgumentException("模型空白页判断与已提取正文矛盾");
        if (incomplete) warnings.add("[content_incomplete] 本页内容完整性未通过校验");
        return new PdfContent.Page(
                original.pageNumber(),
                original.pageLabel(),
                original.width(),
                original.height(),
                incomplete ? "review" : blank ? "blank" : "ready",
                "vision",
                original.nativeText(),
                blocks,
                warnings);
    }

    private static boolean locationOnly(String value) {
        // Legacy free text is exempt only when the entire note is an understood location note.
        // A note containing additional content concerns must remain blocking.
        String text = value.strip().toLowerCase(java.util.Locale.ROOT);
        return text.matches(
                        "(?:box\\s*)?坐标(?:为|是)?(?:基于页面布局的)?(?:估算值|估计值|估算)(?:[，,]可能与实际渲染位置存在偏差)?[。.]?")
                || text.matches(
                        "(?:bounding box|coordinates?) (?:are |is )?(?:estimated|approximate)\\.?")
                || text.matches("双栏内容已按阅读顺序输出[。.]?");
    }

    private static PdfContent.Box box(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isArray() || node.size() != 4) throw new IllegalArgumentException("页内坐标无效");
        for (var value : node) if (!value.isNumber()) throw new IllegalArgumentException("页内坐标无效");
        return new PdfContent.Box(
                node.get(0).asDouble(),
                node.get(1).asDouble(),
                node.get(2).asDouble(),
                node.get(3).asDouble());
    }

    private static String table(JsonNode table) {
        var columns = table.path("columns");
        var rows = table.path("rows");
        if (!columns.isArray()
                || columns.isEmpty()
                || columns.size() > 100
                || !rows.isArray()
                || rows.size() > 2000) throw invalid();
        StringBuilder text = new StringBuilder(row(columns));
        text.append("\n| ")
                .append(String.join(" | ", java.util.Collections.nCopies(columns.size(), "---")))
                .append(" |");
        for (var cells : rows) {
            if (!cells.isArray() || cells.size() != columns.size()) throw invalid();
            text.append('\n').append(row(cells));
        }
        return text.toString();
    }

    private static String row(JsonNode cells) {
        List<String> values = new ArrayList<>();
        for (var cell : cells) {
            if (!cell.isTextual() && !cell.isNull()) throw invalid();
            values.add(
                    cell.isNull()
                            ? ""
                            : cell.asText()
                                    .replace("\\", "\\\\")
                                    .replace("|", "\\|")
                                    .replaceAll("\\R", " "));
        }
        return "| " + String.join(" | ", values) + " |";
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("视觉解析结构不完整或字段无效");
    }
}
