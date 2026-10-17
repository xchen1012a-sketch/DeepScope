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

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import io.deepscope.core.nacos.prompt.NacosPromptListener;
import io.deepscope.spring.boot.DeepscopeAutoConfiguration;
import io.deepscope.spring.boot.nacos.constants.NacosConstants;
import io.deepscope.spring.boot.nacos.properties.DeepScopeNacosPromptProperties;
import io.deepscope.spring.boot.nacos.properties.DeepScopeNacosProperties;
import io.deepscope.spring.boot.properties.DeepscopeProperties;
import java.util.Properties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that builds ReActAgent from prompts stored in Nacos.
 *
 * <p>When this starter is on the classpath and {@code deepscope.nacos.prompt.enabled=true}, the
 * Agent's system prompt will be loaded from Nacos via {@link NacosPromptListener}. The Nacos
 * prompt takes precedence over {@code deepscope.agent.sys-prompt} defined in YAML, with the YAML
 * value still acting as a default fallback.
 */
@AutoConfiguration
@AutoConfigureBefore(DeepscopeAutoConfiguration.class)
@EnableConfigurationProperties({
    DeepScopeNacosProperties.class,
    DeepScopeNacosPromptProperties.class,
    DeepscopeProperties.class
})
@ConditionalOnClass(NacosPromptListener.class)
@ConditionalOnProperty(
        prefix = NacosConstants.NACOS_PROMPT_PREFIX,
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class DeepscopeNacosPromptAutoConfiguration {

    @Bean(name = "deepscopePromptAiService")
    @ConditionalOnMissingBean(name = "deepscopePromptAiService")
    public AiService deepscopePromptAiService(
            DeepScopeNacosProperties nacosProperties,
            DeepScopeNacosPromptProperties promptNacosProperties)
            throws NacosException {
        Properties result = nacosProperties.getNacosProperties();
        result.putAll(promptNacosProperties.getExplicitNacosProperties());
        return AiFactory.createAiService(result);
    }

    @Bean
    @ConditionalOnMissingBean(NacosPromptListener.class)
    public NacosPromptListener nacosPromptListener(AiService deepscopePromptAiService) {
        return new NacosPromptListener(deepscopePromptAiService);
    }
}
