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
package io.agentscope.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.advisor.agent.AgentCreationDraftStore;
import io.agentscope.advisor.agent.AgentCreationOperationStore;
import io.agentscope.advisor.agent.AgentCreationService;
import io.agentscope.advisor.agent.AgentPreviewService;
import io.agentscope.advisor.agent.AgentPreviewStore;
import io.agentscope.advisor.agent.AgentProvisioner;
import io.agentscope.advisor.agent.AgentStore;
import io.agentscope.advisor.agent.AgentWorkspace;
import io.agentscope.advisor.agent.BehaviorDraftService;
import io.agentscope.advisor.auth.ApiAuthenticationFilter;
import io.agentscope.advisor.session.ReportActions;
import io.agentscope.advisor.session.SessionStore;
import io.agentscope.advisor.session.SessionTitleService;
import io.agentscope.advisor.session.TimelineStore;
import io.agentscope.advisor.skill.SkillCatalog;
import io.agentscope.advisor.skill.SkillSync;
import io.agentscope.advisor.tool.ToolCatalog;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import io.agentscope.core.model.Model;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Assembles the technical advisor app.
 *
 * <p>The model bean comes from the OpenAI-protocol starter. Since stage 4 the agents themselves are
 * no longer singleton beans: definitions live in PostgreSQL and {@link AgentProvisioner} builds a
 * {@code HarnessAgent} per row at startup (and on demand for the REST layer), registering each
 * one in the AG-UI registry under its agent id. Builtin and personal agents share the report
 * action protocol, so every conversation stays visible and replayable.
 *
 * <p>Stage 2 wiring is kept: each AG-UI run is attributed to the logged-in account, so agent state
 * stays per-user.
 */
@Configuration
public class AdvisorConfiguration {

    @Bean
    public ObjectMapper advisorObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public io.agentscope.advisor.agent.BasicChatRuntime basicChatRuntime(
            AdvisorProperties properties,
            Model model,
            SessionStore sessions,
            TimelineStore timeline,
            ObjectMapper advisorObjectMapper,
            AguiAgentRegistry registry,
            io.agentscope.advisor.command.ResourceCommandMiddleware commands) {
        return new io.agentscope.advisor.agent.BasicChatRuntime(
                properties, model, sessions, timeline, advisorObjectMapper, registry, commands);
    }

    public io.agentscope.advisor.agent.BasicChatRuntime basicChatRuntime(
            AdvisorProperties properties,
            Model model,
            SessionStore sessions,
            TimelineStore timeline,
            ObjectMapper advisorObjectMapper,
            AguiAgentRegistry registry) {
        return new io.agentscope.advisor.agent.BasicChatRuntime(
                properties, model, sessions, timeline, advisorObjectMapper, registry);
    }

    @Bean
    public SessionStore sessionStore(DataSource dataSource) {
        return new SessionStore(dataSource);
    }

    @Bean
    public TimelineStore timelineStore(
            DataSource dataSource, SessionStore sessions, ObjectMapper advisorObjectMapper) {
        return new TimelineStore(dataSource, sessions, advisorObjectMapper);
    }

    /** Auto-names a session after its first advisor reply (Claude/Codex style). */
    @Bean
    public SessionTitleService sessionTitleService(
            Model model,
            SessionStore sessions,
            TimelineStore timeline,
            ObjectMapper advisorObjectMapper) {
        return new SessionTitleService(model, sessions, timeline, advisorObjectMapper);
    }

    @Bean
    public AgentStore agentStore(DataSource dataSource, AdvisorProperties properties) {
        return new AgentStore(dataSource, properties.agentsRoot());
    }

    @Bean
    public AgentCreationDraftStore agentCreationDraftStore(DataSource dataSource) {
        return new AgentCreationDraftStore(dataSource);
    }

    @Bean
    public AgentCreationOperationStore agentCreationOperationStore(DataSource dataSource) {
        return new AgentCreationOperationStore(dataSource);
    }

    @Bean
    public org.springframework.boot.ApplicationRunner agentCreationStartup(
            AgentCreationOperationStore operations) {
        return args -> operations.markInterruptedOnStartup();
    }

    @Bean
    public AgentPreviewStore agentPreviewStore(DataSource dataSource) {
        return new AgentPreviewStore(dataSource);
    }

    @Bean
    public AgentPreviewService agentPreviewService(
            AgentPreviewStore previewStore,
            AgentCreationDraftStore drafts,
            Model model,
            ObjectMapper advisorObjectMapper) {
        return new AgentPreviewService(previewStore, drafts, model, advisorObjectMapper);
    }

