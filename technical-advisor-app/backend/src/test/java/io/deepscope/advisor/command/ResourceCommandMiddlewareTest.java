/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deepscope.advisor.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.db.AdvisorSchema;
import io.deepscope.advisor.skill.SkillCatalog;
import io.deepscope.advisor.tool.ToolCatalog;
import io.deepscope.advisor.tool.ToolImport;
import io.deepscope.core.agent.Agent;
import io.deepscope.core.agent.RuntimeContext;
import io.deepscope.core.message.Msg;
import io.deepscope.core.message.MsgRole;
import io.deepscope.core.middleware.AgentInput;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import reactor.core.publisher.Flux;

class ResourceCommandMiddlewareTest {
    @TempDir Path root;
    private ToolCatalog tools;
    private SkillCatalog skills;
    private ResourceCommandMiddleware middleware;
    private Agent agent;
    private final RuntimeContext context = RuntimeContext.builder().userId("alice").build();

    @BeforeEach
    void setUp() throws Exception {
        var source =
                new SimpleDriverDataSource(
                        new Driver(),
                        "jdbc:h2:mem:slash-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        AdvisorSchema.initialize(source);
        tools = new ToolCatalog(source, new ObjectMapper());
        skills = new SkillCatalog(source, root);
        middleware = new ResourceCommandMiddleware(new CommandCatalog(source), tools, skills);
        agent = mock(Agent.class);
        when(agent.getAgentId()).thenReturn("agent-a");
    }

    @Test
    void selectedAskToolResolvesWithoutBypassingRuntimeExecutionOrApproval() throws Exception {
        var tool =
                tools.importTools(
                                "alice",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "weather",
                                                "Weather",
                                                "{}",
                                                "http",
                                                "GET",
                                                "https://example.com/weather")),
                                "ask")
                        .get(0);
        tools.replaceSelection("agent-a", List.of(tool.toolId()), List.of());
        var transformed = invoke("/tool-" + tool.toolId() + " Check Shanghai");
        String text = transformed.msgs().get(0).getTextContent();
        assertTrue(text.contains("weather"));
        assertTrue(text.contains("Check Shanghai"));
        assertFalse(text.startsWith("/tool-"));
        verify(agent, never()).getToolkit();
        tools.updatePermission(tool.toolId(), "deny");
        assertThrows(RuntimeException.class, () -> invoke("/tool-" + tool.toolId()));
    }

    @Test
    void foreignOrUnselectedResourcesFailBeforeReachingTheModel() throws Exception {
        var tool =
                tools.importTools(
                                "alice",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "weather",
                                                "Weather",
                                                "{}",
                                                "http",
                                                "GET",
                                                "https://example.com/weather")),
                                "allow")
                        .get(0);
        assertThrows(RuntimeException.class, () -> invoke("/tool-" + tool.toolId()));
        tools.replaceSelection("agent-a", List.of(tool.toolId()), List.of());
        var input = input("/tool-" + tool.toolId());
        assertThrows(
                RuntimeException.class,
                () ->
                        middleware
                                .onAgent(
                                        agent,
                                        RuntimeContext.builder().userId("bob").build(),
                                        input,
                                        next -> {
                                            fail("must not reach the model");
                                            return Flux.empty();
                                        })
                                .blockLast());
    }

    @Test
    void publishedSelectedSkillResolvesAndUnpublishRevokesIt() throws Exception {
        var skill =
                skills.createDraft(
                        "alice",
                        "review",
                        "Review",
                        "---\nname: review\ndescription: Review\n---\nReview the code");
        skills.publish(skill);
        skills.replaceSelection("agent-a", List.of(skill.skillId()));
        String text =
                invoke("/skill-" + skill.skillId() + " Check this diff")
                        .msgs()
                        .get(0)
                        .getTextContent();
        assertTrue(text.contains("review"));
        assertTrue(text.contains("Check this diff"));
        skills.unpublish(skills.find(skill.skillId()).orElseThrow());
        assertThrows(RuntimeException.class, () -> invoke("/skill-" + skill.skillId()));
    }

    @Test
    void ordinaryMessagesAndUnknownNonResourceCommandsStayUnchanged() {
        assertEquals("/some/path", invoke("/some/path").msgs().get(0).getTextContent());
        assertEquals("Hello", invoke("Hello").msgs().get(0).getTextContent());
    }

    @Test
    void sameNamedBindingsAreRejectedInsteadOfCallingTheWrongResource() throws Exception {
        var first =
                tools.importTools(
                                "alice",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "weather",
                                                "First",
                                                "{}",
                                                "http",
                                                "GET",
                                                "https://example.com/first")),
                                "allow")
                        .get(0);
        var second =
                tools.importTools(
                                "alice",
                                List.of(
                                        new ToolImport.ImportedTool(
                                                "weather",
                                                "Second",
                                                "{}",
                                                "http",
                                                "GET",
                                                "https://example.com/second")),
                                "allow")
                        .get(0);
        tools.replaceSelection("agent-a", List.of(first.toolId(), second.toolId()), List.of());
        var error = assertThrows(RuntimeException.class, () -> invoke("/tool-" + first.toolId()));
        assertTrue(error.getMessage().contains("resource_command_ambiguous"));
    }

    private AgentInput invoke(String text) {
        var captured = new AtomicReference<AgentInput>();
        middleware
                .onAgent(
                        agent,
                        context,
                        input(text),
                        value -> {
                            captured.set(value);
                            return Flux.empty();
                        })
                .blockLast();
        return captured.get();
    }

    private static AgentInput input(String text) {
        return new AgentInput(List.of(Msg.builder().role(MsgRole.USER).textContent(text).build()));
    }
}
