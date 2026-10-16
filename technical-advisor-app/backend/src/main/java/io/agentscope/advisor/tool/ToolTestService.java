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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/** One test per account; a run ID owns exactly one immutable request and result. */
public final class ToolTestService implements AutoCloseable {
    private final ToolCatalog catalog;
    private final ObjectMapper json;
    private final ToolHttpExecutor executor;
    private final ToolCredentialStore credentials;
    private final McpToolSource mcp;
    private final CatalogExecutionGuard guard;
    private final ToolTestRunStore records;
    private final java.time.Clock clock;
    private static final int MAX_CONFIRMATIONS_PER_ACCOUNT = 16;
    private static final int MAX_CONFIRMATIONS = 256;
    private static final java.time.Duration CONFIRMATION_TTL = java.time.Duration.ofMinutes(5);
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final Map<String, Run> active = new LinkedHashMap<>();
    private final Map<String, Confirmation> confirmations = new LinkedHashMap<>();
    private boolean closed;

    public ToolTestService(
            ToolCatalog catalog,
            ObjectMapper advisorObjectMapper,
            ToolHttpExecutor executor,
            ToolCredentialStore credentials,
            McpToolSource mcp) {
        this(catalog, advisorObjectMapper, executor, credentials, mcp, java.time.Clock.systemUTC());
    }

    ToolTestService(
            ToolCatalog catalog,
            ObjectMapper advisorObjectMapper,
            ToolHttpExecutor executor,
            ToolCredentialStore credentials,
            McpToolSource mcp,
            java.time.Clock clock) {
        this.catalog = catalog;
        this.json = advisorObjectMapper;
        this.executor = executor;
        this.credentials = credentials;
        this.mcp = mcp;
        this.guard = new CatalogExecutionGuard(catalog);
        this.records = new ToolTestRunStore(catalog, advisorObjectMapper);
        this.clock = clock;
    }

    public record Request(
            Map<String, Object> input,
            Long savedRevision,
            Map<String, Object> draftDefinition,
            String runId,
            Boolean confirmed,
            String confirmationId) {}

    public record StopResult(String runId, String status, String messageKey) {}

    private record Definition(
            String method,
            String address,
            Map<String, Object> schema,
            Map<String, Object> credential) {}

    public ToolHttpExecutor.Result test(
            String owner, ToolCatalog.ToolRow tool, boolean canEdit, Request request) {
        if (request.runId() == null || request.runId().isBlank() || request.runId().length() > 128)
            throw new ToolProblem(
                    422, "invalid_run_id", "Provide a test run ID of at most 128 characters");
        var current = guard.check(tool.toolId());
        if (request.draftDefinition() != null && !canEdit)
            throw new ToolProblem(
                    403,
                    "permission_denied",
                    "Editing rights are required to test an unsaved definition");
        if (request.savedRevision() != null && request.savedRevision() != current.revision())
            throw new ToolProblem(
                    409,
                    "revision_conflict",
                    "The saved tool definition has changed",
                    Map.of("currentRevision", current.revision()));
        Map<String, Object> input = snapshot(request.input() == null ? Map.of() : request.input());
        Definition definition = definition(current, request.draftDefinition());
        ToolHttpRequestMapper.validate(definition.schema(), input, "input");
        String digest = digest(owner, current, request, input, definition);
        String key = owner + ":" + request.runId();
        Run run;
        synchronized (this) {
            if (closed)
                throw new ToolProblem(
                        503, "execution_unavailable", "Test execution is shutting down");
            run = runs.get(key);
            if (run != null) {
                if (!run.digest.equals(digest))
                    throw new ToolProblem(
                            409,
                            "run_conflict",
                            "This run ID already belongs to a different request");
            } else {
                var existing = records.find(owner, request.runId());
                if (existing.isPresent()) return replay(existing.get(), digest);
                if (active.containsKey(owner))
                    throw new ToolProblem(
                            429, "test_busy", "This account already has an active test");
                confirm(owner, current, request, digest);
                if (!records.reserve(owner, request.runId(), digest))
                    return replay(
                            records.find(owner, request.runId())
                                    .orElseThrow(
                                            () ->
                                                    new ToolProblem(
                                                            503,
                                                            "execution_record_unavailable",
                                                            "The execution record is unavailable")),
                            digest);
                run = new Run(owner, request.runId(), digest);
                runs.put(key, run);
                active.put(owner, run);
                Run started = run;
                Mono<ToolHttpExecutor.Result> execution;
                if (ToolCatalog.ORIGIN_MCP.equals(current.origin()))
                    execution = mcp(current, input, definition.credential());
                else
                    execution =
                            executor.execute(
                                    definition.method(),
                                    definition.address(),
                                    definition.schema(),
                                    input,
                                    definition.credential(),
                                    () -> checkExecution(current));
                started.attach(
                        execution.subscribe(
                                result -> finish(started, result, null),
                                error -> finish(started, null, error)));
            }
        }
        try {
            return run.result.join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new ToolProblem(502, "connection_failed", "Test execution failed");
        }
    }

