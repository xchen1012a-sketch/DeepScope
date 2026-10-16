/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.context;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.util.JsonUtils;

/** Replace with an adapter-specific tokenizer when available. Estimates are not provider usage. */
@FunctionalInterface
public interface ContextTokenEstimator {
    /**
     * Latin text averages about four characters per token (the same ratio the eviction docs use).
     * One source for the whole budget chain — do not fork it per call site.
     */
    double CHARS_PER_TOKEN = 4.0;

    Estimate estimate(ModelCallInput input);

    record Estimate(long tokens, String method, boolean exact) {
        public Estimate {
            if (tokens < 0 || method == null)
                throw new IllegalArgumentException("Invalid estimate");
        }
    }

    static ContextTokenEstimator approximate() {
        return input -> {
            long count = 0;
            for (var msg : input.messages()) {
                count += 12 + text(msg.getName());
                for (var block : msg.getContent()) count += block(block);
            }
            for (var tool : input.tools()) {
                count += 16 + text(JsonUtils.getJsonCodec().toJson(tool));
            }
            return new Estimate(count, "mixed-text-4chars-cjk1-media-2048-v2", false);
        };
    }

    /**
     * CJK chars are roughly one token each on modern tokenizers — counting them at the Latin
     * ratio would undercount Chinese by 2-4x and let oversized Chinese contexts pass validation.
     */
    private static long text(String value) {
        if (value == null) return 0;
        long cjk = 0;
        long other = 0;
        for (int i = 0; i < value.length(); i++) {
            if (isCjk(value.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (long) Math.ceil(other / CHARS_PER_TOKEN);
    }

    private static boolean isCjk(char c) {
        return c >= 0x2E80 && c <= 0x9FFF // CJK radicals, Han
                || c >= 0x3040 && c <= 0x30FF // Hiragana, Katakana
                || c >= 0xF900 && c <= 0xFAFF // CJK compatibility ideographs
                || c >= 0xFF00 && c <= 0xFFEF; // fullwidth forms, CJK punctuation
    }

    private static long block(ContentBlock block) {
        if (block instanceof TextBlock text) return text(text.getText());
        if (block instanceof ThinkingBlock thinking) return text(thinking.getThinking());
        if (block instanceof ToolUseBlock use) {
            return 12
                    + text(use.getId())
                    + text(use.getName())
                    + text(JsonUtils.getJsonCodec().toJson(use.getInput()))
                    + text(use.getContent());
        }
        if (block instanceof ToolResultBlock result) {
            long tokens = 12 + text(result.getId()) + text(result.getName());
            for (var output : result.getOutput()) tokens += block(output);
            return tokens;
        }
        // Explicitly approximate: media cost depends on provider, resolution and duration.
        return 2048;
    }
}
