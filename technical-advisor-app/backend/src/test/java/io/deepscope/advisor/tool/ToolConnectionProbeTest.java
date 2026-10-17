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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The check answers "can we reach it"; it never claims the call itself was verified. */
class ToolConnectionProbeTest {

    private final ToolConnectionProbe probe = new ToolConnectionProbe();
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
    private HttpServer guarded;
    private HttpServer target;
    private String guardedAddress;
    private String targetAddress;

    @BeforeEach
    void setUp() throws Exception {
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/answer", exchange -> respond(exchange, 200, null));
        target.createContext("/hop", exchange -> respond(exchange, 302, guardedAddress));
        target.createContext("/nohost", exchange -> respond(exchange, 302, null));
        target.createContext("/loop", exchange -> respond(exchange, 302, targetAddress + "/loop"));
        target.createContext("/notmodified", exchange -> respond(exchange, 304, null));
        target.createContext("/broken", exchange -> respond(exchange, 503, null));
        target.start();
        targetAddress = "http://127.0.0.1:" + target.getAddress().getPort();

        guarded = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        guarded.createContext(
                "/answer",
                exchange -> {
                    seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    respond(exchange, 200, null);
                });
        guarded.start();
        guardedAddress = "http://127.0.0.1:" + guarded.getAddress().getPort() + "/answer";
    }

    @AfterEach
    void tearDown() {
        target.stop(0);
        guarded.stop(0);
    }

    @Test
    void anUnansweredChallengeStillMeansTheServiceIsReachable() {
        var result = probe.check(targetAddress + "/answer", Map.of());

        assertEquals(200, result.httpStatus());
        assertEquals("call_not_tested", result.warning());
    }

    @Test
    void aServiceThatRefusesTheConfiguredCredentialsIsReportedAsAnAuthFailure() throws Exception {
        var rejected = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        rejected.createContext("/answer", exchange -> respond(exchange, 401, null));
        rejected.start();
        try {
            String address = "http://127.0.0.1:" + rejected.getAddress().getPort() + "/answer";
            assertEquals(
                    "auth_failed",
                    assertThrows(
                                    ToolProblem.class,
                                    () ->
                                            probe.check(
                                                    address,
                                                    Map.of("Authorization", "Bearer nope")))
                            .code());
            // The same answer without credentials only proves the service is up.
            assertEquals(401, probe.check(address, Map.of()).httpStatus());
        } finally {
            rejected.stop(0);
        }
    }

    @Test
    void credentialsDoNotFollowARedirectToAnotherHost() {
        var result = probe.check(targetAddress + "/hop", Map.of("Authorization", "Bearer secret"));

        assertEquals(200, result.httpStatus());
        assertNull(seenAuthorization.get(), "a redirect to another host must drop the credential");
    }

    @Test
    void aRedirectWithoutATargetIsRefused() {
        assertEquals(
                "invalid_redirect",
                assertThrows(
                                ToolProblem.class,
                                () -> probe.check(targetAddress + "/nohost", Map.of()))
                        .code());
    }

    @Test
    void aRedirectLoopIsRefused() {
        assertEquals(
                "invalid_redirect",
                assertThrows(
                                ToolProblem.class,
                                () -> probe.check(targetAddress + "/loop", Map.of()))
                        .code());
    }

    @Test
    void notModifiedCountsAsAnAnswerRatherThanARedirect() {
        assertEquals(304, probe.check(targetAddress + "/notmodified", Map.of()).httpStatus());
    }

    @Test
    void anUnavailableServiceIsNotSaved() {
        assertEquals(
                "service_unavailable",
                assertThrows(
                                ToolProblem.class,
                                () -> probe.check(targetAddress + "/broken", Map.of()))
                        .code());
    }

    @Test
    void anAddressWithoutAHostIsRefusedBeforeAnyRequest() {
        assertEquals(
                "address_required",
                assertThrows(ToolProblem.class, () -> probe.check(null, Map.of())).code());
        assertEquals(
                "invalid_address",
                assertThrows(ToolProblem.class, () -> probe.check("ftp://example.test", Map.of()))
                        .code());
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange, int status, String location) {
        if (location != null) exchange.getResponseHeaders().set("Location", location);
        try {
            exchange.sendResponseHeaders(status, -1);
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        } finally {
            exchange.close();
        }
    }
}
