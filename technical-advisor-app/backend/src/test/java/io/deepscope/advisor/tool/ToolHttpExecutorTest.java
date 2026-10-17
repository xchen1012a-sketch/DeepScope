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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolHttpExecutorTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private String address;
    private ToolHttpExecutor executor;
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    calls.incrementAndGet();
                    request.set(
                            exchange.getRequestMethod()
                                    + " "
                                    + exchange.getRequestURI()
                                    + " "
                                    + exchange.getRequestHeaders().getFirst("X-Trace")
                                    + " "
                                    + exchange.getRequestHeaders().getFirst("Cookie")
                                    + " "
                                    + exchange.getRequestHeaders().getFirst("Content-Type")
                                    + " "
                                    + new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] response =
                            (exchange.getRequestURI().getPath().equals("/large")
                                            ? "x".repeat(300000)
                                            : exchange.getRequestURI().getPath().equals("/bad-json")
                                                    ? "{broken"
                                                    : "{\"ok\":true}")
                                    .getBytes(StandardCharsets.UTF_8);
                    if (exchange.getRequestURI().getPath().equals("/bad-json"))
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(
                            exchange.getRequestURI().getPath().equals("/failure") ? 503 : 200,
                            response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        address = "http://127.0.0.1:" + server.getAddress().getPort();
        executor = new ToolHttpExecutor(json, HttpClient.newHttpClient(), Duration.ofSeconds(2));
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
    }

    @Test
    void mapsPathQueryHeaderCookieAndExplodedArrays() {
        var schema =
                Map.<String, Object>of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "id", Map.of("type", "string", "x-parameter-in", "path"),
                                "q",
                                        Map.of(
                                                "type",
                                                "array",
                                                "x-parameter-in",
                                                "query",
                                                "x-parameter-name",
                                                "tag",
                                                "x-explode",
                                                true),
                                "trace",
                                        Map.of(
                                                "type",
                                                "string",
                                                "x-parameter-in",
                                                "header",
                                                "x-parameter-name",
                                                "X-Trace"),
                                "session", Map.of("type", "string", "x-parameter-in", "cookie")));
        var result =
                executor.execute(
                                "GET",
                                address + "/items/{id}",
                                schema,
                                Map.of(
                                        "id",
                                        "a/b c",
                                        "q",
                                        List.of("one", "two"),
                                        "trace",
                                        "trace-value",
                                        "session",
                                        "abc"),
                                null)
                        .block();
        assertEquals(200, result.httpStatus());
        assertEquals(
                "GET /items/a%2Fb%20c?tag=one&tag=two trace-value session=abc null ",
                request.get());
    }

    @Test
    void preservesOriginalBodyNamesAndFormMultipartSerialization() {
        for (String media :
                List.of(
                        "application/json",
                        "application/x-www-form-urlencoded",
                        "multipart/form-data")) {
            var schema =
                    Map.<String, Object>of(
                            "x-body-content-type",
                            media,
                            "properties",
                            Map.of(
                                    "body_id",
                                    Map.of(
                                            "type",
                                            "string",
                                            "x-parameter-in",
                                            "body",
                                            "x-parameter-name",
                                            "id")));
            executor.execute("POST", address + "/", schema, Map.of("body_id", "a b"), null).block();
            String sent = request.get();
            assertTrue(sent.contains(media));
            assertTrue(
                    media.equals("application/json")
                            ? sent.contains("{\"id\":\"a b\"}")
                            : media.contains("urlencoded")
                                    ? sent.contains("id=a%20b")
                                    : sent.contains("name=\"id\"") && sent.contains("a b"));
        }
    }

    @Test
    void injectsBearerBasicAndQueryApiKey() {
        executor.execute(
                        "GET",
                        address,
                        Map.of(),
                        Map.of(),
                        ToolAuth.secret(Map.of("type", "bearer", "value", "fake")))
                .block();
        assertEquals("Bearer fake", authorization.get());
        executor.execute(
                        "GET",
                        address,
                        Map.of(),
                        Map.of(),
                        ToolAuth.secret(Map.of("type", "basic", "username", "u", "password", "p")))
                .block();
        assertEquals("Basic dTpw", authorization.get());
        executor.execute(
                        "GET",
                        address,
                        Map.of(),
                        Map.of(),
                        ToolAuth.secret(
                                Map.of(
                                        "type", "apiKey", "name", "key", "in", "query", "value",
                                        "fake")))
                .block();
        assertTrue(request.get().contains("?key=fake"));
    }

    @Test
    void validatesRequiredTypesEnumsAndRangesBeforeSending() {
        var schema =
                Map.<String, Object>of(
                        "required",
                        List.of("count"),
                        "properties",
                        Map.of(
                                "count",
                                Map.of(
                                        "type",
                                        "integer",
                                        "minimum",
                                        1,
                                        "maximum",
                                        3,
                                        "enum",
                                        List.of(1, 2))));
        for (Map<String, Object> input :
                List.of(
                        Map.<String, Object>of(),
                        Map.<String, Object>of("count", "2"),
                        Map.<String, Object>of("count", 0),
                        Map.<String, Object>of("count", 3),
                        Map.<String, Object>of("count", 4))) {
            ToolProblem error =
                    assertThrows(
                            ToolProblem.class,
                            () -> executor.execute("GET", address, schema, input, null).block());
            assertEquals("invalid_parameters", error.code());
        }
        assertEquals(0, calls.get());
    }

    @Test
    void limitsResponseAndDistinguishesBusinessAndDecodeErrors() {
        var large = executor.execute("GET", address + "/large", Map.of(), Map.of(), null).block();
        assertTrue(large.truncated());
        assertEquals(256 * 1024, large.result().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(
                "http_error",
                executor.execute("GET", address + "/failure", Map.of(), Map.of(), null)
                        .block()
                        .errorCode());
        assertEquals(
                "invalid_response",
                executor.execute("GET", address + "/bad-json", Map.of(), Map.of(), null)
                        .block()
                        .errorCode());
    }

    @Test
    void arrayStyleAndExplodePreserveHttpDelimiters() {
        for (var example :
                List.of(
                        List.of("simple", "false", "/a,b"),
                        List.of("label", "true", "/.a.b"),
                        List.of("matrix", "true", "/;id=a;id=b"))) {
            var schema =
                    Map.<String, Object>of(
                            "properties",
                            Map.of(
                                    "id",
                                    Map.of(
                                            "type",
                                            "array",
                                            "x-parameter-in",
                                            "path",
                                            "x-parameter-name",
                                            "id",
                                            "x-style",
                                            example.get(0),
                                            "x-explode",
                                            Boolean.parseBoolean(example.get(1)))));
            executor.execute(
                            "GET", address + "/{id}", schema, Map.of("id", List.of("a", "b")), null)
                    .block();
            assertTrue(request.get().startsWith("GET " + example.get(2) + " "), request.get());
        }
    }

    @Test
    void validatesJsonSchemaExclusiveRangesAndUnionTypes() {
        for (var schema :
                List.of(
                        Map.<String, Object>of("type", List.of("integer", "null")),
                        Map.<String, Object>of(
                                "type", "number", "exclusiveMinimum", 1, "exclusiveMaximum", 3))) {
            var root = Map.<String, Object>of("properties", Map.of("value", schema));
            var invalid =
                    schema.containsKey("exclusiveMinimum")
                            ? Map.<String, Object>of("value", 1)
                            : Map.<String, Object>of("value", "wrong");
            assertEquals(
                    "invalid_parameters",
                    assertThrows(
                                    ToolProblem.class,
                                    () ->
                                            executor.execute("GET", address, root, invalid, null)
                                                    .block())
                            .code());
        }
        assertEquals(0, calls.get());
    }

    @Test
    void redirectsRecheckTargetsAndRemoveCrossOriginCredentials() throws Exception {
        HttpServer next = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var seen = new AtomicReference<String>();
        next.createContext(
                "/",
                exchange -> {
                    seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        next.start();
        try {
            server.createContext(
                    "/redirect",
                    exchange -> {
                        exchange.getResponseHeaders()
                                .set(
                                        "Location",
                                        "http://127.0.0.1:" + next.getAddress().getPort() + "/");
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    });
            var result =
                    executor.execute(
                                    "GET",
                                    address + "/redirect",
                                    Map.of(),
                                    Map.of(),
                                    Map.of("type", "bearer", "value", "fake"))
                            .block();
            assertEquals(200, result.httpStatus());
            assertEquals(null, seen.get());
            server.createContext(
                    "/invalid-redirect",
                    exchange -> {
                        exchange.getResponseHeaders().set("Location", "file:///etc/passwd");
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    });
            assertEquals(
                    "invalid_address",
                    assertThrows(
                                    ToolProblem.class,
                                    () ->
                                            executor.execute(
                                                            "GET",
                                                            address + "/invalid-redirect",
                                                            Map.of(),
                                                            Map.of(),
                                                            null)
                                                    .block())
                            .code());
        } finally {
            next.stop(0);
        }
    }

    @Test
    void stalledBodyHasATotalDeadlineAndCancellationClosesStreamAndFuture() throws Exception {
        var stream = new ControlledStream();
        var client = org.mockito.Mockito.mock(HttpClient.class);
        org.mockito.Mockito.when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<java.io.InputStream> response =
                org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(response.body()).thenReturn(stream);
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(response.headers())
                .thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));
        var future = java.util.concurrent.CompletableFuture.completedFuture(response);
        org.mockito.Mockito.when(
                        client.sendAsync(
                                org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                                org.mockito.ArgumentMatchers
                                        .<java.net.http.HttpResponse.BodyHandler<
                                                        java.io.InputStream>>
                                                any()))
                .thenReturn(future);
        var bounded = new ToolHttpExecutor(json, client, Duration.ofMillis(200));
        long start = System.nanoTime();
        assertEquals(
                "execution_timeout",
                bounded.execute("GET", address, Map.of(), Map.of(), null).block().errorCode());
        assertTrue(stream.closed.await(1, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500);
        var pending =
                new java.util.concurrent.CompletableFuture<
                        java.net.http.HttpResponse<java.io.InputStream>>();
        var sent = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(
                        client.sendAsync(
                                org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                                org.mockito.ArgumentMatchers
                                        .<java.net.http.HttpResponse.BodyHandler<
                                                        java.io.InputStream>>
                                                any()))
                .thenAnswer(
                        invocation -> {
                            sent.countDown();
                            return pending;
                        });
        var subscription =
                new ToolHttpExecutor(json, client, Duration.ofSeconds(2))
                        .execute("GET", address, Map.of(), Map.of(), null)
                        .subscribe();
        assertTrue(sent.await(1, java.util.concurrent.TimeUnit.SECONDS));
        subscription.dispose();
        assertTrue(pending.isCancelled());
    }

    @Test
    void globalExecutionLimitsAreFourWorkersAndSixteenQueued() throws Exception {
        var client = org.mockito.Mockito.mock(HttpClient.class);
        org.mockito.Mockito.when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        var sent = new java.util.concurrent.CountDownLatch(4);
        org.mockito.Mockito.when(
                        client.sendAsync(
                                org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                                org.mockito.ArgumentMatchers
                                        .<java.net.http.HttpResponse.BodyHandler<
                                                        java.io.InputStream>>
                                                any()))
                .thenAnswer(
                        invocation -> {
                            sent.countDown();
                            return new java.util.concurrent.CompletableFuture<
                                    java.net.http.HttpResponse<java.io.InputStream>>();
                        });
        var bounded = new ToolHttpExecutor(json, client, Duration.ofSeconds(5));
        var subscriptions = new java.util.ArrayList<reactor.core.Disposable>();
        try {
            for (int i = 0; i < 4; i++)
                subscriptions.add(
                        bounded.execute("GET", address, Map.of(), Map.of(), null).subscribe());
            assertTrue(sent.await(2, java.util.concurrent.TimeUnit.SECONDS));
            for (int i = 0; i < 16; i++)
                subscriptions.add(
                        bounded.execute("GET", address, Map.of(), Map.of(), null).subscribe());
            var error =
                    assertThrows(
                            ToolProblem.class,
                            () ->
                                    bounded.execute("GET", address, Map.of(), Map.of(), null)
                                            .block());
            assertEquals(429, error.status());
            assertEquals("execution_busy", error.code());
            org.mockito.Mockito.verify(client, org.mockito.Mockito.times(4))
                    .sendAsync(
                            org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                            org.mockito.ArgumentMatchers
                                    .<java.net.http.HttpResponse.BodyHandler<java.io.InputStream>>
                                            any());
        } finally {
            subscriptions.forEach(reactor.core.Disposable::dispose);
        }
    }

    private static final class ControlledStream extends java.io.InputStream {
        final java.util.concurrent.CountDownLatch closed =
                new java.util.concurrent.CountDownLatch(1);

        @Override
        public int read() throws java.io.IOException {
            try {
                closed.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(error);
            }
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    @Test
    void crossOriginRedirectStripsQueryApiKeyEvenWhenLocationEchoesIt() throws Exception {
        HttpServer next = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var seen = new AtomicReference<String>();
        next.createContext(
                "/",
                exchange -> {
                    seen.set(exchange.getRequestURI().toString());
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        next.start();
        try {
            server.createContext(
                    "/query-redirect",
                    exchange -> {
                        exchange.getResponseHeaders()
                                .set(
                                        "Location",
                                        "http://127.0.0.1:"
                                                + next.getAddress().getPort()
                                                + "/next?%6bey=fake&keep=value");
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    });
            executor.execute(
                            "GET",
                            address + "/query-redirect",
                            Map.of(),
                            Map.of(),
                            Map.of("type", "apiKey", "in", "query", "name", "key", "value", "fake"))
                    .block();
            assertEquals("/next?keep=value", seen.get());
        } finally {
            next.stop(0);
        }
    }

    @Test
    void returnedAndModelResultsRedactActiveCredentialsOnSuccessAndBusinessErrors()
            throws Exception {
        for (int code : List.of(200, 422)) {
            server.createContext(
                    "/echo-" + code,
                    exchange -> {
                        byte[] response =
                                ("Authorization: Bearer fake-token; key=fake-key; Basic dTpw; u:p")
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(code, response.length);
                        exchange.getResponseBody().write(response);
                        exchange.close();
                    });
            for (var credential :
                    List.of(
                            Map.<String, Object>of("type", "bearer", "value", "fake-token"),
                            Map.<String, Object>of(
                                    "type",
                                    "apiKey",
                                    "in",
                                    "header",
                                    "name",
                                    "X-Key",
                                    "value",
                                    "fake-key"),
                            Map.<String, Object>of(
                                    "type", "basic", "username", "u", "password", "p"))) {
                var result =
                        executor.execute(
                                        "GET",
                                        address + "/echo-" + code,
                                        Map.of(),
                                        Map.of(),
                                        credential)
                                .block();
                String secret =
                        "basic".equals(credential.get("type"))
                                ? "dTpw"
                                : String.valueOf(credential.get("value"));
                assertTrue(!result.result().contains(secret), result.result());
                var tool =
                        new ToolCatalog.ToolRow(
                                "echo",
                                "echo",
                                "Echo",
                                "{}",
                                "http",
                                "allow",
                                "owner",
                                null,
                                "GET",
                                address + "/echo-" + code,
                                true,
                                0,
                                0,
                                1,
                                "{}");
                var model =
                        HttpCatalogTool.create(tool, json, executor, () -> credential, () -> {})
                                .callAsync(
                                        io.deepscope.core.tool.ToolCallParam.builder()
                                                .input(Map.of())
                                                .build())
                                .block();
                String text =
                        ((io.deepscope.core.message.TextBlock) model.getOutput().get(0)).getText();
                assertTrue(!text.contains(secret), text);
            }
        }
    }

    @Test
    void formRetainsBodyArrayMetadataWhenQueryHasTheSameOriginalName() {
        var schema =
                Map.<String, Object>of(
                        "x-body-content-type",
                        "application/x-www-form-urlencoded",
                        "properties",
                        Map.of(
                                "tags", Map.of("type", "string", "x-parameter-in", "query"),
                                "body_tags",
                                        Map.of(
                                                "type",
                                                "array",
                                                "x-parameter-in",
                                                "body",
                                                "x-parameter-name",
                                                "tags",
                                                "x-explode",
                                                false)));
        executor.execute(
                        "POST",
                        address,
                        schema,
                        Map.of("tags", "query", "body_tags", List.of("a", "b")),
                        null)
                .block();
        assertTrue(request.get().endsWith(" tags=a,b"), request.get());
        assertTrue(request.get().contains("?tags=query"), request.get());
    }

    @Test
    void credentialCrossingResponseLimitIsRedactedBeforeFinalTruncation() throws Exception {
        String token = "fake-sensitive-token";
        byte[] body =
                ("x".repeat(ToolHttpExecutor.MAX_RESPONSE_BYTES - token.length() + 1)
                                + token
                                + "suffix".repeat(1000))
                        .getBytes(StandardCharsets.UTF_8);
        var stream = new java.io.ByteArrayInputStream(body);
        var client = org.mockito.Mockito.mock(HttpClient.class);
        org.mockito.Mockito.when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<java.io.InputStream> response =
                org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(response.body()).thenReturn(stream);
        org.mockito.Mockito.when(response.headers())
                .thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));
        org.mockito.Mockito.when(
                        client.sendAsync(
                                org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                                org.mockito.ArgumentMatchers
                                        .<java.net.http.HttpResponse.BodyHandler<
                                                        java.io.InputStream>>
                                                any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(response));
        var result =
                new ToolHttpExecutor(json, client, Duration.ofSeconds(2))
                        .execute(
                                "GET",
                                address,
                                Map.of(),
                                Map.of(),
                                Map.of("type", "bearer", "value", token))
                        .block();
        assertTrue(result.truncated());
        assertTrue(
                !result.result().contains("fake-sensitive"),
                "A credential prefix crossed the limit");
        assertTrue(
                result.result().getBytes(StandardCharsets.UTF_8).length
                        <= ToolHttpExecutor.MAX_RESPONSE_BYTES);
        assertTrue(
                body.length - stream.available()
                        <= ToolHttpExecutor.MAX_RESPONSE_BYTES
                                + 18 * ("Bearer ".length() + token.length())
                                + 1,
                "Only bounded secret lookahead may be read");
    }

    @Test
    void mixedUnicodeJsonEscapesCannotExposeTheActiveCredential() throws Exception {
        String slash = String.valueOf((char) 92);
        String body = "{\"token\":\"fake" + slash + "u002dsensitive" + slash + "u002dtoken\"}";
        server.createContext(
                "/unicode-echo",
                exchange -> {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        var result =
                executor.execute(
                                "GET",
                                address + "/unicode-echo",
                                Map.of(),
                                Map.of(),
                                Map.of("type", "bearer", "value", "fake-sensitive-token"))
                        .block();
        assertEquals(200, result.httpStatus());
        assertEquals(null, result.errorCode());
        assertTrue(
                !"fake-sensitive-token"
                        .equals(json.readTree(result.result()).path("token").asText()),
                "JSON decoding recovered the active credential");
    }
}
