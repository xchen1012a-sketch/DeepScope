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

package io.deepscope.spring.boot.nacos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import io.deepscope.core.nacos.prompt.NacosPromptListener;
import io.deepscope.spring.boot.nacos.properties.DeepScopeNacosPromptProperties;
import io.deepscope.spring.boot.nacos.properties.DeepScopeNacosProperties;
import io.deepscope.spring.boot.properties.DeepscopeProperties;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Unit tests for {@link DeepscopeNacosPromptAutoConfiguration}.
 *
 * <p>Verifies that AiService and NacosPromptListener beans are correctly created,
 * conditional on properties and classpath conditions.
 */
class DeepscopeNacosPromptAutoConfigurationTest {

    private AiService mockAiService;

    @BeforeEach
    void setUp() {
        mockAiService = mock(AiService.class);
    }

    @Test
    @DisplayName("should create AiService and NacosPromptListener when enabled")
    void shouldCreateBeansWhenEnabled() {
        try (MockedStatic<AiFactory> mocked = Mockito.mockStatic(AiFactory.class)) {
            mocked.when(() -> AiFactory.createAiService(any(Properties.class)))
                    .thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeNacosPromptAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.nacos.server-addr=127.0.0.1:8848",
                            "deepscope.nacos.prompt.enabled=true",
                            "deepscope.nacos.prompt.sys-prompt-key=test-agent")
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(DeepScopeNacosProperties.class);
                                assertThat(context)
                                        .hasSingleBean(DeepScopeNacosPromptProperties.class);
                                assertThat(context).hasSingleBean(DeepscopeProperties.class);
                                assertThat(context).hasSingleBean(AiService.class);
                                assertThat(context).hasSingleBean(NacosPromptListener.class);
                            });
        }
    }

    @Test
    @DisplayName("should not create beans when prompt is disabled")
    void shouldNotCreateBeansWhenDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(DeepscopeNacosPromptAutoConfiguration.class))
                .withPropertyValues("deepscope.nacos.prompt.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(AiService.class);
                            assertThat(context).doesNotHaveBean(NacosPromptListener.class);
                        });
    }

    @Test
    @DisplayName("should bind prompt properties correctly including version and label")
    void shouldBindProperties() {
        try (MockedStatic<AiFactory> mocked = Mockito.mockStatic(AiFactory.class)) {
            mocked.when(() -> AiFactory.createAiService(any(Properties.class)))
                    .thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeNacosPromptAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.nacos.server-addr=10.0.0.1:8848",
                            "deepscope.nacos.prompt.enabled=true",
                            "deepscope.nacos.prompt.sys-prompt-key=my-agent",
                            "deepscope.nacos.prompt.version=2.0.0",
                            "deepscope.nacos.prompt.label=prod",
                            "deepscope.nacos.prompt.variables.role=Helper",
                            "deepscope.nacos.prompt.variables.env=prod")
                    .run(
                            context -> {
                                DeepScopeNacosPromptProperties props =
                                        context.getBean(DeepScopeNacosPromptProperties.class);
                                assertThat(props.isEnabled()).isTrue();
                                assertThat(props.getSysPromptKey()).isEqualTo("my-agent");
                                assertThat(props.getVersion()).isEqualTo("2.0.0");
                                assertThat(props.getLabel()).isEqualTo("prod");
                                assertThat(props.getVariables())
                                        .containsEntry("role", "Helper")
                                        .containsEntry("env", "prod");
                            });
        }
    }

    @Test
    @DisplayName("should not replace existing NacosPromptListener bean")
    void shouldNotReplaceExistingBean() {
        NacosPromptListener customListener = new NacosPromptListener(mockAiService);

        try (MockedStatic<AiFactory> mocked = Mockito.mockStatic(AiFactory.class)) {
            mocked.when(() -> AiFactory.createAiService(any(Properties.class)))
                    .thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeNacosPromptAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.nacos.prompt.enabled=true",
                            "deepscope.nacos.prompt.sys-prompt-key=test")
                    .withBean(NacosPromptListener.class, () -> customListener)
                    .run(
                            context -> {
                                assertThat(context).hasSingleBean(NacosPromptListener.class);
                                assertThat(context.getBean(NacosPromptListener.class))
                                        .isSameAs(customListener);
                            });
        }
    }

    @Test
    @DisplayName("should use prompt-specific Nacos config when both global and prompt config set")
    void shouldUsePromptSpecificNacosConfig() {
        try (MockedStatic<AiFactory> mocked = Mockito.mockStatic(AiFactory.class)) {
            mocked.when(() -> AiFactory.createAiService(any(Properties.class)))
                    .thenReturn(mockAiService);

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeNacosPromptAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.nacos.server-addr=global.example.com:8848",
                            "deepscope.nacos.prompt.enabled=true",
                            "deepscope.nacos.prompt.server-addr=prompt.example.com:8848",
                            "deepscope.nacos.prompt.namespace=prompt-ns",
                            "deepscope.nacos.prompt.sys-prompt-key=test")
                    .run(
                            context -> {
                                DeepScopeNacosPromptProperties promptProps =
                                        context.getBean(DeepScopeNacosPromptProperties.class);
                                assertThat(promptProps.getServerAddr())
                                        .isEqualTo("prompt.example.com:8848");
                                assertThat(promptProps.getNamespace()).isEqualTo("prompt-ns");

                                DeepScopeNacosProperties globalProps =
                                        context.getBean(DeepScopeNacosProperties.class);
                                assertThat(globalProps.getServerAddr())
                                        .isEqualTo("global.example.com:8848");
                            });
        }
    }

    @Test
    @DisplayName("should not overwrite global server-addr when prompt server-addr is not set")
    void shouldNotOverwriteGlobalServerAddrWhenPromptNotSet() {
        try (MockedStatic<AiFactory> mocked = Mockito.mockStatic(AiFactory.class)) {
            AtomicReference<Properties> capturedProps = new AtomicReference<>();
            mocked.when(() -> AiFactory.createAiService(any(Properties.class)))
                    .thenAnswer(
                            invocation -> {
                                capturedProps.set(invocation.getArgument(0));
                                return mockAiService;
                            });

            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(DeepscopeNacosPromptAutoConfiguration.class))
                    .withPropertyValues(
                            "deepscope.nacos.server-addr=production.nacos.com:8848",
                            "deepscope.nacos.namespace=prod-ns",
                            "deepscope.nacos.prompt.enabled=true",
                            "deepscope.nacos.prompt.sys-prompt-key=test")
                    .run(
                            context -> {
                                assertThat(capturedProps.get()).isNotNull();
                                assertThat(capturedProps.get().get(PropertyKeyConst.SERVER_ADDR))
                                        .isEqualTo("production.nacos.com:8848");
                                assertThat(capturedProps.get().get(PropertyKeyConst.NAMESPACE))
                                        .isEqualTo("prod-ns");
                            });
        }
    }
}
