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
package io.deepscope.core.formatter;

import io.deepscope.core.message.Msg;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.ToolChoice;
import io.deepscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;

/**
 * Formatter interface for converting between DeepScope and provider-specific formats.
 * This is an internal interface used by Model implementations.
 *
 * <p>Formatters are responsible for:
 * 1. Converting Msg objects to provider-specific request format
 * 2. Converting provider-specific responses back to DeepScope ChatResponse
 * 3. Applying generation options to provider-specific request builders
 * 4. Applying tool schemas to provider-specific request builders
 *
 * <p>Each formatter is type-safe and handles the exact types expected by the provider's SDK.
 *
 * <p><b>Dependency:</b> This interface requires the provider-specific extension module.
 * For DashScope, add the following Maven dependency:
 * <pre>{@code
 * <dependency>
 *     <groupId>deepscope</groupId>
 *     <artifactId>deepscope-extensions-model-dashscope</artifactId>
 *     <version>${deepscope.version}</version>
 * </dependency>
 * }</pre>
 *
 * @param <TReq> Provider-specific request message type (e.g., com.alibaba.dashscope.common.Message
 *               for DashScope, or ChatCompletionMessageParam for OpenAI)
 * @param <TResp> Provider-specific response type (e.g., GenerationResult for DashScope,
 *                or ChatCompletion/ChatCompletionChunk for OpenAI)
 * @param <TParams> Provider-specific request parameters builder type (e.g.,
 *                  GenerationParam for DashScope, or ChatCompletionCreateParams.Builder for OpenAI)
 */
public interface Formatter<TReq, TResp, TParams> {

    /**
     * Format DeepScope messages to provider-specific request format.
     *
     * @param msgs List of DeepScope messages
     * @return List of provider-specific request messages
     */
    List<TReq> format(List<Msg> msgs);

    /**
     * Format DeepScope messages using request-scoped generation options.
     *
     * <p>Providers that derive request fields from both message metadata and generation options
     * should override this method. The default implementation preserves the legacy behavior and
     * ignores the options during message conversion.
     *
     * @param msgs list of DeepScope messages
     * @param options request-scoped generation options; may be {@code null}
     * @return list of provider-specific request messages
     */
    default List<TReq> format(List<Msg> msgs, GenerateOptions options) {
        return format(msgs);
    }

    /**
     * Parse provider-specific response to DeepScope ChatResponse.
     *
     * @param response Provider-specific response object
     * @param startTime Request start time for calculating duration
     * @return DeepScope ChatResponse
     */
    ChatResponse parseResponse(TResp response, Instant startTime);

    /**
     * Apply generation options to provider-specific request parameters.
     *
     * @param paramsBuilder Provider-specific request parameters builder
     * @param options Generation options to apply
     * @param defaultOptions Default options to use if options parameter is null
     */
    void applyOptions(
            TParams paramsBuilder, GenerateOptions options, GenerateOptions defaultOptions);

    /**
     * Apply tool schemas to provider-specific request parameters.
     *
     * @param paramsBuilder Provider-specific request parameters builder
     * @param tools List of tool schemas to apply (may be null or empty)
     */
    void applyTools(TParams paramsBuilder, List<ToolSchema> tools);

    /**
     * Apply tool schemas to provider-specific request parameters with provider compatibility handling.
     *
     * <p>This method allows formatters to detect the provider from baseUrl/modelName and adjust
     * tool definitions for compatibility (e.g., removing unsupported parameters like 'strict').
     *
     * <p>The default implementation delegates to {@code applyTools(TParams, List)}.
     * Formatters that support provider-specific tool handling should override this method.
     *
     * @param paramsBuilder Provider-specific request parameters builder
     * @param tools Tool schemas to apply (may be null or empty)
     * @param baseUrl API base URL for provider detection (null for default)
     * @param modelName Model name for provider detection fallback (null)
     */
    default void applyTools(
            TParams paramsBuilder, List<ToolSchema> tools, String baseUrl, String modelName) {
        // Default implementation: delegate to the simpler method
        applyTools(paramsBuilder, tools);
    }

    /**
     * Apply tool choice configuration to provider-specific request parameters.
     *
     * <p>This method controls how the model uses tools. The default implementation does nothing,
     * allowing formatters to override for providers that support tool choice configuration.
     *
     * @param paramsBuilder Provider-specific request parameters builder
     * @param toolChoice Tool choice configuration (null means provider default)
     */
    default void applyToolChoice(TParams paramsBuilder, ToolChoice toolChoice) {
        // Default implementation: do nothing
        // Subclasses can override to provide provider-specific behavior
    }

    /**
     * Apply tool choice configuration to provider-specific request parameters with provider detection.
     *
     * <p>This method allows formatters to detect the provider from baseUrl/modelName and adjust
     * tool_choice format or gracefully degrade when the provider doesn't support certain options.
     *
     * <p>The default implementation delegates to {@code applyToolChoice(TParams, ToolChoice)}.
     * Formatters that support provider-specific tool_choice handling should override this method.
     *
     * @param paramsBuilder Provider-specific request parameters builder
     * @param toolChoice Tool choice configuration (null means provider default)
     * @param baseUrl API base URL for provider detection (null for default)
     * @param modelName Model name for provider detection fallback (null)
     */
    default void applyToolChoice(
            TParams paramsBuilder, ToolChoice toolChoice, String baseUrl, String modelName) {
        // Default implementation: delegate to the simpler method
        applyToolChoice(paramsBuilder, toolChoice);
    }
}
