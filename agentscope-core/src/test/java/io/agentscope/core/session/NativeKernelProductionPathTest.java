/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Pins the cutover that matters: a real agent run must persist through the new kernel.
 *
 * <p>Reading history out of the legacy log is not the goal. The goal is that the kernel which owns
 * the model-visible surface is the kernel that stores the run, so {@code deriveMessages()} — not a
 * parallel reader — is what reconstructs history.
 */
class NativeKernelProductionPathTest {

    private static RuntimeContext context() {
        return RuntimeContext.builder().userId("u").sessionId("s").build();
    }

    private static final class CaptureModel implements Model {
        public String getModelName() {
            return "fake";
        }

        public Flux<ChatResponse> stream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("answer").build()))
                            .build());
        }
    }

    @Test
    @DisplayName("a finished run releases its write ownership")
    void finishedRunReleasesWriteOwnership(@TempDir Path root) {
        var store = new io.agentscope.core.session.log.LocalSessionStore(root);
        var agent =
                ReActAgent.builder()
                        .name("release")
                        .model(new CaptureModel())
                        .nativeSessionLog(store)
                        .build();
        agent.call(List.of(new UserMessage("hello")), context()).block(Duration.ofSeconds(10));

        // A completed run must not keep the session owned, or nothing can ever write to it again.
        try (var handle =
                store.open("s", io.agentscope.core.session.log.LocalSessionStore.Access.WRITE)) {
            assertTrue(handle.log().size() > 0, "the released session still holds its records");
        }
    }

    @Test
    @DisplayName("a crashed run is repaired from the kernel's log, not from a checkpoint")
    void crashedRunIsRepairedFromTheKernelLog(@TempDir Path root) {
        var store = new io.agentscope.core.session.log.LocalSessionStore(root);
        var agent =
                ReActAgent.builder()
                        .name("crash")
                        .model(new CaptureModel())
                        .nativeSessionLog(store)
                        .build();
        agent.call(List.of(new UserMessage("start something")), context())
                .block(Duration.ofSeconds(10));

        // Simulate a crash: open the stored log and leave a turn open by appending its start only.
        var handle = store.open("s", io.agentscope.core.session.log.LocalSessionStore.Access.WRITE);
        var turn = handle.log().nextSeq();
        handle.appendLogOnly(
                1_700_000_000_000L,
                new io.agentscope.core.session.log.SessionExecutionData.TurnStart(99L));
        handle.flush();
        handle.close();

        // Repair must be derivable from the kernel's own log.
        var reopened =
                store.open("s", io.agentscope.core.session.log.LocalSessionStore.Access.READ);
        var closers =
                io.agentscope.core.session.log.TurnRepair.interruptedTurnClosers(
                        reopened.log().events());

        assertFalse(
                closers.isEmpty(),
                "a log whose last turn never closed must offer closing events for repair");
        var last = closers.get(closers.size() - 1).data();
        assertEquals(
                io.agentscope.core.session.log.SessionExecutionData.TurnEndReason.INTERRUPTED,
                ((io.agentscope.core.session.log.SessionExecutionData.TurnEnd) last).reason(),
                "a crash-orphaned turn closes as interrupted");
    }

    @Test
    @DisplayName("an agent run is stored in the new kernel and its surface derives the history")
    void agentRunIsStoredInTheNewKernel(@TempDir Path root) {
        var store = new io.agentscope.core.session.log.LocalSessionStore(root);
        var agent =
                ReActAgent.builder()
                        .name("native")
                        .model(new CaptureModel())
                        .nativeSessionLog(store)
                        .build();

        agent.call(List.of(new UserMessage("hello native")), context())
                .block(Duration.ofSeconds(10));

        // The kernel must now hold the run, and its own projection must reproduce the history.
        var handle = store.open("s", io.agentscope.core.session.log.LocalSessionStore.Access.READ);
        var derived = handle.log().deriveMessages().stream().map(Msg::getTextContent).toList();

        assertTrue(
                derived.contains("hello native"),
                "the new kernel must hold the request the model received: " + derived);
        assertTrue(
                derived.contains("answer"),
                "the new kernel must hold the reply the model produced: " + derived);
        assertTrue(
                derived.indexOf("hello native") < derived.indexOf("answer"),
                "the request must precede the reply it produced: " + derived);
    }
}
