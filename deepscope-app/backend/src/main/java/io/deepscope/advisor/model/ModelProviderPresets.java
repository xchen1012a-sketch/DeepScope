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
package io.deepscope.advisor.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Connection suggestions; protocol identifiers remain independent of supplier names. */
final class ModelProviderPresets {
    // URLs and chat IDs from CC Switch openclawProviderPresets.ts; original app presets
    // retained for OpenAI, Anthropic and DashScope. Suggestions do not imply availability.
    private static final List<Preset> PRESETS =
            List.of(
                    preset(
                            "openai",
                            "OpenAI",
                            "openai",
                            "https://api.openai.com/v1",
                            "https://platform.openai.com/api-keys",
                            "gpt chatgpt",
                            List.of("gpt-4.1", "gpt-4.1-mini"),
                            List.of("text-embedding-3-small", "text-embedding-3-large")),
                    preset(
                            "anthropic",
                            "Anthropic",
                            "anthropic",
                            "https://api.anthropic.com",
                            "https://console.anthropic.com/settings/keys",
                            "claude",
                            List.of("claude-sonnet-4-5", "claude-opus-4-1"),
                            List.of()),
                    preset(
                            "dashscope",
                            "阿里云百炼 (DashScope)",
                            "dashscope",
                            "https://dashscope.aliyuncs.com/compatible-mode/v1",
                            "https://bailian.console.aliyun.com/",
                            "阿里 通义 千问 qwen alibaba aliyun",
                            List.of("qwen-plus", "qwen-turbo", "qwen-max"),
                            List.of("text-embedding-v3")),
                    preset(
                            "deepseek",
                            "DeepSeek",
                            "openai",
                            "https://api.deepseek.com/v1",
                            "https://platform.deepseek.com/api_keys",
                            "深度求索",
                            List.of("deepseek-v4-pro", "deepseek-flash"),
                            List.of()),
                    preset(
                            "zhipu",
                            "智谱 GLM",
                            "openai",
                            "https://open.bigmodel.cn/api/paas/v4",
                            "https://open.bigmodel.cn",
                            "zhipu glm 智谱 bigmodel",
                            List.of("glm-5.3", "glm-4.7"),
                            List.of("embedding-3")),
                    preset(
                            "zhipu-coding",
                            "智谱 GLM Coding",
                            "openai",
                            "https://open.bigmodel.cn/api/coding/paas/v4",
                            "https://open.bigmodel.cn",
                            "zhipu glm 智谱 coding 套餐",
                            List.of("glm-5.3", "glm-5.3-flash"),
                            List.of()),
                    preset(
                            "zai-coding",
                            "Z.ai GLM Coding",
                            "openai",
                            "https://api.z.ai/api/coding/paas/v4",
                            "https://z.ai",
                            "zhipu glm 智谱 国际 coding",
                            List.of("glm-5.3", "glm-5.3-flash"),
                            List.of()),
                    preset(
                            "kimi",
                            "Kimi",
                            "openai",
                            "https://api.moonshot.cn/v1",
                            "https://platform.kimi.com/console/api-keys",
                            "月之暗面 moonshot",
                            List.of("kimi-k2.7-code", "kimi-k3", "kimi-k2.6"),
                            List.of()),
                    preset(
                            "kimi-global",
                            "Kimi Global",
                            "openai",
                            "https://api.moonshot.ai/v1",
                            "https://platform.kimi.ai/console/api-keys",
                            "月之暗面 国际 moonshot",
                            List.of("kimi-k2.7-code", "kimi-k3", "kimi-k2.6"),
                            List.of()),
                    preset(
                            "siliconflow",
                            "硅基流动 (SiliconFlow)",
                            "openai",
                            "https://api.siliconflow.cn/v1",
                            "https://cloud.siliconflow.cn",
                            "硅基",
                            List.of("Pro/MiniMaxAI/MiniMax-M2.5"),
                            List.of()),
                    preset(
                            "siliconflow-global",
                            "SiliconFlow Global",
                            "openai",
                            "https://api.siliconflow.com/v1",
                            "https://siliconflow.com",
                            "硅基流动 国际",
                            List.of("MiniMaxAI/MiniMax-M3"),
                            List.of()),
                    preset(
                            "openrouter",
                            "OpenRouter",
                            "openai",
                            "https://openrouter.ai/api/v1",
                            "https://openrouter.ai/keys",
                            "聚合",
                            List.of("anthropic/claude-opus-5", "anthropic/claude-sonnet-5"),
                            List.of()),
                    preset(
                            "minimax",
                            "MiniMax",
                            "openai",
                            "https://api.minimax.cn/v1",
                            "https://platform.minimax.cn",
                            "海螺 稀宇",
                            List.of("MiniMax-M3"),
                            List.of()),
                    preset(
                            "minimax-global",
                            "MiniMax Global",
                            "openai",
                            "https://api.minimax.io/v1",
                            "https://platform.minimax.io",
                            "海螺 稀宇 国际",
                            List.of("MiniMax-M3"),
                            List.of()),
                    preset(
                            "stepfun",
                            "阶跃星辰 Step Plan",
                            "openai",
                            "https://api.stepfun.com/step_plan/v1",
                            "https://platform.stepfun.com/interface-key",
                            "阶跃",
                            List.of("step-3.5-flash", "step-3.7-flash", "step-5-preview"),
                            List.of()),
                    preset(
                            "stepfun-global",
                            "StepFun Global Step Plan",
                            "openai",
                            "https://api.stepfun.ai/step_plan/v1",
                            "https://platform.stepfun.ai/interface-key",
                            "阶跃星辰 国际",
                            List.of("step-3.5-flash", "step-3.7-flash", "step-5-preview"),
                            List.of()),
                    preset(
                            "groq",
                            "Groq",
                            "openai",
                            "https://api.groq.com/openai/v1",
                            "https://console.groq.com/keys",
                            "",
                            List.of("openai/gpt-oss-120b", "openai/gpt-oss-20b"),
                            List.of()),
                    preset(
                            "tencent-token-plan",
                            "腾讯云 Token Plan",
                            "openai",
                            "https://api.lkeap.cloud.tencent.com/plan/v3",
                            "https://console.cloud.tencent.com/tokenhub/tokenplan",
                            "tencent 混元 hunyuan",
                            List.of("tc-code-latest", "hy3", "hy3-preview"),
                            List.of()),
                    preset(
                            "nvidia",
                            "NVIDIA",
                            "openai",
                            "https://integrate.api.nvidia.com/v1",
                            "https://build.nvidia.com/settings/api-keys",
                            "英伟达 nim",
                            List.of(),
                            List.of()),
                    preset(
                            "mistral",
                            "Mistral AI",
                            "openai",
                            "https://api.mistral.ai/v1",
                            "https://console.mistral.ai/api-keys",
                            "",
                            List.of("mistral-medium-3-5", "mistral-small-2603"),
                            List.of("mistral-embed")),
                    preset(
                            "zai",
                            "Z.ai GLM",
                            "openai",
                            "https://api.z.ai/api/paas/v4",
                            "https://z.ai",
                            "glm zhipu 智谱 国际 常规",
                            List.of("glm-5.3"),
                            List.of()),
                    preset(
                            "stepfun-api",
                            "阶跃星辰 API",
                            "openai",
                            "https://api.stepfun.com/v1",
                            "https://platform.stepfun.com",
                            "stepfun 阶跃 普通 常规",
                            List.of("step-5-preview", "step-3.7-flash"),
                            List.of()),
                    preset(
                            "stepfun-api-global",
                            "StepFun Global API",
                            "openai",
                            "https://api.stepfun.ai/v1",
                            "https://platform.stepfun.ai",
                            "stepfun 阶跃 国际 常规",
                            List.of("step-5-preview"),
                            List.of()),
                    preset(
                            "gemini",
                            "Google Gemini",
                            "openai",
                            "https://generativelanguage.googleapis.com/v1beta/openai",
                            "https://aistudio.google.com",
                            "google gemini 谷歌",
                            List.of("gemini-3.8-flash"),
                            List.of()),
                    preset(
                            "xai",
                            "xAI Grok",
                            "openai",
                            "https://api.x.ai/v1",
                            "https://console.x.ai",
                            "xai grok",
                            List.of("grok-4.7"),
                            List.of()),
                    preset(
                            "volcengine",
                            "火山方舟（豆包）",
                            "openai",
                            "https://ark.cn-beijing.volces.com/api/v3",
                            "https://console.volcengine.com/ark",
                            "volcengine doubao ark 字节 火山 豆包 常规",
                            List.of("doubao-seed-2-1-pro-260915"),
                            List.of()),
                    preset(
                            "volcengine-coding",
                            "火山方舟 Coding Plan",
                            "openai",
                            "https://ark.cn-beijing.volces.com/api/coding/v3",
                            "https://console.volcengine.com/ark",
                            "volcengine doubao ark 字节 火山 豆包 coding 套餐",
                            List.of("ark-code-latest"),
                            List.of()),
                    preset(
                            "qianfan",
                            "百度千帆",
                            "openai",
                            "https://qianfan.baidubce.com/v2",
                            "https://console.bce.baidu.com/qianfan",
                            "baidu qianfan 百度 千帆",
                            List.of("deepseek-v3.2"),
                            List.of()),
                    preset(
                            "custom",
                            "自定义 OpenAI 兼容服务",
                            "custom",
                            "",
                            "",
                            "custom 自定义",
                            List.of(),
                            List.of()));

