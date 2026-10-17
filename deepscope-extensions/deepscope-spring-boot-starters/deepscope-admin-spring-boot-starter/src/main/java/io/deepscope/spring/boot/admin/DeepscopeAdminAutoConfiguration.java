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
package io.deepscope.spring.boot.admin;

import io.deepscope.core.agent.Agent;
import io.deepscope.core.agent.AgentBase;
import io.deepscope.core.hook.Hook;
import io.deepscope.core.model.Model;
import io.deepscope.core.state.AgentStateStore;
import io.deepscope.core.tool.Toolkit;
import io.deepscope.harness.agent.middleware.SubagentEntry;
import io.deepscope.harness.agent.subagent.SubagentDeclaration;
import io.deepscope.harness.agent.subagent.task.TaskRepository;
import io.deepscope.spring.boot.admin.audit.AdminAuditLogger;
import io.deepscope.spring.boot.admin.command.AdminCommandRegistry;
import io.deepscope.spring.boot.admin.command.BuiltinCommandRegistrar;
import io.deepscope.spring.boot.admin.controller.SessionAdminController;
import io.deepscope.spring.boot.admin.controller.SubagentTaskController;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeAgentsEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeCommandsEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeDoctorEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeDrainEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeModelsEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopePermissionsEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeShutdownEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeStatusEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeSubagentsEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeToolsEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeUsageEndpoint;
import io.deepscope.spring.boot.admin.metrics.MetricsHook;
import io.deepscope.spring.boot.admin.metrics.MetricsRecorder;
import io.deepscope.spring.boot.admin.openapi.AdminOpenApiConfiguration;
import io.deepscope.spring.boot.admin.properties.AdminProperties;
import io.deepscope.spring.boot.admin.registry.AgentRegistry;
import io.deepscope.spring.boot.admin.registry.InMemoryAgentRegistry;
import io.deepscope.spring.boot.admin.service.AgentInventory;
import io.deepscope.spring.boot.admin.service.ModelSummarizationStrategy;
import io.deepscope.spring.boot.admin.service.SessionOperations;
import io.deepscope.spring.boot.admin.service.SubagentTaskOperations;
import io.deepscope.spring.boot.admin.service.SummarizationStrategy;
import io.deepscope.spring.boot.admin.snapshot.SnapshotStore;
import io.deepscope.spring.boot.admin.subagent.SpringSubagentInventory;
import io.deepscope.spring.boot.admin.subagent.SubagentInventory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configuration for the DeepScope admin/ops starter.
 *
 * <p>Activation rules:
 *
 * <ul>
 *   <li>{@code deepscope.admin.enabled=true} — master switch (defaults to false so this starter
 *       never accidentally exposes admin endpoints just by being on the classpath).
 *   <li>{@link Agent} on the classpath — guards against running without deepscope-core.
 * </ul>
 *
 * <p>Web controllers register only when a Servlet web application is detected
 * ({@link ConditionalOnWebApplication}). Actuator endpoints register only when the Actuator
 * {@code Endpoint} type is on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(Agent.class)
