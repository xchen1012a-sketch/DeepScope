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
package io.deepscope.spring.boot.agui.mvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.deepscope.core.ReActAgent;
import io.deepscope.core.agent.Agent;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.agui.adapter.AguiAdapterConfig;
import io.deepscope.core.agui.adapter.AguiAgentAdapter;
import io.deepscope.core.agui.event.AguiEvent;
import io.deepscope.core.agui.event.AguiEventType;
import io.deepscope.core.agui.model.AguiMessage;
import io.deepscope.core.agui.model.RunAgentInput;
import io.deepscope.core.agui.processor.AguiRequestProcessor;
import io.deepscope.core.agui.registry.AguiAgentRegistry;
import io.deepscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.deepscope.core.agui.runtime.AguiRuntimeContextResolver;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

class AguiMvcControllerTest {

    @Test
    void secondRequestDoesNotReplaceTheActiveStream() throws Exception {
        ControllerFixture fixture = fixture(true);
        try {
            SseEmitter first = fixture.controller.handle(input("run-1"), null);
            assertTrue(fixture.firstRunSubscribed.await(5, TimeUnit.SECONDS));
            fixture.controller.handle(input("run-2"), null);
            assertFalse((Boolean) ReflectionTestUtils.getField(first, "complete"));
            assertEquals(1, fixture.runCount.get());
        } finally {
            fixture.controller.interrupt("thread-1");
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void aStaleStopRequestDoesNotCancelTheCurrentRun() throws Exception {
        ControllerFixture fixture = fixture(true);
        try {
            SseEmitter first = fixture.controller.handle(input("run-1"), null);
            assertTrue(fixture.firstRunSubscribed.await(5, TimeUnit.SECONDS));
            assertFalse(fixture.controller.interrupt("thread-1", "old-run"));
            assertFalse((Boolean) ReflectionTestUtils.getField(first, "complete"));
        } finally {
            fixture.controller.interrupt("thread-1");
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void idleMutationReservesTheSameAdmissionSlotAsRuns() throws Exception {
        ControllerFixture fixture = fixture(true);
        try {
            assertTrue(
                    fixture.controller.withIdleThread(
                            "thread-1",
                            () -> {
                                fixture.controller.handle(input("run-1"), null);
                                assertEquals(0, fixture.runCount.get());
                                assertFalse(
                                        fixture.controller.withIdleThread("thread-1", () -> {}));
                            }));
            assertFalse(fixture.controller.hasActiveRun("thread-1"));
        } finally {
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void stoppingCannotReleaseAdmissionBeforeItsResourceRegistrationFinishes() throws Exception {
        CountDownLatch admitting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        ControllerFixture fixture =
                fixture(
                        true,
                        Flux.never(),
                        new AguiRuntimeContextResolver() {
                            @Override
                            public RuntimeContext resolve(AguiRuntimeContextRequest<?> request) {
                                return RuntimeContext.empty();
                            }

                            @Override
                            public void admitted(AguiRuntimeContextRequest<?> request) {
                                admitting.countDown();
                                try {
                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                } catch (InterruptedException error) {
                                    throw new IllegalStateException(error);
                                }
                            }
                        });
        Thread request = new Thread(() -> fixture.controller.handle(input("run-1"), null));
        Thread stop =
                new Thread(
                        () -> {
                            fixture.controller.interrupt("thread-1");
                            stopped.countDown();
                        });
        try {
            request.start();
            assertTrue(admitting.await(5, TimeUnit.SECONDS));
            stop.start();
            assertFalse(
                    stopped.await(300, TimeUnit.MILLISECONDS),
                    "admission must stay owned until registration finishes");
            assertEquals("run-1", fixture.controller.activeRunId("thread-1"));
        } finally {
            release.countDown();
            request.join(5000);
            stop.join(5000);
            fixture.controller.interrupt("thread-1");
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void disconnectKeepsAdmissionReservedUntilAgentInterruptionFinishes() throws Exception {
        CountDownLatch releasing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ControllerFixture fixture =
                fixture(
                        true,
                        Flux.never(),
                        new AguiRuntimeContextResolver() {
                            @Override
                            public RuntimeContext resolve(AguiRuntimeContextRequest<?> request) {
                                return RuntimeContext.empty();
                            }

                            @Override
                            public void release(AguiRuntimeContextRequest<?> request) {
                                releasing.countDown();
                                try {
                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                } catch (InterruptedException error) {
                                    throw new IllegalStateException(error);
                                }
                            }
                        });
        Thread disconnect = null;
        try {
            SseEmitter first = fixture.controller.handle(input("run-1"), null);
            assertTrue(fixture.firstRunSubscribed.await(5, TimeUnit.SECONDS));
            disconnect = new Thread(() -> invokeError(first));
            disconnect.start();
            assertTrue(releasing.await(5, TimeUnit.SECONDS));
            assertEquals("run-1", fixture.controller.activeRunId("thread-1"));
            fixture.controller.handle(input("run-2"), null);
            assertEquals(1, fixture.runCount.get());
        } finally {
            release.countDown();
            if (disconnect != null) disconnect.join(5000);
            fixture.controller.interrupt("thread-1");
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void aLateCancelledInitializerDoesNotInterruptTheNewRun() throws Exception {
        CountDownLatch initializing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AguiRuntimeContextResolver resolver =
                new AguiRuntimeContextResolver() {
                    @Override
                    public RuntimeContext resolve(AguiRuntimeContextRequest<?> request) {
                        if ("run-1".equals(request.getInput().getRunId())) {
                            initializing.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException error) {
                                throw new IllegalStateException(error);
                            }
                        }
                        return RuntimeContext.empty();
                    }

                    @Override
                    public void release(AguiRuntimeContextRequest<?> request) {
                        if ("run-1".equals(request.getInput().getRunId())) released.countDown();
                    }
                };
        ControllerFixture fixture = fixture(true, Flux.never(), resolver);
        try {
            fixture.controller.handle(input("run-1"), null);
            assertTrue(initializing.await(5, TimeUnit.SECONDS));
            assertTrue(fixture.controller.interrupt("thread-1"));
            fixture.controller.handle(input("run-2"), null);
            assertTrue(fixture.firstRunSubscribed.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(released.await(5, TimeUnit.SECONDS));
            org.mockito.Mockito.verify(fixture.agent, org.mockito.Mockito.after(300).never())
                    .interrupt(org.mockito.ArgumentMatchers.any(RuntimeContext.class));
            assertEquals("run-2", fixture.controller.activeRunId("thread-1"));
        } finally {
            release.countDown();
            fixture.controller.interrupt("thread-1");
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void stoppingCannotRemoveTheIdleMutationGuard() throws Exception {
        ControllerFixture fixture = fixture(true);
        try {
            assertTrue(
                    fixture.controller.withIdleThread(
                            "thread-1",
                            () -> {
                                assertFalse(fixture.controller.interrupt("thread-1"));
                                fixture.controller.handle(input("run-1"), null);
                                assertEquals(0, fixture.runCount.get());
                                assertFalse(
                                        fixture.controller.withIdleThread("thread-1", () -> {}));
                            }));
        } finally {
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void stoppingDuringInitializationDoesNotStartTheAdapterLater() throws Exception {
        CountDownLatch initializing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ControllerFixture fixture =
                fixture(
                        true,
                        Flux.never(),
                        request -> {
                            initializing.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(error);
                            }
                            return RuntimeContext.empty();
                        });
        try {
            fixture.controller.handle(input("run-1"), null);
            assertTrue(initializing.await(5, TimeUnit.SECONDS));
            assertTrue(fixture.controller.interrupt("thread-1"));
            release.countDown();
            assertFalse(fixture.firstRunSubscribed.await(300, TimeUnit.MILLISECONDS));
            assertEquals(0, fixture.runCount.get());
        } finally {
            release.countDown();
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void validatesBeforeAdmissionAndReleasesOnlyTheAdmittedRun() throws Exception {
        AtomicInteger validations = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        AguiRuntimeContextResolver resolver =
                new AguiRuntimeContextResolver() {
                    @Override
                    public RuntimeContext resolve(AguiRuntimeContextRequest<?> request) {
                        return RuntimeContext.empty();
                    }

                    @Override
                    public void validate(AguiRuntimeContextRequest<?> request) {
                        validations.incrementAndGet();
                        if ("invalid".equals(request.getInput().getRunId()))
                            throw new IllegalStateException("not authorized");
                    }

                    @Override
                    public void release(AguiRuntimeContextRequest<?> request) {
                        releases.incrementAndGet();
                    }
                };
        ControllerFixture fixture = fixture(true, Flux.never(), resolver);
        try {
            SseEmitter first = fixture.controller.handle(input("run-1"), null);
            assertTrue(fixture.firstRunSubscribed.await(5, TimeUnit.SECONDS));
            fixture.controller.handle(input("invalid"), null);
            assertEquals(2, validations.get());
            assertFalse((Boolean) ReflectionTestUtils.getField(first, "complete"));
            fixture.controller.interrupt("thread-1");
            fixture.controller.interrupt("thread-1");
            assertEquals(1, releases.get());
        } finally {
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void eventStreamSignalsAreForwardedAndEmitterCompletes() throws Exception {
        ControllerFixture fixture =
                fixture(true, Flux.just(new AguiEvent.RunFinished("thread-1", "run-1")));
        try {
            SseEmitter emitter = fixture.controller.handle(input("run-1"), null);

            assertTrue(fixture.firstRunTerminated.await(5, TimeUnit.SECONDS));
            assertTrue((Boolean) ReflectionTestUtils.getField(emitter, "complete"));
            assertEquals(1, fixture.runCount.get());
        } finally {
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void eventStreamErrorCompletesEmitter() throws Exception {
        ControllerFixture fixture =
                fixture(true, Flux.error(new IllegalStateException("run failed")));
        try {
            SseEmitter emitter = fixture.controller.handle(input("run-1"), null);

            assertTrue(fixture.firstRunTerminated.await(5, TimeUnit.SECONDS));
            assertTrue((Boolean) ReflectionTestUtils.getField(emitter, "complete"));
            assertEquals(1, fixture.runCount.get());
        } finally {
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void sseErrorCancelsSubscriptionAndReleasesThread() throws Exception {
        ControllerFixture fixture = fixture(true);
        try {
            SseEmitter emitter = fixture.controller.handle(input("run-1"), null);
            assertTrue(fixture.firstRunSubscribed.await(5, TimeUnit.SECONDS));

            invokeError(emitter);
            assertTrue(fixture.firstRunTerminated.await(5, TimeUnit.SECONDS));

            awaitSecondRunAccepted(fixture);

            assertEquals(2, fixture.runCount.get());
            verify(fixture.agent).interrupt(any(RuntimeContext.class));
        } finally {
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void sseErrorKeepsRunActiveWhenInterruptOnDisconnectIsDisabled() throws Exception {
        ControllerFixture fixture = fixture(false);
        try {
            SseEmitter emitter = fixture.controller.handle(input("run-1"), null);
            assertTrue(fixture.firstRunSubscribed.await(5, TimeUnit.SECONDS));

            invokeError(emitter);

            List<AguiEvent> events =
                    fixture.processor
                            .process(
                                    AguiRuntimeContextRequest.builder()
                                            .input(input("run-2"))
                                            .build())
                            .events()
                            .collectList()
                            .block();

            assertEquals(1, fixture.runCount.get());
            assertEquals(
                    List.of(AguiEventType.RUN_STARTED, AguiEventType.RUN_ERROR),
                    events.stream().map(AguiEvent::getType).toList());
        } finally {
            fixture.executor.shutdownNow();
        }
    }

    @Test
    void sseTimeoutCancelsSubscriptionAndReleasesThread() throws Exception {
        ControllerFixture fixture = fixture(true);
        try {
            SseEmitter emitter = fixture.controller.handle(input("run-1"), null);
            assertTrue(fixture.firstRunSubscribed.await(5, TimeUnit.SECONDS));

            Object timeoutCallback = ReflectionTestUtils.getField(emitter, "timeoutCallback");
            ReflectionTestUtils.invokeMethod(timeoutCallback, "run");
            assertTrue(fixture.firstRunTerminated.await(5, TimeUnit.SECONDS));

            awaitSecondRunAccepted(fixture);

            assertEquals(2, fixture.runCount.get());
            verify(fixture.agent).interrupt(any(RuntimeContext.class));
        } finally {
            fixture.executor.shutdownNow();
        }
    }

    private static ControllerFixture fixture(boolean interruptOnDisconnect) {
        return fixture(interruptOnDisconnect, Flux.never());
    }

    private static ControllerFixture fixture(
            boolean interruptOnDisconnect, Flux<AguiEvent> firstRunEvents) {
        return fixture(interruptOnDisconnect, firstRunEvents, null);
    }

    private static ControllerFixture fixture(
            boolean interruptOnDisconnect,
            Flux<AguiEvent> firstRunEvents,
            AguiRuntimeContextResolver resolver) {
        ReActAgent agent = mock(ReActAgent.class);
        AguiAgentRegistry registry = new AguiAgentRegistry();
        registry.register("default", agent);
        CountDownLatch firstRunSubscribed = new CountDownLatch(1);
        CountDownLatch firstRunTerminated = new CountDownLatch(1);
        AtomicInteger runCount = new AtomicInteger();
        AguiMvcController controller =
                AguiMvcController.builder()
                        .agentRegistry(registry)
                        .runtimeContextResolver(resolver)
                        .interruptOnDisconnect(interruptOnDisconnect)
                        .adapterFactory(
                                (resolvedAgent, config) ->
                                        new TestAdapter(
                                                resolvedAgent,
                                                config,
                                                firstRunSubscribed,
                                                firstRunTerminated,
                                                firstRunEvents,
                                                runCount))
                        .build();
        AguiRequestProcessor processor =
                (AguiRequestProcessor) ReflectionTestUtils.getField(controller, "processor");
        ExecutorService executor =
                (ExecutorService) ReflectionTestUtils.getField(controller, "executorService");
        return new ControllerFixture(
                controller,
                processor,
                executor,
                agent,
                firstRunSubscribed,
                firstRunTerminated,
                runCount);
    }

    private static void invokeError(SseEmitter emitter) {
        Object errorCallback = ReflectionTestUtils.getField(emitter, "errorCallback");
        ReflectionTestUtils.invokeMethod(
                errorCallback, "accept", new IOException("client disconnected"));
    }

    /**
     * Submits run-2 until it is accepted by the processor.
     *
     * <p>On disconnect the controller cancels run-1's subscription. Reactor's {@code doFinally}
     * fires inner callbacks before outer ones on cancel, so the adapter's own teardown (which
     * counts down {@code firstRunTerminated}) completes <em>before</em> {@code
     * AguiResumeCoordinator.finishRun} releases the thread. Retrying run-2 therefore waits
     * deterministically for the thread to actually become free instead of racing it.
     */
    private static void awaitSecondRunAccepted(ControllerFixture fixture) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fixture.runCount.get() < 2 && System.nanoTime() < deadline) {
            fixture.processor
                    .process(AguiRuntimeContextRequest.builder().input(input("run-2")).build())
                    .events()
                    .collectList()
                    .block();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
    }

    private static RunAgentInput input(String runId) {
        return RunAgentInput.builder()
                .threadId("thread-1")
                .runId(runId)
                .messages(List.of(AguiMessage.userMessage("message-1", "hello")))
                .build();
    }

    private record ControllerFixture(
            AguiMvcController controller,
            AguiRequestProcessor processor,
            ExecutorService executor,
            ReActAgent agent,
            CountDownLatch firstRunSubscribed,
            CountDownLatch firstRunTerminated,
            AtomicInteger runCount) {}

    private static final class TestAdapter extends AguiAgentAdapter {

        private final CountDownLatch firstRunSubscribed;
        private final CountDownLatch firstRunTerminated;
        private final Flux<AguiEvent> firstRunEvents;
        private final AtomicInteger runCount;

        private TestAdapter(
                Agent agent,
                AguiAdapterConfig config,
                CountDownLatch firstRunSubscribed,
                CountDownLatch firstRunTerminated,
                Flux<AguiEvent> firstRunEvents,
                AtomicInteger runCount) {
            super(agent, config);
            this.firstRunSubscribed = firstRunSubscribed;
            this.firstRunTerminated = firstRunTerminated;
            this.firstRunEvents = firstRunEvents;
            this.runCount = runCount;
        }

        @Override
        public Flux<AguiEvent> run(RunAgentInput input, RuntimeContext runtimeContext) {
            if (runCount.incrementAndGet() == 1) {
                return firstRunEvents
                        .doOnRequest(ignored -> firstRunSubscribed.countDown())
                        .doFinally(signalType -> firstRunTerminated.countDown());
            }
            return Flux.empty();
        }
    }
}
