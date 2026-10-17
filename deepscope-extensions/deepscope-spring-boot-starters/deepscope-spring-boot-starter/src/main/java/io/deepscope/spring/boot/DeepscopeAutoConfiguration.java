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
package io.deepscope.spring.boot;

import io.deepscope.core.ReActAgent;
import io.deepscope.core.memory.InMemoryMemory;
import io.deepscope.core.memory.Memory;
import io.deepscope.core.model.Model;
import io.deepscope.core.tool.Toolkit;
import io.deepscope.spring.boot.properties.AgentProperties;
import io.deepscope.spring.boot.properties.DeepscopeProperties;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/**
 * Spring Boot auto-configuration that exposes default Memory, Toolkit and ReActAgent beans for
 * DeepScope.
 *
 * <p>Model beans are provided by provider-specific starters such as
 * {@code deepscope-dashscope-spring-boot-starter}, {@code deepscope-openai-spring-boot-starter},
 * {@code deepscope-gemini-spring-boot-starter}, {@code deepscope-anthropic-spring-boot-starter},
 * or by user-defined {@link Model} beans.
 *
 * <p>Basic configuration:
 *
 * <pre>{@code
 * deepscope:
 *   agent:
 *     enabled: true
 *     name: "Assistant"
 *     sys-prompt: "You are a helpful AI assistant."
 *     max-iters: 10
 * }</pre>
 */
@AutoConfiguration
@EnableConfigurationProperties(DeepscopeProperties.class)
@ConditionalOnClass(ReActAgent.class)
public class DeepscopeAutoConfiguration {

    /**
     * Default Memory implementation backed by InMemoryMemory.
     *
     * <p>
     * Memory is stateful and not thread-safe, so we expose it as a prototype-scoped
     * bean.
     * In multi-threaded / web environments, it is recommended to obtain instances
     * lazily via
     * {@code ObjectProvider<Memory>} or method injection.
     */
    @Bean
    @ConditionalOnProperty(prefix = "deepscope.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public Memory deepscopeMemory() {
        return new InMemoryMemory();
    }

    /**
     * Default Toolkit implementation with an initially empty tool set.
     *
     * <p>
     * Toolkit holds mutable state and is not thread-safe, so it is also exposed as
     * a
     * prototype-scoped bean. In application code, prefer obtaining instances lazily
     * via
     * {@code ObjectProvider<Toolkit>} or method injection.
     */
    @Bean
    @ConditionalOnProperty(prefix = "deepscope.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public Toolkit deepscopeToolkit() {
        return new Toolkit();
    }

    /**
     * Default ReActAgent that wires together the configured Model, Memory and
     * Toolkit beans using
     * {@link AgentProperties}.
     *
     * ReActAgent in 2.0 is thread-safe, so we just use a singleton instance.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Model.class)
    @ConditionalOnProperty(prefix = "deepscope.agent", name = "enabled", havingValue = "true")
    public ReActAgent deepscopeReActAgent(
            Model model, Memory memory, Toolkit toolkit, DeepscopeProperties properties) {
        AgentProperties config = properties.getAgent();
        return ReActAgent.builder()
                .name(config.getName())
                .sysPrompt(config.getSysPrompt())
                .model(model)
                .toolkit(toolkit)
                .maxIters(config.getMaxIters())
                .build();
    }
}
