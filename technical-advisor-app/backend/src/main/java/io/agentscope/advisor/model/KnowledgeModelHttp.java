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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/** Cancellable HTTP boundary for knowledge calls; one bounded retry policy, no nested SDK retries. */
public final class KnowledgeModelHttp {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    private KnowledgeModelHttp() {}

    public record Policy(Duration requestTimeout, Duration totalTimeout, int retries) {
        public Policy {
            if (requestTimeout.isNegative()
                    || requestTimeout.isZero()
                    || totalTimeout.isNegative()
                    || totalTimeout.isZero()
                    || retries < 0
                    || retries > 2) throw new IllegalArgumentException("模型请求预算无效");
        }
    }

    public static final Policy VISION =
            new Policy(Duration.ofSeconds(45), Duration.ofSeconds(90), 1);
    private static final Policy DEFAULT =
            new Policy(Duration.ofSeconds(25), Duration.ofSeconds(60), 2);

    public static Mono<JsonNode> post(String url, Map<String, String> headers, Object body) {
        return post(url, headers, body, DEFAULT);
    }

    static Mono<JsonNode> post(
            String url, Map<String, String> headers, Object body, Duration totalTimeout) {
        return post(
                url,
                headers,
                body,
                new Policy(DEFAULT.requestTimeout(), totalTimeout, DEFAULT.retries()));
    }

    public static Mono<JsonNode> post(
            String url, Map<String, String> headers, Object body, Policy policy) {
        var context = org.slf4j.MDC.getCopyOfContextMap();
        return Mono.defer(
                        () -> {
                            try {
                                URI uri = URI.create(url);
                                if (!java.util.Set.of("https", "http").contains(uri.getScheme())
                                        || uri.getHost() == null
                                        || uri.getUserInfo() != null
                                        || uri.getQuery() != null
                                        || uri.getFragment() != null) {
                                    return Mono.error(new IllegalArgumentException("模型地址无效"));
                                }
                                var request =
                                        HttpRequest.newBuilder(uri)
                                                .timeout(policy.requestTimeout())
                                                .header("Content-Type", "application/json")
                                                .POST(
                                                        HttpRequest.BodyPublishers.ofString(
                                                                JSON.writeValueAsString(body)));
                                headers.forEach(request::header);
                                return Mono.<HttpResponse<byte[]>>create(
                                                sink -> {
                                                    var cancelled =
                                                            new java.util.concurrent.atomic
                                                                    .AtomicBoolean();
                                                    var future =
                                                            CLIENT.sendAsync(
                                                                    request.build(),
                                                                    info -> new LimitedBody());
                                                    sink.onCancel(
                                                            () -> {
                                                                cancelled.set(true);
                                                                future.cancel(true);
                                                            });
                                                    future.whenComplete(
                                                            (response, error) -> {
                                                                if (cancelled.get()) return;
                                                                if (error == null)
                                                                    sink.success(response);
                                                                else
                                                                    sink.error(
                                                                            error
                                                                                                    instanceof
                                                                                                    java
                                                                                                            .util
                                                                                                            .concurrent
                                                                                                            .CompletionException
                                                                                            && error
                                                                                                            .getCause()
                                                                                                    != null
                                                                                    ? error
                                                                                            .getCause()
                                                                                    : error);
                                                            });
                                                })
                                        .flatMap(
                                                response -> {
                                                    int status = response.statusCode();
                                                    if (status < 200 || status >= 300)
                                                        return Mono.error(
                                                                new KnowledgeModelFailure(
                                                                        status,
                                                                        response.headers()
                                                                                .firstValue(
                                                                                        "x-request-id")
                                                                                .orElse(
                                                                                        response.headers()
                                                                                                .firstValue(
                                                                                                        "request-id")
                                                                                                .orElse(
                                                                                                        ""))));
                                                    if (response.body().length > 1024 * 1024)
                                                        return Mono.error(
                                                                new IllegalArgumentException(
                                                                        "模型响应超出限制"));
                                                    try {
                                                        return Mono.just(
                                                                JSON.readTree(response.body()));
                                                    } catch (IOException error) {
                                                        return Mono.error(
                                                                new IllegalArgumentException(
                                                                        "模型响应格式无效"));
                                                    }
                                                });
                            } catch (Exception error) {
                                return Mono.error(error);
                            }
                        })
                .retryWhen(
                        Retry.backoff(policy.retries(), Duration.ofMillis(400))
                                .maxBackoff(Duration.ofSeconds(2))
                                .filter(
                                        error ->
                                                error instanceof IOException
                                                        || error
                                                                        instanceof
                                                                        KnowledgeModelFailure
                                                                                failure
                                                                && failure.retryable())
                                .doBeforeRetry(
                                        signal ->
                                                org.slf4j.LoggerFactory.getLogger(
                                                                KnowledgeModelHttp.class)
                                                        .warn(
                                                                "Knowledge model retry document={}"
                                                                        + " attempt={} retry={}"
                                                                        + " code={}",
                                                                context == null
                                                                        ? ""
                                                                        : context.getOrDefault(
                                                                                "documentId", ""),
                                                                context == null
                                                                        ? ""
                                                                        : context.getOrDefault(
                                                                                "attemptId", ""),
                                                                signal.totalRetries() + 1,
                                                                KnowledgeModelFailure.code(
                                                                        signal.failure())))
                                .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .timeout(policy.totalTimeout());
    }

    /** Limit raw response bytes during reception and cancel oversized bodies immediately. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result =
                new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 1024 * 1024 - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalArgumentException("模型响应超出限制"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
