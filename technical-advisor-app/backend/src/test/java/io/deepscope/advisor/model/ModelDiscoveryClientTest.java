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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ModelDiscoveryClientTest {
    private HttpServer server;
    private String base;
    private final OpenAiEndpointClient client = new OpenAiEndpointClient();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"data\":[\"a\",{\"id\":\"b\"},\"a\"]}",
                "{\"models\":[\"a\",{\"id\":\"b\"}]}",
                "[\"a\",{\"id\":\"b\"}]"
            })
    void acceptsKnownShapesAndPreservesUniqueIds(String body) {
        server.createContext(
                "/v1/models",
                exchange -> {
                    assertEquals(
                            "Bearer fake-key",
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    reply(exchange, 200, body);
                });
        var result = client.discoverResult(draft("custom"));
        assertEquals(List.of("a", "b"), result.items());
        assertTrue(result.complete());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"data\":null}",
                "{\"data\":[{}]}",
                "{\"data\":[{\"name\":\"a\"}]}",
                "{\"data\":[{\"id\":3}]}",
                "{\"data\":[\"\"]}",
                "{\"data\":[null]}",
                "not json"
            })
    void rejectsUnknownOrMalformedShapes(String body) {
        server.createContext("/v1/models", exchange -> reply(exchange, 200, body));
        failure("invalid_response", draft("custom"));
    }

    @Test
    void emptyArrayIsACompleteEmptyResult() {
        server.createContext("/v1/models", exchange -> reply(exchange, 200, "{\"data\":[]}"));
        assertEquals(
                new ModelProbe.DiscoveryResult(List.of(), true),
                client.discoverResult(draft("custom")));
    }

    @Test
    void embeddingDiscoveryRetainsExplicitCapabilityEvidenceForOpaqueIds() {
        server.createContext(
                "/v1/models",
                exchange ->
                        reply(
                                exchange,
                                200,
                                "{\"data\":[{\"id\":\"vector-deployment\",\"capabilities\":{\"embedding\":true}},{\"id\":\"chat-deployment\",\"type\":\"chat\"}]}"));
        var result =
                client.discoverResult(
                        new ModelCatalog.EndpointDraft(
                                "embedding", "custom", null, base, "fake-key"));
        assertTrue(result.embeddingIds().contains("vector-deployment"));
        assertFalse(result.embeddingIds().contains("chat-deployment"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"total", "total_count"})
    void advertisedLargerTotalWithoutUsableNextRemainsPartial(String field) {
        server.createContext(
                "/v1/models",
                exchange -> reply(exchange, 200, "{\"data\":[\"a\"],\"" + field + "\":65}"));
        assertEquals(
                new ModelProbe.DiscoveryResult(List.of("a"), false),
                client.discoverResult(draft("custom")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"next_page_token\":\"opaque\"",
                "\"nextPageToken\":\"opaque\"",
                "\"next_cursor\":\"opaque\"",
                "\"pagination\":{\"has_more\":true}",
                "\"pagination\":{\"next\":\"opaque\"}"
            })
    void rejectsUnrecognizedExplicitContinuationSignals(String metadata) {
        server.createContext(
                "/v1/models",
                exchange -> reply(exchange, 200, "{\"data\":[\"a\"]," + metadata + "}"));
        failure("invalid_pagination", draft("custom"));
    }

    @Test
    void totalHintFromAnEarlierPageStillPreventsFalseCompleteness() {
        server.createContext(
                "/v1/models",
                exchange -> {
                    if (exchange.getRequestURI().getQuery() == null)
                        reply(
                                exchange,
                                200,
                                "{\"data\":[\"a\"],\"total\":65,\"next\":\"?page=2\"}");
                    else reply(exchange, 200, "[\"b\"]");
                });
        assertEquals(
                new ModelProbe.DiscoveryResult(List.of("a", "b"), false),
                client.discoverResult(draft("custom")));
    }

    @Test
    void exhaustedPaginationWithMatchingTotalIsComplete() {
        server.createContext(
                "/v1/models",
                exchange ->
                        reply(
                                exchange,
                                200,
                                "{\"data\":[\"a\"],\"total\":1,\"next_cursor\":null,\"pagination\":{\"has_more\":false,\"next\":null}}"));
        assertEquals(
                new ModelProbe.DiscoveryResult(List.of("a"), true),
                client.discoverResult(draft("custom")));
    }

    @Test
    void followsAnthropicCursorWithNativeAuthentication() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/v1/models",
                exchange -> {
                    assertEquals("fake-key", exchange.getRequestHeaders().getFirst("x-api-key"));
                    assertEquals(
                            "2023-06-01",
                            exchange.getRequestHeaders().getFirst("anthropic-version"));
                    assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                    if (calls.getAndIncrement() == 0) {
                        assertNull(exchange.getRequestURI().getQuery());
                        reply(
                                exchange,
                                200,
                                "{\"data\":[{\"id\":\"a/+\"}],\"has_more\":true,\"last_id\":\"a/+\"}");
                    } else {
                        assertEquals("after_id=a%2F%2B", exchange.getRequestURI().getRawQuery());
                        reply(exchange, 200, "{\"data\":[{\"id\":\"b\"}],\"has_more\":false}");
                    }
                });
        assertEquals(
                new ModelProbe.DiscoveryResult(List.of("a/+", "b"), true),
                client.discoverResult(draft("anthropic")));
        assertEquals(2, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"next", "next_page_url", "links", "header"})
    void followsExplicitSameOriginNextLinks(String mode) {
        server.createContext(
                "/v1/models",
                exchange -> {
                    if (exchange.getRequestURI().getQuery() != null) {
                        assertEquals("page=2", exchange.getRequestURI().getQuery());
                        reply(exchange, 200, "[\"b\"]");
                        return;
                    }
                    String next = "/v1/models?page=2";
                    String field =
                            switch (mode) {
                                case "links" -> ",\"links\":{\"next\":\"" + next + "\"}";
                                case "header" -> "";
                                default -> ",\"" + mode + "\":\"" + next + "\"";
                            };
                    if (mode.equals("header"))
                        exchange.getResponseHeaders().add("Link", "<" + next + ">; rel=\"next\"");
                    reply(exchange, 200, "{\"data\":[\"a\"]" + field + "}");
                });
        assertEquals(
                new ModelProbe.DiscoveryResult(List.of("a", "b"), true),
                client.discoverResult(draft("custom")));
    }

    @Test
    void followsRelativeUrlWithSameResourcePath() {
        server.createContext(
                "/v1/models",
                exchange -> {
                    if (exchange.getRequestURI().getQuery() == null)
                        reply(exchange, 200, "{\"data\":[\"a\"],\"next\":\"models?page=2\"}");
                    else reply(exchange, 200, "[\"b\"]");
                });
        assertEquals(List.of("a", "b"), client.discoverResult(draft("custom")).items());
    }

    @Test
    void rejectsMalformedNextLinkHeader() {
        server.createContext(
                "/v1/models",
                exchange -> {
                    exchange.getResponseHeaders().add("Link", "</v1/models?page=2; rel=next");
                    reply(exchange, 200, "[\"a\"]");
                });
        failure("invalid_pagination", draft("custom"));
    }

    @Test
    void slowResponseBodyTimesOutWithinThePageDeadline() {
        CountDownLatch finish = new CountDownLatch(1);
        server.createContext(
                "/v1/models",
                exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('[');
                    exchange.getResponseBody().flush();
                    try {
                        finish.await(25, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        long start = System.nanoTime();
        try {
            failure("timeout", draft("custom"));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(24));
        } finally {
            finish.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/v1/models",
                "http://localhost:1/v1/models?page=2",
                "/other?page=2",
                "cursor-token",
                "?page=2#fragment"
            })
    void rejectsLoopCrossOriginPathChangeAndUnknownCursor(String next) {
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/v1/models",
                exchange -> {
                    calls.incrementAndGet();
                    reply(exchange, 200, "{\"data\":[\"a\"],\"next\":\"" + next + "\"}");
                });
        failure("invalid_pagination", draft("custom"));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"data\":[\"a\"],\"has_more\":true}",
                "{\"data\":[\"a\"],\"has_more\":\"yes\"}",
                "{\"data\":[\"a\"],\"next\":7}"
            })
    void rejectsInvalidPaginationMetadata(String body) {
        server.createContext("/v1/models", exchange -> reply(exchange, 200, body));
        failure("invalid_pagination", draft("anthropic"));
    }

    @ParameterizedTest
    @CsvSource({
        "401,auth",
        "403,auth",
        "404,endpoint_not_found",
        "405,unsupported",
        "501,unsupported",
        "429,rate_limited",
        "500,upstream",
        "302,upstream"
    })
    void classifiesFailuresWithoutReturningUpstreamBody(int status, String code) {
        server.createContext(
                "/v1/models",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", base + "/stolen");
                    reply(exchange, status, "secret upstream body fake-key");
                });
        AtomicInteger redirected = new AtomicInteger();
        server.createContext(
                "/v1/stolen",
                exchange -> {
                    redirected.incrementAndGet();
                    reply(exchange, 200, "[]");
                });
        failure(code, draft("custom"));
        assertEquals(0, redirected.get());
    }

    @Test
    void secondPageFailureDoesNotBecomeCompleteSuccess() {
        server.createContext(
                "/v1/models",
                exchange -> {
                    if (exchange.getRequestURI().getQuery() == null)
                        reply(exchange, 200, "{\"data\":[\"a\"],\"next\":\"?page=2\"}");
                    else reply(exchange, 429, "do not expose");
                });
        failure("rate_limited", draft("custom"));
    }

    @Test
    void limitsUniqueIdsAndReportsPartialResults() {
        String ids =
                IntStream.range(0, 2001)
                        .mapToObj(i -> "\"m" + i + "\"")
                        .collect(java.util.stream.Collectors.joining(","));
        server.createContext("/v1/models", exchange -> reply(exchange, 200, "[" + ids + "]"));
        var result = client.discoverResult(draft("custom"));
        assertEquals(2000, result.items().size());
        assertEquals("m1999", result.items().get(1999));
        assertFalse(result.complete());
    }

    @Test
    void limitsPaginationToTwentyPages() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/v1/models",
                exchange -> {
                    int page = calls.incrementAndGet();
                    reply(
                            exchange,
                            200,
                            "{\"data\":[\"m" + page + "\"],\"next\":\"?page=" + (page + 1) + "\"}");
                });
        var result = client.discoverResult(draft("custom"));
        assertEquals(20, calls.get());
        assertEquals(20, result.items().size());
        assertFalse(result.complete());
    }

    @Test
    void boundsResponseBodyBeforeJsonParsing() {
        server.createContext(
                "/v1/models",
                exchange -> reply(exchange, 200, "[\"" + "x".repeat(2 * 1024 * 1024) + "\"]"));
        failure("invalid_response", draft("custom"));
    }

    @Test
    void rejectsOversizedModelIdsWithoutTruncatingThem() {
        server.createContext(
                "/v1/models", exchange -> reply(exchange, 200, "[\"" + "x".repeat(1025) + "\"]"));
        failure("invalid_response", draft("custom"));
    }

    @Test
    void rejectsTrailingJsonInsteadOfTreatingTheFirstArrayAsComplete() {
        server.createContext("/v1/models", exchange -> reply(exchange, 200, "[] {}"));
        failure("invalid_response", draft("custom"));
    }

    @Test
    void cancellationInterruptsBodyReadAndPreservesInterruptFlag() throws Exception {
        CountDownLatch headers = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        server.createContext(
                "/v1/models",
                exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('[');
                    exchange.getResponseBody().flush();
                    headers.countDown();
                    try {
                        finish.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        AtomicReference<String> outcome = new AtomicReference<>();
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                client.discoverResult(draft("custom"));
                            } catch (ModelProbe.DiscoveryException failure) {
                                outcome.set(
                                        failure.code()
                                                + ":"
                                                + Thread.currentThread().isInterrupted());
                            }
                        });
        worker.start();
        try {
            assertTrue(headers.await(3, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(3000);
            assertFalse(worker.isAlive());
            assertEquals("interrupted:true", outcome.get());
        } finally {
            finish.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "file:///tmp/models",
                "http://user:password@localhost/v1",
                "http://localhost/v1?token=fake",
                "http://localhost/v1#fragment"
            })
    void rejectsInvalidAddresses(String address) {
        failure(
                "invalid_address",
                new ModelCatalog.EndpointDraft("chat", "custom", "", address, "fake-key"));
    }

    private ModelCatalog.EndpointDraft draft(String provider) {
        return new ModelCatalog.EndpointDraft("chat", provider, "", base, "fake-key");
    }

    private void failure(String code, ModelCatalog.EndpointDraft draft) {
        var error =
                assertThrows(
                        ModelProbe.DiscoveryException.class, () -> client.discoverResult(draft));
        assertEquals(code, error.code());
        assertEquals(code, error.getMessage());
        assertNull(error.getCause());
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
