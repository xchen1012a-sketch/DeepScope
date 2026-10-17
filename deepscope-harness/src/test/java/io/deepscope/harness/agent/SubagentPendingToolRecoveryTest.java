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
package io.deepscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.ReActAgent;
import io.deepscope.core.agent.Agent;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.agent.test.MockModel;
import io.deepscope.core.event.AgentEvent;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.MsgRole;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.message.ToolResultBlock;
import io.deepscope.core.message.ToolResultState;
import io.deepscope.core.message.ToolUseBlock;
import io.deepscope.core.middleware.ActingInput;
import io.deepscope.core.middleware.MiddlewareBase;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.Model;
import io.deepscope.core.session.SessionKey;
import io.deepscope.core.session.log.MessageDerivation;
import io.deepscope.core.session.log.SessionEvent;
import io.deepscope.core.session.log.SessionLog;
import io.deepscope.core.state.AgentState;
import io.deepscope.core.state.AgentStateStore;
import io.deepscope.core.state.InMemoryAgentStateStore;
import io.deepscope.core.state.JsonFileAgentStateStore;
import io.deepscope.harness.agent.filesystem.local.LocalFilesystem;
import io.deepscope.harness.agent.middleware.SubagentEntry;
import io.deepscope.harness.agent.session.WorkspaceSessionLogStore;
import io.deepscope.harness.agent.subagent.SubagentDeclaration;
import io.deepscope.harness.agent.subagent.WorkspaceMode;
import io.deepscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/** Regression coverage for Issue #3016: recovery must reach automatically built subagents. */
@HarnessQuiescence
class SubagentPendingToolRecoveryTest {