    static SearchResult<ProviderView> search(String kind, String query, int limit) {
        String keyword = keyword(query);
        var matches =
                PRESETS.stream()
                        .filter(
                                preset ->
                                        keyword(
                                                        preset.view().name()
                                                                + " "
                                                                + preset.view().id()
                                                                + " "
                                                                + preset.aliases())
                                                .contains(keyword))
                        .map(Preset::view)
                        .toList();
        return new SearchResult<>(matches.stream().limit(limit).toList(), matches.size());
    }

    static Optional<ProviderView> identify(String provider, String baseUrl) {
        return matching(provider, baseUrl).map(Preset::view);
    }

    static Optional<ProviderView> find(String id) {
        return PRESETS.stream().map(Preset::view).filter(view -> view.id().equals(id)).findFirst();
    }

    static boolean supportsConnection(ModelCatalog.EndpointDraft draft) {
        return !ModelCatalog.EMBEDDING.equals(draft.kind())
                || !"anthropic".equals(draft.provider());
    }

    static List<String> models(ModelCatalog.EndpointDraft draft, String presetId) {
        return matching(draft.provider(), draft.baseUrl())
                .filter(preset -> presetId == null || preset.view().id().equals(presetId))
                .map(
                        preset ->
                                ModelCatalog.EMBEDDING.equals(draft.kind())
                                        ? java.util.stream.Stream.concat(
                                                        preset.chat().stream(),
                                                        preset.embedding().stream())
                                                .distinct()
                                                .toList()
                                        : preset.chat())
                .orElse(List.of());
    }

