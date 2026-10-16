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
package io.agentscope.advisor.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextWindows;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** Builds and probes OpenAI-compatible endpoints, including a custom address. */
public final class OpenAiEndpointClient implements ModelProbe {

    @Override
    public boolean supportsImages(ModelCatalog.EndpointDraft draft) {
        return ModelCatalog.CHAT.equals(draft.kind()) && KnowledgeChatClient.supportsImages(draft);
    }

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ModelDiscoveryClient discovery = new ModelDiscoveryClient();

    @Override
    public ModelProbe.Result check(ModelCatalog.EndpointDraft draft) {
        if (draft.modelName() == null
                || draft.modelName().isBlank()
                || draft.secret() == null
                || draft.secret().isBlank()) {
            return ModelProbe.Result.failure("模型名称和密钥都要填");
        }
        if (ModelCatalog.EMBEDDING.equals(draft.kind()) && "anthropic".equals(draft.provider())) {
            return ModelProbe.Result.failure("这个服务商不提供向量模型");
        }
        try {
            boolean vector = ModelCatalog.EMBEDDING.equals(draft.kind());
            boolean anthropic = "anthropic".equals(draft.provider());
            String path = vector ? "/embeddings" : anthropic ? "/messages" : "/chat/completions";
            String body =
                    new ObjectMapper()
                            .writeValueAsString(
                                    vector
                                            ? java.util.Map.of(
                                                    "model", draft.modelName(), "input", "ping")
                                            : java.util.Map.of(
                                                    "model",
                                                    draft.modelName(),
                                                    "messages",
                                                    java.util.List.of(
                                                            java.util.Map.of(
                                                                    "role", "user", "content",
                                                                    "ping")),
                                                    !anthropic && reasoningModel(draft.modelName())
                                                            ? "max_completion_tokens"
                                                            : "max_tokens",
                                                    1));
            HttpResponse<String> response =
                    http.send(
                            request(draft, path)
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                return ModelProbe.Result.failure(statusMessage(response.statusCode()));
            var json = new ObjectMapper().readTree(response.body());
            if (vector) {
                var embedding = json.path("data").path(0).path("embedding");
                if (!embedding.isArray() || embedding.isEmpty())
                    return ModelProbe.Result.failure("向量响应无效");
                for (var value : embedding)
                    if (!value.isNumber()) return ModelProbe.Result.failure("向量响应无效");
                return ModelProbe.Result.success(embedding.size());
            }
            var content = json.path(anthropic ? "content" : "choices");
            return content.isArray() && !content.isEmpty()
                    ? ModelProbe.Result.success(null)
                    : ModelProbe.Result.failure("模型响应无效");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ModelProbe.Result.failure("连接测试已中断");
        } catch (Exception unavailable) {
            return ModelProbe.Result.failure("连接失败，请检查服务地址和配置");
        }
    }

    private HttpRequest.Builder request(ModelCatalog.EndpointDraft draft, String path) {
        URI uri = URI.create(base(draft) + path);
        if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) throw new IllegalArgumentException("服务地址无效");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20));
        return "anthropic".equals(draft.provider())
                ? request.header("x-api-key", draft.secret())
                        .header("anthropic-version", "2023-06-01")
                : request.header("Authorization", "Bearer " + draft.secret());
    }

    private static String statusMessage(int status) {
        return switch (status) {
            case 401, 403 -> "密钥无效或无访问权限";
            case 404 -> "模型或服务地址不可用";
            case 429 -> "请求受限，请稍后重试";
            default -> "服务连接失败（HTTP " + status + "）";
        };
    }

    @Override
    public java.util.List<String> discover(ModelCatalog.EndpointDraft draft) {
        return discoverResult(draft).items();
    }

    @Override
    public ModelProbe.DiscoveryResult discoverResult(ModelCatalog.EndpointDraft draft) {
        return discovery.discover(draft);
    }

    public Model chat(ModelCatalog.Endpoint endpoint) {
        return chat(endpoint, null);
    }

    /** Builds the actual selected model with the endpoint-advertised window when available. */
    public Model chat(ModelCatalog.Endpoint endpoint, Integer contextWindow) {
        if ("anthropic".equals(endpoint.provider())) {
            var nativeBuilder =
                    io.agentscope.extensions.model.anthropic.AnthropicChatModel.builder()
                            .apiKey(endpoint.secret())
                            .modelName(endpoint.modelName());
            if (contextWindow != null && contextWindow > 0)
                nativeBuilder.contextWindowSize(contextWindow);
            if (endpoint.baseUrl() != null && !endpoint.baseUrl().isBlank()) {
                nativeBuilder.baseUrl(anthropicRoot(endpoint.baseUrl()));
            }
            return nativeBuilder.build();
        }
        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder().apiKey(endpoint.secret()).modelName(endpoint.modelName());
        int window =
                contextWindow != null && contextWindow > 0
                        ? contextWindow
                        : knownContextWindow(endpoint.modelName());
        if (window > 0) {
            builder.contextWindowSize(window);
        }
        String base = endpoint.baseUrl();
        if (base != null && !base.isBlank()) {
            builder.baseUrl(trim(base));
        } else if ("dashscope".equals(endpoint.provider())) {
            builder.baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        }
        return builder.build();
    }

    @Override
    public Optional<Integer> contextWindow(ModelCatalog.EndpointDraft draft) {
        if (!ModelCatalog.CHAT.equals(draft.kind())) {
            return Optional.empty();
        }
        try {
            HttpRequest request =
                    request(draft, "/models").timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var data = new ObjectMapper().readTree(response.body()).path("data");
                if (data.isArray()) {
                    for (var model : data) {
                        if (!draft.modelName().equals(model.path("id").asText())) {
                            continue;
                        }
                        var window =
                                model.has("context_window")
                                        ? model.get("context_window")
                                        : model.path("context_length");
                        if (window.isIntegralNumber()
                                && window.canConvertToInt()
                                && window.intValue() > 0) {
                            return Optional.of(window.intValue());
                        }
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException | RuntimeException unavailable) {
            // Capability discovery is optional; preserve endpoint selection and prior known data.
        }
        int known = knownContextWindow(draft.modelName());
        return known > 0 ? Optional.of(known) : Optional.empty();
    }

    private static int knownContextWindow(String modelName) {
        return ModelContextWindows.find(modelName);
    }

    public EmbeddingModel embedding(ModelCatalog.Endpoint endpoint) {
        int dimensions = endpoint.dimensions() == null ? 1024 : endpoint.dimensions();
        String base = endpoint.baseUrl();
        if (base == null || base.isBlank())
            base =
                    "dashscope".equals(endpoint.provider())
                            ? "https://dashscope.aliyuncs.com/compatible-mode/v1"
                            : "https://api.openai.com/v1";
        return new KnowledgeEmbeddingModel(
                endpoint.modelName(), base, endpoint.secret(), dimensions);
    }

    static String base(ModelCatalog.EndpointDraft draft) {
        if ("anthropic".equals(draft.provider())) {
            return anthropicRoot(draft.baseUrl()) + "/v1";
        }
        if (draft.baseUrl() != null && !draft.baseUrl().isBlank()) {
            return trim(draft.baseUrl());
        }
        return switch (draft.provider()) {
            case "dashscope" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            default -> "https://api.openai.com/v1";
        };
    }

    private static boolean reasoningModel(String modelName) {
        return modelName != null
                && modelName
                        .toLowerCase(java.util.Locale.ROOT)
                        .matches("gpt-5(?:[.-].*)?|o[0-9]+(?:-.*)?");
    }

    /** The Anthropic SDK appends /v1 itself; manual HTTP probes share that same root. */
    private static String anthropicRoot(String baseUrl) {
        String root =
                baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com" : trim(baseUrl);
        return root.endsWith("/v1") ? root.substring(0, root.length() - 3) : root;
    }

    private static String trim(String url) {
        String value = url.strip();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
