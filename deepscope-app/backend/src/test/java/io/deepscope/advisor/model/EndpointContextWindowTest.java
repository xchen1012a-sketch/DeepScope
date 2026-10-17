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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EndpointContextWindowTest {
    @Test
    void discoversTheSelectedModelsAdvertisedWindowWithoutAssumingOneMillion() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/models",
                exchange -> {
                    byte[] body =
                            "{\"data\":[{\"id\":\"large-model\",\"context_window\":1048576},{\"id\":\"small-model\",\"context_window\":128000}]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var stream = exchange.getResponseBody()) {
                        stream.write(body);
                    }
                });
        server.start();
        try {
            var client = new OpenAiEndpointClient();
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            assertEquals(
                    1_048_576,
                    client.contextWindow(
                                    new ModelCatalog.EndpointDraft(
                                            "chat", "custom", "large-model", base, "fake-key"))
                            .orElseThrow());
            assertEquals(
                    128_000,
                    client.contextWindow(
                                    new ModelCatalog.EndpointDraft(
                                            "chat", "custom", "small-model", base, "fake-key"))
                            .orElseThrow());
            assertTrue(
                    client.contextWindow(
                                    new ModelCatalog.EndpointDraft(
                                            "chat", "custom", "not-advertised", base, "fake-key"))
                            .isEmpty());
        } finally {
            server.stop(0);
        }
    }
}
