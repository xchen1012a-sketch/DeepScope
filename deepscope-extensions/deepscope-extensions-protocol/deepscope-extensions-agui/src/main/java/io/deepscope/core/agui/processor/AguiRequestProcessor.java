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
package io.deepscope.core.agui.processor;

import io.deepscope.core.ReActAgent;
import io.deepscope.core.agent.Agent;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.agui.AguiUtil;
import io.deepscope.core.agui.adapter.AguiAdapterConfig;
import io.deepscope.core.agui.adapter.AguiAgentAdapter;
import io.deepscope.core.agui.adapter.AguiAgentAdapterFactory;
import io.deepscope.core.agui.event.AguiEvent;
import io.deepscope.core.agui.model.AguiMessage;
import io.deepscope.core.agui.model.RunAgentInput;
import io.deepscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.deepscope.core.agui.runtime.AguiRuntimeContextResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Core processor for AG-UI requests.
 *
 * <p>This class encapsulates the common logic for processing AG-UI requests,
 * extracting it from MVC and WebFlux handlers to avoid code duplication.
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li>Agent ID resolution from multiple sources</li>
 *   <li>Message extraction for server-side memory scenarios</li>
 *   <li>Agent resolution via {@link AgentResolver}</li>
 *   <li>Event stream generation via {@link AguiAgentAdapter}</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * AguiRequestProcessor processor = AguiRequestProcessor.builder()
 *     .agentResolver(resolver)
 *     .config(AguiAdapterConfig.defaultConfig())
 *     .build();
 *
 * AguiRuntimeContextRequest<?> request = AguiRuntimeContextRequest.builder()
 *         .input(input)
 *         .build();
 * ProcessResult result = processor.process(request);
 * Flux<AguiEvent> events = result.events();
 * }</pre>
 */
