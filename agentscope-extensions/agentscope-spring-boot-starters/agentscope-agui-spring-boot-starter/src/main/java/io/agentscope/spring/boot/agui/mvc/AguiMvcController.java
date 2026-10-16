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
package io.agentscope.spring.boot.agui.mvc;

import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapterFactory;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import io.agentscope.spring.boot.agui.common.DefaultAgentResolver;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;

/**
 * MVC controller for AG-UI protocol requests.
 *
 * <p>This controller processes AG-UI run requests and returns Server-Sent Events (SSE)
 * streams with AG-UI protocol events using Spring MVC's {@link SseEmitter}.
 *
 * <p><b>Agent ID Resolution Priority:</b>
 * <ol>
 *   <li>URL path variable: {@code /agui/run/{agentId}}</li>
 *   <li>HTTP header: configurable via {@code agentIdHeader} (default: X-Agent-Id)</li>
 *   <li>forwardedProps.agentId in request body</li>
 *   <li>config.defaultAgentId</li>
 *   <li>"default"</li>
 * </ol>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * AguiMvcController controller = AguiMvcController.builder()
 *     .agentRegistry(registry)
 *     .config(AguiAdapterConfig.defaultConfig())
 *     .agentIdHeader("X-Agent-Id")
 *     .build();
 * }</pre>
 */
public class AguiMvcController {

    private static final Logger logger = LoggerFactory.getLogger(AguiMvcController.class);

    private static final String DEFAULT_AGENT_ID_HEADER = "X-Agent-Id";

    private final AguiRequestProcessor processor;
    private final AguiEventEncoder encoder;
    private final String agentIdHeader;
    private final long sseTimeout;
    private final boolean interruptOnDisconnect;
    private final long heartbeatInterval;
    private final ExecutorService executorService;
    private final ScheduledExecutorService heartbeatScheduler;
    private final java.util.concurrent.ConcurrentHashMap<String, ActiveRun> activeRuns =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class ActiveRun {
        final String runId;
        final SseEmitter emitter;
        boolean started;
        boolean mutation;
        final AtomicReference<AguiRequestProcessor.ProcessResult> resultRef =
                new AtomicReference<>();
        final AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean streamTerminated =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean cancelling =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

        ActiveRun(String runId, SseEmitter emitter) {
            this.runId = runId;
            this.emitter = emitter;
        }
    }