    @Bean
    public AgentCreationService agentCreationService(
            AgentCreationDraftStore drafts,
            AgentCreationOperationStore operations,
            AgentStore agents,
            BehaviorDraftService behaviorDraftService,
            io.agentscope.advisor.access.AccessGuard accessGuard,
            @org.springframework.context.annotation.Lazy AgentProvisioner agentProvisioner,
            ObjectMapper advisorObjectMapper) {
        return new AgentCreationService(
                drafts,
                operations,
                agents,
                new AgentWorkspace(),
                behaviorDraftService,
                accessGuard,
                agentProvisioner,
                advisorObjectMapper);
    }

    /**
     * Provisions every stored agent into the AG-UI registry at startup; the REST layer reuses it
     * so created/edited agents go live without a restart.
     */
    @Bean
    public BehaviorDraftService behaviorDraftService(Model model) {
        return new BehaviorDraftService(model);
    }

    @Bean
    public io.agentscope.advisor.skill.SkillDraftService skillDraftService(Model model) {
        return new io.agentscope.advisor.skill.SkillDraftService(model);
    }

    @Bean
    public ToolCatalog toolCatalog(DataSource dataSource, ObjectMapper advisorObjectMapper) {
        return new ToolCatalog(dataSource, advisorObjectMapper);
    }

    @Bean
    public io.agentscope.advisor.tool.McpToolSource mcpToolSource() {
        return new io.agentscope.advisor.tool.FrameworkMcpToolSource();
    }

    @Bean
    public io.agentscope.advisor.tool.McpUpdateService mcpUpdateService(
            ToolCatalog catalog,
            io.agentscope.advisor.tool.McpToolSource mcpToolSource,
            ObjectMapper advisorObjectMapper) {
        return new io.agentscope.advisor.tool.McpUpdateService(
                catalog, mcpToolSource, advisorObjectMapper);
    }

    @Bean
    public io.agentscope.advisor.tool.ToolCredentialStore toolCredentialStore(
            DataSource dataSource,
            ObjectMapper advisorObjectMapper,
            io.agentscope.advisor.tool.ToolProperties toolProperties) {
        return new io.agentscope.advisor.tool.ToolCredentialStore(
                advisorObjectMapper, toolProperties.credentialKey(), dataSource);
    }

    @Bean
    public AgentProvisioner agentProvisioner(
            AgentStore agents,
            Model model,
            ReportActions reportActions,
            AguiAgentRegistry registry,
            ToolCatalog catalog,
            io.agentscope.advisor.tool.McpToolSource mcpToolSource,
            ObjectMapper advisorObjectMapper,
            SkillSync skillSync,
            io.agentscope.advisor.knowledge.KnowledgeSearch knowledgeSearch,
            io.agentscope.advisor.session.TimelineStore timeline,
            AdvisorProperties properties,
            io.agentscope.advisor.tool.ToolCredentialStore toolCredentialStore,
            io.agentscope.advisor.command.ResourceCommandMiddleware commands) {
        var provisioner =
                new AgentProvisioner(
                        agents,
                        model,
                        reportActions,
                        registry,
                        catalog,
                        mcpToolSource,
                        advisorObjectMapper,
                        skillSync,
                        knowledgeSearch,
                        timeline,
                        properties.workspaceDir(),
                        toolCredentialStore);
        provisioner.setCommandMiddleware(commands);
        return provisioner;
    }

    @Bean
    public io.agentscope.advisor.tool.ToolRuntimeCoordinator toolRuntimeCoordinator(
            ToolCatalog catalog,
            AgentStore agents,
            AgentProvisioner agentProvisioner,
            DataSource dataSource,
            ObjectMapper advisorObjectMapper) {
        var coordinator =
                new io.agentscope.advisor.tool.ToolRuntimeCoordinator(
                        catalog, agents, agentProvisioner, dataSource, advisorObjectMapper);
        agentProvisioner.setCoordinator(coordinator);
        return coordinator;
    }

    @Bean
    public io.agentscope.advisor.knowledge.KnowledgeCatalog knowledgeCatalog(
            DataSource dataSource) {
        return new io.agentscope.advisor.knowledge.KnowledgeCatalog(dataSource);
    }

