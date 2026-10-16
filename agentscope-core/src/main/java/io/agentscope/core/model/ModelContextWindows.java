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
package io.agentscope.core.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Known context window sizes for popular models. Used by {@link ChatModelBase} subclasses
 * to infer {@link Model#getContextWindowSize()} from the model name when no explicit
 * value is provided via the builder.
 *
 * <p>Values are total context window (input + output) in tokens. Entries are matched
 * via prefix: a model name {@code "qwen-max-latest"} matches the {@code "qwen-max"} entry.
 * Longer prefixes are checked first so {@code "gpt-4.1-mini"} matches before {@code "gpt-4.1"}.
 */
public final class ModelContextWindows {

    /** Safe baseline context window (128K) when a model name cannot be matched in any catalog. */
    public static final int DEFAULT_FALLBACK_WINDOW = 131_072;

    private static final Pattern TOKEN_SUFFIX_PATTERN =
            Pattern.compile("[-_]?(\\d+)(k|m)(?:[-_.]|$)");

    private ModelContextWindows() {}

    // @formatter:off

    public static final Map<String, Integer> DASHSCOPE =
            Map.ofEntries(
                    // Qwen3 series
                    Map.entry("qwen3-235b", 131_072),
                    Map.entry("qwen3-32b", 131_072),
                    Map.entry("qwen3-30b", 131_072),
                    Map.entry("qwen3-14b", 131_072),
                    Map.entry("qwen3-8b", 131_072),
                    Map.entry("qwen3-4b", 131_072),
                    Map.entry("qwen3-1.7b", 32_768),
                    Map.entry("qwen3-0.6b", 32_768),
                    Map.entry("qwen3-max", 131_072),
                    Map.entry("qwen3-plus", 131_072),
                    Map.entry("qwen3-turbo", 1_000_000),
                    // Qwen3.7 series
                    Map.entry("qwen3.7", 131_072),
                    // Qwen-Max / Plus / Turbo / Long / VL / Coder / Math
                    Map.entry("qwen-max-longcontext", 30_000),
                    Map.entry("qwen-max", 32_768),
                    Map.entry("qwen-plus", 131_072),
                    Map.entry("qwen-turbo", 1_000_000),
                    Map.entry("qwen-long", 10_000_000),
                    Map.entry("qwen-vl-max", 131_072),
                    Map.entry("qwen-vl-plus", 131_072),
                    Map.entry("qwen-vl", 131_072),
                    Map.entry("qwen-coder-plus", 131_072),
                    Map.entry("qwen-coder-turbo", 131_072),
                    Map.entry("qwen-coder", 131_072),
                    Map.entry("qwen-math", 131_072),
                    // Qwen2.5 & Qwen2 series
                    Map.entry("qwen2.5-coder", 131_072),
                    Map.entry("qwen2.5-math", 131_072),
                    Map.entry("qwen2.5-vl", 131_072),
                    Map.entry("qwen2.5", 131_072),
                    Map.entry("qwen2-vl", 131_072),
                    Map.entry("qwen2", 131_072),
                    // QVQ / QwQ
                    Map.entry("qvq", 131_072),
                    Map.entry("qwq", 131_072));

    public static final Map<String, Integer> OPENAI =
            Map.ofEntries(
                    Map.entry("gpt-4.1-mini", 1_047_576),
                    Map.entry("gpt-4.1-nano", 1_047_576),
                    Map.entry("gpt-4.1", 1_047_576),
                    Map.entry("gpt-4.5", 128_000),
                    Map.entry("gpt-4o-mini", 128_000),
                    Map.entry("gpt-4o", 128_000),
                    Map.entry("chatgpt-4o", 128_000),
                    Map.entry("gpt-4-turbo", 128_000),
                    Map.entry("gpt-4-32k", 32_768),
                    Map.entry("gpt-4", 8_192),
                    Map.entry("gpt-3.5-turbo", 16_385),
                    Map.entry("o4-mini", 200_000),
                    Map.entry("o3-mini", 200_000),
                    Map.entry("o3-pro", 200_000),
                    Map.entry("o3", 200_000),
                    Map.entry("o1-mini", 128_000),
                    Map.entry("o1-preview", 128_000),
                    Map.entry("o1-pro", 200_000),
                    Map.entry("o1", 200_000),
                    Map.entry("gpt-5.4-mini", 400_000),
                    Map.entry("gpt-5.4", 1_050_000),
                    Map.entry("gpt-5.5", 1_050_000),
                    Map.entry("gpt-5.6-luna", 1_050_000),
                    Map.entry("gpt-5.6-terra", 1_050_000),
                    Map.entry("gpt-5.6-sol", 1_050_000),
                    Map.entry("gpt-6-astra", 1_050_000));

    public static final Map<String, Integer> DEEPSEEK =
            Map.ofEntries(
                    Map.entry("deepseek-chat", 131_072),
                    Map.entry("deepseek-reasoner", 131_072),
                    Map.entry("deepseek-r1", 131_072),
                    Map.entry("deepseek-v3", 131_072),
                    Map.entry("deepseek-v2.5", 131_072),
                    Map.entry("deepseek-v2", 131_072),
                    Map.entry("deepseek-coder", 131_072),
                    Map.entry("deepseek-vl", 131_072),
                    Map.entry("deepseek-flash", 1_048_576),
                    Map.entry("deepseek-v4.1-flash", 1_048_576),
                    Map.entry("deepseek-v4-flash", 1_048_576),
                    Map.entry("deepseek-v4-pro", 1_048_576),
                    Map.entry("deepseek-v4", 1_048_576));

    public static final Map<String, Integer> GLM =
            Map.ofEntries(
                    Map.entry("glm-5.3", 1_000_000),
                    Map.entry("glm-5.2", 1_000_000),
                    Map.entry("glm-5.1", 200_000),
                    Map.entry("glm-5-turbo", 200_000),
                    Map.entry("glm-5", 200_000),
                    Map.entry("glm-4.7-flashx", 200_000),
                    Map.entry("glm-4.7-flash", 200_000),
                    Map.entry("glm-4.7", 200_000),
                    Map.entry("glm-4.6", 200_000),
                    Map.entry("glm-4.5-airx", 128_000),
                    Map.entry("glm-4.5-air", 128_000),
                    Map.entry("glm-4.5", 128_000),
                    Map.entry("glm-4-plus", 128_000),
                    Map.entry("glm-4-airx", 128_000),
                    Map.entry("glm-4-air", 128_000),
                    Map.entry("glm-4-flashx-250414", 128_000),
                    Map.entry("glm-4-flash-250414", 128_000),
                    Map.entry("glm-4-flashx", 128_000),
                    Map.entry("glm-4-flash", 128_000),
                    Map.entry("glm-4-long", 1_000_000),
                    Map.entry("glm-4v", 128_000),
                    Map.entry("glm-4", 128_000),
                    Map.entry("glm-zero", 128_000),
                    Map.entry("codegeex-4", 128_000));

    public static final Map<String, Integer> MINIMAX =
            Map.ofEntries(
                    Map.entry("minimax-m3", 1_000_000),
                    Map.entry("minimax-m2.7-highspeed", 204_800),
                    Map.entry("minimax-m2.7", 204_800),
                    Map.entry("minimax-m2.5-highspeed", 204_800),
                    Map.entry("minimax-m2.5", 204_800),
                    Map.entry("minimax-m2.1-highspeed", 204_800),
                    Map.entry("minimax-m2.1", 204_800),
                    Map.entry("minimax-m2", 204_800),
                    Map.entry("minimax-text-01", 1_000_000),
                    Map.entry("abab6.5s", 245_760),
                    Map.entry("abab6.5t", 8_192),
                    Map.entry("abab6.5", 245_760),
                    Map.entry("m2-her", 65_536));

    public static final Map<String, Integer> KIMI =
            Map.ofEntries(
                    Map.entry("kimi-k3", 1_048_576),
                    Map.entry("kimi-k2.7-code-highspeed", 262_144),
                    Map.entry("kimi-k2.7-code", 262_144),
                    Map.entry("kimi-k2.6", 262_144),
                    Map.entry("kimi-k2.5", 262_144),
                    Map.entry("kimi-k2", 262_144),
                    Map.entry("kimi-latest", 131_072),
                    Map.entry("moonshot-v1-auto", 131_072),
                    Map.entry("moonshot-v1-128k-vision-preview", 131_072),
                    Map.entry("moonshot-v1-128k", 131_072),
                    Map.entry("moonshot-v1-32k-vision-preview", 32_768),
                    Map.entry("moonshot-v1-32k", 32_768),
                    Map.entry("moonshot-v1-8k-vision-preview", 8_192),
                    Map.entry("moonshot-v1-8k", 8_192));

    public static final Map<String, Integer> ANTHROPIC =
            Map.ofEntries(
                    Map.entry("claude-opus-4", 200_000),
                    Map.entry("claude-sonnet-4", 200_000),
                    Map.entry("claude-haiku-4", 200_000),
                    Map.entry("claude-3-7", 200_000),
                    Map.entry("claude-3-5", 200_000),
                    Map.entry("claude-3", 200_000),
                    Map.entry("claude-2", 100_000));

    public static final Map<String, Integer> GEMINI =
            Map.ofEntries(
                    Map.entry("gemini-2.5", 1_048_576),
                    Map.entry("gemini-2.0", 1_048_576),
                    Map.entry("gemini-1.5-pro", 2_097_152),
                    Map.entry("gemini-1.5-flash", 1_048_576),
                    Map.entry("gemini-1.5", 2_097_152));

    public static final Map<String, Integer> OTHER_MAINSTREAM =
            Map.ofEntries(
                    // Meta Llama
                    Map.entry("llama-3.3", 131_072),
                    Map.entry("llama-3.2", 131_072),
                    Map.entry("llama-3.1", 131_072),
                    Map.entry("llama-3", 8_192),
                    Map.entry("llama-2", 4_096),
                    // Mistral & Codestral
                    Map.entry("mistral-large", 131_072),
                    Map.entry("mistral-small", 131_072),
                    Map.entry("codestral", 262_144),
                    Map.entry("pixtral", 131_072),
                    Map.entry("mixtral-8x22b", 65_536),
                    Map.entry("mixtral-8x7b", 32_768),
                    // Doubao (Volcengine)
                    Map.entry("doubao-1.5-pro-256k", 262_144),
                    Map.entry("doubao-1.5-pro-32k", 32_768),
                    Map.entry("doubao-pro-256k", 262_144),
                    Map.entry("doubao-pro-128k", 131_072),
                    Map.entry("doubao-pro-32k", 32_768),
                    Map.entry("doubao-pro-4k", 4_096),
                    Map.entry("doubao-lite-128k", 131_072),
                    Map.entry("doubao-lite-32k", 32_768),
                    // StepFun (阶跃星辰)
                    Map.entry("step-2-16k", 16_384),
                    Map.entry("step-1-256k", 262_144),
                    Map.entry("step-1-128k", 131_072),
                    Map.entry("step-1-32k", 32_768),
                    Map.entry("step-1-8k", 8_192),
                    // Yi (零一万物)
                    Map.entry("yi-lightning", 16_384),
                    Map.entry("yi-large", 32_768),
                    Map.entry("yi-medium-200k", 200_000),
                    Map.entry("yi-medium", 16_384),
                    // Baichuan (百川)
                    Map.entry("baichuan4", 32_768),
                    Map.entry("baichuan3-turbo-128k", 131_072),
                    Map.entry("baichuan3-turbo", 32_768),
                    Map.entry("baichuan2-turbo", 32_768),
                    // Hunyuan (腾讯混元) & Ernie (文心一言)
                    Map.entry("hunyuan-turbo", 32_768),
                    Map.entry("hunyuan-pro", 32_768),
                    Map.entry("hunyuan-standard-256k", 262_144),
                    Map.entry("hunyuan-standard", 32_768),
                    Map.entry("ernie-4.0-turbo-128k", 131_072),
                    Map.entry("ernie-4.0", 8_192),
                    Map.entry("ernie-speed-128k", 131_072));

    private static final List<Map<String, Integer>> ALL_REGISTRIES =
            List.of(
                    OPENAI,
                    DASHSCOPE,
                    DEEPSEEK,
                    GLM,
                    MINIMAX,
                    KIMI,
                    ANTHROPIC,
                    GEMINI,
                    OTHER_MAINSTREAM);

    // @formatter:on

    /**
     * Finds the context window size for the given model name from the provided map.
     * Uses prefix matching: longest matching prefix wins.
     *
     * @return context window size, or 0 if no match
     */
    public static int lookup(String modelName, Map<String, Integer> knownModels) {
        if (modelName == null || modelName.isBlank()) {
            return 0;
        }
        String lower = normalizeModelName(modelName);
        int bestLen = 0;
        int bestValue = 0;
        for (Map.Entry<String, Integer> entry : knownModels.entrySet()) {
            String prefix = entry.getKey();
            if (lower.startsWith(prefix) && prefix.length() > bestLen) {
                bestLen = prefix.length();
                bestValue = entry.getValue();
            }
        }
        return bestValue;
    }

    /**
     * Looks up the context window for a model name from known registries, suffix matching,
     * or family heuristics, returning 0 if completely unknown.
     *
     * @param modelName the raw model identifier
     * @return context window size in tokens, or 0 if unknown
     */
    public static int find(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return 0;
        }
        String normalized = normalizeModelName(modelName);
        int bestLen = 0;
        int bestValue = 0;
        for (Map<String, Integer> registry : ALL_REGISTRIES) {
            for (Map.Entry<String, Integer> entry : registry.entrySet()) {
                String prefix = entry.getKey();
                if (normalized.startsWith(prefix) && prefix.length() > bestLen) {
                    bestLen = prefix.length();
                    bestValue = entry.getValue();
                }
            }
        }
        if (bestValue > 0) {
            return bestValue;
        }

        // Parse explicit capacity suffix like "-128k", "-32k", "-256k", "-1m"
        Matcher suffixMatcher = TOKEN_SUFFIX_PATTERN.matcher(normalized);
        if (suffixMatcher.find()) {
            try {
                int num = Integer.parseInt(suffixMatcher.group(1));
                String unit = suffixMatcher.group(2);
                if ("m".equals(unit) && num > 0) {
                    return num * 1_000_000;
                } else if ("k".equals(unit) && num > 0) {
                    return num * 1024;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to family keyword match
            }
        }

        // Broad model family keyword matching
        if (normalized.contains("deepseek")) return 131_072;
        if (normalized.contains("qwen")) return 131_072;
        if (normalized.contains("claude")) return 200_000;
        if (normalized.contains("gemini")) return 1_048_576;
        if (normalized.contains("gpt-4") || normalized.contains("gpt4")) return 128_000;
        if (normalized.contains("glm")) return 128_000;
        if (normalized.contains("kimi") || normalized.contains("moonshot")) return 131_072;
        if (normalized.contains("minimax")) return 204_800;
        if (normalized.contains("llama")) return 131_072;
        if (normalized.contains("mistral") || normalized.contains("mixtral")) return 131_072;
        if (normalized.contains("doubao")) return 131_072;

        return 0;
    }

    /**
     * Intelligently infers the context window size for any model name across all known providers,
     * falling back to token-suffix parsing, family keyword heuristics, and finally {@link #DEFAULT_FALLBACK_WINDOW}.
     *
     * @param modelName the raw model identifier (may include org prefix like {@code deepseek-ai/DeepSeek-V3})
     * @return positive context window size in tokens (or {@link #DEFAULT_FALLBACK_WINDOW} when unknown)
     */
    public static int infer(String modelName) {
        int found = find(modelName);
        return found > 0 ? found : DEFAULT_FALLBACK_WINDOW;
    }

    private static String normalizeModelName(String modelName) {
        String lower = modelName.trim().toLowerCase(Locale.ROOT);
        int slash = lower.lastIndexOf('/');
        if (slash >= 0 && slash < lower.length() - 1) {
            lower = lower.substring(slash + 1);
        }
        return lower.replace('_', '-');
    }
}
