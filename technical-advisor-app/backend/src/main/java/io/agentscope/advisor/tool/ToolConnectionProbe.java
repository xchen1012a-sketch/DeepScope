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
package io.agentscope.advisor.tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Connectivity checks use HEAD only; they never execute a configured business operation. */
public final class ToolConnectionProbe {
    /** The whole check, redirects included, has to finish inside this budget. */
    private static final Duration TOTAL_BUDGET = Duration.ofSeconds(10);

    private static final int MAX_REDIRECTS = 3;

    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(4))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public record Result(int httpStatus, long elapsedMs, String warning) {}

    public Result check(String address, Map<String, String> headers) {
        URI uri = target(address);
        long start = System.nanoTime();
        Map<String, String> sent = headers;
        boolean authenticated = !headers.isEmpty();
        try {
            for (int redirects = 0; ; redirects++) {
                if (redirects > MAX_REDIRECTS)
                    throw new ToolProblem(422, "invalid_redirect", "Too many redirects");
                long remaining = TOTAL_BUDGET.toMillis() - elapsedMs(start);
                if (remaining <= 0)
                    throw new ToolProblem(504, "connection_timeout", "Connection check timed out");
                HttpRequest.Builder request =
                        HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(remaining));
                sent.forEach(request::header);
                var response =
                        client.send(
                                request.method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                                HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status >= 300 && status < 400 && status != 304) {
                    String location = response.headers().firstValue("location").orElse(null);
                    if (location == null)
                        throw new ToolProblem(
                                422, "invalid_redirect", "The service redirected without a target");
                    URI next = target(uri.resolve(location).toString());
                    if (!origin(uri).equals(origin(next))) sent = Map.of();
                    uri = next;
                    continue;
                }
                if (status >= 500)
                    throw new ToolProblem(
                            502, "service_unavailable", "The service is temporarily unavailable");
                if ((status == 401 || status == 403) && authenticated)
                    throw new ToolProblem(
                            422, "auth_failed", "The service rejected the saved credentials");
                return new Result(status, elapsedMs(start), "call_not_tested");
            }
        } catch (ToolProblem error) {
            throw error;
        } catch (java.net.http.HttpTimeoutException error) {
            throw new ToolProblem(504, "connection_timeout", "Connection check timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolProblem(503, "connection_cancelled", "Connection check was cancelled");
        } catch (Exception error) {
            throw new ToolProblem(
                    502, "connection_failed", "Could not connect to the configured service");
        }
    }

    /**
     * Checks a whole document's operations. One probe per host answers "can this deployment reach
     * the service", so fifty operations still cost a single request instead of fifty. An operation
     * with no execution address is refused, so nothing is saved that could never be called.
     *
     * @return the last probe's result
     */
    public Result checkAll(List<String> addresses, Map<String, String> headers) {
        Map<String, String> perHost = new LinkedHashMap<>();
        for (String address : addresses) perHost.putIfAbsent(origin(target(address)), address);
        Result last = null;
        for (String address : perHost.values()) last = check(address, headers);
        return last;
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    public static URI target(String address) {
        if (address == null || address.isBlank())
            throw new ToolProblem(
                    422,
                    "address_required",
                    "No execution address was found; provide a server address");
        try {
            URI uri = URI.create(address.replaceAll("\\{[^}]+\\}", "probe"));
            if (!("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException error) {
            throw new ToolProblem(
                    422, "invalid_address", "A valid HTTP or HTTPS service address is required");
        }
    }

    private static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
    }
}