    @Bean
    public io.agentscope.advisor.knowledge.VectorModelSource localVectorModelSource(
            io.agentscope.advisor.knowledge.EmbeddingProperties embeddingProperties) {
        return new io.agentscope.advisor.knowledge.ConfiguredVectorModelSource(embeddingProperties);
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public io.agentscope.advisor.knowledge.VectorModelSource vectorModelSource(
            io.agentscope.advisor.model.ModelCatalog modelCatalog,
            io.agentscope.advisor.model.OpenAiEndpointClient endpointClient,
            io.agentscope.advisor.knowledge.VectorModelSource localVectorModelSource) {
        return new io.agentscope.advisor.model.SavedVectorModelSource(
                modelCatalog, endpointClient, localVectorModelSource);
    }

    @Bean
    public io.agentscope.advisor.model.ModelCatalog modelCatalog(DataSource dataSource) {
        return new io.agentscope.advisor.model.ModelCatalog(dataSource);
    }

    @Bean
    public io.agentscope.advisor.model.OpenAiEndpointClient endpointClient() {
        return new io.agentscope.advisor.model.OpenAiEndpointClient();
    }

    @Bean
    public io.agentscope.advisor.model.ChatModelSource chatModelSource(
            io.agentscope.advisor.model.ModelCatalog modelCatalog,
            io.agentscope.advisor.model.OpenAiEndpointClient endpointClient,
            SessionStore sessions) {
        return new io.agentscope.advisor.model.SavedChatModelSource(
                modelCatalog, endpointClient, sessions);
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public io.agentscope.core.model.Model chatModel(
            io.agentscope.advisor.model.ChatModelSource chatModelSource,
            io.agentscope.spring.boot.openai.OpenAIProperties openAIProperties,
            io.agentscope.advisor.model.ModelCatalog modelCatalog) {
        String modelName = openAIProperties.getModelName();
        io.agentscope.extensions.model.openai.OpenAIChatModel.Builder builder =
                io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
                        .apiKey(openAIProperties.getApiKey())
                        .modelName(modelName == null || modelName.isBlank() ? "local" : modelName);
        if (openAIProperties.getBaseUrl() != null && !openAIProperties.getBaseUrl().isBlank()) {
            builder.baseUrl(openAIProperties.getBaseUrl());
        }
        // Layer 1 fix: bound model HTTP calls so a hung endpoint cannot block the run forever.
        builder.httpTransport(
                io.agentscope.core.model.transport.JdkHttpTransport.builder()
                        .config(
                                io.agentscope.core.model.transport.HttpTransportConfig.builder()
                                        .connectTimeout(java.time.Duration.ofSeconds(10))
                                        .responseTimeout(java.time.Duration.ofSeconds(120))
                                        .streamIdleTimeout(java.time.Duration.ofSeconds(120))
                                        .readTimeout(java.time.Duration.ofSeconds(120))
                                        .build())
                        .build());
        return new io.agentscope.advisor.model.SwitchingChatModel(
                chatModelSource, builder.build(), modelCatalog);
    }

    @Bean
    public io.agentscope.advisor.model.UsageGuard usageGuard(
            io.agentscope.advisor.model.ModelCatalog modelCatalog, DataSource dataSource) {
        return new io.agentscope.advisor.model.UsageGuard(modelCatalog, dataSource);
    }

    /**
     * Adds the two facts the conversation page needs on top of the AG-UI protocol events: the tool
     * result state and the current model's context window. The AG-UI starter collects every
     * {@code AguiEventEnricher} bean into the adapter configuration, so the extension source stays
     * untouched.
     */
    @Bean
    public io.agentscope.core.agui.adapter.strategy.AguiEventEnricher advisoryAguiEventEnricher(
            Model model, TimelineStore timeline, SessionStore sessions) {
        return new io.agentscope.advisor.session.AdvisorAguiEventEnricher(
                model, timeline, sessions);
    }

    /**
     * Server-side last resort for rounds the frontend never persisted (refresh/disconnect/stop):
     * accumulates the AG-UI stream on the server and, after a grace delay, writes the round and
     * closes the session into the interrupted terminal state. Registered like the enricher above
     * so the AG-UI starter feeds it the same event stream.
     */
    @Bean(destroyMethod = "close")
    public io.agentscope.advisor.session.AguiRoundPersister aguiRoundPersister(
            TimelineStore timeline,
            SessionStore sessions,
            @org.springframework.context.annotation.Lazy
                    io.agentscope.advisor.tool.ToolRuntimeCoordinator toolRuntimeCoordinator,
            org.springframework.beans.factory.ObjectProvider<
                            io.agentscope.spring.boot.agui.mvc.AguiMvcController>
                    mvcProvider) {
        var persister = new io.agentscope.advisor.session.AguiRoundPersister(timeline, sessions);
        persister.setCoordinator(toolRuntimeCoordinator);
        if (mvcProvider != null) {
            persister.setActiveRunCheck(
                    session -> {
                        var mvc = mvcProvider.getIfAvailable();
                        return mvc != null && mvc.hasActiveRun(session);
                    });
            persister.setRecoveryGuard(
                    (session, operation) -> {
                        var mvc = mvcProvider.getIfAvailable();
                        return mvc != null && mvc.withIdleThread(session, operation);
                    });
        }
        return persister;
    }

    public io.agentscope.advisor.session.AguiRoundPersister aguiRoundPersister(
            TimelineStore timeline,
            SessionStore sessions,
            io.agentscope.advisor.tool.ToolRuntimeCoordinator coordinator) {
        return aguiRoundPersister(timeline, sessions, coordinator, null);
    }

    public io.agentscope.advisor.session.AguiRoundPersister aguiRoundPersister(
            TimelineStore timeline, SessionStore sessions) {
        return aguiRoundPersister(timeline, sessions, null);
    }

    /** One-click compaction of a whole session from the composer's context panel. */
    @Bean
    public io.agentscope.advisor.session.SessionCompactionService sessionCompactionService(
            SessionStore sessions,
            TimelineStore timeline,
            AguiAgentRegistry registry,
            ObjectMapper advisorObjectMapper,
            org.springframework.beans.factory.ObjectProvider<
                            io.agentscope.spring.boot.agui.mvc.AguiMvcController>
                    mvcProvider,
            org.springframework.beans.factory.ObjectProvider<
                            io.agentscope.advisor.session.AguiRoundPersister>
                    persisterProvider) {
        var service =
                new io.agentscope.advisor.session.SessionCompactionService(
                        sessions, timeline, registry, advisorObjectMapper);
        if (mvcProvider != null)
            service.setRunGuard(
                    (session, operation) -> {
                        var mvc = mvcProvider.getIfAvailable();
                        java.util.concurrent.atomic.AtomicBoolean executed =
                                new java.util.concurrent.atomic.AtomicBoolean();
                        if (mvc == null) return false;
                        mvc.withIdleThread(
                                session,
                                () -> {
                                    var persister =
                                            persisterProvider == null
                                                    ? null
                                                    : persisterProvider.getIfAvailable();
                                    if (persister != null && persister.hasPending(session)) return;
                                    operation.run();
                                    executed.set(true);
                                });
                        return executed.get();
                    });
        return service;
    }

    public io.agentscope.advisor.session.SessionCompactionService sessionCompactionService(
            SessionStore sessions,
            TimelineStore timeline,
            AguiAgentRegistry registry,
            ObjectMapper json) {
        return sessionCompactionService(sessions, timeline, registry, json, null, null);
    }

    @Bean
    public io.agentscope.advisor.knowledge.ChunkIndex chunkIndex(
            DataSource dataSource,
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.url}")
                    String jdbcUrl,
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.username}")
                    String username,
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.password:}")
                    String password) {
        return new io.agentscope.advisor.knowledge.PgChunkIndex(
                dataSource, jdbcUrl, username, password);
    }

    @Bean
    public io.agentscope.advisor.knowledge.PdfModelReader pdfModelReader(
            io.agentscope.advisor.model.ChatModelSource models,
            ObjectMapper json,
            io.agentscope.advisor.model.ModelCatalog catalog,
            @org.springframework.beans.factory.annotation.Value(
                            "${advisor.knowledge.pdf.vision-endpoint-id:}")
                    String visionEndpoint,
            @org.springframework.beans.factory.annotation.Value(
                            "${advisor.knowledge.pdf.rerank-endpoint-id:}")
                    String rerankEndpoint,
            @org.springframework.beans.factory.annotation.Value(
                            "${advisor.knowledge.pdf.json-output:false}")
                    boolean jsonOutput) {
        var reader =
                new io.agentscope.advisor.knowledge.PdfModelReader(
                        models, json, visionEndpoint, rerankEndpoint, catalog);
        reader.setJsonOutput(jsonOutput);
        return reader;
    }

    @Bean
    public io.agentscope.advisor.knowledge.PdfParser pdfParser(
            io.agentscope.advisor.knowledge.PdfModelReader models,
            @org.springframework.beans.factory.annotation.Value(
                            "${advisor.knowledge.pdf.max-pages:300}")
                    int maxPages,
            @org.springframework.beans.factory.annotation.Value(
                            "${advisor.knowledge.pdf.max-image-edge:2000}")
                    int maxImageEdge) {
        return new io.agentscope.advisor.knowledge.PdfParser(models, maxPages, maxImageEdge);
    }

    @Bean
    public io.agentscope.advisor.knowledge.DocumentIngest documentIngest(
            io.agentscope.advisor.knowledge.KnowledgeCatalog knowledgeCatalog,
            io.agentscope.advisor.knowledge.VectorModelSource vectorModelSource,
            io.agentscope.advisor.knowledge.ChunkIndex chunkIndex,
            io.agentscope.advisor.knowledge.PdfParser pdfParser,
            @org.springframework.beans.factory.annotation.Value(
                            "${advisor.knowledge.ingest.workers:2}")
                    int workers,
            @org.springframework.beans.factory.annotation.Value(
                            "${advisor.knowledge.ingest.queue-capacity:32}")
                    int capacity,
            @org.springframework.beans.factory.annotation.Value(
                            "${advisor.knowledge.ingest.timeout-minutes:20}")
                    int timeoutMinutes,
            @org.springframework.beans.factory.annotation.Value(
                            "${advisor.knowledge.ingest.embedding-workers:3}")
                    int embeddingWorkers) {
        return new io.agentscope.advisor.knowledge.DocumentIngest(
                knowledgeCatalog,
                vectorModelSource,
                chunkIndex,
                pdfParser,
                workers,
                capacity,
                java.time.Duration.ofMinutes(timeoutMinutes),
                embeddingWorkers);
    }

    @Bean
    public io.agentscope.advisor.knowledge.KnowledgeSearch knowledgeSearch(
            io.agentscope.advisor.knowledge.KnowledgeCatalog knowledgeCatalog,
            io.agentscope.advisor.knowledge.VectorModelSource vectorModelSource,
            io.agentscope.advisor.knowledge.ChunkIndex chunkIndex,
            io.agentscope.advisor.knowledge.PdfModelReader pdfModels,
            io.agentscope.advisor.knowledge.PdfParser pdfParser) {
        return new io.agentscope.advisor.knowledge.KnowledgeSearch(
                knowledgeCatalog, vectorModelSource, chunkIndex, pdfModels, pdfParser);
    }

    @Bean
    public io.agentscope.advisor.access.AccessCatalog accessCatalog(DataSource dataSource) {
        return new io.agentscope.advisor.access.AccessCatalog(dataSource);
    }

    @Bean
    public io.agentscope.advisor.access.AccessGuard accessGuard(
            io.agentscope.advisor.access.AccessCatalog accessCatalog) {
        return new io.agentscope.advisor.access.AccessGuard(accessCatalog);
    }

    @Bean
    public org.springframework.boot.ApplicationRunner accessStartup(
            io.agentscope.advisor.access.AccessCatalog accessCatalog) {
        return args -> accessCatalog.ensureBuiltin();
    }

    @Bean
    public org.springframework.boot.ApplicationRunner knowledgeStartup(
            io.agentscope.advisor.knowledge.KnowledgeCatalog knowledgeCatalog) {
        return args -> knowledgeCatalog.failInterrupted();
    }

    @Bean
    public io.agentscope.advisor.command.CommandCatalog commandCatalog(DataSource dataSource) {
        return new io.agentscope.advisor.command.CommandCatalog(dataSource);
    }

    @Bean
    public io.agentscope.advisor.command.ResourceCommandMiddleware resourceCommandMiddleware(
            io.agentscope.advisor.command.CommandCatalog commands,
            ToolCatalog tools,
            SkillCatalog skills) {
        return new io.agentscope.advisor.command.ResourceCommandMiddleware(commands, tools, skills);
    }

    @Bean
    public org.springframework.boot.ApplicationRunner commandStartup(
            io.agentscope.advisor.command.CommandCatalog commandCatalog) {
        return args -> commandCatalog.ensureBuiltin();
    }

    @Bean
    public SkillCatalog skillCatalog(DataSource dataSource, AdvisorProperties properties) {
        return new SkillCatalog(dataSource, properties.dataDir().resolve("skill-catalog"));
    }

    @Bean
    public SkillSync skillSync(SkillCatalog skillCatalog) {
        return new SkillSync(skillCatalog);
    }

    /**
     * Attributes each AG-UI run to the account resolved by {@link ApiAuthenticationFilter}, so the
     * agent runtime (state store, memory) is keyed per user for the same thread.
     */
    @Bean
    public AguiRuntimeContextResolver advisorRuntimeContextResolver(
            AgentStore agents,
            SessionStore sessions,
            SkillSync skillSync,
            io.agentscope.advisor.model.UsageGuard usageGuard,
            @org.springframework.context.annotation.Lazy
                    io.agentscope.advisor.tool.ToolRuntimeCoordinator toolRuntimeCoordinator) {
        return new AguiRuntimeContextResolver() {
            @Override
            public void admitted(AguiRuntimeContextRequest<?> request) {
                if (toolRuntimeCoordinator != null)
                    toolRuntimeCoordinator.claimRun(
                            request.getInput().getThreadId(), request.getInput().getRunId());
            }

            @Override
            public void validate(AguiRuntimeContextRequest<?> request) {
                resolveAdvisorContext(
                        request,
                        agents,
                        sessions,
                        skillSync,
                        usageGuard,
                        toolRuntimeCoordinator,
                        false);
            }

            @Override
            public RuntimeContext resolve(AguiRuntimeContextRequest<?> request) {
                return resolveAdvisorContext(
                        request,
                        agents,
                        sessions,
                        skillSync,
                        usageGuard,
                        toolRuntimeCoordinator,
                        true);
            }

            @Override
            public void release(AguiRuntimeContextRequest<?> request) {
                if (toolRuntimeCoordinator != null) {
                    toolRuntimeCoordinator.releaseLease(
                            request.getInput().getThreadId(), request.getInput().getRunId());
                }
            }
        };
    }

    private static RuntimeContext resolveAdvisorContext(
            AguiRuntimeContextRequest<?> request,
            AgentStore agents,
            SessionStore sessions,
            SkillSync skillSync,
            io.agentscope.advisor.model.UsageGuard usageGuard,
            io.agentscope.advisor.tool.ToolRuntimeCoordinator toolRuntimeCoordinator,
            boolean prepare) {
        if (request.getNativeRequest() instanceof HttpServletRequest httpRequest) {
            var account =
                    ApiAuthenticationFilter.account(httpRequest)
                            .orElseThrow(
                                    () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
            String agentId = request.getPathAgentId();
            if (agentId == null) agentId = request.getHeaderAgentId();
            try {
                var session =
                        sessions.find(request.getInput().getThreadId())
                                .filter(row -> account.userId().equals(row.ownerUserId()))
                                .orElseThrow(
                                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND));
                boolean basic =
                        io.agentscope.advisor.agent.BasicChatRuntime.AGENT_ID.equals(agentId);
                AgentStore.AgentRow agent = null;
                if (basic) {
                    if (session.agentId() != null)
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Session is locked to an agent");
                } else {
                    agent =
                            agents.find(agentId)
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND));
                    if (!agent.enabled() || !account.userId().equals(agent.ownerUserId()))
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    if (session.agentId() != null && !session.agentId().equals(agentId))
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Session is locked to another agent");
                }
                try {
                    usageGuard.check(account.userId());
                } catch (IllegalStateException limited) {
                    throw new ResponseStatusException(
                            HttpStatus.TOO_MANY_REQUESTS, limited.getMessage());
                }
                if (prepare && agent != null) {
                    skillSync.sync(agent);
                    if (toolRuntimeCoordinator != null) {
                        toolRuntimeCoordinator.acquireLease(
                                request.getInput().getThreadId(),
                                agent.agentId(),
                                request.getInput().getRunId());
                    }
                }
                Object thinkingProp = request.getInput().getForwardedProp("thinking");
                boolean deepThinking =
                        Boolean.TRUE.equals(thinkingProp)
                                || "true".equalsIgnoreCase(String.valueOf(thinkingProp));
                return RuntimeContext.builder()
                        .userId(account.userId())
                        .put("advisorSessionId", request.getInput().getThreadId())
                        .put(
                                io.agentscope.advisor.model.SwitchingChatModel.THINKING_KEY,
                                deepThinking)
                        .build();
            } catch (SQLException | java.io.IOException error) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Session storage unavailable", error);
            }
        }
        return null;
    }

    public AguiRuntimeContextResolver advisorRuntimeContextResolver(
            AgentStore agents,
            SessionStore sessions,
            SkillSync skillSync,
            io.agentscope.advisor.model.UsageGuard usageGuard) {
        return advisorRuntimeContextResolver(agents, sessions, skillSync, usageGuard, null);
    }
}
