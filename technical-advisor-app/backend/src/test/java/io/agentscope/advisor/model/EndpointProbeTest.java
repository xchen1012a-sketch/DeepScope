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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class EndpointProbeTest {
    @Test
    void parsesEmbeddingDimensionsAndDiscoversModels() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/embeddings",
                exchange -> {
                    byte[] body = "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}".getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/v1/models",
                exchange -> {
                    byte[] body = "{\"data\":[{\"id\":\"one\"},{\"id\":\"two\"}]}".getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            var draft =
                    new ModelCatalog.EndpointDraft(
                            "embedding",
                            "custom",
                            "embed",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "fake-key");
            var client = new OpenAiEndpointClient();
            assertEquals(3, client.check(draft).dimensions());
            assertEquals(List.of("one", "two"), client.discover(draft));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anthropicUsesNativeMessagesProtocol() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/messages",
                exchange -> {
                    assertEquals("fake-key", exchange.getRequestHeaders().getFirst("x-api-key"));
                    assertEquals(
                            "2023-06-01",
                            exchange.getRequestHeaders().getFirst("anthropic-version"));
                    byte[] body = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}".getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            var draft =
                    new ModelCatalog.EndpointDraft(
                            "chat",
                            "anthropic",
                            "claude",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                            "fake-key");
            assertTrue(new OpenAiEndpointClient().check(draft).ok());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedSuccessIsRejectedAndErrorsNeverExposeCredential() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    byte[] body = "fake-key".getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            var result =
                    new OpenAiEndpointClient()
                            .check(
                                    new ModelCatalog.EndpointDraft(
                                            "chat",
                                            "custom",
                                            "chat",
                                            "http://127.0.0.1:"
                                                    + server.getAddress().getPort()
                                                    + "/v1",
                                            "fake-key"));
            assertFalse(result.ok());
            assertFalse(result.failure().contains("fake-key"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anthropicRootAndVersionedAddressesUseSameProbeAndRuntimePath() throws Exception {
        var paths = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    paths.add(exchange.getRequestURI().getPath());
                    var request =
                            new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(exchange.getRequestBody());
                    byte[] body =
                            request.path("stream").asBoolean()
                                    ? "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"fixture rejection\"}}"
                                            .getBytes()
                                    : "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"
                                            .getBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(
                            request.path("stream").asBoolean() ? 401 : 200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new OpenAiEndpointClient();
            for (String base : List.of(root, root + "/v1/")) {
                assertTrue(
                        client.check(
                                        new ModelCatalog.EndpointDraft(
                                                "chat", "anthropic", "claude", base, "fake-key"))
                                .ok());
                var model =
                        client.chat(
                                new ModelCatalog.Endpoint(
                                        "id",
                                        "chat",
                                        "anthropic",
                                        "claude",
                                        base,
                                        "fake-key",
                                        false,
                                        null));
                org.junit.jupiter.api.Assertions.assertThrows(
                        RuntimeException.class,
                        () ->
                                model.stream(
                                                List.of(
                                                        io.agentscope.core.message.Msg.builder()
                                                                .role(
                                                                        io.agentscope.core.message
                                                                                .MsgRole.USER)
                                                                .textContent("ping")
                                                                .build()),
                                                List.of(),
                                                null)
                                        .blockLast(java.time.Duration.ofSeconds(5)));
            }
            assertEquals(
                    List.of("/v1/messages", "/v1/messages", "/v1/messages", "/v1/messages"), paths);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reasoningModelsUseCompletionTokenLimitAndAcceptEmptyText() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    var json =
                            new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(exchange.getRequestBody());
                    boolean valid = json.has("max_completion_tokens") && !json.has("max_tokens");
                    byte[] body =
                            (valid
                                            ? "{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"length\"}]}"
                                            : "{\"error\":{\"message\":\"max_tokens"
                                                    + " unsupported\"}}")
                                    .getBytes();
                    exchange.sendResponseHeaders(valid ? 200 : 400, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            for (String name : List.of("o1", "o3-mini", "o4-mini", "gpt-5", "gpt-5.1")) {
                assertTrue(
                        new OpenAiEndpointClient()
                                .check(
                                        new ModelCatalog.EndpointDraft(
                                                "chat",
                                                "openai",
                                                name,
                                                "http://127.0.0.1:"
                                                        + server.getAddress().getPort()
                                                        + "/v1",
                                                "fake-key"))
                                .ok(),
                        name);
            }
        } finally {
            server.stop(0);
        }
    }
}
