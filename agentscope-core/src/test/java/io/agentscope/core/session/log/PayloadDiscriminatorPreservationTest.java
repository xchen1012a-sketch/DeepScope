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
package io.agentscope.core.session.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.observation.ActionObservation;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.state.AgentState;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the lossless encoding of the kernel's own opaque payloads.
 *
 * <p>A {@code Map<String, Object>} erases the declared type of its values, so Jackson would write a
 * nested content block without the {@code type} discriminator a reader needs. The payload's
 * constructor is where that is repaired, so both the convenience append path and a directly
 * constructed payload keep the discriminator on disk.
 */
class PayloadDiscriminatorPreservationTest {

    @TempDir Path directory;

    private final SessionKey key = new SessionKey("u", "agent", "s");

    private NativeSessionLogStore store() {
        return new NativeSessionLogStore(new LocalSessionStore(directory));
    }

    private static Map<String, Object> fieldsOf(SessionLog log, String type) {
        return log.events().stream()
                .filter(event -> type.equals(event.type()))
                .findFirst()
                .map(event -> assertInstanceOf(SessionEventData.PayloadData.class, event.data()))
                .map(SessionEventData.PayloadData::fields)
                .orElseThrow(() -> new AssertionError("no " + type + " record in " + log.events()));
    }

    @Test
    @DisplayName("a model stream chunk keeps the tool-result discriminator across the store")
    void toolChunkKeepsItsDiscriminator() {
        try (DurableSession writer = store().openWriter("sess", "run-1", "turn-1")) {
            writer.append(
                    "tool/chunk",
                    Map.of(
                            "toolCallId",
                            "c1",
                            "actionId",
                            "a1",
                            "chunk",
                            ToolResultBlock.text("hello")));
        }

        var chunk =
                assertInstanceOf(
                        Map.class, fieldsOf(store().read("sess"), "tool/chunk").get("chunk"));
        assertEquals("tool_result", chunk.get("type"));
    }

    @Test
    @DisplayName("an action result keeps the tool-result discriminator across the store")
    void actionEndKeepsItsDiscriminator() {
        var observation =
                new ActionObservation(
                        "a1",
                        "c1",
                        "probe",
                        "agent",
                        "u",
                        "s",
                        1L,
                        2L,
                        ActionObservation.Status.RETURNED,
                        null,
                        null,
                        null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", "c1");
        payload.put("observation", observation);
        payload.put("result", ToolResultBlock.builder().id("call").build());
        try (DurableSession writer = store().openWriter("sess", "run-1", "turn-1")) {
            writer.append("action/end", payload);
        }

        var fields = fieldsOf(store().read("sess"), "action/end");
        var result = assertInstanceOf(Map.class, fields.get("result"));
        assertEquals("tool_result", result.get("type"));
        var stored = assertInstanceOf(Map.class, fields.get("observation"));
        assertEquals("RETURNED", stored.get("status"));
    }

    @Test
    @DisplayName("a suspended interaction keeps the tool-use discriminator across the store")
    void interactionRequestedKeepsItsDiscriminator() {
        try (DurableSession writer = store().openWriter("sess", "run-1", "turn-1")) {
            writer.append(
                    new SessionEventData.PayloadData(
                            "interaction/requested",
                            Map.of(
                                    "requestId",
                                    "req",
                                    "kind",
                                    "confirmation",
                                    "call",
                                    ToolUseBlock.builder()
                                            .id("call")
                                            .name("probe")
                                            .input(Map.of())
                                            .build())));
        }

        var call =
                assertInstanceOf(
                        Map.class,
                        fieldsOf(store().read("sess"), "interaction/requested").get("call"));
        assertEquals("tool_use", call.get("type"));
        assertEquals("call", call.get("id"));
    }

    @Test
    @DisplayName("run/end and state/checkpoint keep their retained field names and values")
    void retainedPayloadNamesKeepTheirFields() {
        var store = new InMemorySessionLogStore();
        var context = RuntimeContext.builder().userId("u").sessionId("s").build();
        var state = AgentState.builder().sessionId("s").userId("u").build();
        try (DurableSession writer = store.openWriter(key, "run-1", "turn-1", context)) {
            writer.captureState(state, "test");
            writer.finish(state, "completed").block();
        }

        var checkpoint = fieldsOf(store.readKernel(key, context), "state/checkpoint");
        assertEquals(Set.of("stateJson", "reason", "projectorVersion"), checkpoint.keySet());
        assertInstanceOf(String.class, checkpoint.get("stateJson"));
        assertEquals("test", checkpoint.get("reason"));
        assertEquals(1, checkpoint.get("projectorVersion"));

        var runEnd = fieldsOf(store.readKernel(key, context), "run/end");
        assertEquals(Set.of("turn", "status", "reason"), runEnd.keySet());
        assertInstanceOf(Number.class, runEnd.get("turn"));
        assertEquals(1L, ((Number) runEnd.get("turn")).longValue());
        assertEquals("completed", runEnd.get("status"));
        assertNull(runEnd.get("reason"));
    }
}