    private AguiMvcController(Builder builder) {
        this.processor =
                AguiRequestProcessor.builder()
                        .agentResolver(
                                DefaultAgentResolver.builder()
                                        .registry(builder.registry)
                                        .sessionManager(builder.sessionManager)
                                        .serverSideMemory(builder.serverSideMemory)
                                        .build())
                        .config(
                                builder.config != null
                                        ? builder.config
                                        : AguiAdapterConfig.defaultConfig())
                        .adapterFactory(builder.adapterFactory)
                        .runtimeContextResolver(builder.runtimeContextResolver)
                        .build();
        this.encoder = new AguiEventEncoder();
        this.agentIdHeader =
                builder.agentIdHeader != null ? builder.agentIdHeader : DEFAULT_AGENT_ID_HEADER;
        this.sseTimeout = builder.sseTimeout > 0 ? builder.sseTimeout : 600000L;
        this.interruptOnDisconnect = builder.interruptOnDisconnect;
        this.heartbeatInterval = builder.heartbeatInterval;
        this.executorService = Executors.newCachedThreadPool();
        this.heartbeatScheduler =
                Executors.newScheduledThreadPool(
                        1,
                        runnable -> {
                            Thread thread = new Thread(runnable, "agui-sse-heartbeat");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    /**
     * Handle an AG-UI run request.
     *
     * @param input The run agent input
     * @param headerAgentId The agent ID from HTTP header (may be null)
     * @return An SseEmitter for streaming AG-UI events
     */
    public SseEmitter handle(RunAgentInput input, String headerAgentId) {
        return handle(input, headerAgentId, null);
    }

    /**
     * Handle an AG-UI run request.
     *
     * @param input The run agent input
     * @param headerAgentId The agent ID from HTTP header (may be null)
     * @param request The native servlet request (may be null)
     * @return An SseEmitter for streaming AG-UI events
     */
    public SseEmitter handle(
            RunAgentInput input, String headerAgentId, HttpServletRequest request) {
        return handleInternal(input, headerAgentId, null, request);
    }

    /**
     * Handle an AG-UI run request with agent ID in the URL path.
     *
     * @param input The run agent input
     * @param headerAgentId The agent ID from HTTP header (may be null)
     * @param pathAgentId The agent ID from URL path variable
     * @return An SseEmitter for streaming AG-UI events
     */
    public SseEmitter handleWithAgentId(
            RunAgentInput input, String headerAgentId, String pathAgentId) {
        return handleWithAgentId(input, headerAgentId, pathAgentId, null);
    }

    /**
     * Handle an AG-UI run request with agent ID in the URL path.
     *
     * @param input The run agent input
     * @param headerAgentId The agent ID from HTTP header (may be null)
     * @param pathAgentId The agent ID from URL path variable
     * @param request The native servlet request (may be null)
     * @return An SseEmitter for streaming AG-UI events
     */
    public SseEmitter handleWithAgentId(
            RunAgentInput input,
            String headerAgentId,
            String pathAgentId,
            HttpServletRequest request) {
        return handleInternal(input, headerAgentId, pathAgentId, request);
    }

    private SseEmitter handleInternal(
            RunAgentInput input,
            String headerAgentId,
            String pathAgentId,
            HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(sseTimeout);
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        try {
            processor.validateRequest(
                    runtimeContextRequest(input, headerAgentId, pathAgentId, request));
        } catch (RuntimeException error) {
            sendErrorAndComplete(emitter, threadId, runId, error.getMessage());
            return emitter;
        }

        ActiveRun activeRun = new ActiveRun(runId, emitter);
        ActiveRun previous = activeRuns.putIfAbsent(threadId, activeRun);
        if (previous != null) {
            sendErrorAndComplete(
                    emitter,
                    threadId,
                    runId,
                    "Thread already has an active run; wait for it to finish");
            return emitter;
        }
        try {
            synchronized (activeRun) {
                if (activeRun.streamTerminated.get() || activeRuns.get(threadId) != activeRun)
                    return emitter;
                processor.admitRequest(
                        runtimeContextRequest(input, headerAgentId, pathAgentId, request));
            }
        } catch (RuntimeException error) {
            activeRuns.remove(threadId, activeRun);
            sendErrorAndComplete(emitter, threadId, runId, error.getMessage());
            return emitter;
        }

        executorService.submit(
                () -> {
                    try {
                        if (activeRun.streamTerminated.get()) return;
                        // Process request - returns both agent and event stream
                        AguiRequestProcessor.ProcessResult result =
                                processor.processAdmitted(
                                        runtimeContextRequest(
                                                input, headerAgentId, pathAgentId, request));
                        activeRun.resultRef.set(result);
                        if (activeRun.streamTerminated.get()) {
                            result.release();
                            return;
                        }
                        Runnable cancelHeartbeat =
                                () -> {
                                    ScheduledFuture<?> task = activeRun.heartbeat.getAndSet(null);
                                    if (task != null) {
                                        task.cancel(false);
                                    }
                                };
                        BaseSubscriber<AguiEvent> subscription =
                                new BaseSubscriber<>() {
                                    @Override
                                    protected void hookOnNext(AguiEvent event) {
                                        if (activeRun.streamTerminated.get()) {
                                            return;
                                        }
                                        if (!sendEvent(emitter, event) && interruptOnDisconnect) {
                                            interruptTrackedRun(threadId, activeRun);
                                        }
                                    }

                                    @Override
                                    protected void hookOnError(Throwable error) {
                                        if (!activeRun.streamTerminated.compareAndSet(
                                                false, true)) {
                                            return;
                                        }
                                        cancelHeartbeat.run();
                                        result.release();
                                        removeActiveRun(threadId, activeRun);
                                        logger.error(
                                                "Error during AG-UI run: {}", error.getMessage());
                                        sendErrorAndComplete(
                                                emitter, threadId, runId, error.getMessage());
                                    }

                                    @Override
                                    protected void hookOnComplete() {
                                        if (!activeRun.streamTerminated.compareAndSet(
                                                false, true)) {
                                            return;
                                        }
                                        cancelHeartbeat.run();
                                        result.release();
                                        removeActiveRun(threadId, activeRun);
                                        try {
                                            emitter.complete();
                                        } catch (Exception e) {
                                            logger.debug(
                                                    "Error completing emitter: {}", e.getMessage());
                                        }
                                    }
                                };
                        activeRun.subscriptionRef.set(subscription);
                        if (activeRun.streamTerminated.get()) {
                            result.release();
                            subscription.dispose();
                            return;
                        }

                        // Set up callbacks for client disconnect handling
                        // using the same agent instance from the result
                        emitter.onCompletion(
                                () -> {
                                    logger.debug("SSE connection completed for run {}", runId);
                                    cancelHeartbeat.run();
                                    if (interruptOnDisconnect) {
                                        logger.info(
                                                "SSE connection closed before run {} finished,"
                                                        + " interrupting agent",
                                                runId);
                                        interruptTrackedRun(threadId, activeRun);
                                    }
                                });
                        emitter.onTimeout(
                                () -> {
                                    cancelHeartbeat.run();
                                    if (interruptOnDisconnect) {
                                        logger.info(
                                                "SSE connection timed out for run {}, interrupting"
                                                        + " agent",
                                                runId);
                                        interruptTrackedRun(threadId, activeRun);
                                    } else {
                                        logger.info(
                                                "SSE connection timed out for run {}, agent"
                                                        + " continues running",
                                                runId);
                                    }
                                });
                        emitter.onError(
                                (ex) -> {
                                    cancelHeartbeat.run();
                                    if (interruptOnDisconnect) {
                                        logger.info(
                                                "SSE connection error for run {}: {}, interrupting"
                                                        + " agent",
                                                runId,
                                                ex.getMessage());
                                        interruptTrackedRun(threadId, activeRun);
                                    } else {
                                        logger.info(
                                                "SSE connection error for run {}: {}, agent"
                                                        + " continues running",
                                                runId,
                                                ex.getMessage());
                                    }
                                });

                        // Subscribe to event stream from the same result
                        // The subscriber is created before disconnect callbacks are registered so
                        // cancellation is safe even if the client disconnects before subscribe().
                        synchronized (activeRun) {
                            if (!activeRun.streamTerminated.get()) {
                                activeRun.started = true;
                                result.events().subscribe(subscription);
                            } else {
                                result.release();
                                subscription.dispose();
                            }
                        }

                        // Layer 2 fix: periodic SSE comment frames act as keep-alive probes.
                        // SseEmitter only notices a dead client when a write is attempted, so
                        // without writes a hung run would leak its active-run marker forever.
                        if (heartbeatInterval > 0) {
                            ScheduledFuture<?> task =
                                    heartbeatScheduler.scheduleAtFixedRate(
                                            () -> {
                                                if (activeRun.streamTerminated.get()) {
                                                    return;
                                                }
                                                if (!sendHeartbeat(emitter)) {
                                                    logger.info(
                                                            "SSE heartbeat detected disconnected"
                                                                    + " client for run {},"
                                                                    + " interrupting agent",
                                                            runId);
                                                    interruptTrackedRun(threadId, activeRun);
                                                }
                                            },
                                            heartbeatInterval,
                                            heartbeatInterval,
                                            TimeUnit.MILLISECONDS);
                            if (!activeRun.heartbeat.compareAndSet(null, task)) {
                                task.cancel(false);
                            }
                        }

                    } catch (AguiException.AgentNotFoundException e) {
                        removeActiveRun(threadId, activeRun);
                        logger.error("Agent not found: {}", e.getMessage());
                        sendErrorAndComplete(emitter, threadId, runId, e.getMessage());
                    } catch (Exception e) {
                        removeActiveRun(threadId, activeRun);
                        logger.error("Error processing AG-UI request: {}", e.getMessage());
                        sendErrorAndComplete(emitter, threadId, runId, e.getMessage());
                    }
                });

        return emitter;
    }

    private void removeActiveRun(String threadId, ActiveRun run) {
        if (!run.cancelling.get()) activeRuns.remove(threadId, run);
    }

    private void cancelActiveRun(String threadId, ActiveRun run) {
        synchronized (run) {
            if (run.streamTerminated.compareAndSet(false, true)) {
                ScheduledFuture<?> task = run.heartbeat.getAndSet(null);
                if (task != null) {
                    task.cancel(false);
                }
                try {
                    run.emitter.complete();
                } catch (Exception ignored) {
                }
                if (run.started) {
                    interruptAndCancel(run.resultRef.get(), threadId, run.subscriptionRef.get());
                } else {
                    if (run.resultRef.get() != null) run.resultRef.get().release();
                    if (run.subscriptionRef.get() != null) run.subscriptionRef.get().dispose();
                }
            }
            processor.cancelRun(threadId);
        }
    }

    /**
     * Checks whether an AG-UI run is currently actively tracked for the given thread.
     *
     * @param threadId The AG-UI thread ID
     * @return true if an active run exists
     */
    public boolean hasActiveRun(String threadId) {
        return threadId != null && activeRuns.containsKey(threadId);
    }

    /**
     * Interrupts and cancels any active AG-UI run for the given thread, releasing active run locks.
     *
     * @param threadId The AG-UI thread ID
     * @return true if an active run was found and interrupted
     */
    public boolean interrupt(String threadId) {
        return interrupt(threadId, null);
    }

    public String activeRunId(String threadId) {
        ActiveRun run = threadId == null ? null : activeRuns.get(threadId);
        return run == null ? null : run.runId;
    }

    public java.util.Map<String, Object> runtimeSnapshot(String threadId) {
        java.util.Map<String, Object> snapshot =
                new java.util.LinkedHashMap<>(processor.runtimeSnapshot(threadId));
        ActiveRun active = activeRuns.get(threadId);
        if (active != null) {
            snapshot.put("runId", active.runId);
            snapshot.put("phase", "running");
        }
        return java.util.Map.copyOf(snapshot);
    }

    /** Reserve an idle thread for an application mutation without interrupting any run. */
    public boolean withIdleThread(String threadId, Runnable operation) {
        ActiveRun marker =
                new ActiveRun(
                        "mutation-" + java.util.UUID.randomUUID(), new SseEmitter(sseTimeout));
        marker.mutation = true;
        if (activeRuns.putIfAbsent(threadId, marker) != null) return false;
        try {
            if (!"idle".equals(processor.runtimeSnapshot(threadId).get("phase"))) return false;
            operation.run();
            return true;
        } finally {
            activeRuns.remove(threadId, marker);
        }
    }

    public boolean interrupt(String threadId, String expectedRunId) {
        if (threadId == null) {
            return false;
        }
        ActiveRun active = activeRuns.get(threadId);
        if (active == null) {
            if (expectedRunId == null) {
                ActiveRun marker = new ActiveRun("cancel-pending", new SseEmitter(sseTimeout));
                marker.mutation = true;
                if (activeRuns.putIfAbsent(threadId, marker) == null) {
                    try {
                        processor.cancelRun(threadId);
                    } finally {
                        activeRuns.remove(threadId, marker);
                    }
                }
            }
            return false;
        }
        if (active.mutation || expectedRunId != null && !expectedRunId.equals(active.runId))
            return false;
        return interruptTrackedRun(threadId, active);
    }

    private static void interruptAndCancel(
            AguiRequestProcessor.ProcessResult result, String threadId, Disposable subscription) {
        try {
            if (result != null) {
                result.interrupt(threadId);
            }
        } finally {
            if (subscription != null) {
                subscription.dispose();
            }
        }
    }

    private boolean interruptTrackedRun(String threadId, ActiveRun run) {
        synchronized (run) {
            if (activeRuns.get(threadId) != run || run.streamTerminated.get()) return false;
            run.cancelling.set(true);
            try {
                cancelActiveRun(threadId, run);
                return true;
            } finally {
                activeRuns.remove(threadId, run);
                run.cancelling.set(false);
            }
        }
    }

    private AguiRuntimeContextRequest<HttpServletRequest> runtimeContextRequest(
            RunAgentInput input,
            String headerAgentId,
            String pathAgentId,
            HttpServletRequest request) {
        return AguiRuntimeContextRequest.<HttpServletRequest>builder()
                .input(input)
                .headerAgentId(headerAgentId)
                .pathAgentId(pathAgentId)
                .transport(AguiRuntimeContextRequest.Transport.MVC)
                .method(request != null ? request.getMethod() : null)
                .path(request != null ? request.getRequestURI() : null)
                .headers(headers(request))
                .queryParams(queryParams(request))
                .nativeRequest(request)
                .build();
    }

    private static Map<String, List<String>> headers(HttpServletRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, Collections.list(request.getHeaders(name)));
        }
        return headers;
    }

    private static Map<String, List<String>> queryParams(HttpServletRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, List<String>> queryParams = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> queryParams.put(name, List.of(values)));
        return queryParams;
    }

