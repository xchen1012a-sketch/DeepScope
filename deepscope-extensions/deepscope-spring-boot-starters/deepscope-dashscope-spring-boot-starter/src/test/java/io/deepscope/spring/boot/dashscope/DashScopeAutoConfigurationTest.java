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
package io.deepscope.spring.boot.dashscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.deepscope.core.ReActAgent;
import io.deepscope.core.message.Msg;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.Model;
import io.deepscope.core.model.ToolSchema;
import io.deepscope.extensions.model.dashscope.DashScopeChatModel;
import io.deepscope.spring.boot.DeepscopeAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

class DashScopeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(DashScopeAutoConfiguration.class));

    @Test
    void shouldCreateDashScopeModelWhenProviderIsDashscope() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=dashscope",
                        "deepscope.dashscope.api-key=test-dashscope-key",
                        "deepscope.dashscope.model-name=qwen-max")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(DashScopeChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("qwen-max");
                        });
    }

    @Test
    void shouldCreateDashScopeModelWhenProviderIsMissing() {
        contextRunner
                .withPropertyValues(
                        "deepscope.dashscope.api-key=test-dashscope-key",
                        "deepscope.dashscope.model-name=qwen-plus")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(DashScopeChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("qwen-plus");
                        });
    }

    @Test
    void shouldBindSupportedDashScopeProperties() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=dashscope",
                        "deepscope.dashscope.api-key=test-dashscope-key",
                        "deepscope.dashscope.model-name=qwen-max",
                        "deepscope.dashscope.base-url=https://dashscope.example.com",
                        "deepscope.dashscope.stream=false",
                        "deepscope.dashscope.enable-thinking=true")
                .run(
                        context -> {
                            DashScopeChatModel model = context.getBean(DashScopeChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("qwen-max");
                        });
    }

    @Test
    void shouldNotCreateDashScopeModelWhenProviderIsDifferent() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=gemini",
                        "deepscope.dashscope.api-key=test-dashscope-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(DashScopeChatModel.class);
                        });
    }

    @Test
    void shouldNotCreateDashScopeModelWhenDisabled() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=dashscope",
                        "deepscope.dashscope.enabled=false",
                        "deepscope.dashscope.api-key=test-dashscope-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(DashScopeChatModel.class);
                        });
    }

    @Test
    void shouldFailClearlyWhenApiKeyMissing() {
        contextRunner
                .withPropertyValues("deepscope.model.provider=dashscope")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .isNotNull()
                                        .hasMessageContaining(
                                                "deepscope.dashscope.api-key must be configured"));
    }

    @Test
    void shouldBackOffWhenUserDefinesModelBean() {
        contextRunner
                .withUserConfiguration(CustomModelConfiguration.class)
                .withPropertyValues(
                        "deepscope.model.provider=dashscope",
                        "deepscope.dashscope.api-key=test-dashscope-key")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).doesNotHaveBean(DashScopeChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("custom-model");
                        });
    }

    @Test
    void shouldIntegrateWithGenericDeepscopeAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                DashScopeAutoConfiguration.class, DeepscopeAutoConfiguration.class))
                .withPropertyValues(
                        "deepscope.agent.enabled=true",
                        "deepscope.model.provider=dashscope",
                        "deepscope.dashscope.api-key=test-dashscope-key",
                        "deepscope.dashscope.model-name=qwen-max")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(DashScopeChatModel.class);
                            assertThat(context).hasSingleBean(ReActAgent.class);
                        });
    }

    @Test
    void shouldApplyDashScopeChatModelBuilderCustomizer() {
        contextRunner
                .withUserConfiguration(CustomBuilderConfiguration.class)
                .withPropertyValues(
                        "deepscope.model.provider=dashscope",
                        "deepscope.dashscope.api-key=test-dashscope-key",
                        "deepscope.dashscope.model-name=qwen-max")
                .run(
                        context -> {
                            DashScopeChatModel model = context.getBean(DashScopeChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("customized-qwen");
                        });
    }

    @Test
    void shouldDelegateAcceptToCustomizeOnDashScopeChatModelBuilderCustomizer() {
        DashScopeChatModel.Builder builder =
                DashScopeChatModel.builder().apiKey("test-key").modelName("original");
        DashScopeChatModelBuilderCustomizer customizer = b -> b.modelName("customized");

        customizer.accept(builder);

        assertThat(builder.build().getModelName()).isEqualTo("customized");
    }

    @Test
    void shouldBindMultimodalModelPatterns() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=dashscope",
                        "deepscope.dashscope.api-key=test-dashscope-key",
                        "deepscope.dashscope.model-name=deepseek-v4.1",
                        "deepscope.dashscope.multimodal-model-patterns[0]=deepseek-v4",
                        "deepscope.dashscope.multimodal-model-patterns[1]=deepseek-v5")
                .run(
                        context -> {
                            DashScopeChatModel model = context.getBean(DashScopeChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("deepseek-v4.1");
                            DashScopeProperties properties =
                                    context.getBean(DashScopeProperties.class);
                            assertThat(properties.getMultimodalModelPatterns())
                                    .containsExactly("deepseek-v4", "deepseek-v5");
                        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomModelConfiguration {

        @Bean
        Model customModel() {
            return new TestModel();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBuilderConfiguration {

        @Bean
        DashScopeChatModelBuilderCustomizer testDashScopeChatModelBuilderCustomizer() {
            return builder -> builder.modelName("customized-qwen");
        }
    }

    private static final class TestModel implements Model {
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "custom-model";
        }
    }
}