    private static final String CALL_ID = "interrupted-call";
    private static final RuntimeContext CONTEXT =
            RuntimeContext.builder().userId("user").sessionId("child-session").build();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir Path workspace;

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void automaticSubagentInheritsParentSetting(boolean declared, boolean enabled) {
        HarnessAgent.Builder parent =
                parent(new MockModel("unused"), new InMemoryAgentStateStore())
                        .enablePendingToolRecovery(enabled);
        try (HarnessAgent child = child(parent, declared)) {
            assertEquals(enabled, child.getDelegate().isPendingToolRecoveryEnabled());
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void declaredSubagentCanOverrideParentSetting(boolean parentEnabled, boolean override) {
        HarnessAgent.Builder parent =
                parent(new MockModel("unused"), new InMemoryAgentStateStore())
                        .enablePendingToolRecovery(parentEnabled);
        try (HarnessAgent child = child(parent, true, override)) {
            assertEquals(override, child.getDelegate().isPendingToolRecoveryEnabled());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void workspaceSpecOverrideReachesConstructedChild(boolean override) throws Exception {
        Path specs = Files.createDirectories(workspace.resolve("subagents"));
        Files.writeString(
                specs.resolve("worker.md"),
                "---\ndescription: Worker\nenable_pending_tool_recovery: "
                        + override
                        + "\n---\nComplete the task.\n");
        SubagentEntry entry =
                parent(new MockModel("unused"), new InMemoryAgentStateStore())
                        .enablePendingToolRecovery(!override)
                        .buildSubagentEntries(workspace)
                        .stream()
                        .filter(candidate -> "worker".equals(candidate.name()))
                        .findFirst()
                        .orElseThrow();
        try (HarnessAgent child = (HarnessAgent) entry.factory().create(CONTEXT)) {
            assertEquals(override, child.getDelegate().isPendingToolRecoveryEnabled());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recoveryRemainsDisabledByDefault(boolean declared) {
        try (HarnessAgent child =
                child(parent(new MockModel("unused"), new InMemoryAgentStateStore()), declared)) {
            assertEquals(false, child.getDelegate().isPendingToolRecoveryEnabled());
        }
    }

    @Test
    void disablingRecoveryAgainUpdatesBothDelegateAndChildConfiguration() {
        HarnessAgent.Builder parent =
                parent(new MockModel("unused"), new InMemoryAgentStateStore())
                        .enablePendingToolRecovery(true)
                        .enablePendingToolRecovery(false);
        try (HarnessAgent child = child(parent, true)) {
            assertEquals(false, child.getDelegate().isPendingToolRecoveryEnabled());
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void fromAgentPreservesSettingForAutomaticSubagents(boolean declared, boolean enabled) {
        try (ReActAgent source =
                ReActAgent.builder()
                        .name("source")
                        .model(new MockModel("unused"))
                        .enablePendingToolRecovery(enabled)
                        .build()) {
            HarnessAgent.Builder parent =
                    HarnessAgent.Builder.fromAgent(source)
                            .workspace(workspace)
                            .stateStore(new InMemoryAgentStateStore())
                            .disableMemoryHooks();
            try (HarnessAgent child = child(parent, declared)) {
                assertEquals(enabled, child.getDelegate().isPendingToolRecoveryEnabled());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void rebuiltChildRespectsRecoveryAfterPersistedActingFailure(
            boolean declared, boolean enabled) {
        Path stateDirectory = workspace.resolve("state");
        RuntimeException failure = new IllegalStateException("acting failed before tool execution");
        HarnessAgent.Builder failingParent =
                parent(
                                MockModel.withToolCall("unfinished_tool", CALL_ID, Map.of()),
                                new JsonFileAgentStateStore(stateDirectory))
                        .enablePendingToolRecovery(true)
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Flux<AgentEvent> onActing(
                                            Agent agent,
                                            RuntimeContext context,
                                            ActingInput input,
                                            Function<ActingInput, Flux<AgentEvent>> next) {
                                        return Flux.error(failure);
                                    }
                                });

        SessionKey childKey;
        try (HarnessAgent child = child(failingParent, declared)) {
            childKey = child.sessionKey(CONTEXT);
            assertSame(
                    failure,
                    assertThrows(
                            IllegalStateException.class,
                            () -> child.call(List.of(user("start")), CONTEXT).block(TIMEOUT)));
        }

        // Reopen the native journal and rebuild the child; a legacy state store cannot hide the
        // bug.
        JsonFileAgentStateStore reopenedStore = new JsonFileAgentStateStore(stateDirectory);
        var reopenedLogStore =
                new WorkspaceSessionLogStore(new LocalFilesystem(workspace.resolve("journal")));
        List<Msg> persisted =
                MessageDerivation.history(reopenedLogStore.readKernel(childKey, CONTEXT));
        assertTrue(
                persisted.stream()
                        .flatMap(msg -> msg.getContentBlocks(ToolUseBlock.class).stream())
                        .anyMatch(tool -> CALL_ID.equals(tool.getId())));
        assertTrue(results(persisted).isEmpty());

        MockModel recoveryModel = new MockModel("recovered");
        HarnessAgent.Builder recoveryParent =
                parent(recoveryModel, reopenedStore).enablePendingToolRecovery(enabled);
        try (HarnessAgent child = child(recoveryParent, declared)) {
            if (!enabled) {
                IllegalStateException thrown =
                        assertThrows(
                                IllegalStateException.class,
                                () ->
                                        child.call(List.of(user("continue")), CONTEXT)
                                                .block(TIMEOUT));
                assertTrue(
                        thrown.getMessage().contains("Pending tool calls exist without results"));
                assertEquals(0, recoveryModel.getCallCount());
                assertTrue(
                        results(
                                        child.getDelegate()
                                                .getAgentState("user", "child-session")
                                                .getContext())
                                .isEmpty());
                return;
            }
            assertEquals(
                    "recovered",
                    child.call(List.of(user("continue")), CONTEXT).block(TIMEOUT).getTextContent());
            List<ToolResultBlock> patchedResults = results(recoveryModel.getLastMessages());
            assertEquals(1, patchedResults.size());
            assertEquals(CALL_ID, patchedResults.get(0).getId());
            assertEquals(ToolResultState.ERROR, patchedResults.get(0).getState());
            assertTrue(
                    patchedResults.get(0).getOutput().stream()
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .anyMatch(
                                    text -> text.getText().contains("failed or was interrupted")));
            assertTrue(
                    recoveryModel.getLastMessages().stream()
                            .anyMatch(msg -> "continue".equals(msg.getTextContent())));
        }
        assertEquals(
                1,
                results(MessageDerivation.history(reopenedLogStore.readKernel(childKey, CONTEXT)))
                        .size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void childRecoversPendingCallAfterSubscriptionCancellation(boolean declared) throws Exception {
        CountDownLatch actingSubscribed = new CountDownLatch(1);
        CountDownLatch actingCancelled = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        MockModel model =
                new MockModel(
                        messages ->
                                List.of(
                                        ChatResponse.builder()
                                                .content(
                                                        calls.incrementAndGet() == 2
                                                                ? List.of(
                                                                        ToolUseBlock.builder()
                                                                                .id(CALL_ID)
                                                                                .name(
                                                                                        "unfinished_tool")
                                                                                .input(Map.of())
                                                                                .build())
                                                                : List.of(
                                                                        TextBlock.builder()
                                                                                .text("recovered")
                                                                                .build()))
                                                .build()));
        HarnessAgent.Builder parent =
                parent(model, new InMemoryAgentStateStore())
                        .enablePendingToolRecovery(true)
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Flux<AgentEvent> onActing(
                                            Agent agent,
                                            RuntimeContext context,
                                            ActingInput input,
                                            Function<ActingInput, Flux<AgentEvent>> next) {
                                        return Flux.<AgentEvent>never()
                                                .doOnSubscribe(
                                                        ignored -> actingSubscribed.countDown())
                                                .doOnCancel(actingCancelled::countDown);
                                    }
                                });
        try (HarnessAgent child = child(parent, declared)) {
            // Establish a reusable native session, then interrupt during acting.
            child.call(List.of(user("initialize")), CONTEXT).block(TIMEOUT);
            Disposable subscription = child.call(List.of(user("start")), CONTEXT).subscribe();
            try {
                assertTrue(actingSubscribed.await(10, TimeUnit.SECONDS));
            } finally {
                subscription.dispose();
            }
            assertTrue(actingCancelled.await(10, TimeUnit.SECONDS));
            // Cancellation starts asynchronous journal cleanup; wait for its committed run/end.
            Flux.interval(Duration.ZERO, Duration.ofMillis(10))
                    .filter(
                            ignored -> {
                                var store = child.sessionStore();
                                var log = store.readKernel(child.sessionKey(CONTEXT), CONTEXT);
                                return store.owner(child.sessionKey(CONTEXT), CONTEXT) == null
                                        && hasNoOpenTurn(log);
                            })
                    .next()
                    .block(TIMEOUT);
            AgentState cancelledState = child.getDelegate().getAgentState("user", "child-session");
            assertTrue(results(cancelledState.getContext()).isEmpty());
            assertTrue(
                    cancelledState.getContext().stream()
                            .flatMap(msg -> msg.getContentBlocks(ToolUseBlock.class).stream())
                            .anyMatch(tool -> CALL_ID.equals(tool.getId())));

            assertEquals(
                    "recovered",
                    child.call(List.of(user("continue")), CONTEXT).block(TIMEOUT).getTextContent());
            assertEquals(3, model.getCallCount());
            assertEquals(ToolResultState.ERROR, results(model.getLastMessages()).get(0).getState());
            assertEquals(
                    List.of(CALL_ID),
                    results(model.getLastMessages()).stream().map(ToolResultBlock::getId).toList());
        }
    }

    private HarnessAgent.Builder parent(Model model, AgentStateStore store) {
        return HarnessAgent.builder()
                .model(model)
                .workspace(workspace)
                .stateStore(store)
                .sessionLogStore(
                        new WorkspaceSessionLogStore(
                                new LocalFilesystem(workspace.resolve("journal"))))
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableCompaction()
                .disableToolResultEviction();
    }

    private HarnessAgent child(HarnessAgent.Builder parent, boolean declared) {
        return child(parent, declared, null);
    }

    private HarnessAgent child(HarnessAgent.Builder parent, boolean declared, Boolean override) {
        String name = declared ? "worker" : "general-purpose";
        if (declared) {
            parent.subagent(
                    SubagentDeclaration.builder()
                            .name(name)
                            .description("Worker")
                            .inlineAgentsBody("Complete the task.")
                            .enablePendingToolRecovery(override)
                            .workspaceMode(WorkspaceMode.SHARED)
                            .build());
        }
        SubagentEntry entry =
                parent.buildSubagentEntries(workspace).stream()
                        .filter(candidate -> name.equals(candidate.name()))
                        .findFirst()
                        .orElseThrow();
        return (HarnessAgent) entry.factory().create(CONTEXT);
    }

    private static List<ToolResultBlock> results(List<Msg> messages) {
        return messages.stream()
                .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                .toList();
    }

    private static Msg user(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /** Whether every recorded turn has closed, the kernel's form of "no active execution". */
    private static boolean hasNoOpenTurn(SessionLog log) {
        long open = 0;
        for (SessionEvent event : log.events()) {
            if ("turn/start".equals(event.type()) || "turn/resumed".equals(event.type())) {
                open++;
            } else if ("turn/end".equals(event.type())) {
                open--;
            }
        }
        return open <= 0;
    }
}
