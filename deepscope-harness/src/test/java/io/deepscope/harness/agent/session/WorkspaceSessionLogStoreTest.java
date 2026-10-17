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
package io.deepscope.harness.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.message.ContentBlock;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.session.SessionKey;
import io.deepscope.core.session.log.DurableSession;
import io.deepscope.core.session.log.SessionEventData;
import io.deepscope.core.session.log.SessionExecutionData;
import io.deepscope.core.session.log.SessionLogException;
import io.deepscope.harness.agent.filesystem.BakedContextFilesystem;
import io.deepscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.deepscope.harness.agent.filesystem.local.LocalFilesystem;
import io.deepscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.deepscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.deepscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.deepscope.harness.agent.tool.SessionSearchTool;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSessionLogStoreTest {
    @TempDir Path directory;
    private final SessionKey key = new SessionKey("u", "agent", "session");

    @Test
    void localLogSurvivesIndependentBackendInstancesAndPreservesOwnership() {
        var context = RuntimeContext.empty();
        var first = new WorkspaceSessionLogStore(new LocalFilesystem(directory));
        try (DurableSession writer = first.openWriter(key, "owner", "turn", context)) {
            writer.append(new SessionExecutionData.TurnStart(1));
            var second = new WorkspaceSessionLogStore(new LocalFilesystem(directory));
            assertEquals(1, second.readKernel(key, context).size());
            assertThrows(
                    SessionLogException.class,
                    () -> second.openWriter(key, "other", "turn", context));
        }
        // The released writer leaves ownership free for the next execution.
        var third = new WorkspaceSessionLogStore(new LocalFilesystem(directory));
        try (DurableSession next = third.openWriter(key, "other", "turn", context)) {
            assertEquals(1, next.kernel().size());
        }
    }

    @Test
    void distributedWorkspaceRetainsItsRuntimeNamespace() {
        var backend = new InMemoryStore();
        var filesystem =
                new RemoteFilesystem(backend, rc -> List.of("tenant", (String) rc.get("tenant")));
        var a = RuntimeContext.builder().put("tenant", "a").build();
        var b = RuntimeContext.builder().put("tenant", "b").build();
        var store = new WorkspaceSessionLogStore(filesystem);
        try (DurableSession writer = store.openWriter(key, "owner", "turn", a)) {
            writer.append(new SessionExecutionData.TurnStart(1));
            assertEquals(1, store.readKernel(key, a).size());
            assertEquals(0, store.readKernel(key, b).size());
        }
    }

    @Test
    void agentFilesystemCannotOverwriteRuntimeJournal() {
        var filesystem = new LocalFilesystem(directory);
        assertThrows(
                SecurityException.class,
                () ->
                        filesystem.write(
                                RuntimeContext.empty(), ".deepscope-runtime/head.json", "corrupt"));
    }

    @Test
    void nativeDiscoveryReadsSharedBackendAndPreservesUserIsolation() {
        var backend = new InMemoryStore();
        var first =
                new WorkspaceSessionLogStore(
                        new RemoteFilesystem(backend, rc -> List.of("tenant")));
        var other =
                new WorkspaceSessionLogStore(
                        new RemoteFilesystem(backend, rc -> List.of("tenant")));
        var user = RuntimeContext.builder().userId("u").build();
        var unknown = RuntimeContext.builder().userId("other").build();
        assertTrue(first.list(user).isEmpty());
        first.readKernel(key, user); // Reader opens must not create discoverable phantom sessions.
        assertTrue(other.list(user).isEmpty());
        try (DurableSession writer = first.openWriter(key, "owner", "turn", user)) {
            writer.append(new SessionExecutionData.TurnStart(1));
        }
        assertEquals(List.of(key), other.list(user));
        assertTrue(other.list(unknown).isEmpty());
    }

    @Test
    void localHistorySearchUsesNativeMessagesWithoutTranscriptFiles() {
        var store = new WorkspaceSessionLogStore(new LocalFilesystem(directory));
        var rc = RuntimeContext.builder().userId("u").build();
        ContentBlock content = TextBlock.builder().text("Native history marker").build();
        try (DurableSession writer = store.openWriter(key, "owner", "turn", rc)) {
            writer.append(
                    new SessionEventData.UserMessageData("message-id", List.of(content), null));
        }
        var reader = new WorkspaceSessionLogStore(new LocalFilesystem(directory));
        assertEquals(List.of(key), reader.list(rc));
        var tool = new SessionSearchTool(reader);
        assertTrue(tool.sessionSearch(rc, "history marker", "agent", 10).contains("message-id"));
        assertTrue(
                tool.sessionHistory(rc, "agent", "session", 20, null)
                        .contains("Native history marker"));
        assertTrue(tool.sessionList(rc, "agent").contains("session"));
    }

    @Test
    void routedAndBakedFilesystemsPreserveTheNativeStorageCapability() {
        var backend = new InMemoryStore();
        var remote = new RemoteFilesystem(backend, rc -> List.of("tenant", rc.getUserId()));
        var rc = RuntimeContext.builder().userId("u").sessionId("session").build();
        var routed =
                new RoutedSandboxFilesystem(
                        mock(AbstractSandboxFilesystem.class), Map.of("agents/", remote));
        try (DurableSession writer =
                new WorkspaceSessionLogStore(routed).openWriter(key, "owner", "turn", rc)) {
            writer.append(new SessionExecutionData.TurnStart(1));
        }
        var baked = new WorkspaceSessionLogStore(new BakedContextFilesystem(remote, rc));
        assertEquals(1, baked.readKernel(key, RuntimeContext.empty()).size());
        assertEquals(List.of(key), baked.list(rc));
        assertTrue(baked.list(RuntimeContext.builder().userId("another").build()).isEmpty());
    }

    @Test
    void sessionScopedDiscoveryDoesNotCrossTheCurrentNamespace() {
        var backend = new InMemoryStore();
        var store =
                new WorkspaceSessionLogStore(
                        new RemoteFilesystem(backend, rc -> List.of("session", rc.getSessionId())));
        var a = RuntimeContext.builder().userId("u").sessionId("a").build();
        var b = RuntimeContext.builder().userId("u").sessionId("b").build();
        var keyA = new SessionKey("u", "agent", "a");
        var keyB = new SessionKey("u", "agent", "b");
        for (var context : List.of(a, b)) {
            try (DurableSession writer =
                    store.openWriter(
                            new SessionKey("u", "agent", context.getSessionId()),
                            "owner",
                            "turn",
                            context)) {
                writer.append(new SessionExecutionData.TurnStart(1));
            }
        }
        assertEquals(List.of(keyA), store.list(a));
        assertEquals(List.of(keyB), store.list(b));
        assertEquals(1, store.readKernel(keyB, b).size());
    }
}