@ConditionalOnProperty(prefix = "deepscope.admin", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AdminProperties.class)
@org.springframework.context.annotation.Import(AdminOpenApiConfiguration.class)
public class DeepscopeAdminAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(DeepscopeAdminAutoConfiguration.class);

    /**
     * Default {@link AgentRegistry}. Auto-seeded from any singleton {@link Agent} beans found in
     * the context — applications using prototype-scoped agents (the DeepScope default) must call
     * {@link AgentRegistry#register(Agent)} themselves when they create an agent.
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentRegistry deepscopeAgentRegistry(ObjectProvider<Map<String, Agent>> agentBeans) {
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        Map<String, Agent> beans = agentBeans.getIfAvailable();
        if (beans != null) {
            for (Map.Entry<String, Agent> entry : beans.entrySet()) {
                try {
                    registry.register(entry.getValue());
                } catch (RuntimeException e) {
                    log.warn(
                            "Failed to seed AgentRegistry from bean {}: {}",
                            entry.getKey(),
                            e.toString());
                }
            }
            log.info("Seeded AgentRegistry with {} singleton agent bean(s)", beans.size());
        }
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminCommandRegistry deepscopeAdminCommandRegistry() {
        return new AdminCommandRegistry();
    }

    @Bean
    public BuiltinCommandRegistrar deepscopeBuiltinCommandRegistrar(
            AdminCommandRegistry registry, AdminProperties properties) {
        return new BuiltinCommandRegistrar(registry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminAuditLogger deepscopeAdminAuditLogger(
            AdminProperties properties, ApplicationEventPublisher publisher) {
        return new AdminAuditLogger(properties, publisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public SummarizationStrategy deepscopeSummarizationStrategy() {
        return new ModelSummarizationStrategy();
    }

    @Bean
    @ConditionalOnMissingBean
    public SnapshotStore deepscopeSnapshotStore() {
        return new SnapshotStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionOperations deepscopeSessionOperations(
            AgentRegistry registry,
            SummarizationStrategy summarizer,
            AdminProperties properties,
            SnapshotStore snapshots) {
        return new SessionOperations(registry, summarizer, properties, snapshots);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentInventory deepscopeAgentInventory(
            AgentRegistry registry,
            ObjectProvider<Map<String, Toolkit>> toolkits,
            ObjectProvider<Map<String, Model>> models) {
        return new AgentInventory(registry, toolkits, models);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubagentInventory deepscopeSubagentInventory(
            ObjectProvider<Map<String, SubagentEntry>> entryBeans,
            ObjectProvider<Map<String, SubagentDeclaration>> declarationBeans) {
        return new SpringSubagentInventory(entryBeans, declarationBeans);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubagentTaskOperations deepscopeSubagentTaskOperations(
            ObjectProvider<TaskRepository> repoProvider) {
        return new SubagentTaskOperations(repoProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public MetricsRecorder deepscopeMetricsRecorder() {
        return new MetricsRecorder();
    }

    /**
     * Registers a {@link MetricsHook} into {@link AgentBase#addSystemHook(Hook)}
     * at startup so every subsequently constructed agent contributes token-usage counters.
     *
     * <p>The hook is unregistered on context close, so reloading the starter does not double-count.
     */
    @Bean
    @ConditionalOnMissingBean
    public MetricsHookLifecycle deepscopeMetricsHookLifecycle(MetricsRecorder recorder) {
        return new MetricsHookLifecycle(new MetricsHook(recorder));
    }

    /** Tiny lifecycle bean — wires/unwires the metrics hook with the application context. */
    public static final class MetricsHookLifecycle {

        private static final Logger logger = LoggerFactory.getLogger(MetricsHookLifecycle.class);

        private final MetricsHook hook;

        public MetricsHookLifecycle(MetricsHook hook) {
            this.hook = hook;
        }

        @PostConstruct
        public void register() {
            AgentBase.addSystemHook(hook);
            logger.info(
                    "Registered MetricsHook as AgentBase system hook (applies to agents constructed"
                            + " after this point)");
        }

        @PreDestroy
        public void unregister() {
            AgentBase.removeSystemHook(hook);
        }

        public MetricsHook hook() {
            return hook;
        }
    }

    /**
     * Data-plane REST surface. Only loaded for Servlet web applications.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    public static class WebMvcConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SessionAdminController deepscopeSessionAdminController(
                SessionOperations ops,
                ObjectProvider<AgentStateStore> sessions,
                AdminAuditLogger audit,
                AdminProperties properties) {
            return new SessionAdminController(ops, sessions, audit, properties);
        }

        @Bean
        @ConditionalOnMissingBean
        public SubagentTaskController deepscopeSubagentTaskController(
                SubagentTaskOperations ops, AdminAuditLogger audit, AdminProperties properties) {
            return new SubagentTaskController(ops, audit, properties);
        }
    }

    /**
     * Control-plane Actuator endpoints. Loaded only when Spring Boot Actuator's
     * {@code @Endpoint} type is on the classpath; each endpoint registration still respects the
     * standard {@code management.endpoint.<id>.enabled} / {@code management.endpoints.web.exposure}
     * conventions.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    public static class ActuatorEndpointsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public DeepscopeStatusEndpoint deepscopeStatusEndpoint(
                AgentInventory inventory, AdminProperties properties) {
            return new DeepscopeStatusEndpoint(inventory, properties);
        }

        @Bean
        @ConditionalOnMissingBean
        public DeepscopeAgentsEndpoint deepscopeAgentsEndpoint(AgentInventory inventory) {
            return new DeepscopeAgentsEndpoint(inventory);
        }

        @Bean
        @ConditionalOnMissingBean
        public DeepscopeToolsEndpoint deepscopeToolsEndpoint(AgentInventory inventory) {
            return new DeepscopeToolsEndpoint(inventory);
        }

        @Bean
        @ConditionalOnMissingBean
        public DeepscopeModelsEndpoint deepscopeModelsEndpoint(AgentInventory inventory) {
            return new DeepscopeModelsEndpoint(inventory);
        }

        @Bean
        @ConditionalOnMissingBean
        public DeepscopeCommandsEndpoint deepscopeCommandsEndpoint(AdminCommandRegistry registry) {
            return new DeepscopeCommandsEndpoint(registry);
        }

        @Bean
        @ConditionalOnMissingBean
        public DeepscopeDoctorEndpoint deepscopeDoctorEndpoint(
                AdminProperties properties,
                AgentInventory inventory,
                ObjectProvider<AgentStateStore> sessions) {
            return new DeepscopeDoctorEndpoint(properties, inventory, sessions);
        }

        @Bean
        @ConditionalOnMissingBean
        public DeepscopeUsageEndpoint deepscopeUsageEndpoint(MetricsRecorder recorder) {
            return new DeepscopeUsageEndpoint(recorder);
        }

        @Bean
        @ConditionalOnMissingBean
        public DeepscopePermissionsEndpoint deepscopePermissionsEndpoint(AgentRegistry registry) {
            return new DeepscopePermissionsEndpoint(registry);
        }

        @Bean
        @ConditionalOnMissingBean
        public DeepscopeDrainEndpoint deepscopeDrainEndpoint(
                AdminProperties properties, AdminAuditLogger audit) {
            return new DeepscopeDrainEndpoint(properties, audit);
        }

        @Bean
        @ConditionalOnMissingBean
        public DeepscopeShutdownEndpoint deepscopeShutdownEndpoint(
                AdminProperties properties, AdminAuditLogger audit, ApplicationContext context) {
            return new DeepscopeShutdownEndpoint(properties, audit, context);
        }

        @Bean
        @ConditionalOnMissingBean
        public DeepscopeSubagentsEndpoint deepscopeSubagentsEndpoint(SubagentInventory inventory) {
            return new DeepscopeSubagentsEndpoint(inventory);
        }
    }
}
