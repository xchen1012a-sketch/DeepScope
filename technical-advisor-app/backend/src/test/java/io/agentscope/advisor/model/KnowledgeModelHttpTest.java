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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KnowledgeModelHttpTest {
    @Test
    void jsonResponseModeIsSentOnlyWhenExplicitlyEnabled() throws Exception {
        var requests = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    requests.add(json.readTree(exchange.getRequestBody()));
                    byte[] response =
                            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        server.start();
        try {
            var endpoint =
                    new ModelCatalog.EndpointDraft(
                            "chat",
                            "openai",
                            "fixture",
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "fake");
            KnowledgeChatClient.ask(endpoint, "Return JSON", new byte[] {1});
            KnowledgeChatClient.ask(endpoint, "Return JSON", new byte[] {1}, true);
            assertTrue(!requests.get(0).has("response_format"));
            assertEquals(
                    "json_object", requests.get(1).path("response_format").path("type").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oversizedBodyIsCancelledDuringReceptionWithoutRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/large",
                exchange -> {
                    calls.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    byte[] body = new byte[1024 * 1024 + 8192];
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    } catch (java.io.IOException cancelled) {
                        /* Client enforces the boundary. */
                    }
                });
        server.start();
        try {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            KnowledgeModelHttp.post(
                                            "http://127.0.0.1:"
                                                    + server.getAddress().getPort()
                                                    + "/large",
                                            Map.of(),
                                            Map.of())
                                    .block());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void temporaryFailureRetriesButInvalidRequestsDoNot() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/embeddings",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    int count = calls.incrementAndGet();
                    byte[] response =
                            "{\"data\":[{\"embedding\":[1,0,0]}]}"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(count == 1 ? 429 : 200, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        server.start();
        try {
            var model =
                    new KnowledgeEmbeddingModel(
                            "fixture",
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "fake",
                            3);
            assertEquals(
                    3,
                    model.embed(
                                    io.agentscope.core.message.TextBlock.builder()
                                            .text("sample")
                                            .build())
                            .block()
                            .length);
            assertEquals(2, calls.get());
            server.removeContext("/embeddings");
            server.createContext(
                    "/embeddings",
                    exchange -> {
                        calls.incrementAndGet();
                        exchange.getResponseHeaders().add("x-request-id", "request-fixture-123");
                        exchange.sendResponseHeaders(400, -1);
                        exchange.close();
                    });
            var failure =
                    assertThrows(
                            KnowledgeModelFailure.class,
                            () ->
                                    model.embed(
                                                    io.agentscope.core.message.TextBlock.builder()
                                                            .text("sample")
                                                            .build())
                                            .block());
            assertEquals("request-fixture-123", failure.requestId());
            assertEquals("model_http_400", KnowledgeModelFailure.code(failure));
            assertEquals(3, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void realPendingHttpRequestHasAnOverallDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var held =
                new java.util.concurrent.atomic.AtomicReference<
                        com.sun.net.httpserver.HttpExchange>();
        server.createContext("/slow", held::set);
        server.start();
        long start = System.nanoTime();
        try {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            KnowledgeModelHttp.post(
                                            "http://127.0.0.1:"
                                                    + server.getAddress().getPort()
                                                    + "/slow",
                                            Map.of(),
                                            Map.of("test", true),
                                            Duration.ofMillis(250))
                                    .block());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000);
        } finally {
            if (held.get() != null) held.get().close();
            server.stop(0);
        }
    }
}
