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
package io.deepscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.deepscope.core.ReActAgent;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.message.UserMessage;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.Model;
import io.deepscope.core.model.ToolSchema;
import io.deepscope.core.permission.PermissionMode;
import io.deepscope.core.session.log.DurableSession;
import io.deepscope.core.session.log.KernelJournal;
import io.deepscope.core.session.log.LocalSessionStore;
import io.deepscope.core.session.log.MessageDerivation;
import io.deepscope.core.session.log.NativeSessionLogStore;
import io.deepscope.core.session.log.SessionEvent;
import io.deepscope.core.session.log.SessionJournalStore;
import io.deepscope.core.session.log.SessionLogException;
import io.deepscope.core.session.log.SessionLogStore;
import io.deepscope.core.state.AgentState;
import io.deepscope.core.state.InMemoryAgentStateStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class ReActSessionLogTest {
    private RuntimeContext context() {
        return RuntimeContext.builder().userId("u").sessionId("s").build();
    }

    private SessionKey key() {
        return new SessionKey("u", "stable", "s");
    }

    private static class CaptureModel implements Model {
        List<List<Msg>> calls = new ArrayList<>();

        public String getModelName() {
            return "fake";
        }

        public Flux<ChatResponse> stream(
                List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(List.copyOf(msgs));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("answer").build()))
                            .build());
        }
    }

    @Test
    void rebuiltAgentRestoresCompleteHistoryAndRecordsFinalRequest() {
        var store = new InMemorySessionLogStore();
        var first = new CaptureModel();
        var a = ReActAgent.builder().name("stable").model(first).sessionLogStore(store).build();
        String large = "input".repeat(1000);
        a.call(List.of(new UserMessage(large)), context()).block(Duration.ofSeconds(10));
        var second = new CaptureModel();
        var b = ReActAgent.builder().name("stable").model(second).sessionLogStore(store).build();
        b.call(List.of(new UserMessage("continue")), context()).block(Duration.ofSeconds(10));
        assertTrue(second.calls.get(0).stream().anyMatch(m -> large.equals(m.getTextContent())));
        var events = store.readKernel(key(), context()).events();
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e.type().equals("user/message")
                                                && e.data()
                                                        instanceof
                                                        io.deepscope.core.session.log
                                                                        .SessionEventData
                                                                        .UserMessageData
                                                                user
                                                && user.content().stream()
                                                        .anyMatch(
                                                                block ->
                                                                        block
                                                                                        instanceof
                                                                                        TextBlock
                                                                                                text
                                                                                && large.equals(
                                                                                        text
                                                                                                .getText()))));
        assertEquals(2, events.stream().filter(e -> e.type().equals("run/end")).count());
        assertNull(
                store.owner(key(), context()),
                "no execution may still hold the log once both calls returned");
    }

    @Test
    void nativeAdministrationUsesFencedCheckpointsAndNeverLoadsLegacyState() {
        var store = new InMemorySessionLogStore();
        var legacy = new InMemoryAgentStateStore();
        legacy.save(
                "u",
                "s",
                "agent_state",
                AgentState.builder()
                        .sessionId("s")
                        .userId("u")
                        .context(List.of(new UserMessage("legacy-only")))
                        .build());
        var model = new CaptureModel();
        var agent =
                ReActAgent.builder()
                        .name("stable")
                        .model(model)
                        .sessionLogStore(store)
                        .stateStore(legacy)
                        .build();
        assertTrue(agent.getAgentState(context()).getContext().isEmpty());
        agent.call(List.of(new UserMessage("native")), context()).block(Duration.ofSeconds(10));
        assertTrue(
                model.calls.get(0).stream()
                        .noneMatch(message -> "legacy-only".equals(message.getTextContent())));
        agent.setPermissionMode(context(), PermissionMode.BYPASS);
        agent.clearContext(context());
        var rebuilt =
                ReActAgent.builder()
                        .name("stable")
                        .model(new CaptureModel())
                        .sessionLogStore(store)
                        .build();
        assertTrue(rebuilt.getAgentState(context()).getContext().isEmpty());
        assertEquals(PermissionMode.BYPASS, rebuilt.getPermissionMode("u", "s"));
        assertTrue(
                MessageDerivation.history(store.readKernel(key(), context())).stream()
                        .anyMatch(message -> "native".equals(message.getTextContent())));
        assertEquals(
                "legacy-only",
                legacy.get("u", "s", "agent_state", AgentState.class)
                        .orElseThrow()
                        .getContext()
                        .get(0)
                        .getTextContent());
    }

    @Test
    void detachedStateCannotOverwriteAnotherWriterAndInspectionDoesNotMutate() {
        var store = new InMemorySessionLogStore();
        var first =
                ReActAgent.builder()
                        .name("stable")
                        .model(new CaptureModel())
                        .sessionLogStore(store)
                        .build();
        var second =
                ReActAgent.builder()
                        .name("stable")
                        .model(new CaptureModel())
                        .sessionLogStore(store)
                        .build();
        long before = store.readKernel(key(), context()).size();
        first.inspectAgentState(context());
        assertEquals(
                before,
                store.readKernel(key(), context()).size(),
                "inspection must not append to the log");
        AgentState stale = first.getAgentState(context());
        stale.setSummary("stale edit");
        second.updateAgentState(context(), "admin_update", state -> state.setSummary("current"));
        assertThrows(SessionLogException.class, () -> first.saveAgentState(context()));
        assertEquals("current", first.inspectAgentState(context()).state().getSummary());
        try (DurableSession writer = store.openWriter(key(), "execution", null, context())) {
            assertThrows(SessionLogException.class, () -> first.clearContext(context()));
            assertEquals("current", first.inspectAgentState(context()).state().getSummary());
        }
        var editable = first.getAgentState(context());
        editable.setSummary("saved");
        first.saveAgentState(context());
        assertEquals("saved", second.inspectAgentState(context()).state().getSummary());
    }

    @Test
    void nativeAdministrationPreservesBackendRoutingAttributes() {
        var backing = new InMemorySessionLogStore();
        SessionLogStore routed =
                new SessionLogStore() {
                    public DurableSession openWriter(
                            SessionKey key, String runId, String turnId, RuntimeContext context) {
                        assertEquals("tenant-space", context.get("namespace"));
                        return backing.openWriter(key, runId, turnId, context);
                    }

                    public void appendExternal(
                            SessionKey key,
                            io.deepscope.core.session.log.SessionEventData data,
                            String turnId,
                            RuntimeContext context) {
                        assertEquals("tenant-space", context.get("namespace"));
                        backing.appendExternal(key, data, turnId, context);
                    }

                    public io.deepscope.core.session.log.SessionLog readKernel(
                            SessionKey key, RuntimeContext context) {
                        assertEquals("tenant-space", context.get("namespace"));
                        return backing.readKernel(key, context);
                    }

                    public String owner(SessionKey key, RuntimeContext context) {
                        assertEquals("tenant-space", context.get("namespace"));
                        return backing.owner(key, context);
                    }
                };
        var ctx = context();
        ctx.put("namespace", "tenant-space");
        var agent =
                ReActAgent.builder()
                        .name("stable")
                        .model(new CaptureModel())
                        .sessionLogStore(routed)
                        .build();
        agent.updateAgentState(ctx, "setup", state -> state.setSummary("routed"));
        assertEquals("routed", agent.inspectAgentState(ctx).state().getSummary());
        agent.getAgentState(ctx).setSummary("edited");
        agent.saveAgentState(ctx);
        agent.setPermissionMode(ctx, PermissionMode.BYPASS);
        agent.clearContext(ctx);
        assertEquals(
                PermissionMode.BYPASS,
                agent.inspectAgentState(ctx).state().getPermissionContext().getMode());
    }

    @Test
    void failedDispatchCheckpointPreventsModelInvocation(@TempDir Path directory) {
        var model = new CaptureModel();
        var store =
                new NativeSessionLogStore(failingOnModelDispatch(new LocalSessionStore(directory)));
        var agent = ReActAgent.builder().name("stable").model(model).sessionLogStore(store).build();
        assertThrows(
                RuntimeException.class,
                () ->
                        agent.call(List.of(new UserMessage("x")), context())
                                .block(Duration.ofSeconds(10)));
        assertTrue(model.calls.isEmpty());
    }

    /** A journal store that refuses the model-dispatch append, as a persistence failure would. */
    private static SessionJournalStore failingOnModelDispatch(SessionJournalStore delegate) {
        return new SessionJournalStore() {
            @Override
            public void create(String sessionId) {
                delegate.create(sessionId);
            }

            @Override
            public KernelJournal open(String sessionId, Access access) {
                KernelJournal journal = delegate.open(sessionId, access);
                return new KernelJournal() {
                    @Override
                    public String sessionId() {
                        return journal.sessionId();
                    }

                    @Override
                    public boolean isWritable() {
                        return journal.isWritable();
                    }

                    @Override
                    public List<SessionEvent> committed() {
                        return journal.committed();
                    }

                    @Override
                    public void commitValidated(SessionEvent event) {
                        if ("model/dispatch".equals(event.type())) {
                            throw new SessionLogException("injected");
                        }
                        journal.commitValidated(event);
                    }

                    @Override
                    public void flush() {
                        journal.flush();
                    }

                    @Override
                    public void close() {
                        journal.close();
                    }
                };
            }

            @Override
            public List<String> list() {
                return delegate.list();
            }
        };
    }
}