    private void checkExecution(ToolCatalog.ToolRow expected) {
        var current = guard.check(expected.toolId());
        if (current.revision() != expected.revision())
            throw new ToolProblem(
                    409,
                    "revision_conflict",
                    "The tool changed while this execution was queued",
                    Map.of("currentRevision", current.revision()));
    }

    private record Confirmation(String owner, String digest, java.time.Instant expiresAt) {}

    private void confirm(String owner, ToolCatalog.ToolRow tool, Request request, String digest) {
        java.time.Instant now = clock.instant();
        confirmations.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        if (!ToolCatalog.PERMISSION_ASK.equals(tool.permission())) return;
        if (request.confirmationId() != null) {
            Confirmation confirmation = confirmations.get(request.confirmationId());
            if (confirmation == null
                    || !confirmation.owner().equals(owner)
                    || !confirmation.digest().equals(digest))
                throw new ToolProblem(
                        409, "confirmation_invalid", "Confirmation no longer matches this request");
            confirmations.remove(request.confirmationId());
            return;
        }
        // Legacy confirmed:true still authorizes only the request admitted under this owner and
        // revision.
        if (Boolean.TRUE.equals(request.confirmed())) return;
        long owned =
                confirmations.values().stream()
                        .filter(confirmation -> confirmation.owner().equals(owner))
                        .count();
        if (owned >= MAX_CONFIRMATIONS_PER_ACCOUNT || confirmations.size() >= MAX_CONFIRMATIONS)
            throw new ToolProblem(
                    429,
                    "confirmation_busy",
                    "Too many pending confirmations; use an existing confirmation or wait for it to"
                            + " expire");
        String id = UUID.randomUUID().toString();
        confirmations.put(id, new Confirmation(owner, digest, now.plus(CONFIRMATION_TTL)));
        throw new ToolProblem(
                409,
                "confirmation_required",
                "Confirm this tool execution",
                Map.of("confirmationId", id, "currentRevision", tool.revision()));
    }

    private static ToolHttpExecutor.Result replay(ToolTestRunStore.Stored stored, String digest) {
        if (!stored.digest().equals(digest))
            throw new ToolProblem(
                    409, "run_conflict", "This run ID already belongs to a different request");
        return stored.result();
    }

    private Definition definition(ToolCatalog.ToolRow tool, Map<String, Object> draft) {
        String method = tool.httpMethod();
        String address = tool.httpUrl();
        Object schema = ToolInputSchemas.resolve(tool, catalog, mcp, json);
        Map<String, Object> secret = null;
        try {
            String credentialId = ToolAuth.credentialId(tool.configJson());
            if (credentialId != null) secret = credentials.read(tool.ownerUserId(), credentialId);
            if (draft != null) {
                if (draft.containsKey("httpMethod"))
                    method = String.valueOf(draft.get("httpMethod"));
                if (draft.containsKey("httpUrl"))
                    address = draft.get("httpUrl") instanceof String value ? value : null;
                if (draft.containsKey("inputSchema")) schema = draft.get("inputSchema");
                if (draft.containsKey("auth")) secret = ToolAuth.secret(draft.get("auth"));
            }
            Map<String, Object> parsed =
                    schema instanceof String value
                            ? json.readValue(value, new TypeReference<Map<String, Object>>() {})
                            : snapshot(schema);
            return new Definition(method, address, parsed, secret);
        } catch (ToolProblem error) {
            throw error;
        } catch (Exception error) {
            throw new ToolProblem(
                    422,
                    "invalid_schema",
                    "The test definition must contain an object input schema");
        }
    }

