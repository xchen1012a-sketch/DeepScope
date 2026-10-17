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
package io.deepscope.spring.boot.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import io.deepscope.core.ReActAgent;
import io.deepscope.core.message.Msg;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.Model;
import io.deepscope.core.model.ToolSchema;
import io.deepscope.extensions.model.gemini.GeminiChatModel;
import io.deepscope.spring.boot.DeepscopeAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

class GeminiAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(GeminiAutoConfiguration.class));

    @Test
    void shouldCreateGeminiModelWhenProviderIsGemini() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=gemini",
                        "deepscope.gemini.api-key=test-gemini-key",
                        "deepscope.gemini.model-name=gemini-2.0-flash")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(GeminiChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("gemini-2.0-flash");
                        });
    }

    @Test
    void shouldCreateGeminiModelWithVertexAIProject() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=gemini",
                        "deepscope.gemini.project=test-project",
                        "deepscope.gemini.location=us-central1",
                        "deepscope.gemini.vertex-ai=true",
                        "deepscope.gemini.model-name=gemini-2.0-flash")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .isNotNull()
                                        .hasMessageContaining(
                                                "Failed to get application default credentials"));
    }

    @Test
    void shouldBindSupportedGeminiProperties() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=gemini",
                        "deepscope.gemini.api-key=test-gemini-key",
                        "deepscope.gemini.model-name=gemini-2.0-flash",
                        "deepscope.gemini.base-url=https://gemini.example.com",
                        "deepscope.gemini.stream=false",
                        "deepscope.gemini.project=test-project",
                        "deepscope.gemini.location=us-central1",
                        "deepscope.gemini.vertex-ai=false")
                .run(
                        context -> {
                            GeminiChatModel model = context.getBean(GeminiChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("gemini-2.0-flash");
                        });
    }

    @Test
    void shouldNotCreateGeminiModelWhenProviderIsDifferent() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=dashscope",
                        "deepscope.gemini.api-key=test-gemini-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(GeminiChatModel.class);
                        });
    }

    @Test
    void shouldNotCreateGeminiModelWhenDisabled() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=gemini",
                        "deepscope.gemini.enabled=false",
                        "deepscope.gemini.api-key=test-gemini-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(GeminiChatModel.class);
                        });
    }

    @Test
    void shouldFailClearlyWhenCredentialMissing() {
        contextRunner
                .withPropertyValues("deepscope.model.provider=gemini")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .isNotNull()
                                        .hasMessageContaining(
                                                "deepscope.gemini.api-key must be configured"
                                                        + " when Gemini API mode is selected"));
    }

    @Test
    void shouldFailClearlyWhenVertexAIProjectMissing() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=gemini", "deepscope.gemini.vertex-ai=true")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .isNotNull()
                                        .hasMessageContaining(
                                                "deepscope.gemini.project must be configured when"
                                                        + " Vertex AI mode is enabled"));
    }

    @Test
    void shouldBackOffWhenUserDefinesModelBean() {
        contextRunner
                .withUserConfiguration(CustomModelConfiguration.class)
                .withPropertyValues(
                        "deepscope.model.provider=gemini",
                        "deepscope.gemini.api-key=test-gemini-key")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).doesNotHaveBean(GeminiChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("custom-model");
                        });
    }

    @Test
    void shouldIntegrateWithGenericDeepscopeAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                GeminiAutoConfiguration.class, DeepscopeAutoConfiguration.class))
                .withPropertyValues(
                        "deepscope.agent.enabled=true",
                        "deepscope.model.provider=gemini",
                        "deepscope.gemini.api-key=test-gemini-key",
                        "deepscope.gemini.model-name=gemini-2.0-flash")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(GeminiChatModel.class);
                            assertThat(context).hasSingleBean(ReActAgent.class);
                        });
    }

    @Test
    void shouldApplyGeminiChatModelBuilderCustomizer() {
        contextRunner
                .withUserConfiguration(CustomBuilderConfiguration.class)
                .withPropertyValues(
                        "deepscope.model.provider=gemini",
                        "deepscope.gemini.api-key=test-gemini-key",
                        "deepscope.gemini.model-name=gemini-2.0-flash")
                .run(
                        context -> {
                            GeminiChatModel model = context.getBean(GeminiChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("customized-model-name");
                        });
    }

    @Test
    void shouldDelegateAcceptToCustomizeOnGeminiChatModelBuilderCustomizer() {
        GeminiChatModel.Builder builder =
                GeminiChatModel.builder().apiKey("test-key").modelName("original");
        GeminiChatModelBuilderCustomizer customizer = b -> b.modelName("customized");

        customizer.accept(builder);

        assertThat(builder.build().getModelName()).isEqualTo("customized");
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
        GeminiChatModelBuilderCustomizer testGeminiChatModelBuilderCustomizer() {
            return builder -> builder.modelName("customized-model-name");
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