    static String connectionUrl(String provider, String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) return normalizeUrl(baseUrl);
        return PRESETS.stream()
                .filter(preset -> preset.view().id().equals(provider))
                .map(preset -> normalizeUrl(preset.view().baseUrl()))
                .findFirst()
                .orElse("");
    }

    static String keyword(String query) {
        return query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    }

    static boolean validKind(String kind) {
        return ModelCatalog.CHAT.equals(kind) || ModelCatalog.EMBEDDING.equals(kind);
    }

    static int limit(Object value) {
        if (value == null) return 30;
        try {
            int limit = Integer.parseInt(value.toString());
            if (limit >= 1 && limit <= 50) return limit;
        } catch (NumberFormatException invalid) {
            // All invalid numeric forms use the same API validation message.
        }
        throw new IllegalArgumentException("limit 必须在 1 到 50 之间");
    }

    private static Optional<Preset> matching(String provider, String baseUrl) {
        String url = connectionUrl(provider, baseUrl);
        return PRESETS.stream()
                .filter(
                        preset ->
                                preset.view().provider().equals(provider)
                                        && normalizeUrl(preset.view().baseUrl()).equals(url))
                .findFirst();
    }

    private static String normalizeUrl(String url) {
        return url.strip().replaceAll("/+$", "");
    }

    private static Preset preset(
            String id,
            String name,
            String provider,
            String url,
            String keysUrl,
            String aliases,
            List<String> chat,
            List<String> embedding) {
        return new Preset(
                new ProviderView(id, name, provider, url, keysUrl), aliases, chat, embedding);
    }

    record ProviderView(String id, String name, String provider, String baseUrl, String keysUrl) {}

    record SearchResult<T>(List<T> items, int total) {}

    private record Preset(
            ProviderView view, String aliases, List<String> chat, List<String> embedding) {}
}
