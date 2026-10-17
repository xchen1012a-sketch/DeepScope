/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.deepscope.spring.boot.nacos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import io.deepscope.core.a2a.agent.card.AgentCardResolver;
import io.deepscope.core.a2a.server.registry.AgentRegistry;
import io.deepscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import io.deepscope.core.nacos.a2a.registry.NacosAgentRegistry;
import io.deepscope.spring.boot.nacos.properties.DeepScopeNacosProperties;
import io.deepscope.spring.boot.nacos.properties.a2a.DeepScopeA2aNacosProperties;
import io.deepscope.spring.boot.nacos.properties.a2a.NacosA2aDiscoveryProperties;
import io.deepscope.spring.boot.nacos.properties.a2a.NacosA2aRegistryProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tests for {@link DeepscopeA2aNacosAutoConfiguration}.
 *
 * <p>These tests verify that the auto-configuration creates the expected beans under different
 * property setups.
 */
class DeepscopeA2aNacosAutoConfigurationTest {

    private AiService mockAiService;

    @BeforeEach
    void setUp() {
        mockAiService = mock(AiService.class);
    }

    @Test
    void shouldCreateDefaultBeansWhenEnabled() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.a2a.nacos.enabled=true",
                            "deepscope.a2a.nacos.server-addr=127.0.0.1:8848",
                            "deepscope.a2a.nacos.namespace=public")
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(DeepScopeNacosProperties.class);
                                assertThat(context)
                                        .hasSingleBean(DeepScopeA2aNacosProperties.class);
                                assertThat(context).hasSingleBean(AgentCardResolver.class);
                                assertThat(context).hasSingleBean(AgentRegistry.class);
                            });
        }
    }

    @Test
    void shouldNotCreateNacosBeansWhenDisabled() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withPropertyValues("deepscope.a2a.nacos.enabled=false")
                    .run(
                            context -> {
                                assertThat(context).doesNotHaveBean(AgentCardResolver.class);
                                assertThat(context).doesNotHaveBean(AgentRegistry.class);
                            });
        }
    }

    @Test
    void shouldCreateNacosBeansWhenEnabledExplicitly() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withPropertyValues("deepscope.a2a.nacos.enabled=true")
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(AgentCardResolver.class);
                                assertThat(context).hasSingleBean(AgentRegistry.class);
                            });
        }
    }

    @Test
    void shouldCreateNacosAgentCardResolverBean() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.a2a.nacos.enabled=true",
                            "deepscope.a2a.nacos.server-addr=127.0.0.1:8848",
                            "deepscope.a2a.nacos.namespace=public")
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(AgentCardResolver.class);
                                assertThat(context.getBean(AgentCardResolver.class))
                                        .isInstanceOf(NacosAgentCardResolver.class);
                            });
        }
    }

    @Test
    void shouldCreateNacosAgentRegistryBean() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.a2a.nacos.enabled=true",
                            "deepscope.a2a.nacos.server-addr=127.0.0.1:8848",
                            "deepscope.a2a.nacos.namespace=public")
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(AgentRegistry.class);
                                assertThat(context.getBean(AgentRegistry.class))
                                        .isInstanceOf(NacosAgentRegistry.class);
                            });
        }
    }

    @Test
    void shouldNotCreateNacosAgentCardResolverWhenDiscoveryDisabled() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.a2a.nacos.enabled=true",
                            "deepscope.a2a.nacos.discovery.enabled=false")
                    .run(
                            context -> {
                                assertThat(context).doesNotHaveBean(AgentCardResolver.class);
                                assertThat(context).hasSingleBean(AgentRegistry.class);
                            });
        }
    }

    @Test
    void shouldNotCreateNacosAgentRegistryWhenRegistryDisabled() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.a2a.nacos.enabled=true",
                            "deepscope.a2a.nacos.registry.enabled=false")
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(AgentCardResolver.class);
                                assertThat(context).doesNotHaveBean(AgentRegistry.class);
                            });
        }
    }

    @Test
    void shouldBindNacosProperties() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.a2a.nacos.enabled=true",
                            "deepscope.a2a.nacos.server-addr=127.0.0.1:8848",
                            "deepscope.a2a.nacos.namespace=test-namespace",
                            "deepscope.a2a.nacos.username=test-user",
                            "deepscope.a2a.nacos.password=test-password",
                            "deepscope.a2a.nacos.registry.register-as-latest=false",
                            "deepscope.a2a.nacos.registry.enabled-register-endpoint=false",
                            "deepscope.a2a.nacos.discovery.enabled=true")
                    .run(
                            context -> {
                                assertThat(context)
                                        .hasSingleBean(DeepScopeA2aNacosProperties.class);

                                DeepScopeA2aNacosProperties properties =
                                        context.getBean(DeepScopeA2aNacosProperties.class);
                                assertThat(properties.getServerAddr()).isEqualTo("127.0.0.1:8848");
                                assertThat(properties.getNamespace()).isEqualTo("test-namespace");
                                assertThat(properties.getUsername()).isEqualTo("test-user");
                                assertThat(properties.getPassword()).isEqualTo("test-password");

                                NacosA2aRegistryProperties registryProperties =
                                        properties.getRegistry();
                                assertThat(registryProperties.isEnabled()).isTrue();
                                assertThat(registryProperties.isRegisterAsLatest()).isFalse();
                                assertThat(registryProperties.isEnabledRegisterEndpoint())
                                        .isFalse();

                                NacosA2aDiscoveryProperties discoveryProperties =
                                        properties.getDiscovery();
                                assertThat(discoveryProperties.isEnabled()).isTrue();
                            });
        }
    }

    @Test
    void shouldCreateWithCustomAgentCardResolver() {
        AgentCardResolver mockResolver = mock(AgentCardResolver.class);
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withBean(AgentCardResolver.class, () -> mockResolver)
                    .withPropertyValues(
                            "deepscope.a2a.nacos.enabled=true",
                            "deepscope.a2a.nacos.server-addr=127.0.0.1:8848",
                            "deepscope.a2a.nacos.namespace=public")
                    .run(
                            context -> {
                                Map<String, AgentCardResolver> resolvers =
                                        context.getBeansOfType(AgentCardResolver.class);
                                assertEquals(2, resolvers.size());
                            });
        }
    }

    @Test
    void shouldCreateWithCustomAgentRegistry() {
        AgentRegistry mockRegistry = mock(AgentRegistry.class);
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withBean(AgentRegistry.class, () -> mockRegistry)
                    .withPropertyValues(
                            "deepscope.a2a.nacos.enabled=true",
                            "deepscope.a2a.nacos.server-addr=127.0.0.1:8848",
                            "deepscope.a2a.nacos.namespace=public")
                    .run(
                            context -> {
                                Map<String, AgentRegistry> registries =
                                        context.getBeansOfType(AgentRegistry.class);
                                assertEquals(2, registries.size());
                            });
        }
    }

    @Test
    void shouldCreateBeansWithDefaultProperties() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withPropertyValues("deepscope.a2a.nacos.enabled=true")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Test
    void shouldCreateBeansWithRegistryProperties() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.a2a.nacos.enabled=true",
                            "deepscope.a2a.nacos.registry.enabled=true",
                            "deepscope.a2a.nacos.registry.register-as-latest=true",
                            "deepscope.a2a.nacos.registry.enabled-register-endpoint=true",
                            "deepscope.a2a.nacos.registry.overwrite-preferred-transport=JSONRPC")
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(AgentRegistry.class);
                                assertThat(context)
                                        .hasSingleBean(DeepScopeA2aNacosProperties.class);

                                DeepScopeA2aNacosProperties properties =
                                        context.getBean(DeepScopeA2aNacosProperties.class);
                                NacosA2aRegistryProperties registryProps = properties.getRegistry();
                                assertThat(registryProps.isEnabled()).isTrue();
                                assertThat(registryProps.isRegisterAsLatest()).isTrue();
                                assertThat(registryProps.isEnabledRegisterEndpoint()).isTrue();
                                assertThat(registryProps.getOverwritePreferredTransport())
                                        .isEqualTo("JSONRPC");
                            });
        }
    }

    @Test
    void shouldCreateBeansWithDiscoveryProperties() {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.a2a.nacos.enabled=true",
                            "deepscope.a2a.nacos.discovery.enabled=true")
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(AgentCardResolver.class);
                                assertThat(context)
                                        .hasSingleBean(DeepScopeA2aNacosProperties.class);

                                DeepScopeA2aNacosProperties properties =
                                        context.getBean(DeepScopeA2aNacosProperties.class);
                                NacosA2aDiscoveryProperties discoveryProps =
                                        properties.getDiscovery();
                                assertThat(discoveryProps.isEnabled()).isTrue();
                            });
        }
    }

    @Test
    void shouldCloseNacosServiceOnShutdown() throws Exception {
        try (MockedStatic<AiFactory> mockedStatic = Mockito.mockStatic(AiFactory.class)) {
            mockedStatic.when(() -> AiFactory.createAiService(any())).thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeA2aNacosAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.a2a.nacos.enabled=true",
                            "deepscope.a2a.nacos.server-addr=127.0.0.1:8848",
                            "deepscope.a2a.nacos.namespace=public")
                    .run(
                            context -> {
                                // Verify that the auto-configuration class is available
                                assertThat(context)
                                        .hasSingleBean(DeepscopeA2aNacosAutoConfiguration.class);

                                // Get the auto-configuration instance and verify it can be closed
                                DeepscopeA2aNacosAutoConfiguration autoConfiguration =
                                        context.getBean(DeepscopeA2aNacosAutoConfiguration.class);
                                assertThat(autoConfiguration).isNotNull();

                                // Call close method to test shutdown behavior
                                autoConfiguration.close();
                                verify(mockAiService).shutdown();
                            });
        }
    }
}
