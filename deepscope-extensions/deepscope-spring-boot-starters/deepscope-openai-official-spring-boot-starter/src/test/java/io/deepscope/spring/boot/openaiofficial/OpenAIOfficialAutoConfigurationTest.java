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
package io.deepscope.spring.boot.openaiofficial;

import static org.assertj.core.api.Assertions.assertThat;

import io.deepscope.core.ReActAgent;
import io.deepscope.core.message.Msg;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.GenerateOptions;
import io.deepscope.core.model.Model;
import io.deepscope.core.model.ToolSchema;
import io.deepscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;
import io.deepscope.spring.boot.DeepscopeAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

class OpenAIOfficialAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(OpenAIOfficialAutoConfiguration.class));

    @Test
    void shouldCreateOpenAIOfficialModelWhenProviderIsOpenAIOfficial() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=openai-official",
                        "deepscope.openai-official.api-key=test-openai-key",
                        "deepscope.openai-official.model-name=gpt-4o")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(OpenAIResponsesChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("gpt-4o");
                        });
    }

    @Test
    void shouldRequireModelNameWhenBlankPropertyIsSet() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=openai-official",
                        "deepscope.openai-official.api-key= ",
                        "deepscope.openai-official.model-name= ")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .isNotNull()
                                        .hasMessageContaining(
                                                "deepscope.openai-official.model-name must be"
                                                        + " configured"));
    }

    @Test
    void shouldBindSupportedOpenAIOfficialProperties() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=openai-official",
                        "deepscope.openai-official.api-key=test-openai-key",
                        "deepscope.openai-official.model-name=gpt-4o",
                        "deepscope.openai-official.base-url=https://example.com/v1",
                        "deepscope.openai-official.stream=false")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(OpenAIResponsesChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("gpt-4o");
                        });
    }

    @Test
    void shouldNotCreateOpenAIOfficialModelWhenProviderIsDifferent() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=openai",
                        "deepscope.openai-official.api-key=test-openai-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(OpenAIResponsesChatModel.class);
                        });
    }

    @Test
    void shouldNotCreateOpenAIOfficialModelWhenDisabled() {
        contextRunner
                .withPropertyValues(
                        "deepscope.model.provider=openai-official",
                        "deepscope.openai-official.enabled=false",
                        "deepscope.openai-official.api-key=test-openai-key")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Model.class);
                            assertThat(context).doesNotHaveBean(OpenAIResponsesChatModel.class);
                        });
    }

    @Test
    void shouldBackOffWhenUserDefinesModelBean() {
        contextRunner
                .withUserConfiguration(CustomModelConfiguration.class)
                .withPropertyValues(
                        "deepscope.model.provider=openai-official",
                        "deepscope.openai-official.api-key=test-openai-key")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).doesNotHaveBean(OpenAIResponsesChatModel.class);
                            assertThat(context.getBean(Model.class).getModelName())
                                    .isEqualTo("custom-model");
                        });
    }

    @Test
    void shouldIntegrateWithGenericDeepscopeAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                OpenAIOfficialAutoConfiguration.class,
                                DeepscopeAutoConfiguration.class))
                .withPropertyValues(
                        "deepscope.agent.enabled=true",
                        "deepscope.model.provider=openai-official",
                        "deepscope.openai-official.api-key=test-openai-key",
                        "deepscope.openai-official.model-name=gpt-4o")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context).hasSingleBean(OpenAIResponsesChatModel.class);
                            assertThat(context).hasSingleBean(ReActAgent.class);
                        });
    }

    @Test
    void shouldApplyOpenAIResponsesChatModelBuilderCustomizer() {
        contextRunner
                .withUserConfiguration(CustomBuilderConfiguration.class)
                .withPropertyValues(
                        "deepscope.model.provider=openai-official",
                        "deepscope.openai-official.api-key=test-openai-key",
                        "deepscope.openai-official.model-name=gpt-4o")
                .run(
                        context -> {
                            OpenAIResponsesChatModel model =
                                    context.getBean(OpenAIResponsesChatModel.class);
                            assertThat(model.getModelName()).isEqualTo("customized-model-name");
                        });
    }

    @Test
    void shouldDelegateAcceptToCustomizeOnOpenAIResponsesChatModelBuilderCustomizer() {
        OpenAIResponsesChatModel.Builder builder =
                OpenAIResponsesChatModel.builder().apiKey("test-openai-key").modelName("original");
        OpenAIResponsesChatModelBuilderCustomizer customizer =
                modelBuilder -> modelBuilder.modelName("customized");

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
        OpenAIResponsesChatModelBuilderCustomizer testOpenAIResponsesChatModelBuilderCustomizer() {
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
