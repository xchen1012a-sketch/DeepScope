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
package io.deepscope.advisor.model;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** No tool execution: only the explicitly selected endpoint receives this bounded request. */
public final class KnowledgeChatClient {
    private KnowledgeChatClient() {}

    public static String ask(ModelCatalog.EndpointDraft endpoint, String prompt, byte[] image) {
        return ask(endpoint, prompt, image, false);
    }

    public static String ask(
            ModelCatalog.EndpointDraft endpoint, String prompt, byte[] image, boolean jsonOutput) {
        return ask(endpoint, prompt, image, jsonOutput, null);
    }

    public static String ask(
            ModelCatalog.EndpointDraft endpoint,
            String prompt,
            byte[] image,
            boolean jsonOutput,
            KnowledgeModelHttp.Policy policy) {
        boolean anthropic = "anthropic".equals(endpoint.provider());
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", prompt));
        if (image != null) {
            String encoded = Base64.getEncoder().encodeToString(image);
            content.add(
                    anthropic
                            ? Map.of(
                                    "type",
                                    "image",
                                    "source",
                                    Map.of(
                                            "type",
                                            "base64",
                                            "media_type",
                                            "image/png",
                                            "data",
                                            encoded))
                            : Map.of(
                                    "type",
                                    "image_url",
                                    "image_url",
                                    Map.of("url", "data:image/png;base64," + encoded)));
        }
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("model", endpoint.modelName());
        body.put("stream", false);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        if (anthropic) body.put("max_tokens", 16000);
        if (!anthropic && jsonOutput) body.put("response_format", Map.of("type", "json_object"));
        String base = endpoint.baseUrl();
        if (base == null || base.isBlank())
            base = anthropic ? "https://api.anthropic.com/v1" : OpenAiEndpointClient.base(endpoint);
        base = base.replaceAll("/+$", "");
        if (anthropic && !base.endsWith("/v1")) base += "/v1";
        var headers =
                anthropic
                        ? Map.of("x-api-key", endpoint.secret(), "anthropic-version", "2023-06-01")
                        : Map.of("Authorization", "Bearer " + endpoint.secret());
        var result =
                (policy == null
                                ? KnowledgeModelHttp.post(
                                        base + (anthropic ? "/messages" : "/chat/completions"),
                                        headers,
                                        body)
                                : KnowledgeModelHttp.post(
                                        base + (anthropic ? "/messages" : "/chat/completions"),
                                        headers,
                                        body,
                                        policy))
                        .block(
                                policy == null
                                        ? Duration.ofSeconds(65)
                                        : policy.totalTimeout().plusSeconds(5));
        if (result == null) throw new IllegalStateException("模型响应为空");
        String finish =
                anthropic
                        ? result.path("stop_reason").asText()
                        : result.path("choices").path(0).path("finish_reason").asText();
        if (java.util.Set.of("length", "max_tokens", "content_filter").contains(finish))
            throw new IllegalArgumentException("模型响应被截断或过滤");
        String text;
        if (anthropic) {
            StringBuilder combined = new StringBuilder();
            for (var block : result.path("content"))
                if ("text".equals(block.path("type").asText()))
                    combined.append(block.path("text").asText());
            text = combined.toString();
        } else text = result.path("choices").path(0).path("message").path("content").asText("");
        if (text.isBlank()
                || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256 * 1024)
            throw new IllegalArgumentException("模型正文为空或超限");
        return text.strip();
    }

    public static boolean supportsImages(ModelCatalog.EndpointDraft endpoint) {
        try {
            String challenge =
                    Integer.toString(100000 + new java.security.SecureRandom().nextInt(900000));
            BufferedImage image = new BufferedImage(320, 100, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 320, 100);
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 48));
            graphics.drawString(challenge, 40, 68);
            graphics.dispose();
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                ImageIO.write(image, "png", bytes);
                image.flush();
                String answer =
                        ask(
                                endpoint,
                                "Read the six digits in the image. Return only those digits.",
                                bytes.toByteArray());
                return challenge.equals(answer.replaceAll("\\s", ""));
            }
        } catch (RuntimeException | java.io.IOException unavailable) {
            return false;
        }
    }
}
