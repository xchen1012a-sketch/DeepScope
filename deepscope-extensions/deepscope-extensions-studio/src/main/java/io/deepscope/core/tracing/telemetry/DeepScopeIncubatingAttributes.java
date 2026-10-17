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

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

import io.opentelemetry.api.common.AttributeKey;

public class DeepScopeIncubatingAttributes {

    static final AttributeKey<String> DEEPSCOPE_FUNCTION_NAME =
            stringKey("deepscope.function.name");

    static final AttributeKey<String> DEEPSCOPE_FUNCTION_INPUT =
            stringKey("deepscope.function.input");

    static final AttributeKey<String> DEEPSCOPE_FUNCTION_OUTPUT =
            stringKey("deepscope.function.output");

    static final AttributeKey<String> DEEPSCOPE_FORMAT_TARGET =
            stringKey("deepscope.format.output");

    static final AttributeKey<String> DEEPSCOPE_FORMAT_COUNT = stringKey("deepscope.format.count");

    static final AttributeKey<Long> DEEPSCOPE_USAGE_TOOL_USE_PROMPT_TOKENS =
            longKey("deepscope.usage.tool_use_prompt_tokens");

    static final class GenAiOperationNameDeepScopeIncubatingValues {
        static final String FORMAT = "format";

        static final String INVOKE_GENERIC_FUNCTION = "invoke_generic_function";

        private GenAiOperationNameDeepScopeIncubatingValues() {}
    }

    static final class GenAiProviderNameDeepScopeIncubatingValues {
        static final String DASHSCOPE = "dashscope";

        static final String MOONSHOT = "moonshot";

        private GenAiProviderNameDeepScopeIncubatingValues() {}
    }

    private DeepScopeIncubatingAttributes() {}
}
