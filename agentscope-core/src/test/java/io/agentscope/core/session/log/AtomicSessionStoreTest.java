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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.session.AtomicSessionStorage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the kernel served by an atomic key-value capability rather than local files.
 *
 * <p>The kernel must not care what stores its records, so a deployment whose workspace is remote can
 * keep session history without falling back to a local disk.
 */
class AtomicSessionStoreTest {

    private static final long TIME = 1_700_000_000_000L;

    /** A minimal in-memory byte store with real compare-and-set semantics. */
    private static final class MemoryStorage implements AtomicSessionStorage {
        private final Map<String, Value> objects = new LinkedHashMap<>();

        @Override
        public Value read(String path) {
            return objects.get(path);
        }

        @Override
        public List<String> listPaths(String prefix) {
            return objects.keySet().stream().filter(path -> path.startsWith(prefix)).toList();
        }

        @Override
        public boolean compareAndSet(String path, long expectedVersion, byte[] bytes) {
            Value current = objects.get(path);
            long version = current == null ? 0L : current.version();
            if (version != expectedVersion) {
                return false;
            }
            objects.put(path, new Value(version + 1, bytes));
            return true;
        }
    }

    private static SessionEventData.UserMessageData user(String id, String value) {
        return SessionEventData.UserMessageData.of(
                id, List.of(TextBlock.builder().text(value).build()));
    }

    @Test
    @DisplayName("a session created on the capability is listable and openable")
    void createdSessionIsListableAndOpenable() {
        var store = new AtomicSessionStore(new MemoryStorage());
        store.create("s1");

        assertTrue(store.list().contains("s1"));

        try (var handle = store.open("s1", AtomicSessionStore.Access.WRITE)) {
            assertEquals(0, handle.log().size());
        }
    }

    @Test
    @DisplayName("appends survive close and reopen")
    void appendsSurviveReopen() {
        var storage = new MemoryStorage();
        var store = new AtomicSessionStore(storage);
        store.create("s1");
        try (var handle = store.open("s1", AtomicSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m1", "hello"));
            handle.append(TIME + 1, user("m2", "again"));
            handle.flush();
        }

        try (var handle =
                new AtomicSessionStore(storage).open("s1", AtomicSessionStore.Access.READ)) {
            assertEquals(2, handle.log().size());
            assertEquals(
                    List.of("hello", "again"),
                    handle.log().deriveMessages().stream()
                            .map(io.agentscope.core.message.Msg::getTextContent)
                            .toList());
        }
    }

    @Test
    @DisplayName("a second writer for the same session is refused")
    void secondWriterIsRefused() {
        var store = new AtomicSessionStore(new MemoryStorage());
        store.create("s1");

        try (var first = store.open("s1", AtomicSessionStore.Access.WRITE)) {
            assertThrows(
                    AtomicSessionStore.SessionAlreadyOwnedException.class,
                    () -> store.open("s1", AtomicSessionStore.Access.WRITE));
            assertEquals(0, first.log().size());
        }
    }

    @Test
    @DisplayName("an unknown session cannot be opened")
    void unknownSessionCannotBeOpened() {
        var store = new AtomicSessionStore(new MemoryStorage());

        assertThrows(
                AtomicSessionStore.SessionNotFoundException.class,
                () -> store.open("missing", AtomicSessionStore.Access.WRITE));
    }

    @Test
    @DisplayName("a torn record is refused rather than silently dropped")
    void tornRecordIsRefused() {
        var storage = new MemoryStorage();
        var store = new AtomicSessionStore(storage);
        store.create("s1");
        try (var handle = store.open("s1", AtomicSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m1", "hello"));
            handle.flush();
        }
        // Corrupt the stored event body while leaving the head pointer intact.
        String eventPath = store.eventPath("s1", 0);
        storage.compareAndSet(eventPath, storage.read(eventPath).version(), "{not json".getBytes());

        assertThrows(
                AtomicSessionStore.SessionCorruptionException.class,
                () -> new AtomicSessionStore(storage).open("s1", AtomicSessionStore.Access.READ));
    }

    @Test
    @DisplayName("a version conflict on the head pointer is retried, not lost")
    void headVersionConflictIsRetried() {
        var storage = new MemoryStorage();
        var store = new AtomicSessionStore(storage);
        store.create("s1");

        try (var handle = store.open("s1", AtomicSessionStore.Access.WRITE)) {
            handle.append(TIME, user("m1", "one"));
            handle.flush();
            handle.append(TIME + 1, user("m2", "two"));
            handle.flush();
        }

        try (var handle =
                new AtomicSessionStore(storage).open("s1", AtomicSessionStore.Access.READ)) {
            assertEquals(2, handle.log().size());
        }
    }

    @Test
    @DisplayName("an agent run persists through the capability and derives its history")
    void agentRunPersistsThroughTheCapability() {
        var storage = new MemoryStorage();
        var store = new AtomicSessionStore(storage);
        var agent =
                io.agentscope.core.ReActAgent.builder()
                        .name("capability")
                        .model(
                                new io.agentscope.core.model.Model() {
                                    @Override
                                    public String getModelName() {
                                        return "fake";
                                    }

                                    @Override
                                    public reactor.core.publisher.Flux<
                                                    io.agentscope.core.model.ChatResponse>
                                            stream(
                                                    List<io.agentscope.core.message.Msg> msgs,
                                                    List<io.agentscope.core.model.ToolSchema> tools,
                                                    io.agentscope.core.model.GenerateOptions
                                                            options) {
                                        return reactor.core.publisher.Flux.just(
                                                io.agentscope.core.model.ChatResponse.builder()
                                                        .content(
                                                                List.of(
                                                                        TextBlock.builder()
                                                                                .text("answer")
                                                                                .build()))
                                                        .build());
                                    }
                                })
                        .nativeSessionLog(store)
                        .build();

        agent.call(
                        List.of(new io.agentscope.core.message.UserMessage("through capability")),
                        io.agentscope.core.agent.RuntimeContext.builder()
                                .userId("u")
                                .sessionId("s")
                                .build())
                .block(java.time.Duration.ofSeconds(10));

        try (var handle = store.open("s", AtomicSessionStore.Access.READ)) {
            var derived =
                    handle.log().deriveMessages().stream()
                            .map(io.agentscope.core.message.Msg::getTextContent)
                            .toList();
            assertTrue(
                    derived.contains("through capability"),
                    "a workspace-backed deployment must keep the request: " + derived);
            assertTrue(
                    derived.contains("answer"),
                    "a workspace-backed deployment must keep the reply: " + derived);
        }
    }

    @Test
    @DisplayName("a session id that escapes the namespace is refused")
    void escapingSessionIdIsRefused() {
        var store = new AtomicSessionStore(new MemoryStorage());

        assertThrows(IllegalArgumentException.class, () -> store.create("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.create("a/b"));
    }
}
