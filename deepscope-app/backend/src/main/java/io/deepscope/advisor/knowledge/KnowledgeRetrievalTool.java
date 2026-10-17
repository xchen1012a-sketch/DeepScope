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

import io.deepscope.advisor.session.TimelineStore;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.message.ToolResultBlock;
import io.deepscope.core.tool.ToolBase;
import io.deepscope.core.tool.ToolCallParam;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** Searches the libraries in scope for this agent at the moment of the call. */
public final class KnowledgeRetrievalTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalTool.class);

    private final String agentId;
    private final KnowledgeSearch search;
    private final TimelineStore timeline;

    public KnowledgeRetrievalTool(String agentId, KnowledgeSearch search, TimelineStore timeline) {
        super(
                builder()
                        .name("retrieve_knowledge")
                        .description(
                                "【业务分类：知识库只读检索】\n"
                                    + "【适用场景】：仅当用户明确询问专有领域业务事实、产品规格、企业内部文档或上传资料等需要实据佐证的内容时调用。\n"
                                    + "【禁用场景】：用户进行日常问候、概念常识解释、逻辑推导、代码编写或对上下文内容进行总结归纳时，严禁调用本工具。\n"
                                    + "【审核规则】：本工具为只读查询，免人工审核。\n"
                                    + "【执行规范】：检索结果中若有物理页码须准确引用，不得编造材料与来源；若未检索到相关内容，直接据实告知，严禁声称已在文档中查证。")
                        .inputSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "query",
                                                Map.of("type", "string", "description", "要查的问题")),
                                        "required",
                                        List.of("query"))));
        this.agentId = agentId;
        this.search = search;
        this.timeline = timeline;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> answer(param));
    }

    private ToolResultBlock answer(ToolCallParam param) throws SQLException {
        String query = "";
        if (param.getInput() != null && param.getInput().get("query") != null) {
            query = param.getInput().get("query").toString();
        }
        List<String> libraryIds = search.scope(agentId);
        if (libraryIds.isEmpty()) {
            return ToolResultBlock.text("没有可查的已就绪文档，本次没有检索。");
        }
        List<KnowledgeSearch.Found> found = search.search(libraryIds, query);
        remember(param.getRuntimeContext(), found);
        if (found.isEmpty()) {
            return ToolResultBlock.text("没有达到相似度门槛的段落。");
        }
        StringBuilder text = new StringBuilder();
        for (KnowledgeSearch.Found hit : found) {
            text.append("库：")
                    .append(hit.libraryName())
                    .append("\n文档：")
                    .append(hit.filename())
                    .append("\n排名分（非相似度）：")
                    .append(String.format("%.3f", hit.score()))
                    .append("\n")
                    .append(hit.passage())
                    .append("\n\n");
            if (hit.source() != null) {
                text.append("来源：PDF 第 ")
                        .append(hit.source().pageNumber())
                        .append(" 页；")
                        .append(hit.source().method())
                        .append(hit.source().interpretation() ? "；图表解释" : "")
                        .append("\n");
                if (hit.source().pageLabel() != null)
                    text.append("印刷页标签：").append(hit.source().pageLabel()).append("\n");
                text.append("原页链接：[查看第 ")
                        .append(hit.source().pageNumber())
                        .append(" 页](/knowledge/")
                        .append(hit.libraryId())
                        .append("?document=")
                        .append(hit.documentId())
                        .append("&page=")
                        .append(hit.source().pageNumber())
                        .append(")\n");
            }
            for (String warning : hit.warnings()) text.append("注意：").append(warning).append("\n");
            for (PdfContent.Chunk context : hit.context()) {
                text.append("相邻上下文（第 ")
                        .append(context.source().pageNumber())
                        .append(" 页，")
                        .append(context.source().method())
                        .append(context.source().interpretation() ? "，图表解释" : "")
                        .append("）：\n")
                        .append(context.text())
                        .append("\n");
            }
        }
        return ToolResultBlock.text(text.toString().strip());
    }

    private void remember(RuntimeContext runtimeContext, List<KnowledgeSearch.Found> found) {
        if (timeline == null || runtimeContext == null || found.isEmpty()) {
            return;
        }
        Object session = runtimeContext.getExtra().get("advisorSessionId");
        if (!(session instanceof String sessionId) || sessionId.isBlank()) {
            return;
        }
        List<Map<String, Object>> hits = new ArrayList<>();
        for (KnowledgeSearch.Found hit : found) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("library", hit.libraryName());
            view.put("document", hit.filename());
            view.put("score", hit.score());
            view.put("passage", hit.passage());
            view.put("libraryId", hit.libraryId());
            view.put("documentId", hit.documentId());
            view.put("source", hit.source());
            view.put("context", hit.context());
            view.put("warnings", hit.warnings());
            view.put("rankingMethod", hit.rankingMethod());
            hits.add(view);
        }
        try {
            timeline.append(sessionId, TimelineStore.KNOWLEDGE, Map.of("hits", hits));
        } catch (SQLException error) {
            log.warn("Knowledge hits were not written to the timeline: {}", error.getMessage());
        }
    }
}
