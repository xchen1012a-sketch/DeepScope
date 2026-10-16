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
package io.agentscope.advisor.model;

/** Safe error metadata only: never carries upstream bodies, credentials or document text. */
public final class KnowledgeModelFailure extends RuntimeException {
    private final int status;
    private final String requestId;

    public KnowledgeModelFailure(int status, String requestId) {
        super("模型请求失败 HTTP " + status);
        this.status = status;
        this.requestId =
                requestId != null && requestId.matches("[A-Za-z0-9_.:-]{1,160}") ? requestId : "";
    }

    public int status() {
        return status;
    }

    public String requestId() {
        return requestId;
    }

    public boolean retryable() {
        return status == 408 || status == 429 || status >= 500;
    }

    public static String code(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof KnowledgeModelFailure failure)
                return failure.status == 429
                        ? "model_rate_limited"
                        : failure.status == 401 || failure.status == 403
                                ? "model_auth_failed"
                                : "model_http_" + failure.status;
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.http.HttpTimeoutException)
                return "model_timeout";
            if (current instanceof InterruptedException
                    || current instanceof java.util.concurrent.CancellationException)
                return "cancelled";
            if (current instanceof java.io.IOException
                    && !(current instanceof com.fasterxml.jackson.core.JsonProcessingException))
                return "model_network_error";
        }
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("截断") || message.contains("过滤")) return "model_output_truncated";
        if (message.contains("为空") || message.contains("超限") || message.contains("超出限制"))
            return "model_output_empty_or_large";
        if (message.contains("JSON")
                || message.contains("字段")
                || message.contains("结构")
                || error instanceof com.fasterxml.jackson.core.JsonProcessingException)
            return "model_output_invalid";
        if (message.contains("配置") || message.contains("不可用")) return "model_unavailable";
        return "page_processing_failed";
    }

    public static String safeMessage(Throwable error) {
        String code = code(error);
        String detail =
                switch (code) {
                    case "model_timeout" -> "模型请求超时";
                    case "model_rate_limited" -> "模型服务限流，有限重试后仍未完成";
                    case "model_auth_failed" -> "模型鉴权失败，请检查配置";
                    case "model_network_error" -> "模型网络连接失败";
                    case "model_output_invalid" -> "模型输出JSON或字段无效，格式修正后仍未通过";
                    case "model_output_truncated" -> "模型输出截断或被过滤";
                    case "model_output_empty_or_large" -> "模型正文为空或超过长度限制";
                    case "model_unavailable" -> "解析模型未配置或不可用";
                    case "cancelled" -> "任务已取消";
                    default -> code.startsWith("model_http_") ? "模型服务请求失败" : "本页解析失败";
                };
        return "[" + code + "] " + detail;
    }
}
