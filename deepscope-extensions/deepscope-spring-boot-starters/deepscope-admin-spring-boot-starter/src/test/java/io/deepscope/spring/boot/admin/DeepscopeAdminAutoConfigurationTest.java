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

import static org.assertj.core.api.Assertions.assertThat;

import io.deepscope.spring.boot.admin.audit.AdminAuditLogger;
import io.deepscope.spring.boot.admin.command.AdminCommandRegistry;
import io.deepscope.spring.boot.admin.controller.SessionAdminController;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeCommandsEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeDoctorEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeDrainEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopePermissionsEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeShutdownEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeStatusEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeSubagentsEndpoint;
import io.deepscope.spring.boot.admin.endpoint.DeepscopeUsageEndpoint;
import io.deepscope.spring.boot.admin.metrics.MetricsRecorder;
import io.deepscope.spring.boot.admin.properties.AdminProperties;
import io.deepscope.spring.boot.admin.registry.AgentRegistry;
import io.deepscope.spring.boot.admin.service.AgentInventory;
import io.deepscope.spring.boot.admin.service.SessionOperations;
import io.deepscope.spring.boot.admin.service.SubagentTaskOperations;
import io.deepscope.spring.boot.admin.service.SummarizationStrategy;
import io.deepscope.spring.boot.admin.snapshot.SnapshotStore;
import io.deepscope.spring.boot.admin.subagent.SubagentInventory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class DeepscopeAdminAutoConfigurationTest {

    @Test
    void disabledByDefault() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DeepscopeAdminAutoConfiguration.class))
                .run(
                        ctx -> {
                            assertThat(ctx).doesNotHaveBean(AgentRegistry.class);
                            assertThat(ctx).doesNotHaveBean(AdminCommandRegistry.class);
                            assertThat(ctx).doesNotHaveBean(SessionOperations.class);
                        });
    }

    @Test
    void coreBeansLoadWhenEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DeepscopeAdminAutoConfiguration.class))
                .withPropertyValues("deepscope.admin.enabled=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasSingleBean(AdminProperties.class);
                            assertThat(ctx).hasSingleBean(AgentRegistry.class);
                            assertThat(ctx).hasSingleBean(AdminCommandRegistry.class);
                            assertThat(ctx).hasSingleBean(AdminAuditLogger.class);
                            assertThat(ctx).hasSingleBean(SummarizationStrategy.class);
                            assertThat(ctx).hasSingleBean(SessionOperations.class);
                            assertThat(ctx).hasSingleBean(AgentInventory.class);
                            assertThat(ctx).hasSingleBean(SnapshotStore.class);
                            assertThat(ctx).hasSingleBean(SubagentInventory.class);
                            assertThat(ctx).hasSingleBean(SubagentTaskOperations.class);
                            // No web context here, so the controller should NOT be loaded.
                            assertThat(ctx).doesNotHaveBean(SessionAdminController.class);
                        });
    }

    @Test
    void builtinCommandsAreRegistered() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DeepscopeAdminAutoConfiguration.class))
                .withPropertyValues("deepscope.admin.enabled=true")
                .run(
                        ctx -> {
                            AdminCommandRegistry registry = ctx.getBean(AdminCommandRegistry.class);
                            // 26 built-ins (15 data plane + 11 control plane)
                            assertThat(registry.list()).hasSizeGreaterThanOrEqualTo(26);
                            assertThat(registry.find("session.compact")).isPresent();
                            assertThat(registry.find("session.undo")).isPresent();
                            assertThat(registry.find("session.redo")).isPresent();
                            assertThat(registry.find("session.plan")).isPresent();
                            assertThat(registry.find("session.enter_plan_mode")).isPresent();
                            assertThat(registry.find("session.exit_plan_mode")).isPresent();
                            assertThat(registry.find("session.agent_tasks")).isPresent();
                            assertThat(registry.find("subagent.task.list")).isPresent();
                            assertThat(registry.find("subagent.task.get")).isPresent();
                            assertThat(registry.find("subagent.task.cancel")).isPresent();
                            assertThat(registry.find("system.status")).isPresent();
                            assertThat(registry.find("system.commands")).isPresent();
                            assertThat(registry.find("system.drain")).isPresent();
                            assertThat(registry.find("system.shutdown")).isPresent();
                            assertThat(registry.find("system.usage")).isPresent();
                            assertThat(registry.find("system.permissions")).isPresent();
                            assertThat(registry.find("system.subagents")).isPresent();
                        });
    }

    @Test
    void actuatorEndpointsRegisterWhenActuatorPresent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DeepscopeAdminAutoConfiguration.class))
                .withPropertyValues("deepscope.admin.enabled=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasSingleBean(DeepscopeStatusEndpoint.class);
                            assertThat(ctx).hasSingleBean(DeepscopeCommandsEndpoint.class);
                            assertThat(ctx).hasSingleBean(DeepscopeDoctorEndpoint.class);
                            assertThat(ctx).hasSingleBean(DeepscopeUsageEndpoint.class);
                            assertThat(ctx).hasSingleBean(DeepscopePermissionsEndpoint.class);
                            assertThat(ctx).hasSingleBean(DeepscopeDrainEndpoint.class);
                            assertThat(ctx).hasSingleBean(DeepscopeShutdownEndpoint.class);
                            assertThat(ctx).hasSingleBean(DeepscopeSubagentsEndpoint.class);
                            assertThat(ctx).hasSingleBean(MetricsRecorder.class);
                        });
    }

    @Test
    void controllerRegistersInWebApplication() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DeepscopeAdminAutoConfiguration.class))
                .withPropertyValues("deepscope.admin.enabled=true")
                .run(
                        ctx -> {
                            assertThat(ctx).hasSingleBean(SessionAdminController.class);
                        });
    }

    @Test
    void basePathProperty() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DeepscopeAdminAutoConfiguration.class))
                .withPropertyValues(
                        "deepscope.admin.enabled=true", "deepscope.admin.base-path=/ops/admin/")
                .run(
                        ctx -> {
                            AdminProperties props = ctx.getBean(AdminProperties.class);
                            assertThat(props.getBasePath()).isEqualTo("/ops/admin");
                        });
    }
}
