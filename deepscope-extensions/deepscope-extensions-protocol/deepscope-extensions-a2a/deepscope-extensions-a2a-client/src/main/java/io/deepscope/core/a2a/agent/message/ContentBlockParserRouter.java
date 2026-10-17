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

package io.deepscope.core.a2a.agent.message;

import io.a2a.spec.Part;
import io.deepscope.core.a2a.agent.utils.LoggerUtil;
import io.deepscope.core.message.AudioBlock;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.HintBlock;
import io.deepscope.core.message.ImageBlock;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.message.ThinkingBlock;
import io.deepscope.core.message.ToolResultBlock;
import io.deepscope.core.message.ToolUseBlock;
import io.deepscope.core.message.VideoBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The router for {@link ContentBlockParser} according to class type of {@link ContentBlock}.
 */
public class ContentBlockParserRouter {

    private static final Logger log = LoggerFactory.getLogger(ContentBlockParserRouter.class);

    /**
     * Parse {@link ContentBlock} to {@link Part}.
     *
     * @param contentBlock the content block to parse
     * @return the parsed part, or null if the part is null or not supported
     */
    public Part<?> parse(ContentBlock contentBlock) {
        if (null == contentBlock) {
            return null;
        }
        if (contentBlock instanceof TextBlock textBlock) {
            return new TextBlockParser().parse(textBlock);
        } else if (contentBlock instanceof ThinkingBlock thinkingBlock) {
            return new ThinkingBlockParser().parse(thinkingBlock);
        } else if (contentBlock instanceof HintBlock hintBlock) {
            return new HintBlockParser().parse(hintBlock);
        } else if (contentBlock instanceof ImageBlock imageBlock) {
            return new ImageBlockParser().parse(imageBlock);
        } else if (contentBlock instanceof AudioBlock audioBlock) {
            return new AudioBlockParser().parse(audioBlock);
        } else if (contentBlock instanceof VideoBlock videoBlock) {
            return new VideoBlockParser().parse(videoBlock);
        } else if (contentBlock instanceof ToolUseBlock toolUseBlock) {
            return new ToolUseBlockParser().parse(toolUseBlock);
        } else if (contentBlock instanceof ToolResultBlock toolResultBlock) {
            return new ToolResultBlockParser().parse(toolResultBlock);
        }
        LoggerUtil.warn(
                log,
                "Unsupported content block type: {}, ignore this content block.",
                contentBlock.getClass().getName());
        return null;
    }
}