public class AguiRequestProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AguiRequestProcessor.class);

    private final AgentResolver agentResolver;
    private final AguiAdapterConfig config;
    private final AguiAgentAdapterFactory adapterFactory;
    private final AguiResumeCoordinator resumeCoordinator;
    private final AguiRuntimeContextResolver runtimeContextResolver;

    private AguiRequestProcessor(Builder builder) {
        this.agentResolver =
                Objects.requireNonNull(builder.agentResolver, "agentResolver cannot be null");
        this.config = builder.config != null ? builder.config : AguiAdapterConfig.defaultConfig();
        this.adapterFactory =
                builder.adapterFactory != null
                        ? builder.adapterFactory
                        : AguiAgentAdapterFactory.defaultFactory();
        this.resumeCoordinator = new AguiResumeCoordinator();
        this.runtimeContextResolver = builder.runtimeContextResolver;
    }

    /**
     * Result of processing an AG-UI request.
     *
     * <p>Contains the resolved agent (for interrupt handling) and the event stream.
     *
     * @param agent The resolved agent instance
     * @param events The event stream
     * @param runtimeContext The resolved caller-provided runtime context, may be null
     */
    public record ProcessResult(
            Agent agent,
            Flux<AguiEvent> events,
            RuntimeContext runtimeContext,
            Runnable onInterrupt) {

        public ProcessResult(Agent agent, Flux<AguiEvent> events, RuntimeContext runtimeContext) {
            this(agent, events, runtimeContext, null);
        }

        /** Release this request's resources without interrupting a shared agent. */
        public void release() {
            if (onInterrupt != null) {
                try {
                    onInterrupt.run();
                } catch (RuntimeException ignored) {
                    // Best-effort release; a failed cleanup must not block cancellation.
                }
            }
        }

        /**
         * Interrupt this request's active session, preserving the caller's user identity.
         *
         * @param threadId the AG-UI thread id, also used as the agent session id
         */
        public void interrupt(String threadId) {
            release();
            ReActAgent reActAgent = AguiUtil.asReActAgent(agent);
            if (reActAgent != null) {
                RuntimeContext interruptContext =
                        RuntimeContext.builder(runtimeContext).sessionId(threadId).build();
                reActAgent.interrupt(interruptContext);
            } else {
                agent.interrupt();
            }
        }
    }

    /**
     * Process an AG-UI request and return the result containing agent and event stream.
     *
     * <p>The {@link AguiRuntimeContextResolver} (if configured on this processor) is invoked with
     * the given request to obtain a caller-provided {@link RuntimeContext}. That context is copied
     * and enriched by {@link AguiAgentAdapter}, so callers can provide custom attributes without
     * replacing the standard AG-UI metadata.
     *
     * @param request The AG-UI request context carrying input, agent IDs, transport details and the
     *     native request
     * @return A ProcessResult containing the agent and event stream
     */
    public ProcessResult process(AguiRuntimeContextRequest<?> request) {
        validateRequest(request);
        admitRequest(request);
        return processAdmitted(request);
    }

    /** Process a request whose transport already validated and admitted its resource identity. */
    public ProcessResult processAdmitted(AguiRuntimeContextRequest<?> request) {
        AtomicBoolean released = new AtomicBoolean();
        Runnable cleanup =
                () -> {
                    if (runtimeContextResolver != null && released.compareAndSet(false, true)) {
                        try {
                            runtimeContextResolver.release(request);
                        } catch (RuntimeException error) {
                            logger.warn("Failed to release AG-UI run resources", error);
                        }
                    }
                };
        try {
            return processOwned(request, cleanup);
        } catch (RuntimeException | Error error) {
            cleanup.run();
            throw error;
        }
    }

    /** Validate the caller before a transport reserves a thread, without resolving runtime resources. */
    public void validateRequest(AguiRuntimeContextRequest<?> request) {
        if (runtimeContextResolver != null) runtimeContextResolver.validate(request);
    }

    public void admitRequest(AguiRuntimeContextRequest<?> request) {
        if (runtimeContextResolver != null) runtimeContextResolver.admitted(request);
    }

    private ProcessResult processOwned(AguiRuntimeContextRequest<?> request, Runnable cleanup) {
        RunAgentInput input = request.getInput();
        String headerAgentId = request.getHeaderAgentId();
        String pathAgentId = request.getPathAgentId();
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        RuntimeContext resolved =
                runtimeContextResolver != null ? runtimeContextResolver.resolve(request) : null;
        RuntimeContext runtimeContext =
                RuntimeContext.builder(resolved).sessionId(threadId).build();

        // Resolve agent ID
        String agentId = resolveAgentId(input, headerAgentId, pathAgentId);

        // Resolve agent
        Agent agent = agentResolver.resolveAgent(agentId, threadId, runtimeContext.getUserId());

        Flux<AguiEvent> events =
                Flux.defer(
                        () -> {
                            AguiResumeCoordinator.ResumeContractResult beginResult =
                                    resumeCoordinator.beginRun(input);
                            if (beginResult.isError()) {
                                return Flux.fromIterable(
                                        resumeCoordinator.contractErrorEvents(
                                                input,
                                                beginResult.message(),
                                                config.isEmitRunFinishedAfterError()));
                            }

                            try {
                                // Determine effective input based on server-side memory
                                RunAgentInput effectiveInput = input;
                                if (agentResolver.hasMemory(runtimeContext)) {
                                    logger.debug(
                                            "Using server-side memory for thread {} user {},"
                                                    + " extracting follow-up messages",
                                            threadId,
                                            runtimeContext.getUserId());
                                    effectiveInput = extractLatestUserMessage(input);
                                }

                                RuntimeContext effectiveRuntimeContext =
                                        resumeCoordinator.addResumeInterrupts(
                                                input, runtimeContext);

                                // Create adapter and run
                                AguiAgentAdapter adapter = adapterFactory.create(agent, config);
                                AtomicBoolean runErrorSeen = new AtomicBoolean(false);
                                Flux<AguiEvent> stream =
                                        Objects.requireNonNull(
                                                        adapter.run(
                                                                effectiveInput,
                                                                effectiveRuntimeContext),
                                                        "adapter event stream is null")
                                                .doOnNext(
                                                        event -> {
                                                            if (event
                                                                    instanceof AguiEvent.RunError) {
                                                                runErrorSeen.set(true);
                                                            }
                                                            resumeCoordinator
                                                                    .trackPendingInterrupts(
                                                                            threadId,
                                                                            runId,
                                                                            event,
                                                                            runErrorSeen.get(),
                                                                            agentId);
                                                        });
                                // Layer 3 fix: apply the configured run timeout so a hung run
                                // cannot hold the active-run marker indefinitely. On timeout,
                                // switch to a RunError event stream instead of a raw error so
                                // clients receive a standard AG-UI error event.
                                java.time.Duration runTimeout = config.getRunTimeout();
                                if (runTimeout != null
                                        && !runTimeout.isZero()
                                        && !runTimeout.isNegative()) {
                                    stream =
                                            stream.timeout(
                                                    runTimeout,
                                                    Flux.defer(
                                                            () ->
                                                                    Flux.fromIterable(
                                                                            runTimeoutEvents(
                                                                                    input,
                                                                                    runTimeout))));
                                }
                                return stream
                                        // Finish the run before the terminal signal reaches
                                        // the caller. doFinally runs after onComplete/onError
                                        // is propagated, so a caller that collected this run's
                                        // events and immediately started the next run on the
                                        // same thread (the common resume flow) could observe
                                        // the stale active-run marker and be rejected with
                                        // AGUI_INTERRUPT_CONTRACT_ERROR. doOnComplete/doOnError
                                        // run before propagation, giving a happens-before
                                        // guarantee; finishRun is idempotent, and doFinally
                                        // still covers the cancellation path.
                                        .doOnComplete(
                                                () -> resumeCoordinator.finishRun(threadId, runId))
                                        .doOnError(
                                                error ->
                                                        resumeCoordinator.finishRun(
                                                                threadId, runId))
                                        .doFinally(
                                                signalType ->
                                                        resumeCoordinator.finishRun(
                                                                threadId, runId));
                            } catch (Throwable error) {
                                resumeCoordinator.finishRun(threadId, runId);
                                return processorErrorEvents(input, error);
                            }
                        });
        return new ProcessResult(
                agent,
                events.doOnComplete(cleanup)
                        .doOnError(error -> cleanup.run())
                        .doFinally(signal -> cleanup.run()),
                runtimeContext,
                () -> {
                    resumeCoordinator.finishRun(threadId, runId);
                    cleanup.run();
                });
    }

    private Flux<AguiEvent> processorErrorEvents(RunAgentInput input, Throwable error) {
        String errorMessage =
                error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        List<AguiEvent> events = new ArrayList<>();
        events.add(new AguiEvent.RunStarted(input.getThreadId(), input.getRunId(), null, input));
        events.add(
                new AguiEvent.RunError(
                        input.getThreadId(),
                        input.getRunId(),
                        errorMessage,
                        mapErrorCode(error),
                        System.currentTimeMillis(),
                        null));
        if (config.isEmitRunFinishedAfterError()) {
            events.add(new AguiEvent.RunFinished(input.getThreadId(), input.getRunId()));
        }
        return Flux.fromIterable(events);
    }

    /**
     * Build the fallback events emitted when a run exceeds the configured run timeout.
     *
     * <p>The adapter stream emits {@code RUN_STARTED} as its first event, so the fallback only
     * carries a {@code RUN_ERROR} (and optionally {@code RUN_FINISHED}) to avoid a duplicate
     * {@code RUN_STARTED}.
     */
    private List<AguiEvent> runTimeoutEvents(RunAgentInput input, java.time.Duration runTimeout) {
        List<AguiEvent> events = new ArrayList<>();
        events.add(
                new AguiEvent.RunError(
                        input.getThreadId(),
                        input.getRunId(),
                        "Run timed out after " + runTimeout.toSeconds() + "s without new events",
                        "TIMEOUT_ERROR",
                        System.currentTimeMillis(),
                        null));
        if (config.isEmitRunFinishedAfterError()) {
            events.add(new AguiEvent.RunFinished(input.getThreadId(), input.getRunId()));
        }
        return events;
    }

    private static String mapErrorCode(Throwable error) {
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "TIMEOUT_ERROR";
        }
        if (error instanceof java.lang.InterruptedException) {
            return "INTERRUPTED_ERROR";
        }
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            return "INVALID_INPUT_ERROR";
        }
        return "INTERNAL_ERROR";
    }

    /**
     * Unconditionally cancel and clear the active run marker for a thread.
     *
     * @param threadId The AG-UI thread ID
     */
    public void cancelRun(String threadId) {
        resumeCoordinator.cancelRun(threadId);
    }

    public Map<String, Object> runtimeSnapshot(String threadId) {
        return resumeCoordinator.runtimeSnapshot(threadId);
    }

    /**
     * Resolve the agent ID from multiple sources.
     *
     * <p>The agent ID is resolved in the following priority order:
     * <ol>
     *   <li>URL path variable (if provided)</li>
     *   <li>HTTP header (if provided)</li>
     *   <li>forwardedProps.agentId in request body</li>
     *   <li>config.defaultAgentId</li>
     *   <li>"default"</li>
     * </ol>
     *
     * @param input The request input
     * @param headerAgentId The agent ID from HTTP header (may be null)
     * @param pathAgentId The agent ID from URL path variable (may be null)
     * @return The resolved agent ID
     */
    public String resolveAgentId(RunAgentInput input, String headerAgentId, String pathAgentId) {
        // 1. URL path variable has highest priority
        if (pathAgentId != null && !pathAgentId.isEmpty()) {
            logger.debug("Using agent ID from path variable: {}", pathAgentId);
            return pathAgentId;
        }

        // 2. Check HTTP header
        if (headerAgentId != null && !headerAgentId.isEmpty()) {
            logger.debug("Using agent ID from header: {}", headerAgentId);
            return headerAgentId;
        }

        // 3. Check forwardedProps for agentId
        Object agentIdProp = input.getForwardedProp("agentId");
        if (agentIdProp != null) {
            String propsAgentId = agentIdProp.toString();
            logger.debug("Using agent ID from forwardedProps: {}", propsAgentId);
            return propsAgentId;
        }

        // 4. Use config default
        if (config.getDefaultAgentId() != null) {
            logger.debug("Using default agent ID from config: {}", config.getDefaultAgentId());
            return config.getDefaultAgentId();
        }

        // 5. Fall back to "default"
        logger.debug("Using fallback agent ID: default");
        return "default";
    }

    /**
     * Extract messages that arrived after the last assistant turn.
     *
     * <p>When server-side memory is enabled the agent already holds prior turns. CopilotKit (and
     * similar clients) still send the full transcript, including HITL tool results that follow the
     * last assistant message. Only those trailing messages should be appended.
     *
     * <p>If the transcript has no assistant message yet, the original input is returned unchanged.
     * If the transcript ends with an assistant turn (regenerate/continue flows) and there
     * are no trailing follow-up messages, the last user message before that turn is returned so
     * the agent has a prompt to regenerate from instead of receiving an empty input.
     *
     * @param input The original input
     * @return A new input containing only the follow-up messages, or the original input
     */
    public RunAgentInput extractLatestUserMessage(RunAgentInput input) {
        List<AguiMessage> messages = input.getMessages();
        if (messages == null || messages.isEmpty()) {
            return input;
        }

        int lastAssistantIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equalsIgnoreCase(messages.get(i).getRole())) {
                lastAssistantIdx = i;
                break;
            }
        }
        if (lastAssistantIdx < 0) {
            return input;
        }

        List<AguiMessage> after =
                lastAssistantIdx < messages.size() - 1
                        ? List.copyOf(messages.subList(lastAssistantIdx + 1, messages.size()))
                        : List.of();

        if (after.isEmpty()) {
            for (int i = lastAssistantIdx - 1; i >= 0; i--) {
                if ("user".equalsIgnoreCase(messages.get(i).getRole())) {
                    after = List.of(messages.get(i));
                    break;
                }
            }
        }

        return RunAgentInput.builder()
                .threadId(input.getThreadId())
                .runId(input.getRunId())
                .messages(after)
                .tools(input.getTools())
                .context(input.getContext())
                .state(input.getState())
                .forwardedProps(input.getForwardedProps())
                .resume(input.getResume())
                .build();
    }

    /**
     * Creates a new builder for AguiRequestProcessor.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for AguiRequestProcessor. */
    public static class Builder {

        private AgentResolver agentResolver;
        private AguiAdapterConfig config;
        private AguiAgentAdapterFactory adapterFactory;
        private AguiRuntimeContextResolver runtimeContextResolver;

        /**
         * Set the agent resolver.
         *
         * @param agentResolver The agent resolver
         * @return This builder
         */
        public Builder agentResolver(AgentResolver agentResolver) {
            this.agentResolver = agentResolver;
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
         * Set the runtime context resolver invoked for each request to produce a caller-provided
         * {@link RuntimeContext}. Optional; when null, no caller context is attached.
         *
         * @param runtimeContextResolver The resolver used for each request
         * @return This builder
         */
        public Builder runtimeContextResolver(AguiRuntimeContextResolver runtimeContextResolver) {
            this.runtimeContextResolver = runtimeContextResolver;
            return this;
        }

        /**
         * Build the processor.
         *
         * @return The built processor
         * @throws NullPointerException if agentResolver is not set
         */
        public AguiRequestProcessor build() {
            return new AguiRequestProcessor(this);
        }
    }
}
