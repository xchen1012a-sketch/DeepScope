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
package io.deepscope.advisor.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Bounded, cancellable HTTP execution shared by catalog tools and the test endpoint. */
public final class ToolHttpExecutor {
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final ThreadPoolExecutor WORKERS =
            new ThreadPoolExecutor(
                    4,
                    4,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(16),
                    runnable -> {
                        var thread = new Thread(runnable, "advisor-tool-http");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static final Scheduler SCHEDULER = Schedulers.fromExecutorService(WORKERS);
    private final ObjectMapper json;
    private final HttpClient client;
    private final Duration budget;
    private final ToolHttpRequestMapper mapper;

    public ToolHttpExecutor(ObjectMapper advisorObjectMapper) {
        this(
                advisorObjectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Duration.ofSeconds(30));
    }

    public ToolHttpExecutor(ObjectMapper advisorObjectMapper, HttpClient client, Duration budget) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("HTTP client must check every redirect");
        this.json = advisorObjectMapper;
        this.client = client;
        this.budget = budget;
        this.mapper = new ToolHttpRequestMapper(json);
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            Integer httpStatus,
            String result,
            long elapsedMs,
            boolean truncated,
            String errorCode) {
        @com.fasterxml.jackson.annotation.JsonProperty("messageKey")
        public String messageKey() {
            return errorCode == null ? null : "tool.errors." + errorCode;
        }
    }

    public Mono<Result> execute(
            String method,
            String address,
            Map<String, Object> schema,
            Map<String, Object> input,
            Map<String, Object> credential) {
        return execute(method, address, schema, input, credential, () -> {});
    }

    public Mono<Result> execute(
            String method,
            String address,
            Map<String, Object> schema,
            Map<String, Object> input,
            Map<String, Object> credential,
            Runnable preflight) {
        return Mono.defer(
                () -> {
                    var mapped = mapper.map(method, address, schema, input, credential);
                    long start = System.nanoTime();
                    var resources = new Resources();
                    return Mono.fromCallable(
                                    () -> {
                                        preflight.run();
                                        return invoke(mapped, resources, start, credential);
                                    })
                            .subscribeOn(SCHEDULER)
                            .timeout(budget)
                            .onErrorResume(
                                    error -> {
                                        if (error instanceof ToolProblem) return Mono.error(error);
                                        if (error instanceof RejectedExecutionException)
                                            return Mono.error(
                                                    new ToolProblem(
                                                            429,
                                                            "execution_busy",
                                                            "All execution slots and the queue are"
                                                                    + " full"));
                                        String code =
                                                error instanceof TimeoutException
                                                                || error
                                                                        instanceof
                                                                        java.net.http
                                                                                .HttpTimeoutException
                                                        ? "execution_timeout"
                                                        : error instanceof InterruptedException
                                                                        || error
                                                                                instanceof
                                                                                java.util.concurrent
                                                                                        .CancellationException
                                                                ? "execution_cancelled"
                                                                : "connection_failed";
                                        return Mono.just(failure(code, start));
                                    })
                            .doOnNext(result -> resources.close())
                            .doOnError(error -> resources.close())
                            .doFinally(
                                    signal -> {
                                        resources.close();
                                        WORKERS.purge();
                                    });
                });
    }

    Duration budget() {
        return budget;
    }

    static <T> Mono<T> schedule(java.util.concurrent.Callable<T> work) {
        return Mono.fromCallable(work)
                .subscribeOn(SCHEDULER)
                .onErrorMap(
                        error ->
                                error instanceof RejectedExecutionException
                                        ? new ToolProblem(
                                                429,
                                                "execution_busy",
                                                "All execution slots and the queue are full")
                                        : error)
                .doFinally(signal -> WORKERS.purge());
    }

    private Result invoke(
            ToolHttpRequestMapper.Request mapped,
            Resources resources,
            long start,
            Map<String, Object> credential)
            throws Exception {
        URI uri = mapped.uri();
        Map<String, String> headers = mapped.headers();
        for (int redirects = 0; ; redirects++) {
            if (redirects > 3) throw new ToolProblem(422, "invalid_redirect", "Too many redirects");
            long remaining = budget.toMillis() - elapsed(start);
            if (remaining <= 0) throw new TimeoutException();
            var future =
                    client.sendAsync(
                            mapped.http(uri, headers, Duration.ofMillis(remaining)),
                            HttpResponse.BodyHandlers.ofInputStream());
            resources.future(future);
            HttpResponse<InputStream> response;
            try {
                response = future.get(remaining, TimeUnit.MILLISECONDS);
            } catch (ExecutionException error) {
                if (error.getCause() instanceof Exception cause) throw cause;
                throw error;
            }
            resources.stream(response.body());
            int status = response.statusCode();
            if (status >= 300 && status < 400 && status != 304) {
                response.body().close();
                String location =
                        response.headers()
                                .firstValue("Location")
                                .orElseThrow(
                                        () ->
                                                new ToolProblem(
                                                        422,
                                                        "invalid_redirect",
                                                        "Redirect has no target"));
                URI next = ToolConnectionProbe.target(uri.resolve(location).toString());
                if (!sameOrigin(mapped.uri(), next)) {
                    next = ToolCredentialRedactor.redirect(next, credential);
                    headers =
                            mapped.headers().entrySet().stream()
                                    .filter(
                                            entry ->
                                                    entry.getKey().equalsIgnoreCase("Content-Type"))
                                    .collect(
                                            java.util.stream.Collectors.toMap(
                                                    Map.Entry::getKey, Map.Entry::getValue));
                }
                uri = next;
                continue;
            }
            byte[] bytes;
            try (InputStream stream = response.body()) {
                bytes =
                        stream.readNBytes(
                                Math.addExact(
                                        MAX_RESPONSE_BYTES + 1,
                                        ToolCredentialRedactor.lookahead(credential)));
            }
            boolean truncated = bytes.length > MAX_RESPONSE_BYTES;
            String text = BoundedToolOutput.utf8(bytes, bytes.length);
            String error = status >= 400 ? "http_error" : null;
            if (!truncated
                    && error == null
                    && response.headers()
                            .firstValue("Content-Type")
                            .orElse("")
                            .toLowerCase(java.util.Locale.ROOT)
                            .contains("json")
                    && !text.isBlank()) {
                try {
                    json.readTree(text);
                } catch (Exception invalid) {
                    error = "invalid_response";
                }
            }
            var redacted = ToolCredentialRedactor.render(text, credential, truncated);
            return new Result(
                    status,
                    redacted.text(),
                    elapsed(start),
                    truncated || redacted.truncated(),
                    error);
        }
    }

    public static Result failure(String code, long start) {
        String message =
                switch (code) {
                    case "execution_timeout" ->
                            "Execution timed out; the external operation's outcome is unknown";
                    case "execution_unknown" ->
                            "The previous execution outcome cannot be confirmed; this run will not"
                                    + " be sent again";
                    case "execution_cancelled" ->
                            "Stopped locally; an external operation already sent may still"
                                    + " complete";
                    default -> "Could not connect to the configured service";
                };
        return new Result(null, message, elapsed(start), false, code);
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0
                ? uri.getPort()
                : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static final class Resources {
        private CompletableFuture<?> future;
        private InputStream stream;
        private boolean closed;

        synchronized void future(CompletableFuture<?> value) {
            future = value;
            if (closed) value.cancel(true);
        }

        synchronized void stream(InputStream value) {
            stream = value;
            if (closed) closeStream();
        }

        synchronized void close() {
            closed = true;
            if (future != null) future.cancel(true);
            closeStream();
        }

        private void closeStream() {
            if (stream != null)
                try {
                    stream.close();
                } catch (java.io.IOException ignored) {
                    /* cancellation is already reported */
                }
        }
    }
}