    private Mono<ToolHttpExecutor.Result> mcp(
            ToolCatalog.ToolRow tool, Map<String, Object> input, Map<String, Object> credential) {
        long start = System.nanoTime();
        return ToolHttpExecutor.schedule(
                        () -> {
                            checkExecution(tool);
                            if (mcp == null || tool.mcpServerId() == null)
                                throw new ToolProblem(
                                        422,
                                        "tool_unavailable",
                                        "The MCP tool has no active server");
                            var server =
                                    catalog.findServer(tool.mcpServerId())
                                            .orElseThrow(
                                                    () ->
                                                            new ToolProblem(
                                                                    404,
                                                                    "tool_not_found",
                                                                    "The MCP server is no longer"
                                                                            + " available"));
                            try (var client = mcp.open(catalog.endpointOf(server))) {
                                var result =
                                        client.callTool(tool.name(), input)
                                                .block(executor.budget());
                                var rendered =
                                        BoundedToolOutput.json(
                                                json,
                                                result,
                                                ToolCredentialRedactor.lookahead(credential));
                                var redacted =
                                        ToolCredentialRedactor.render(
                                                rendered.text(), credential, rendered.truncated());
                                return new ToolHttpExecutor.Result(
                                        null,
                                        redacted.text(),
                                        (System.nanoTime() - start) / 1_000_000,
                                        rendered.truncated() || redacted.truncated(),
                                        result != null && Boolean.TRUE.equals(result.isError())
                                                ? "mcp_error"
                                                : null);
                            }
                        })
                .timeout(executor.budget())
                .onErrorResume(
                        error -> {
                            if (error instanceof ToolProblem) return Mono.error(error);
                            return Mono.just(
                                    ToolHttpExecutor.failure(
                                            error instanceof java.util.concurrent.TimeoutException
                                                    ? "execution_timeout"
                                                    : "connection_failed",
                                            start));
                        });
    }

    public synchronized StopResult stop(String owner, String runId) {
        Run run = runs.get(owner + ":" + runId);
        if (run == null) {
            var stored =
                    records.find(owner, runId)
                            .orElseThrow(
                                    () ->
                                            new ToolProblem(
                                                    404,
                                                    "test_not_found",
                                                    "This test is not available to this account"));
            return new StopResult(runId, stored.status(), "tool.errors.stop_external_unknown");
        }
        if (!run.result.isDone()) {
            run.cancel();
            finish(run, ToolHttpExecutor.failure("execution_cancelled", run.start), null);
        }
        if (run.result.isCompletedExceptionally())
            return new StopResult(runId, "failed", "tool.errors.stop_external_unknown");
        var result = run.result.getNow(null);
        return new StopResult(
                runId,
                result != null && "execution_cancelled".equals(result.errorCode())
                        ? "cancelled"
                        : "completed",
                "tool.errors.stop_external_unknown");
    }

    private synchronized void finish(Run run, ToolHttpExecutor.Result result, Throwable error) {
        if (run.result.isDone()) return;
        try {
            records.complete(run.owner, run.runId, run.digest, result, error);
        } catch (ToolProblem unavailable) {
            result = ToolHttpExecutor.failure("execution_unknown", run.start);
            error = null;
        }
        runs.remove(run.owner + ":" + run.runId, run);
        active.remove(run.owner, run);
        if (error == null) run.result.complete(result);
        else run.result.completeExceptionally(error);
    }

    private String digest(
            String owner,
            ToolCatalog.ToolRow tool,
            Request request,
            Map<String, Object> input,
            Definition definition) {
        try {
            var bound = new LinkedHashMap<String, Object>();
            bound.put("owner", owner);
            bound.put("toolId", tool.toolId());
            bound.put("revision", tool.revision());
            bound.put("input", input);
            bound.put("draft", request.draftDefinition());
            bound.put("method", definition.method());
            bound.put("address", definition.address());
            bound.put("schema", definition.schema());
            byte[] bytes =
                    json.copy()
                            .configure(
                                    com.fasterxml.jackson.databind.SerializationFeature
                                            .ORDER_MAP_ENTRIES_BY_KEYS,
                                    true)
                            .writeValueAsBytes(bound);
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new ToolProblem(422, "invalid_parameters", "Could not normalize test parameters");
        }
    }

    private Map<String, Object> snapshot(Object value) {
        if (!(value instanceof Map<?, ?>))
            throw new ToolProblem(422, "invalid_schema", "An object is required");
        return json.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Run run : java.util.List.copyOf(active.values())) {
            run.cancel();
            finish(run, ToolHttpExecutor.failure("execution_cancelled", run.start), null);
        }
        confirmations.clear();
        runs.clear();
    }

    private static final class Run {
        final String owner;
        final String runId;
        final String digest;
        final long start = System.nanoTime();
        final CompletableFuture<ToolHttpExecutor.Result> result = new CompletableFuture<>();
        private Disposable subscription;
        private boolean cancelled;

        Run(String owner, String runId, String digest) {
            this.owner = owner;
            this.runId = runId;
            this.digest = digest;
        }

        synchronized void attach(Disposable disposable) {
            subscription = disposable;
            if (cancelled) disposable.dispose();
        }

        synchronized void cancel() {
            cancelled = true;
            if (subscription != null) subscription.dispose();
        }
    }
}
