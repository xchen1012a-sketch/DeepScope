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

package io.deepscope.core.tracing.telemetry;

import io.deepscope.core.agent.accumulator.TextAccumulator;
import io.deepscope.core.agent.accumulator.ThinkingAccumulator;
import io.deepscope.core.agent.accumulator.ToolCallsAccumulator;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.message.ThinkingBlock;
import io.deepscope.core.message.ToolUseBlock;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.ChatUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * An aggregator for streaming {@link ChatResponse}.
 * */
final class StreamChatResponseAggregator {

    private String id;

    // Only text output is currently supported.
    private final TextAccumulator textAcc = new TextAccumulator();
    private final ThinkingAccumulator thinkingAcc = new ThinkingAccumulator();
    private final ToolCallsAccumulator toolCallsAcc = new ToolCallsAccumulator();

    // Usage: take the max value from all chunks, since providers report cumulative totals
    private int inputTokens;
    private int outputTokens;
    private int cachedTokens;
    private int cacheCreationTokens;
    private int reasoningTokens;
    private int toolUsePromptTokens;
    private double time;

    private String finishReason;

    public void append(ChatResponse chunk) {
        if (chunk == null) {
            return;
        }
        if (chunk.getId() != null) {
            id = chunk.getId();
        }

        // See io.deepscope.core.agent.accumulator.ReasoningContext.processChunk for more
        // information
        List<ContentBlock> chunkContents = chunk.getContent();
        if (chunkContents != null) {
            for (ContentBlock block : chunkContents) {
                if (block instanceof TextBlock tb) {
                    textAcc.add(tb);
                } else if (block instanceof ThinkingBlock tb) {
                    thinkingAcc.add(tb);
                } else if (block instanceof ToolUseBlock tub) {
                    toolCallsAcc.add(tub);
                }
            }
        }

        ChatUsage usage = chunk.getUsage();
        if (usage != null) {
            inputTokens = Math.max(inputTokens, usage.getInputTokens());
            outputTokens = Math.max(outputTokens, usage.getOutputTokens());
            cachedTokens = Math.max(cachedTokens, usage.getCachedTokens());
            cacheCreationTokens = Math.max(cacheCreationTokens, usage.getCacheCreationTokens());
            reasoningTokens = Math.max(reasoningTokens, usage.getReasoningTokens());
            toolUsePromptTokens = Math.max(toolUsePromptTokens, usage.getToolUsePromptTokens());
            time = usage.getTime();
        }

        if (chunk.getFinishReason() != null) {
            finishReason = chunk.getFinishReason();
        }
    }

    public ChatResponse getResponse() {
        List<ToolUseBlock> toolUseBlocks = toolCallsAcc.buildAllToolCalls();
        List<ContentBlock> contentBlocks = new ArrayList<>(toolUseBlocks.size() + 2);
        contentBlocks.add(textAcc.buildAggregated());
        contentBlocks.add(thinkingAcc.buildAggregated());
        contentBlocks.addAll(toolUseBlocks);

        return ChatResponse.builder()
                .id(id)
                .content(contentBlocks)
                .usage(
                        ChatUsage.builder()
                                .inputTokens(inputTokens)
                                .outputTokens(outputTokens)
                                .cachedTokens(cachedTokens)
                                .cacheCreationTokens(cacheCreationTokens)
                                .reasoningTokens(reasoningTokens)
                                .toolUsePromptTokens(toolUsePromptTokens)
                                .time(time)
                                .build())
                .finishReason(finishReason)
                .build();
    }

    public static StreamChatResponseAggregator create() {
        return new StreamChatResponseAggregator();
    }

    private StreamChatResponseAggregator() {}
}