    private boolean sendHeartbeat(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("keepalive"));
            return true;
        } catch (IOException | IllegalStateException e) {
            logger.debug("Failed to send SSE heartbeat: {}", e.getMessage());
            return false;
        }
    }

    private boolean sendEvent(SseEmitter emitter, AguiEvent event) {
        try {
            String jsonData = encoder.encodeToJson(event);
            emitter.send(SseEmitter.event().data(jsonData, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            logger.debug("Failed to send SSE event: {}", e.getMessage());
            return false;
        }
    }

    private void sendErrorAndComplete(
            SseEmitter emitter, String threadId, String runId, String errorMessage) {
        try {
            String errorJson =
                    encoder.encodeToJson(
                            new AguiEvent.Raw(threadId, runId, Map.of("error", errorMessage)));
            String finishJson = encoder.encodeToJson(new AguiEvent.RunFinished(threadId, runId));
            emitter.send(SseEmitter.event().data(errorJson, MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().data(finishJson, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            logger.debug("Failed to send error event: {}", e.getMessage());
            try {
                emitter.completeWithError(e);
            } catch (Exception ex) {
                logger.debug("Failed to complete emitter with error: {}", ex.getMessage());
            }
        }
    }

    /**
     * Get the agent ID header name.
     *
     * @return The header name
     */
    public String getAgentIdHeader() {
        return agentIdHeader;
    }

    /**
     * Creates a new builder for AguiMvcController.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for AguiMvcController. */
    public static class Builder {

        private AguiAgentRegistry registry;
        private ThreadSessionManager sessionManager;
        private AguiAdapterConfig config;
        private boolean serverSideMemory = false;
        private String agentIdHeader;
        private long sseTimeout = 600000L;
        private boolean interruptOnDisconnect = true;
        private long heartbeatInterval = 15000L;
        private AguiRuntimeContextResolver runtimeContextResolver;
        private AguiAgentAdapterFactory adapterFactory;

        /**
         * Set the agent registry.
         *
         * @param registry The agent registry
         * @return This builder
         */
        public Builder agentRegistry(AguiAgentRegistry registry) {
            this.registry = registry;
            return this;
        }

        /**
         * Set the thread session manager for server-side memory support.
         *
         * @param sessionManager The session manager
         * @return This builder
         */
        public Builder sessionManager(ThreadSessionManager sessionManager) {
            this.sessionManager = sessionManager;
            return this;
        }

        /**
         * Enable or disable server-side memory management.
         *
         * @param enabled Whether to enable server-side memory
         * @return This builder
         */
        public Builder serverSideMemory(boolean enabled) {
            this.serverSideMemory = enabled;
            return this;
        }

        /**
         * Set the adapter configuration.
         *
         * @param config The adapter configuration
         * @return This builder
         */
        public Builder config(AguiAdapterConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Set the HTTP header name to read agent ID from.
         *
         * @param agentIdHeader The header name (default: X-Agent-Id)
         * @return This builder
         */
        public Builder agentIdHeader(String agentIdHeader) {
            this.agentIdHeader = agentIdHeader;
            return this;
        }

        /**
         * Set the SSE timeout in milliseconds.
         *
         * @param sseTimeout The timeout value
         * @return This builder
         */
        public Builder sseTimeout(long sseTimeout) {
            this.sseTimeout = sseTimeout;
            return this;
        }

        /**
         * Set whether to interrupt the agent when the client disconnects.
         *
         * @param interruptOnDisconnect whether to interrupt the agent
         * @return This builder
         */
        public Builder interruptOnDisconnect(boolean interruptOnDisconnect) {
            this.interruptOnDisconnect = interruptOnDisconnect;
            return this;
        }

        /**
         * Set the SSE heartbeat interval in milliseconds.
         *
         * <p>A comment frame is sent periodically so that a disconnected client is detected
         * promptly even when no events are produced. Set to 0 or a negative value to disable.
         *
         * @param heartbeatInterval The interval in milliseconds (default: 15000)
         * @return This builder
         */
        public Builder heartbeatInterval(long heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
            return this;
        }

        /**
         * Set the runtime context resolver.
         *
         * @param runtimeContextResolver The resolver used for each request
         * @return This builder
         */
        public Builder runtimeContextResolver(AguiRuntimeContextResolver runtimeContextResolver) {
            this.runtimeContextResolver = runtimeContextResolver;
            return this;
        }

        /**
         * Set the adapter factory.
         *
         * @param adapterFactory The factory used to create per-request adapters
         * @return This builder
         */
        public Builder adapterFactory(AguiAgentAdapterFactory adapterFactory) {
            this.adapterFactory = adapterFactory;
            return this;
        }

        /**
         * Build the controller.
         *
         * @return The built controller
         * @throws IllegalStateException if registry is not set
         */
        public AguiMvcController build() {
            if (registry == null) {
                throw new IllegalStateException("Agent registry must be set");
            }
            return new AguiMvcController(this);
        }
    }
}
